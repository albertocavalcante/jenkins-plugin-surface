package jps

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/** Version of this tool, recorded in every output document. */
internal const val TOOL_VERSION: String = "0.1.0"

/** Documented process exit codes. */
internal object ExitCode {
    const val OK: Int = 0

    /** The census gate failed: unknown extensions (without `--allow-unknown`) or an index/scan mismatch. Output is still written. */
    const val GATE_FAILED: Int = 1
    const val USAGE: Int = 2

    /** An input archive is missing, unreadable or malformed. */
    const val INPUT: Int = 3
    const val INTERNAL: Int = 4
}

internal class InputException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

internal data class ExtractArgs(
    val hpi: Path,
    val deps: List<Path>,
    val core: Path,
    val out: Path,
    val noHelpText: Boolean,
    val allowUnknown: Boolean,
)

internal sealed interface ParseResult {
    data class Ok(val args: ExtractArgs) : ParseResult

    data class Error(val message: String) : ParseResult

    data object Help : ParseResult
}

internal fun interface Extractor {
    fun extract(args: ExtractArgs): PluginSurface
}

internal val USAGE_TEXT: String =
    """
    usage: jenkins-plugin-surface extract --hpi <file> --core <jar> --out <dir> [--dep <file>]... [--no-help-text] [--allow-unknown]

      --hpi <file>        plugin archive to analyse
      --core <jar>        pinned Jenkins core jar (class hierarchy input)
      --out <dir>         output directory; writes <plugin-id>-<version>.json
      --dep <file>        dependency plugin archive or jar, hierarchy input only (repeatable)
      --no-help-text      omit documentation bodies, keep summaries and links
      --allow-unknown     do not fail when an extension ends in no class (still counted)

    exit codes:
      0  success
      1  census gate failed (unknown extension without --allow-unknown, or index/scan mismatch); output is written
      2  usage error
      3  input error (missing, unreadable or malformed archive)
      4  internal error
    """.trimIndent()

internal fun parseArgs(argv: List<String>): ParseResult {
    if (argv.isEmpty()) return ParseResult.Error("missing command")
    if (argv[0] == "--help" || argv[0] == "-h" || argv[0] == "help") return ParseResult.Help
    if (argv[0] != "extract") return ParseResult.Error("unknown command '${argv[0]}'")
    var hpi: String? = null
    var core: String? = null
    var out: String? = null
    val deps = mutableListOf<String>()
    var noHelp = false
    var allowUnknown = false
    var index = 1
    fun value(): String? {
        val found = argv.getOrNull(index + 1)
        index += 2
        return found
    }
    while (index < argv.size) {
        when (val flag = argv[index]) {
            "--hpi" -> hpi = value() ?: return ParseResult.Error("$flag needs a value")
            "--core" -> core = value() ?: return ParseResult.Error("$flag needs a value")
            "--out" -> out = value() ?: return ParseResult.Error("$flag needs a value")
            "--dep" -> deps.add(value() ?: return ParseResult.Error("$flag needs a value"))
            "--no-help-text" -> {
                noHelp = true
                index++
            }
            "--allow-unknown" -> {
                allowUnknown = true
                index++
            }
            "--help", "-h" -> return ParseResult.Help
            else -> return ParseResult.Error("unknown option '$flag'")
        }
    }
    val hpiValue = hpi ?: return ParseResult.Error("--hpi is required")
    val coreValue = core ?: return ParseResult.Error("--core is required")
    val outValue = out ?: return ParseResult.Error("--out is required")
    return ParseResult.Ok(
        ExtractArgs(Paths.get(hpiValue), deps.map { Paths.get(it) }, Paths.get(coreValue), Paths.get(outValue), noHelp, allowUnknown),
    )
}

/** Reasons the run must exit non-zero even though output was produced. */
internal fun gateFailures(surface: PluginSurface, allowUnknown: Boolean): List<String> {
    val failures = mutableListOf<String>()
    if (!allowUnknown && surface.census.unknown > 0) {
        failures.add("${surface.census.unknown} extension(s) ended in no class (use --allow-unknown to accept)")
    }
    if (!surface.census.indexScanAgree) failures.add("extension index and bytecode scan disagree")
    return failures
}

private fun safeFileName(text: String): String = text.replace(Regex("[^A-Za-z0-9._-]"), "_")

internal fun runCli(argv: List<String>, stdout: PrintStream, stderr: PrintStream, extractor: Extractor): Int {
    val args =
        when (val parsed = parseArgs(argv)) {
            is ParseResult.Help -> {
                stdout.println(USAGE_TEXT)
                return ExitCode.OK
            }
            is ParseResult.Error -> {
                stderr.println("error: ${parsed.message}")
                stderr.println(USAGE_TEXT)
                return ExitCode.USAGE
            }
            is ParseResult.Ok -> parsed.args
        }
    val surface =
        try {
            extractor.extract(args)
        } catch (e: InputException) {
            stderr.println("input error: ${e.message}")
            return ExitCode.INPUT
        } catch (e: IllegalStateException) {
            stderr.println("internal error: ${e.message}")
            return ExitCode.INTERNAL
        }
    Files.createDirectories(args.out)
    val target = args.out.resolve("${safeFileName(surface.plugin.id.value)}-${safeFileName(surface.plugin.version)}.json")
    Files.write(target, surface.render().toByteArray(Charsets.UTF_8))
    val census = surface.census
    stdout.println(
        "${surface.plugin.id.value} ${surface.plugin.version}: extensions=${census.extensionsSeen} " +
            "classified=${census.classified} unknown=${census.unknown} index_scan_agree=${census.indexScanAgree}",
    )
    val failures = gateFailures(surface, args.allowUnknown)
    failures.forEach { stderr.println("gate failed: $it") }
    return if (failures.isEmpty()) ExitCode.OK else ExitCode.GATE_FAILED
}

public fun main(args: Array<String>) {
    val extractor = Extractor { throw IllegalStateException("extraction engine not implemented yet") }
    exitProcess(runCli(args.toList(), System.out, System.err, extractor))
}
