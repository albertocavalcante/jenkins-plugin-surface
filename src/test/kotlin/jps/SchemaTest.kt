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
    fun renderIsDeterministic() {
        assertEquals(Samples.surface().render(), Samples.surface().render())
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
        assertTrue(!text.contains("\"/Users") && !text.contains("\"/home") && !text.contains(":\\\\"))
    }
}
