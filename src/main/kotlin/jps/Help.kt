// SPDX-License-Identifier: Apache-2.0
package jps

/**
 * Finds the help resources Jenkins would show for a class (`help.html`, `help-<param>.html` beside the class,
 * searched up the superclass chain) and turns them into [Docs]. Script and Jelly help is flagged, never executed.
 */
private const val MAX_HELP_BYTES: Int = 1024 * 1024

internal class HelpFinder(private val store: ClassStore, private val includeBodies: Boolean) {
    fun docsFor(start: String, parameter: String?): Docs {
        val stem = if (parameter == null) "help" else "help-$parameter"
        val seen = mutableSetOf<String>()
        var current: String? = start
        // The nearest class wins; within a class static HTML beats script or Jelly help.
        while (current != null && current != Types.OBJECT && !current.startsWith("java/") && seen.add(current)) {
            val cls = store.load(current) ?: break
            val dirs = directories(current)
            for (dir in dirs) {
                val html = cls.container.entries["$dir/$stem.html"] ?: continue
                if (html.size > MAX_HELP_BYTES) return Docs(DocsStatus.ABSENT, source = DocsSource("help-html-too-large", "${cls.container.path}!/$dir/$stem.html"))
                return fromHtml(String(html, Charsets.UTF_8), DocsSource("help-html", "${cls.container.path}!/$dir/$stem.html"))
            }
            for (dir in dirs) {
                for (ext in listOf("jelly", "groovy")) {
                    if (cls.container.entries.containsKey("$dir/$stem.$ext")) {
                        return Docs(DocsStatus.DYNAMIC, source = DocsSource("help-$ext", "${cls.container.path}!/$dir/$stem.$ext"))
                    }
                }
            }
            current = cls.node.superName
        }
        return Docs(DocsStatus.ABSENT)
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
