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
        val file = IndexFileCensus("f", "hudson.Extension", "sezpoz-txt", 1, 1, if (agree) emptyList() else listOf("x"), emptyList())
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
            assertEquals(ExitCode.OK, run(base(dir.toString()) + "--allow-unknown") { surfaceWithUnknown(2) }.code)
            assertTrue(Files.exists(dir.resolve("demo-1.0.json")), "output is written even when the gate fails")
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
    fun helpExitsZeroAndListsExitCodes() {
        val r = run(listOf("--help")) { Samples.surface() }
        assertEquals(ExitCode.OK, r.code)
        assertTrue(r.out.contains("exit codes"))
    }

    @Test
    fun parsesRepeatedDepsAndFlags() {
        val parsed = parseArgs(base("o") + listOf("--dep", "a.hpi", "--dep", "b.hpi", "--no-help-text", "--allow-unknown"))
        val args = (parsed as ParseResult.Ok).args
        assertEquals(2, args.deps.size)
        assertTrue(args.noHelpText && args.allowUnknown)
    }
}
