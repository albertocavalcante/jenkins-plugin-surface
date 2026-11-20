// SPDX-License-Identifier: Apache-2.0
package jps

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Runs the real extractor on pinned plugin archives. Archives are downloaded and verified against the SHA-256 in
 * `corpus-pins.tsv` before use; they are never committed. Golden expectations state facts (names, parameters,
 * flags) taken from the plugins' public sources, not from this tool's own output.
 */
@Tag("corpus")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CorpusTest {
    private class Pin(val role: String, val id: String, val version: String, val url: String, val sha256: String)

    private val pins: List<Pin> =
        Files.readAllLines(Paths.get(System.getProperty("jps.pins"))).filter { it.isNotBlank() && !it.startsWith("#") }.map {
            val c = it.split('\t')
            Pin(c[0], c[1], c[2], c[3], c[4])
        }
    private val cache: Path = Paths.get(System.getProperty("jps.corpus.cache"))
    private val outDir: Path = Paths.get(System.getProperty("jps.corpus.out"))
    private val files = mutableMapOf<String, Path>()
    private val surfaces = mutableMapOf<String, Map<String, Json>>()
    private val goldenOnly = "workflow-scm-step"

    private fun fetch(pin: Pin): Path {
        Files.createDirectories(cache)
        val target = cache.resolve("${pin.id}-${pin.sha256.take(12)}.bin")
        if (Files.exists(target) && sha256Hex(Files.readAllBytes(target)).hex == pin.sha256) return target
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
        val request = HttpRequest.newBuilder(URI.create(pin.url)).timeout(Duration.ofMinutes(5)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, response.statusCode(), "download ${pin.id}")
        val actual = sha256Hex(response.body()).hex
        assertEquals(pin.sha256, actual, "SHA-256 of downloaded ${pin.id} ${pin.version} does not match the pin")
        Files.write(target, response.body())
        return target
    }

    private fun extract(pin: Pin, out: Path): Int {
        val deps = pins.filter { it.role == "dep" && it.id != pin.id }.flatMap { listOf("--dep", files.getValue(it.id).toString()) }
        val core = pins.single { it.role == "core" }
        val args = listOf("extract", "--hpi", files.getValue(pin.id).toString(), "--core", files.getValue(core.id).toString(), "--out", out.toString()) + deps
        val sink = PrintStream(ByteArrayOutputStream())
        return runCli(args, sink, sink, StaticExtractor())
    }

    @BeforeAll
    fun prepare() {
        pins.forEach { files[it.id] = fetch(it) }
        Files.createDirectories(outDir)
    }

    private fun runs(pin: Pin): Map<String, Json> =
        surfaces.getOrPut(pin.id) {
            val first = Files.createTempDirectory("jps-corpus-a")
            val second = Files.createTempDirectory("jps-corpus-b")
            try {
                extract(pin, first)
                extract(pin, second)
                val name = "${pin.id}-${pin.version}.json"
                val a = Files.readAllBytes(first.resolve(name))
                val b = Files.readAllBytes(second.resolve(name))
                assertTrue(a.contentEquals(b), "two runs on ${pin.id} must be byte-identical")
                Files.write(outDir.resolve(name), a)
                (parseJson(String(a, Charsets.UTF_8)) as Json.Obj).fields
            } finally {
                first.toFile().deleteRecursively()
                second.toFile().deleteRecursively()
            }
        }

    private fun targets(): List<Pin> = pins.filter { it.role == "target" }

    private fun obj(json: Json?): Map<String, Json> = (json as Json.Obj).fields

    private fun str(json: Json?): String? = (json as? Json.Str)?.value

    private fun records(doc: Map<String, Json>): List<Map<String, Json>> = (doc["records"] as Json.Arr).items.map { obj(it) }

    private fun step(doc: Map<String, Json>, name: String): Map<String, Json> =
        records(doc).filter { str(it["kind"]) == "step" && str(it["name"]) == name }.also { assertEquals(1, it.size, "exactly one step named $name") }.single()

    private fun params(record: Map<String, Json>): Map<String, Map<String, Json>> =
        (record["parameters"] as Json.Arr).items.map { obj(it) }.associateBy { str(it["name"]) ?: "" }

    private fun required(record: Map<String, Json>): Set<String> =
        params(record).filterValues { it["model_required"] == Json.Bool(true) }.keys

    private fun takesBlock(record: Map<String, Json>): Boolean? = ((obj(record["step"])["takes_block"]) as? Json.Bool)?.value

    private fun doc(id: String): Map<String, Json> = runs(pins.first { it.id == id && it.role != "core" })

    @Test
    fun everyExtensionOfTheFivePluginsIsClassifiedAndRunsAreDeterministic() {
        for (pin in targets()) {
            val census = obj(runs(pin)["census"])
            val unknown = records(runs(pin)).filter { str(it["kind"]) == "unknown" }.map { "${str(it["extension_class"])}: ${str(it["reason"])}" }
            assertEquals(emptyList<String>(), unknown, "unclassified extensions in ${pin.id}")
            assertEquals(Json.Bool(true), census["index_scan_agree"], "index and scan must agree in ${pin.id}")
            assertTrue((census["extensions_seen"] as Json.Num).raw.toInt() > 0)
        }
    }

    @Test
    fun goldenShellStep() {
        val sh = step(doc("workflow-durable-task-step"), "sh")
        assertEquals(setOf("script"), required(sh))
        assertTrue(params(sh).keys.containsAll(listOf("script", "returnStdout", "returnStatus", "encoding", "label")))
        assertEquals(false, takesBlock(sh))
    }

    @Test
    fun goldenNodeLabelIsModelRequiredButNullable() {
        val node = step(doc("workflow-durable-task-step"), "node")
        assertEquals(true, takesBlock(node))
        val label = params(node).getValue("label")
        assertEquals(Json.Bool(true), label["model_required"])
        assertEquals("nullable", str(label["usage_hint"]))
    }

    @Test
    fun goldenBasicSteps() {
        val basic = doc("workflow-basic-steps")
        assertEquals(setOf("message"), required(step(basic, "echo")))
        assertEquals(false, takesBlock(step(basic, "echo")))
        val timeout = step(basic, "timeout")
        assertEquals(setOf("time"), required(timeout))
        assertEquals(true, takesBlock(timeout))
        assertTrue(params(timeout).keys.containsAll(listOf("time", "unit", "activity")))
        assertEquals("enum", str(obj(params(timeout).getValue("unit")["type"])["category"]))
        val dir = step(doc("workflow-durable-task-step"), "dir")
        assertEquals(setOf("path"), required(dir))
        assertEquals(true, takesBlock(dir))
    }

    @Test
    fun goldenWithCredentialsAndMetasteps() {
        val wc = step(doc("credentials-binding"), "withCredentials")
        assertEquals(setOf("bindings"), required(wc))
        assertEquals(true, takesBlock(wc))
        assertEquals("array", str(obj(params(wc).getValue("bindings")["type"])["category"]))
        val basic = doc("workflow-basic-steps")
        val metasteps = records(basic).filter { str(it["kind"]) == "step" && (obj(it["step"])["metastep"] as? Json.Bool)?.value == true }.map { str(it["name"]) }.sortedBy { it ?: "" }
        assertEquals(listOf("step", "wrap"), metasteps)
    }

    @Test
    fun goldenCheckoutIsAnOrdinaryStepWithARequiredScmParameter() {
        val scm = runs(pins.first { it.id == goldenOnly })
        val checkout = step(scm, "checkout")
        assertEquals(setOf("scm"), required(checkout))
        assertEquals(false, (obj(checkout["step"])["metastep"] as? Json.Bool)?.value)
    }

    @Test
    fun goldenGitStep() {
        val git = step(doc("git"), "git")
        assertEquals(setOf("url"), required(git))
        assertTrue(params(git).keys.containsAll(listOf("branch", "credentialsId", "poll", "changelog")))
    }

    @Test
    fun goldenDockerGlobalAndRewrittenArguments() {
        val docker = doc("docker-workflow")
        val global = records(docker).single { str(it["kind"]) == "global-variable" && str(it["name"]) == "docker" }
        assertEquals("docker", str(global["name"]))
        val registry = step(docker, "withDockerRegistry")
        assertEquals(Json.Bool(true), registry["args_rewritten"])
        assertEquals(true, takesBlock(registry))
        assertTrue(records(doc("git")).any { str(it["extension_class"]) == "hudson.plugins.git.UserMergeOptions\$DescriptorImpl" && it["args_rewritten"] == Json.Bool(true) })
    }

    @Test
    fun goldenSymbolsAreNestedOnlyForCredentialBindings() {
        val kinds = records(doc("credentials-binding")).filter { (it["symbols"] as Json.Arr).items.contains(Json.Str("string")) }.map { str(it["kind"]) }
        assertEquals(listOf("nested-only-symbol"), kinds)
    }

    @Test
    fun docsAndLinksArePresent() {
        val echo = step(doc("workflow-basic-steps"), "echo")
        assertEquals("present", str(obj(echo["docs"])["status"]))
        assertTrue(str(obj(echo["docs"])["summary"]).orEmpty().isNotBlank())
        assertTrue((echo["links"] as Json.Arr).items.isNotEmpty())
    }
}
