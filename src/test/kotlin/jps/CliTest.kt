// SPDX-License-Identifier: Apache-2.0
package jps

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliTest {
    private class Run(val code: Int, val out: String, val err: String)

    private fun run(argv: List<String>, extractor: Extractor): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(argv, PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), extractor)
        return Run(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun base(out: String): List<String> = listOf("extract", "--hpi", "p.hpi", "--core", "core.jar", "--out", out)

    private fun surfaceWithUnknown(unknown: Int, agree: Boolean = true): PluginSurface {
        val s = Samples.surface()
        val file = IndexFileCensus("f", "hudson.Extension", IndexSource.SEZPOZ_TXT, 1, 1, if (agree) emptyList() else listOf("x"), emptyList())
        return s.copy(census = s.census.copy(unknown = unknown, jars = listOf(JarCensus("j", listOf(file)))))
    }

    @Test
    fun successWritesOutputAndExitsZero() {
        val dir = Files.createTempDirectory("jps-cli")
        try {
            val r = run(base(dir.toString())) { Samples.surface() }
            assertEquals(ExitCode.OK, r.code)
            assertTrue(Files.exists(dir.resolve("demo-1.0.json")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun unknownExtensionFailsUnlessAllowed() {
        val dir = Files.createTempDirectory("jps-cli")
        try {
            assertEquals(ExitCode.GATE_FAILED, run(base(dir.toString())) { surfaceWithUnknown(2) }.code)
            val written = dir.resolve("demo-1.0.json")
            assertTrue(Files.exists(written), "output is written even when the gate fails")
            assertEquals("jenkins-plugin-surface/v1", ((parseJson(String(Files.readAllBytes(written), Charsets.UTF_8)) as Json.Obj).fields["schema"] as Json.Str).value)
            Files.delete(written)
            assertEquals(ExitCode.OK, run(base(dir.toString()) + "--allow-unknown") { surfaceWithUnknown(2) }.code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun indexMismatchFailsEvenWithAllowUnknown() {
        val dir = Files.createTempDirectory("jps-cli")
        try {
            val r = run(base(dir.toString()) + "--allow-unknown") { surfaceWithUnknown(0, agree = false) }
            assertEquals(ExitCode.GATE_FAILED, r.code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun usageErrorsExitTwo() {
        assertEquals(ExitCode.USAGE, run(emptyList()) { Samples.surface() }.code)
        assertEquals(ExitCode.USAGE, run(listOf("extract", "--hpi", "a")) { Samples.surface() }.code)
        assertEquals(ExitCode.USAGE, run(listOf("extract", "--bogus")) { Samples.surface() }.code)
        assertEquals(ExitCode.USAGE, run(listOf("extract", "--hpi")) { Samples.surface() }.code)
    }

    @Test
    fun inputAndInternalErrorsHaveOwnCodes() {
        val dir = Files.createTempDirectory("jps-cli")
        try {
            assertEquals(ExitCode.INPUT, run(base(dir.toString())) { throw InputException("bad zip") }.code)
            assertEquals(ExitCode.INTERNAL, run(base(dir.toString())) { throw IllegalStateException("boom") }.code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun ioFailuresMapToDocumentedCodes() {
        val dir = Files.createTempDirectory("jps-cli")
        try {
            assertEquals(ExitCode.INPUT, run(base(dir.toString())) { throw java.io.IOException("disk") }.code)
            assertEquals(ExitCode.INTERNAL, run(base(dir.toString())) { throw UnsupportedOperationException("x") }.code)
            val blocker = dir.resolve("file")
            Files.write(blocker, byteArrayOf(1))
            assertEquals(ExitCode.INTERNAL, run(base(blocker.resolve("sub").toString())) { Samples.surface() }.code)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun optionValueMayNotBeAnotherFlag() {
        assertTrue(parseArgs(listOf("extract", "--hpi", "--core", "c", "--out", "o")) is ParseResult.Error)
    }

    @Test
    fun toolVersionIsSemver() {
        assertEquals(3, TOOL_VERSION.split('.').size)
    }

    @Test
    fun helpExitsZeroAndListsExitCodes() {
        val r = run(listOf("--help")) { Samples.surface() }
        assertEquals(ExitCode.OK, r.code)
        assertTrue(r.out.contains("exit codes"))
    }

    @Test
    fun parsesRepeatedDepsAndFlags() {
        val parsed = parseArgs(base("o") + listOf("--dep", "a.hpi", "--dep", "b.hpi", "--no-help-text", "--allow-unknown"))
        val args = (parsed as ParseResult.Ok).args
        assertEquals(listOf("a.hpi", "b.hpi"), args.deps.map { it.toString() })
        assertTrue(args.noHelpText && args.allowUnknown)
    }
}
