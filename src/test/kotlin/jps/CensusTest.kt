// SPDX-License-Identifier: Apache-2.0
package jps

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CensusTest {
    private fun ext(name: String): ByteArray = Fixtures.classBytes(name, annotations = listOf(Fixtures.EXT))

    /** An extension whose superclass is in no archive: classification must end in an unknown record. */
    private fun lost(name: String): ByteArray = Fixtures.classBytes(name, superName = "missing/Base", annotations = listOf(Fixtures.EXT))

    private fun plain(name: String): ByteArray = Fixtures.classBytes(name)

    private fun container(entries: Map<String, ByteArray>): Container = Container("WEB-INF/lib/t.jar", entries)

    @Test
    fun indexAndScanAgree() {
        val result =
            runCensus(
                listOf(
                    container(
                        mapOf("a/A.class" to ext("a/A"), "a/B.class" to ext("a/B"), "a/C.class" to plain("a/C")) +
                            Fixtures.extensionIndex("a.A", "a.B"),
                    ),
                ),
            )
        assertEquals(listOf("a.A", "a.B"), result.extensions.map { it.key })
        val file = result.jars.single().files.single()
        assertEquals(IndexSource.SEZPOZ_TXT, file.source)
        assertTrue(file.agree)
        assertEquals(2, file.scanCount)
    }

    @Test
    fun classMissingFromIndexIsReported() {
        val result = runCensus(listOf(container(mapOf("a/A.class" to ext("a/A"), "a/B.class" to ext("a/B")) + Fixtures.extensionIndex("a.A"))))
        val file = result.jars.single().files.single()
        assertEquals(listOf("a.B"), file.onlyInScan)
        assertFalse(result.jars.single().agree)
    }

    @Test
    fun indexedClassWithoutAnnotationIsReported() {
        val result = runCensus(listOf(container(mapOf("a/A.class" to plain("a/A")) + Fixtures.extensionIndex("a.A"))))
        assertEquals(listOf("a.A"), result.jars.single().files.single().onlyInIndex)
    }

    @Test
    fun bytecodeScanIsTheFallbackWhenNoIndexExists() {
        val result = runCensus(listOf(container(mapOf("a/A.class" to ext("a/A")))))
        val file = result.jars.single().files.single()
        assertEquals(IndexSource.BYTECODE_SCAN, file.source)
        assertTrue(file.agree)
        assertEquals(listOf("a.A"), result.extensions.map { it.key })
    }

    @Test
    fun binaryIndexWithoutTextSiblingFails() {
        val result = runCensus(listOf(container(mapOf("a/A.class" to ext("a/A"), "META-INF/annotations/hudson.Extension" to byteArrayOf(1)))))
        val file = result.jars.single().files.single()
        assertEquals(IndexSource.SEZPOZ_BIN_UNREADABLE, file.source)
        assertFalse(file.agree)
    }

    @Test
    fun jarsWithoutExtensionsAreOmitted() {
        assertTrue(runCensus(listOf(container(mapOf("a/A.class" to plain("a/A"))))).jars.isEmpty())
    }

    @Test
    fun optionalExtensionIndexIsReadToo() {
        val optional = "Lorg/jenkinsci/plugins/variant/OptionalExtension;"
        val entries =
            mapOf("a/O.class" to Fixtures.classBytes("a/O", annotations = listOf(optional))) +
                Fixtures.extensionIndex("a.O", annotation = "org.jenkinsci.plugins.variant.OptionalExtension")
        val result = runCensus(listOf(container(entries)))
        assertEquals(setOf("org.jenkinsci.plugins.variant.OptionalExtension"), result.extensions.single().annotations)
        assertTrue(result.jars.single().agree)
    }

    @Test
    fun methodExtensionsUseMemberKeys() {
        val bytes =
            Fixtures.classBytes("a/F", methods = listOf(Fixtures.MethodSpec("make", "()V", annotations = listOf(Fixtures.EXT))))
        val result = runCensus(listOf(container(mapOf("a/F.class" to bytes) + Fixtures.extensionIndex("a.F#make()"))))
        assertTrue(result.jars.single().agree)
        assertTrue(result.extensions.single().isMember)
        assertEquals("a.F", result.extensions.single().className)
    }

    @Test
    fun annotationIndexerFamilyIsComparedByClass() {
        val symbol = "Lorg/jenkinsci/Symbol;"
        val withField = Fixtures.classBytes("a/S", fields = listOf(Fixtures.FieldSpec("f", "I", listOf(symbol))))
        val entries = mapOf("a/S.class" to withField, "META-INF/services/annotations/org.jenkinsci.Symbol" to "a.S\n".toByteArray())
        val file = runCensus(listOf(container(entries))).jars.single().files.single()
        assertEquals(IndexSource.ANNOTATION_INDEXER, file.source)
        assertTrue(file.agree)
        val missing = runCensus(listOf(container(mapOf("a/S.class" to withField, "META-INF/services/annotations/org.jenkinsci.Symbol" to "".toByteArray()))))
        assertEquals(listOf("a.S"), missing.jars.single().files.single().onlyInScan)
    }

    @Test
    fun extensionsAreDeduplicatedAcrossAnnotations() {
        val optional = "Lorg/jenkinsci/plugins/variant/OptionalExtension;"
        val both = Fixtures.classBytes("a/B", annotations = listOf(Fixtures.EXT, optional))
        val entries =
            mapOf("a/B.class" to both) + Fixtures.extensionIndex("a.B") +
                Fixtures.extensionIndex("a.B", annotation = "org.jenkinsci.plugins.variant.OptionalExtension")
        val result = runCensus(listOf(container(entries)))
        assertEquals(1, result.extensions.size)
        assertEquals(2, result.extensions.single().annotations.size)
    }

    @Test
    fun malformedClassIsAnInputError() {
        assertThrows(InputException::class.java) { runCensus(listOf(container(mapOf("a/Bad.class" to byteArrayOf(1, 2, 3))))) }
    }

    @Test
    fun manifestContinuationLinesAndOptionalDependencies() {
        val text = "Manifest-Version: 1.0\r\nPlugin-Dependencies: a:1.0,b:2.0;resolution:=opt\r\n ional,c:3\r\nShort-Name: x\r\n\r\nName: other\r\nShort-Name: ignored\r\n"
        val manifest = parseManifest(text.toByteArray())
        assertEquals("x", manifest["Short-Name"])
        assertEquals(
            listOf(Dependency(PluginId("a"), "1.0", false), Dependency(PluginId("b"), "2.0", true), Dependency(PluginId("c"), "3", false)),
            parseDependencies(manifest["Plugin-Dependencies"]),
        )
    }

    @Test
    fun realisticSezpozLinesAreParsed() {
        val text = "# informational; use java -jar sezpoz.jar to see authoritative contents\n" +
            "hudson.plugins.git.GitPublisher\$DescriptorImpl{ordinal=-1.0}\n" +
            "jenkins.plugins.git.GitUsernamePasswordBinding\$DescriptorImpl{requirePlugins=[credentials-binding]}\n" +
            "a.Plain\n   continuation of something\n"
        assertEquals(
            setOf("hudson.plugins.git.GitPublisher\$DescriptorImpl", "jenkins.plugins.git.GitUsernamePasswordBinding\$DescriptorImpl", "a.Plain"),
            parseSezpozText(text),
        )
    }

    @Test
    fun scanOnlyExtensionsAreStillRecordedWithProvenance() {
        val result = runCensus(listOf(container(mapOf("a/A.class" to ext("a/A"), "a/B.class" to ext("a/B")) + Fixtures.extensionIndex("a.A"))))
        assertEquals(listOf("a.A", "a.B"), result.extensions.map { it.key })
        assertEquals(listOf(true, false), result.extensions.map { it.indexed })
    }

    @Test
    fun classesOnlyPluginLayoutIsRecognised() {
        val zip = Fixtures.zip(
            mapOf(
                "META-INF/MANIFEST.MF" to Fixtures.manifest("Short-Name" to "x", "Plugin-Version" to "1"),
                "WEB-INF/classes/a/A.class" to ext("a/A"),
                "WEB-INF/classes/META-INF/annotations/hudson.Extension.txt" to "a.A\n".toByteArray(),
            ),
        )
        val dir = fixtureDir()
        try {
            val archive = readArchive(Fixtures.write(dir, "c.hpi", zip))
            assertEquals(listOf(CLASSES_DIR), archive.containers.map { it.path })
            assertTrue(runCensus(archive.containers).jars.single().agree)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun truncatedAndDuplicateArchivesAreInputErrors() {
        val dir = fixtureDir()
        try {
            val good = Fixtures.hpi("demo", "1", mapOf("a/A.class" to plain("a/A")))
            assertEquals(ExitCode.INPUT, run(dir, good.copyOf(good.size / 2)))
            assertEquals(ExitCode.INPUT, run(dir, ByteArray(0)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc".toByteArray()).hex)
    }

    // ---- end to end through the extractor ---------------------------------------------------------

    private fun fixtureDir(): Path = Files.createTempDirectory("jps-census")

    private fun run(dir: Path, hpi: ByteArray, extra: List<String> = emptyList(), out: String = "out"): Int {
        val core = Fixtures.write(dir, "core.jar", Fixtures.zip(mapOf("META-INF/MANIFEST.MF" to Fixtures.manifest("Implementation-Version" to "2.400"))))
        val plugin = Fixtures.write(dir, "p.hpi", hpi)
        val args = listOf("extract", "--hpi", plugin.toString(), "--core", core.toString(), "--out", dir.resolve(out).toString()) + extra
        return runCli(args, java.io.PrintStream(java.io.ByteArrayOutputStream()), java.io.PrintStream(java.io.ByteArrayOutputStream()), StaticExtractor())
    }

    @Test
    fun extractorIsByteDeterministicAndCountsUnknowns() {
        val dir = fixtureDir()
        try {
            val hpi = Fixtures.hpi("demo", "1.2", mapOf("a/A.class" to lost("a/A"), "a/B.class" to lost("a/B")) + Fixtures.extensionIndex("a.A", "a.B"))
            assertEquals(ExitCode.GATE_FAILED, run(dir, hpi, out = "one"))
            assertEquals(ExitCode.GATE_FAILED, run(dir, hpi, out = "two"))
            val first = Files.readAllBytes(dir.resolve("one/demo-1.2.json"))
            assertTrue(first.contentEquals(Files.readAllBytes(dir.resolve("two/demo-1.2.json"))))
            val text = String(first, Charsets.UTF_8)
            assertTrue(text.contains("\"extensions_seen\": 2"))
            assertTrue(text.contains("\"unknown\": 2"))
            assertEquals(ExitCode.OK, run(dir, hpi, listOf("--allow-unknown"), out = "three"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun indexMismatchFailsTheRunEvenWithAllowUnknown() {
        val dir = fixtureDir()
        try {
            val hpi = Fixtures.hpi("demo", "1", mapOf("a/A.class" to ext("a/A")) + Fixtures.extensionIndex("a.A", "a.Ghost"))
            assertEquals(ExitCode.GATE_FAILED, run(dir, hpi, listOf("--allow-unknown")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun nonPluginArchiveIsAnInputError() {
        val dir = fixtureDir()
        try {
            assertEquals(ExitCode.INPUT, run(dir, Fixtures.zip(mapOf("x.txt" to byteArrayOf(1)))))
            assertEquals(ExitCode.INPUT, run(dir, byteArrayOf(1, 2, 3, 4)))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
