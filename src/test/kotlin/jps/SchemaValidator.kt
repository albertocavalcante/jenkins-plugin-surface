// SPDX-License-Identifier: Apache-2.0
package jps

/** A small JSON Schema subset validator (enough for schema/jenkins-plugin-surface-v1.schema.json). */
internal class SchemaValidator(private val root: Json) {
    private companion object {
        val SUPPORTED: Set<String> =
            setOf(
                "\$schema", "\$ref", "\$defs", "title", "description", "type", "const", "enum", "required",
                "properties", "additionalProperties", "items", "pattern", "minLength",
            )
    }

    fun validate(doc: Json): List<String> {
        val errors = mutableListOf<String>()
        check(root, doc, "$", errors)
        return errors
    }

    private fun obj(json: Json?): Map<String, Json>? = (json as? Json.Obj)?.fields

    private fun resolve(ref: String): Json {
        require(ref.startsWith("#/")) { "only local refs: $ref" }
        var node: Json = root
        for (part in ref.removePrefix("#/").split('/')) {
            node = obj(node)?.get(part) ?: error("unresolvable ref $ref")
        }
        return node
    }

    private fun typeMatches(type: String, value: Json): Boolean =
        when (type) {
            "object" -> value is Json.Obj
            "array" -> value is Json.Arr
            "string" -> value is Json.Str
            "boolean" -> value is Json.Bool
            "null" -> value == Json.Null
            "number" -> value is Json.Num
            "integer" -> value is Json.Num && value.raw.all { it.isDigit() || it == '-' }
            else -> error("unsupported type $type")
        }

    private fun check(schema: Json, value: Json, path: String, errors: MutableList<String>) {
        val fields = obj(schema) ?: error("schema at $path is not an object")
        val unsupported = fields.keys - SUPPORTED
        require(unsupported.isEmpty()) { "unsupported schema keywords at $path: $unsupported" }
        fields["\$ref"]?.let { ref ->
            check(resolve((ref as Json.Str).value), value, path, errors)
            return
        }
        fields["const"]?.let { if (it != value) errors.add("$path: expected const $it") }
        fields["enum"]?.let { allowed ->
            if (value !in (allowed as Json.Arr).items) errors.add("$path: not one of the allowed values")
        }
        fields["type"]?.let { type ->
            val names = if (type is Json.Arr) type.items.map { (it as Json.Str).value } else listOf((type as Json.Str).value)
            if (names.none { typeMatches(it, value) }) {
                errors.add("$path: expected type $names")
                return
            }
        }
        if (value is Json.Str) {
            (fields["pattern"] as? Json.Str)?.let { if (!Regex(it.value).containsMatchIn(value.value)) errors.add("$path: pattern mismatch") }
            (fields["minLength"] as? Json.Num)?.let { if (value.value.length < it.raw.toInt()) errors.add("$path: too short") }
        }
        if (value is Json.Obj) {
            val properties = obj(fields["properties"]) ?: emptyMap()
            (fields["required"] as? Json.Arr)?.items?.forEach { name ->
                val key = (name as Json.Str).value
                if (key !in value.fields) errors.add("$path: missing required '$key'")
            }
            for ((key, child) in value.fields) {
                val sub = properties[key]
                if (sub != null) {
                    check(sub, child, "$path.$key", errors)
                } else if (fields["additionalProperties"] == Json.Bool(false)) {
                    errors.add("$path: unexpected property '$key'")
                }
            }
        }
        if (value is Json.Arr) {
            fields["items"]?.let { items -> value.items.forEachIndexed { i, item -> check(items, item, "$path[$i]", errors) } }
        }
    }
}
