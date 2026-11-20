// SPDX-License-Identifier: Apache-2.0
package jps

/** A sanitised, balanced help document event. Produced by [parseHelp]; rendered by [toHtml] and [toMarkdown]. */
internal sealed interface Ev {
    data class Open(val tag: String, val href: String? = null) : Ev

    data class Close(val tag: String) : Ev

    data class Text(val text: String) : Ev

    data object Br : Ev
}

private val ALLOWED: Set<String> =
    setOf("p", "b", "strong", "i", "em", "code", "kbd", "pre", "ul", "ol", "li", "dl", "dt", "dd", "a", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote")
private val ALIASES: Map<String, String> = mapOf("tt" to "code", "var" to "em", "samp" to "code", "u" to "em")
private val DROP_WITH_CONTENT: Set<String> = setOf("script", "style", "iframe", "object", "embed", "svg", "head", "title")
private val BLOCKS: Set<String> = setOf("p", "pre", "ul", "ol", "dl", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6")
private val SIMPLE_ENTITIES: Map<String, String> = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

private fun decodeEntities(text: String): String {
    if ('&' !in text) return text
    return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(text) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") -> codePointString(body.substring(2).toIntOrNull(16)) ?: m.value
            body.startsWith("#") -> codePointString(body.substring(1).toIntOrNull()) ?: m.value
            else -> SIMPLE_ENTITIES[body] ?: m.value
        }
    }
}

private fun codePointString(code: Int?): String? =
    if (code == null || code < 0x20 && code != 0x0a && code != 0x09 || code > 0x10ffff || code in 0xd800..0xdfff) null else String(Character.toChars(code))

private fun safeHref(raw: String): String? {
    val href = decodeEntities(raw).trim()
    if (href.isEmpty()) return null
    val lower = href.lowercase()
    val ok =
        lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:") || href.startsWith("#") || href.startsWith("/") ||
            (':' !in href.substringBefore('/'))
    return if (ok && !lower.startsWith("javascript:") && !lower.startsWith("data:")) href else null
}

private class RawTag(val name: String, val closing: Boolean, val href: String?, val selfClosing: Boolean)

