package jps

/** Minimal JSON tree with a deterministic writer (sorted keys, fixed indentation, no timestamps). */
internal sealed interface Json {
    @JvmInline
    internal value class Str(val value: String) : Json

    /** Numbers keep their raw text so integers never gain a fraction. */
    @JvmInline
    internal value class Num(val raw: String) : Json

    @JvmInline
    internal value class Bool(val value: Boolean) : Json

    internal data object Null : Json

    @JvmInline
    internal value class Arr(val items: List<Json>) : Json

    @JvmInline
    internal value class Obj(val fields: Map<String, Json>) : Json
}

internal fun jstr(value: String): Json = Json.Str(value)

internal fun jint(value: Int): Json = Json.Num(value.toString())

internal fun jbool(value: Boolean): Json = Json.Bool(value)

internal fun jarr(items: Iterable<Json>): Json = Json.Arr(items.toList())

internal fun jstrs(items: Iterable<String>): Json = Json.Arr(items.map { Json.Str(it) })

/** Builds an object, omitting fields whose value is null (absent means "not applicable"). */
internal fun jobj(vararg fields: Pair<String, Json?>): Json =
    Json.Obj(fields.mapNotNull { (key, value) -> value?.let { key to it } }.toMap())

/** Renders with two-space indentation, keys sorted by code unit, and a trailing newline. */
internal fun Json.render(): String =
    buildString {
        write(this@render, 0)
        append('\n')
    }

private fun StringBuilder.indent(depth: Int) {
    repeat(depth) { append("  ") }
}

private fun StringBuilder.write(json: Json, depth: Int) {
    when (json) {
        is Json.Str -> quote(json.value)
        is Json.Num -> append(json.raw)
        is Json.Bool -> append(json.value.toString())
        Json.Null -> append("null")
        is Json.Arr -> {
            if (json.items.isEmpty()) {
                append("[]")
            } else {
                append("[\n")
                json.items.forEachIndexed { index, item ->
                    indent(depth + 1)
                    write(item, depth + 1)
                    if (index < json.items.size - 1) append(',')
                    append('\n')
                }
                indent(depth)
                append(']')
            }
        }
        is Json.Obj -> {
            if (json.fields.isEmpty()) {
                append("{}")
            } else {
                append("{\n")
                val keys = json.fields.keys.sorted()
                keys.forEachIndexed { index, key ->
                    indent(depth + 1)
                    quote(key)
                    append(": ")
                    json.fields[key]?.let { write(it, depth + 1) }
                    if (index < keys.size - 1) append(',')
                    append('\n')
                }
                indent(depth)
                append('}')
            }
        }
    }
}

private fun StringBuilder.quote(text: String) {
    append('"')
    for (ch in text) {
        when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch.code < 0x20 -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> append(ch)
        }
    }
    append('"')
}

internal class JsonParseException(message: String) : RuntimeException(message)

internal fun parseJson(text: String): Json {
    val parser = JsonParser(text)
    val value = parser.parseValue()
    parser.skipWhitespace()
    if (!parser.atEnd()) throw JsonParseException("trailing content at ${parser.position}")
    return value
}

private class JsonParser(private val text: String) {
    var position: Int = 0
        private set

    fun atEnd(): Boolean = position >= text.length

    fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }

    private fun peek(): Char = if (position < text.length) text[position] else throw JsonParseException("unexpected end")

    private fun expect(ch: Char) {
        if (peek() != ch) throw JsonParseException("expected '$ch' at $position")
        position++
    }

    fun parseValue(): Json {
        skipWhitespace()
        return when (val ch = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> Json.Str(parseString())
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (ch == '-' || ch.isDigit()) parseNumber() else throw JsonParseException("bad char '$ch' at $position")
        }
    }

    private fun literal(word: String, value: Json): Json {
        if (!text.startsWith(word, position)) throw JsonParseException("bad literal at $position")
        position += word.length
        return value
    }

    private fun parseNumber(): Json {
        val start = position
        while (position < text.length && (text[position].isDigit() || text[position] in "+-.eE")) position++
        return Json.Num(text.substring(start, position))
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val ch = peek()
            position++
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    val esc = peek()
                    position++
                    when (esc) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> {
                            if (position + 4 > text.length) throw JsonParseException("bad unicode escape")
                            out.append(text.substring(position, position + 4).toInt(16).toChar())
                            position += 4
                        }
                        else -> out.append(esc)
                    }
                }
                else -> out.append(ch)
            }
        }
    }

    private fun parseArray(): Json {
        expect('[')
        val items = mutableListOf<Json>()
        skipWhitespace()
        if (peek() == ']') {
            position++
            return Json.Arr(items)
        }
        while (true) {
            items.add(parseValue())
            skipWhitespace()
            when (peek()) {
                ',' -> position++
                ']' -> {
                    position++
                    return Json.Arr(items)
                }
                else -> throw JsonParseException("expected ',' or ']' at $position")
            }
        }
    }

    private fun parseObject(): Json {
        expect('{')
        val fields = linkedMapOf<String, Json>()
        skipWhitespace()
        if (peek() == '}') {
            position++
            return Json.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            fields[key] = parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> position++
                '}' -> {
                    position++
                    return Json.Obj(fields)
                }
                else -> throw JsonParseException("expected ',' or '}' at $position")
            }
        }
    }
}
