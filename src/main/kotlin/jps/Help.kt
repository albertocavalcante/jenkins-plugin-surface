// SPDX-License-Identifier: Apache-2.0
package jps

/**
 * Finds the help resources Jenkins would show for a class (`help.html`, `help-<param>.html` beside the class,
 * searched up the superclass chain) and turns them into [Docs]. Script and Jelly help is flagged, never executed.
 */
internal class HelpFinder(private val store: ClassStore, private val includeBodies: Boolean) {
    fun docsFor(start: String, parameter: String?): Docs {
        val stem = if (parameter == null) "help" else "help-$parameter"
        var dynamic: DocsSource? = null
        var current: String? = start
        while (current != null && current != Types.OBJECT && !current.startsWith("java/")) {
            val cls = store.load(current) ?: break
            for (dir in directories(current)) {
                val html = cls.container.entries["$dir/$stem.html"]
                if (html != null) return fromHtml(String(html, Charsets.UTF_8), DocsSource("help-html", "${cls.container.path}!/$dir/$stem.html"))
                if (dynamic == null) {
                    for (ext in listOf("jelly", "groovy")) {
                        if (cls.container.entries.containsKey("$dir/$stem.$ext")) dynamic = DocsSource("help-$ext", "${cls.container.path}!/$dir/$stem.$ext")
                    }
                }
            }
            current = cls.node.superName
        }
        return if (dynamic != null) Docs(DocsStatus.DYNAMIC, source = dynamic) else Docs(DocsStatus.ABSENT)
    }

    /** `pkg/Outer/Inner` (Jenkins's rule) and `pkg/Inner` (Structs's simple-name rule). */
    private fun directories(internalName: String): List<String> {
        val primary = internalName.replace('$', '/')
        val pkg = internalName.substringBeforeLast('/', "")
        val simple = internalName.substringAfterLast('/').substringAfterLast('$')
        val secondary = if (pkg.isEmpty()) simple else "$pkg/$simple"
        return listOf(primary, secondary).distinct()
    }

    private fun fromHtml(html: String, source: DocsSource): Docs {
        val events = parseHelp(html)
        return Docs(
            status = DocsStatus.PRESENT,
            summary = summaryOf(events),
            bodyHtml = if (includeBodies) toHtml(events) else null,
            bodyMarkdown = if (includeBodies) toMarkdown(events) else null,
            locale = "en",
            source = source,
        )
    }
}