private val ATTR_HREF: Regex = Regex("""\bhref\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

private fun rawTag(body: String): RawTag? {
    var text = body.trim()
    val closing = text.startsWith("/")
    if (closing) text = text.substring(1).trim()
    val selfClosing = text.endsWith("/")
    if (selfClosing) text = text.removeSuffix("/").trim()
    val name = text.takeWhile { !it.isWhitespace() }.lowercase()
    if (name.isEmpty() || !name[0].isLetter()) return null
    val m = ATTR_HREF.find(text)
    val href = m?.let { it.groups[2]?.value ?: it.groups[3]?.value ?: it.groups[4]?.value }
    return RawTag(name, closing, href, selfClosing)
}

/** Tokenises untrusted help HTML into a balanced, allow-listed event list. Pure and deterministic. */
internal fun parseHelp(html: String): List<Ev> {
    val out = mutableListOf<Ev>()
    val stack = ArrayDeque<String>()
    var skipUntil: String? = null
    var i = 0
    var preDepth = 0

    fun closeTop() {
        val top = stack.removeLast()
        out.add(Ev.Close(top))
        if (top == "pre") preDepth--
    }

    fun text(raw: String) {
        if (raw.isEmpty()) return
        val decoded = decodeEntities(raw)
        out.add(Ev.Text(if (preDepth > 0) decoded.replace("\r\n", "\n") else decoded.replace(Regex("\\s+"), " ")))
    }

    while (i < html.length) {
        val lt = html.indexOf('<', i)
        if (lt < 0) {
            if (skipUntil == null) text(html.substring(i))
            break
        }
        if (skipUntil == null) text(html.substring(i, lt))
        if (html.startsWith("<!--", lt)) {
            val end = html.indexOf("-->", lt + 4)
            i = if (end < 0) html.length else end + 3
            continue
        }
        val gt = html.indexOf('>', lt + 1)
        if (gt < 0) {
            if (skipUntil == null) text(html.substring(lt))
            break
        }
        i = gt + 1
        val tag = rawTag(html.substring(lt + 1, gt))
        if (tag == null) {
            continue
        }
        val name = ALIASES[tag.name] ?: tag.name
        if (skipUntil != null) {
            if (tag.closing && tag.name == skipUntil) skipUntil = null
            continue
        }
        if (!tag.closing && tag.name in DROP_WITH_CONTENT && !tag.selfClosing) {
            skipUntil = tag.name
            continue
        }
        if (name == "br") {
            if (!tag.closing) out.add(Ev.Br)
            continue
        }
        if (name !in ALLOWED) continue
        if (tag.closing) {
            if (name in stack) while (stack.isNotEmpty()) {
                val top = stack.last()
                closeTop()
                if (top == name) break
            }
            continue
        }
        if (name in BLOCKS || name == "li" || name == "dt" || name == "dd") {
            // Implied end tags: a new block closes an open <p>; a new <li>/<dt>/<dd> closes its sibling.
            while (stack.isNotEmpty() && stack.last() == "p") closeTop()
            if ((name == "li" || name == "dt" || name == "dd") && stack.isNotEmpty() && stack.last() in setOf("li", "dt", "dd")) closeTop()
        }
        val href = if (name == "a") tag.href?.let(::safeHref) else null
        stack.addLast(name)
        if (name == "pre") preDepth++
        out.add(Ev.Open(name, href))
    }
    while (stack.isNotEmpty()) closeTop()
    return trimEvents(out)
}

private fun trimEvents(events: List<Ev>): List<Ev> {
    val list = events.toMutableList()
    while (list.isNotEmpty() && (list.first() as? Ev.Text)?.text?.isBlank() == true) list.removeAt(0)
    while (list.isNotEmpty() && (list.last() as? Ev.Text)?.text?.isBlank() == true) list.removeAt(list.size - 1)
    (list.firstOrNull() as? Ev.Text)?.let { list[0] = Ev.Text(it.text.trimStart()) }
    (list.lastOrNull() as? Ev.Text)?.let { list[list.size - 1] = Ev.Text(it.text.trimEnd()) }
    return list
}

private fun escapeHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun toHtml(events: List<Ev>): String =
    buildString {
        for (ev in events) {
            when (ev) {
                is Ev.Open -> append(if (ev.href != null) "<a href=\"${escapeHtml(ev.href).replace("\"", "&quot;")}\">" else "<${ev.tag}>")
                is Ev.Close -> append("</${ev.tag}>")
                is Ev.Text -> append(escapeHtml(ev.text))
                Ev.Br -> append("<br>")
            }
        }
    }

private val MD_SPECIAL: Regex = Regex("([\\\\`*_\\[\\]<>])")

/** Fixed HTML-to-Markdown mapping for the allow-listed tags. */
internal fun toMarkdown(events: List<Ev>): String {
    val out = StringBuilder()
    val lists = ArrayDeque<MutableList<Int>>() // per open list: [0]=ordered flag, [1]=next number
    val links = ArrayDeque<String?>()
    var inPre = false
    var quote = 0

    fun blockBreak() {
        if (out.isNotEmpty() && !out.endsWith("\n\n")) {
            while (out.endsWith(" ")) out.setLength(out.length - 1)
            if (!out.endsWith("\n")) out.append('\n')
            out.append('\n')
        }
    }

    fun prefix() = "> ".repeat(quote)

    for (ev in events) {
        when (ev) {
            is Ev.Text -> {
                if (inPre) {
                    out.append(ev.text)
                } else {
                    val escaped = MD_SPECIAL.replace(ev.text) { "\\" + it.value }
                    out.append(if (out.isEmpty() || out.endsWith("\n")) escaped.trimStart() else escaped)
                }
            }
            Ev.Br -> out.append("  \n")
            is Ev.Open ->
                when (ev.tag) {
                    "p", "ul", "ol", "dl" -> {
                        if (ev.tag != "p" && lists.isNotEmpty()) {
                            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                        } else {
                            blockBreak()
                        }
                        if (ev.tag == "ul") lists.addLast(mutableListOf(0, 1))
                        if (ev.tag == "ol") lists.addLast(mutableListOf(1, 1))
                        out.append(prefix())
                    }
                    "li" -> {
                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                        val list = lists.lastOrNull() ?: mutableListOf(0, 1)
                        out.append("  ".repeat((lists.size - 1).coerceAtLeast(0)))
                        if (list[0] == 1) {
                            out.append("${list[1]}. ")
                            list[1] = list[1] + 1
                        } else {
                            out.append("- ")
                        }
                    }
                    "dt" -> {
                        blockBreak()
                        out.append("**")
                    }
                    "dd" -> {
                        if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
                        out.append(": ")
                    }
                    "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        blockBreak()
                        out.append("#".repeat(ev.tag.substring(1).toInt())).append(' ')
                    }
                    "blockquote" -> {
                        blockBreak()
                        quote++
                        out.append(prefix())
                    }
                    "b", "strong" -> out.append("**")
                    "i", "em" -> out.append('*')
                    "code", "kbd" -> if (!inPre) out.append('`')
                    "pre" -> {
                        blockBreak()
                        out.append("```\n")
                        inPre = true
                    }
                    "a" -> {
                        links.addLast(ev.href)
                        if (ev.href != null) out.append('[')
                    }
                    else -> Unit
                }
            is Ev.Close ->
                when (ev.tag) {
                    "p", "dl" -> blockBreak()
                    "ul", "ol" -> {
                        lists.removeLastOrNull()
                        if (lists.isEmpty()) blockBreak()
                    }
                    "li" -> if (!out.endsWith("\n")) out.append('\n')
                    "dt" -> {
                        out.append("**")
                        out.append('\n')
                    }
                    "dd" -> if (!out.endsWith("\n")) out.append('\n')
                    "h1", "h2", "h3", "h4", "h5", "h6" -> blockBreak()
                    "blockquote" -> {
                        quote--
                        blockBreak()
                    }
                    "b", "strong" -> out.append("**")
                    "i", "em" -> out.append('*')
                    "code", "kbd" -> if (!inPre) out.append('`')
                    "pre" -> {
                        if (!out.endsWith("\n")) out.append('\n')
                        out.append("```")
                        inPre = false
                        out.append("\n\n")
                    }
                    "a" -> {
                        val href = links.removeLastOrNull()
                        if (href != null) out.append("](").append(href.replace(")", "%29").replace(" ", "%20")).append(')')
                    }
                    else -> Unit
                }
        }
    }
    return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
}

/** First sentence of the plain text, whitespace-normalised, at most 300 characters. */
internal fun summaryOf(events: List<Ev>): String {
    val plain = events.filterIsInstance<Ev.Text>().joinToString("") { it.text }.replace(Regex("\\s+"), " ").trim()
    val sentence = Regex("^(.+?[.!?])(\\s|$)").find(plain)?.groupValues?.get(1) ?: plain
    return sentence.take(300)
}
