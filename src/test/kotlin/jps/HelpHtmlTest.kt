// SPDX-License-Identifier: Apache-2.0
package jps

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HelpHtmlTest {
    private fun html(input: String) = toHtml(parseHelp(input))

    private fun md(input: String) = toMarkdown(parseHelp(input))

    @Test
    fun scriptsStylesAndEventHandlersAreRemoved() {
        val out = html("<p onclick=\"x()\">Hi<script>alert(1)</script><style>p{}</style> there</p>")
        assertEquals("<p>Hi there</p>", out)
    }

    @Test
    fun unsafeLinksLoseTheirHref() {
        assertEquals("<a>x</a>", html("<a href=\"javascript:alert(1)\">x</a>"))
        assertEquals("<a>x</a>", html("<a href=\"data:text/html,x\">x</a>"))
        assertEquals("<a href=\"https://example.org/a?b=1&amp;c=2\">x</a>", html("<a href='https://example.org/a?b=1&amp;c=2'>x</a>"))
        assertEquals("<a href=\"#anchor\">x</a>", html("<a href=\"#anchor\">x</a>"))
    }

    @Test
    fun unbalancedMarkupIsBalancedAndAliasesAreMapped() {
        assertEquals("<ul><li>a</li><li>b</li></ul>", html("<ul><li>a<li>b</ul>"))
        assertEquals("<p>one</p><p>two</p>", html("<p>one<p>two"))
        assertEquals("<code>x</code>", html("<tt>x</tt>"))
        assertEquals("<p>text</p>", html("<p>text</b></p>"))
    }

    @Test
    fun entitiesRoundTripAndUnknownTagsKeepTheirText() {
        assertEquals("<p>a &lt; b &amp; c</p>", html("<p>a &lt; b &amp; c</p>"))
        assertEquals("<p>kept</p>", html("<p><st:foo bar=\"1\">kept</st:foo></p>"))
    }

    @Test
    fun commentsAreDropped() {
        assertEquals("<p>a</p>", html("<!-- hidden --><p>a</p>"))
    }

    @Test
    fun preservesPreformattedWhitespace() {
        assertEquals("<pre>a\n  b</pre>", html("<pre>a\n  b</pre>"))
        assertEquals("```\na\n  b\n```", md("<pre>a\n  b</pre>"))
    }

    @Test
    fun markdownMapping() {
        assertEquals("Hello **bold** and *it* and `code`.", md("<p>Hello <b>bold</b> and <i>it</i> and <code>code</code>.</p>"))
        assertEquals("First\n\nSecond", md("<p>First</p><p>Second</p>"))
        assertEquals("- a\n- b", md("<ul><li>a</li><li>b</li></ul>"))
        assertEquals("1. a\n2. b", md("<ol><li>a</li><li>b</li></ol>"))
        assertEquals("- a\n  - b\n- c", md("<ul><li>a<ul><li>b</li></ul></li><li>c</li></ul>"))
        assertEquals("See [docs](https://example.org/x).", md("<p>See <a href=\"https://example.org/x\">docs</a>.</p>"))
        assertEquals("## Title", md("<h2>Title</h2>"))
        assertEquals("line  \nnext", md("line<br>next"))
    }

    @Test
    fun markdownEscapesSpecialCharactersInText() {
        assertEquals("a \\* b \\_c\\_ \\[x\\]", md("<p>a * b _c_ [x]</p>"))
        assertFalse(md("<p>`x`</p>").startsWith("`"))
    }

    @Test
    fun summaryIsTheFirstSentence() {
        assertEquals("Retries the body.", summaryOf(parseHelp("<p>Retries the   body. It does\nmore things.</p>")))
        assertEquals("No terminator here", summaryOf(parseHelp("<p>No terminator here</p>")))
        assertEquals(300, summaryOf(parseHelp("<p>" + "x".repeat(500) + "</p>")).length)
    }

    @Test
    fun outputIsStableAcrossRuns() {
        val input = "<p>A <b>b</b></p><ul><li>x<li>y</ul>"
        assertEquals(html(input), html(input))
        assertEquals(md(input), md(input))
    }
}
