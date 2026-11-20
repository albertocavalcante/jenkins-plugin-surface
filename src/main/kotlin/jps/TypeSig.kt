// SPDX-License-Identifier: Apache-2.0
package jps

import org.objectweb.asm.Type

/** A parsed JVM generic signature fragment. */
internal sealed interface Sig {
    data class Prim(val name: String) : Sig

    data class Cls(val internalName: String, val args: List<Sig>) : Sig

    data class Arr(val element: Sig) : Sig

    data class TVar(val name: String) : Sig

    data object Wild : Sig
}

private val PRIMITIVES: Map<Char, String> =
    mapOf('Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short", 'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double")

private class SigParser(private val text: String) {
    var pos: Int = 0

    fun peek(): Char = text.getOrElse(pos) { throw IllegalArgumentException("truncated signature") }

    fun type(): Sig {
        val c = peek()
        pos++
        return when {
            c in PRIMITIVES -> Sig.Prim(PRIMITIVES.getValue(c))
            c == '[' -> Sig.Arr(type())
            c == 'T' -> {
                val end = text.indexOf(';', pos)
                require(end > 0) { "bad type variable" }
                Sig.TVar(text.substring(pos, end)).also { pos = end + 1 }
            }
            c == 'L' -> classType()
            else -> throw IllegalArgumentException("bad signature char '$c'")
        }
    }

    private fun classType(): Sig {
        val name = StringBuilder()
        var args: List<Sig> = emptyList()
        while (true) {
            val c = peek()
            pos++
            when (c) {
                ';' -> return Sig.Cls(name.toString(), args)
                '<' -> args = typeArgs()
                '.' -> {
                    name.append('$')
                    args = emptyList()
                }
                else -> name.append(c)
            }
        }
    }

    private fun typeArgs(): List<Sig> {
        val out = mutableListOf<Sig>()
        while (peek() != '>') {
            val c = peek()
            when (c) {
                '*' -> {
                    pos++
                    out.add(Sig.Wild)
                }
                '+', '-' -> {
                    pos++
                    out.add(type())
                }
                else -> out.add(type())
            }
        }
        pos++
        return out
    }
}

/** Parses a field signature, or the erased descriptor when there is none. */
internal fun fieldSig(signature: String?, desc: String): Sig = parseOrErase(signature) { it.type() } ?: erased(Type.getType(desc))

/** Parameter types of a method; formal type parameters make the generic form unavailable, so the descriptor is used. */
internal fun methodParamSigs(signature: String?, desc: String): List<Sig> {
    if (signature != null && !signature.startsWith("<")) {
        val parsed =
            try {
                val parser = SigParser(signature)
                require(parser.peek() == '(')
                parser.pos++
                val out = mutableListOf<Sig>()
                while (parser.peek() != ')') out.add(parser.type())
                out
            } catch (_: IllegalArgumentException) {
                null
            }
        if (parsed != null && parsed.size == Type.getArgumentTypes(desc).size) return parsed
    }
    return Type.getArgumentTypes(desc).map(::erased)
}

private fun <T> parseOrErase(signature: String?, parse: (SigParser) -> T): T? {
    if (signature == null) return null
    return try {
        parse(SigParser(signature))
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun erased(type: Type): Sig =
    when (type.sort) {
        Type.ARRAY -> Sig.Arr(erased(type.elementType).let { base -> (1 until type.dimensions).fold(base) { acc, _ -> Sig.Arr(acc) } })
        Type.OBJECT -> Sig.Cls(type.internalName, emptyList())
        else -> Sig.Prim(type.className)
    }

internal fun Sig.render(): String =
    when (this) {
        is Sig.Prim -> name
        is Sig.Cls -> internalName.replace('/', '.') + if (args.isEmpty()) "" else args.joinToString(",", "<", ">") { it.render() }
        is Sig.Arr -> element.render() + "[]"
        is Sig.TVar -> name
        Sig.Wild -> "?"
    }

internal fun Sig.erasedClass(): ClassName? =
    when (this) {
        is Sig.Cls -> ClassName(internalName)
        else -> null
    }
