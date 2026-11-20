// SPDX-License-Identifier: Apache-2.0
package jps

import java.nio.file.Files
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaTest {
    private val validator = SchemaValidator(parseJson(String(Files.readAllBytes(Paths.get("schema/jenkins-plugin-surface-v1.schema.json")), Charsets.UTF_8)))

    private fun sample(): Json = parseJson(Samples.surface().render())

    @Test
    fun sampleSurfaceValidates() {
        assertEquals(emptyList<String>(), validator.validate(sample()))
    }

    @Test
    fun renderDoesNotDependOnInputOrder() {
        val base = Samples.record()
        val a = base.copy(id = "step:a", gaps = listOf(Gap("b", "r"), Gap("a", "r")), evidence = listOf("z", "y"), symbols = listOf("s2", "s1"),
            links = listOf(Link(LinkRel.PLUGIN, "u2"), Link(LinkRel.JAVADOC, "u1")))
        val b = base.copy(id = "step:b")
        val straight = Samples.surface(listOf(a, b))
        val shuffled = Samples.surface(listOf(b, a.copy(gaps = a.gaps.reversed(), evidence = a.evidence.reversed(), symbols = a.symbols.reversed(), links = a.links.reversed())))
        assertEquals(straight.render(), shuffled.render())
    }

    @Test
    fun mainSourcesNeverLoadClasses() {
        val forbidden = listOf("ClassLoader", "Class.forName", "MethodHandles", "defineClass", "ServiceLoader")
        Files.walk(Paths.get("src/main")).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val text = String(Files.readAllBytes(file), Charsets.UTF_8)
                forbidden.forEach { assertTrue(!text.contains(it), "$file mentions $it") }
            }
        }
    }

    @Test
    fun recordsAreSortedById() {
        val a = Samples.record().copy(id = "step:a")
        val b = Samples.record().copy(id = "step:b")
        val ids = Regex("\"id\": \"step:[ab]\"").findAll(Samples.surface(listOf(b, a)).render()).map { it.value }.toList()
        assertEquals(listOf("\"id\": \"step:a\"", "\"id\": \"step:b\""), ids)
    }

    @Test
    fun missingRequiredFieldIsRejected() {
        val doc = (sample() as Json.Obj)
        val broken = Json.Obj(doc.fields - "census")
        assertTrue(validator.validate(broken).any { it.contains("census") })
    }

    @Test
    fun unknownPropertyIsRejected() {
        val doc = (sample() as Json.Obj)
        val broken = Json.Obj(doc.fields + ("surprise" to jbool(true)))
        assertTrue(validator.validate(broken).any { it.contains("surprise") })
    }

    @Test
    fun badEnumIsRejected() {
        val text = Samples.surface().render().replace("\"kind\": \"step\"", "\"kind\": \"magic\"")
        assertTrue(validator.validate(parseJson(text)).isNotEmpty())
    }

    @Test
    fun outputHasNoTimestampsOrAbsolutePaths() {
        val text = Samples.surface().render()
        assertTrue(!Regex("\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(text))
        assertTrue(!Regex("(^|[^\\w.])/(Users|home|Volumes|private|tmp|var)/").containsMatchIn(text))
        assertTrue(!Regex("[A-Za-z]:\\\\").containsMatchIn(text))
    }
}
