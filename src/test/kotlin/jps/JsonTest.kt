// SPDX-License-Identifier: Apache-2.0
package jps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JsonTest {
    @Test
    fun keysAreSortedAndOutputIsStable() {
        val json = jobj("b" to jint(1), "a" to jarr(listOf(jstr("x"), jbool(true))), "c" to null)
        assertEquals("{\n  \"a\": [\n    \"x\",\n    true\n  ],\n  \"b\": 1\n}\n", json.render())
    }

    @Test
    fun escapesControlCharactersAndKeepsUnicode() {
        val rendered = jstr("a\"b\\c\n\u0001é").render()
        assertEquals("\"a\\\"b\\\\c\\n\\u0001é\"\n", rendered)
    }

    @Test
    fun parseRoundTrips() {
        val text = jobj("k" to jarr(listOf(jint(1), jstr("s\n"), Json.Null, jobj()))).render()
        assertEquals(text, parseJson(text).render())
    }

    @Test
    fun parseRejectsTrailingContent() {
        assertThrows(JsonParseException::class.java) { parseJson("{} x") }
    }

    @Test
    fun emptyContainersRenderCompactly() {
        assertEquals("{\n  \"a\": [],\n  \"o\": {}\n}\n", jobj("a" to jarr(emptyList()), "o" to jobj()).render())
    }
}
