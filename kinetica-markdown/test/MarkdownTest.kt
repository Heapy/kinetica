package io.heapy.kinetica.markdown

import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.host
import io.heapy.kinetica.toSafeHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {
    private fun render(source: String, options: MarkdownOptions = MarkdownOptions()): String =
        KineticaRuntime(debug = false).render {
            markdown(source, options)
        }.tree.toSafeHtml()

    @Test
    fun headingsParagraphsAndInlineStyles() {
        val html = render(
            """
            # Title

            Some **bold** and *italic* and `code` text.

            ## Section
            """.trimIndent(),
        )
        assertTrue("<h1>Title</h1>" in html, html)
        assertTrue("<strong>bold</strong>" in html, html)
        assertTrue("<em>italic</em>" in html, html)
        assertTrue("<code>code</code>" in html, html)
        assertTrue("<h2 id=\"section\">Section</h2>" in html, html)
    }

    @Test
    fun fencedCodeBlockPreservesContentVerbatim() {
        val html = render(
            """
            ```kotlin
            val greeting = "<hello & world>"
            ```
            """.trimIndent(),
        )
        assertTrue("language-kotlin" in html, html)
        assertTrue("&lt;hello &amp; world&gt;" in html, html)
    }

    @Test
    fun listsLinksQuotesTablesAndRules() {
        val html = render(
            """
            - first
            - second **item**

            1. one
            2. two

            > quoted wisdom

            | Name | Value |
            |------|-------|
            | a    | 1     |

            ---

            See [the docs](/docs/state).
            """.trimIndent(),
        )
        assertTrue("<ul><li>first</li><li>second <strong>item</strong></li></ul>" in html, html)
        assertTrue("<ol><li>one</li><li>two</li></ol>" in html, html)
        assertTrue("<blockquote><p>quoted wisdom</p></blockquote>" in html, html)
        assertTrue("<th>Name</th>" in html, html)
        assertTrue("<td>a</td>" in html, html)
        assertTrue("<hr>" in html || "<hr/>" in html || "<hr />" in html, html)
        assertTrue("<a href=\"/docs/state\">the docs</a>" in html, html)
    }

    @Test
    fun unsafeLinkSchemesAreSanitizedByTheRuntime() {
        val html = render("[click](javascript:alert(1))")
        assertTrue("javascript:" !in html, html)
    }

    @Test
    fun directiveInvokesHookAndUnknownDirectivesRenderNothing() {
        var seen: Pair<String, String>? = null
        val html = render(
            """
            ::: example counter

            ::: unknown thing
            """.trimIndent(),
            MarkdownOptions(
                directive = { name, argument ->
                    if (name == "example") {
                        seen = name to argument
                        host("div", props = mapOf("data-example" to argument))
                        true
                    } else {
                        false
                    }
                },
            ),
        )
        assertEquals("example" to "counter", seen)
        assertTrue("data-example=\"counter\"" in html, html)
        assertTrue("unknown" !in html, html)
    }

    @Test
    fun plainTextAndSlugs() {
        val inlines = parseInlines("Using **state** and `derived`!")
        assertEquals("Using state and derived!", plainText(inlines))
        assertEquals("using-state-and-derived", headingSlug(inlines))
    }
}
