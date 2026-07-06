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
    fun kotlinCodeBlocksAreHighlightedOnTheServer() {
        val html = render(
            """
            ```kotlin
            fun greet(name: String) = "Hello, ${'$'}name"
            ```
            """.trimIndent(),
        )
        assertTrue("<span class=\"tok-keyword\">fun</span>" in html, html)
        assertTrue("<span class=\"tok-function\">greet</span>" in html, html)
        assertTrue("<span class=\"tok-type\">String</span>" in html, html)
        assertTrue("<span class=\"tok-string\">\"Hello, ${'$'}name\"</span>" in html, html)
    }

    @Test
    fun jsonCodeBlocksAreHighlightedOnTheServer() {
        val html = render(
            """
            ```json
            {"enabled": true, "count": 3, "items": null}
            ```
            """.trimIndent(),
        )
        assertTrue("<span class=\"tok-property\">\"enabled\"</span>" in html, html)
        assertTrue("<span class=\"tok-boolean\">true</span>" in html, html)
        assertTrue("<span class=\"tok-number\">3</span>" in html, html)
        assertTrue("<span class=\"tok-null\">null</span>" in html, html)
    }

    @Test
    fun htmlCodeBlocksAreHighlightedOnTheServer() {
        val html = render(
            """
            ```html
            <a href="/docs">Docs &amp; API</a>
            ```
            """.trimIndent(),
        )
        assertTrue("<span class=\"tok-tag\">a</span>" in html, html)
        assertTrue("<span class=\"tok-attribute\">href</span>" in html, html)
        assertTrue("<span class=\"tok-string\">\"/docs\"</span>" in html, html)
        assertTrue("<span class=\"tok-entity\">&amp;amp;</span>" in html, html)
    }

    @Test
    fun yamlCodeBlocksAreHighlightedOnTheServer() {
        val html = render(
            """
            ```yml
            ---
            name: &app "kinetica"
            enabled: true
            retries: 3
            path: /docs/markdown
            services:
              - *app
            # keep docs fast
            ```
            """.trimIndent(),
        )
        assertTrue("language-yaml" in html, html)
        assertTrue("<span class=\"tok-property\">name</span>" in html, html)
        assertTrue("<span class=\"tok-entity\">&amp;app</span>" in html, html)
        assertTrue("<span class=\"tok-string\">\"kinetica\"</span>" in html, html)
        assertTrue("<span class=\"tok-boolean\">true</span>" in html, html)
        assertTrue("<span class=\"tok-number\">3</span>" in html, html)
        assertTrue("<span class=\"tok-entity\">*app</span>" in html, html)
        assertTrue("<span class=\"tok-comment\"># keep docs fast</span>" in html, html)
    }

    @Test
    fun unsupportedCodeLanguagesFallBackToPlainCode() {
        val html = render(
            """
            ```brainfuck
            ++<--
            ```
            """.trimIndent(),
        )
        assertTrue("language-brainfuck" in html, html)
        assertTrue("++&lt;--" in html, html)
        assertTrue("tok-keyword" !in html, html)
    }

    @Test
    fun codeHighlighterRegistryCanAddLanguages() {
        val highlighter = CodeHighlighterRegistry(
            languages = mapOf(
                "sql" to LanguageCodeHighlighter { code ->
                    listOf(CodeToken(code, CodeTokenKind.Keyword))
                },
            ),
        )
        val html = render(
            """
            ```sql
            select 1
            ```
            """.trimIndent(),
            MarkdownOptions(codeHighlighter = highlighter),
        )
        assertTrue("language-sql" in html, html)
        assertTrue("<span class=\"tok-keyword\">select 1</span>" in html, html)
    }

    @Test
    fun codeHighlighterRegistryNormalizesAliasesAndPlainFallbacks() {
        val highlight = MarkdownCodeHighlighter.highlight(" KTS ", "val answer: Int = 42")
        assertEquals("kotlin", highlight.language)
        assertTrue(highlight.tokens.any { it.text == "val" && it.kind == CodeTokenKind.Keyword })

        val extended = CodeHighlighterRegistry(emptyMap())
            .plus(
                language = "GraphQL",
                highlighter = LanguageCodeHighlighter { code -> listOf(CodeToken(code, CodeTokenKind.Property)) },
                aliases = listOf("gql"),
            )
        val custom = extended.highlight("GQL", "query")
        assertEquals("graphql", custom.language)
        assertEquals(CodeTokenKind.Property, custom.tokens.single().kind)

        val unknown = extended.highlight("  custom format  ", "plain <code>")
        assertEquals("custom", unknown.language)
        assertEquals(listOf(CodeToken.plain("plain <code>")), unknown.tokens)

        val noLanguage = extended.highlight(null, "plain")
        assertEquals(null, noLanguage.language)
        assertEquals(listOf(CodeToken.plain("plain")), noLanguage.tokens)
    }

    @Test
    fun inlineParserCoversMalformedLinksEscapesAndEmptyEmphasis() {
        val inlines = parseInlines("""Escaped \*star [no close [label] no paren [empty]() **ok** _u_ * `unterminated""")

        assertEquals(
            "Escaped *star [no close [label] no paren empty ok u * `unterminated",
            plainText(inlines),
        )
        assertTrue(inlines.any { it is MdText && "[no close" in it.text })
        assertTrue(inlines.any { it is MdLink && it.href.isEmpty() })
        assertTrue(inlines.any { it is MdEmphasis && it.strong && plainText(it.inlines) == "ok" })
        assertTrue(inlines.any { it is MdEmphasis && !it.strong && plainText(it.inlines) == "u" })
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
    fun parserCoversListContinuationsRuleVariantsAndTableEdges() {
        val html = render(
            """
            - first
            - second **item**
              continued

                indented paragraph, not a list

            1. one
            2) two
            1000. not a list marker

            * asterisk item
            1. ordered

            ***
            ___

            ####### not a heading
            #not a heading
            ###### Max ####

            | Bad | Table |
            | abc |

            paragraph
            | Later | Table |
            |-------|-------|
            | left  | right |
            """.trimIndent(),
            MarkdownOptions(headingAnchors = false),
        )

        assertTrue("<ul><li>first</li><li>second <strong>item</strong> continued</li></ul>" in html, html)
        assertTrue("<p>indented paragraph, not a list</p>" in html, html)
        assertTrue("<ol><li>one</li><li>two</li></ol>" in html, html)
        assertTrue("1000. not a list marker" in html, html)
        assertTrue("<ul><li>asterisk item</li></ul>" in html, html)
        assertTrue("<ol><li>ordered</li></ol>" in html, html)
        assertTrue(html.split("<hr").size >= 3, html)
        assertTrue("<p>####### not a heading #not a heading</p>" in html, html)
        assertTrue("<h6>Max</h6>" in html, html)
        assertTrue("<p>| Bad | Table | | abc |</p>" in html, html)
        assertTrue("<p>paragraph</p><table>" in html, html)
    }

    @Test
    fun parserCoversUnterminatedFenceAndEmptyDirective() {
        val blocks = parseMarkdown(
            """
            ```
            unterminated fence
            """.trimIndent(),
        )
        assertEquals(listOf(MdCodeBlock(null, "unterminated fence")), blocks)

        val html = render(":::\n\ntext")
        assertTrue("<p>text</p>" in html, html)
    }

    @Test
    fun htmlHighlighterCoversDeclarationsSelfClosingTagsAndComments() {
        val html = render(
            """
            ```html
            <!doctype html>
            <img alt='Docs &amp; API' src=/logo.svg/>
            <!-- comment -->
            <a data-x=1 href="/docs">Docs &amp; API</a>
            ```
            """.trimIndent(),
        )

        assertTrue("<span class=\"tok-tag\">doctype</span>" in html, html)
        assertTrue("<span class=\"tok-tag\">img</span>" in html, html)
        assertTrue("<span class=\"tok-string\">'Docs &amp;amp; API'</span>" in html, html)
        assertTrue("<span class=\"tok-comment\">&lt;!-- comment --&gt;</span>" in html, html)
        assertTrue("<span class=\"tok-attribute\">data-x</span>" in html, html)
        assertTrue("<span class=\"tok-entity\">&amp;amp;</span>" in html, html)
    }

    @Test
    fun yamlHighlighterCoversTagsQuotedScalarsDocumentEndAndLiterals() {
        val html = render(
            """
            ```yaml
            ---
            alias: *app
            single: 'it''s ok'
            negative: -3.5
            nothing: null
            enabled: ON
            custom: !Ref value
            folded: yes and no
            services:
              - *app
            ...
            ```
            """.trimIndent(),
        )

        assertTrue("<span class=\"tok-entity\">*app</span>" in html, html)
        assertTrue("<span class=\"tok-string\">'it''s ok'</span>" in html, html)
        assertTrue("<span class=\"tok-number\">-3.5</span>" in html, html)
        assertTrue("<span class=\"tok-null\">null</span>" in html, html)
        assertTrue("<span class=\"tok-boolean\">ON</span>" in html, html)
        assertTrue("<span class=\"tok-type\">!Ref</span>" in html, html)
        assertTrue("yes and no" in html, html)
        assertTrue("<span class=\"tok-punctuation\">...</span>" in html, html)
    }

    @Test
    fun kotlinAndJsonHighlightersCoverEscapesCommentsAndWordBoundaries() {
        val kotlinSource = "```kt\n" +
            "/* outer /* inner */ done */\n" +
            "val c = '\\n'\n" +
            "val raw = " + "\"\"\"" + "multi\nline" + "\"\"\"" + "\n" +
            "true false null List<String> value + 1\n" +
            "```\n"
        val kotlinHtml = render(kotlinSource)
        assertTrue("<span class=\"tok-comment\">/* outer /* inner */ done */</span>" in kotlinHtml, kotlinHtml)
        assertTrue("<span class=\"tok-string\">'\\n'</span>" in kotlinHtml, kotlinHtml)
        assertTrue("<span class=\"tok-string\">\"\"\"multi" in kotlinHtml, kotlinHtml)
        assertTrue("<span class=\"tok-boolean\">true</span>" in kotlinHtml, kotlinHtml)
        assertTrue("<span class=\"tok-null\">null</span>" in kotlinHtml, kotlinHtml)
        assertTrue("<span class=\"tok-operator\">+</span>" in kotlinHtml, kotlinHtml)

        val jsonHtml = render(
            """
            ```jsonc
            {"text": "a\"b", "array": [false, null, -12.5e+2], "word": truex}
            ```
            """.trimIndent(),
        )
        assertTrue("""<span class="tok-string">"a\"b"</span>""" in jsonHtml, jsonHtml)
        assertTrue("<span class=\"tok-boolean\">false</span>" in jsonHtml, jsonHtml)
        assertTrue("<span class=\"tok-number\">-12.5e+2</span>" in jsonHtml, jsonHtml)
        assertTrue("truex" in jsonHtml, jsonHtml)
    }

    @Test
    fun codeHighlightersCoverUnterminatedAndMalformedEdges() {
        val kotlin = MarkdownCodeHighlighter.highlight(
            "kotlin",
            "\"unterminated\n" +
                "'unterminated\n" +
                "value?.call(1); @file:OptIn\n" +
                "/* unclosed",
        )
        assertTrue(kotlin.tokens.any { it.text == "\"unterminated" && it.kind == CodeTokenKind.StringLiteral })
        assertTrue(kotlin.tokens.any { it.text == "'unterminated" && it.kind == CodeTokenKind.StringLiteral })
        assertTrue(kotlin.tokens.any { it.text == "/* unclosed" && it.kind == CodeTokenKind.Comment })
        assertTrue(kotlin.tokens.any { it.text == "call" && it.kind == CodeTokenKind.Function })
        assertTrue(kotlin.tokens.any { it.text == "@" && it.kind == CodeTokenKind.Punctuation })
        assertTrue(kotlin.tokens.any { it.text == ":" && it.kind == CodeTokenKind.Operator })

        val json = MarkdownCodeHighlighter.highlight("json", "{\"open\": \"unterminated\", truth: truex, -}")
        assertTrue(json.tokens.any { it.text == "\"unterminated\"" && it.kind == CodeTokenKind.StringLiteral })
        assertTrue(json.tokens.filter { it.kind == null }.joinToString("") { it.text }.contains("truex"))
        assertTrue(json.tokens.any { it.text == "-" && it.kind == CodeTokenKind.Number })

        val html = MarkdownCodeHighlighter.highlight(
            "html",
            "&incomplete text\n" +
                "<!doctype html>\n" +
                "<img alt='unterminated &copy src=/logo.svg/>",
        )
        assertTrue(html.tokens.any { it.text == "<!" && it.kind == CodeTokenKind.Punctuation })
        assertTrue(html.tokens.any { it.text == "&incomplete" && it.kind == CodeTokenKind.Entity })
        assertTrue(html.tokens.any { it.text == "'unterminated &copy src=/logo.svg/>" && it.kind == CodeTokenKind.StringLiteral })

        val yaml = MarkdownCodeHighlighter.highlight(
            "yaml",
            "---not-marker\n" +
                "plus: +12_3.4E-5\n" +
                "bad: 12x\n" +
                "empty-ref: &\n" +
                "empty-tag: !\n" +
                "plain: value:still\n" +
                "...x\n",
        )
        assertTrue(yaml.tokens.any { it.text == "+12_3.4E-5" && it.kind == CodeTokenKind.Number })
        assertTrue(yaml.tokens.any { it.text == "12x" && it.kind == CodeTokenKind.Number })
        assertTrue(yaml.tokens.any { it.text == "&" && it.kind == CodeTokenKind.Entity })
        assertTrue(yaml.tokens.any { it.text == "!" && it.kind == CodeTokenKind.Type })
        assertTrue(yaml.tokens.any { it.text == "---not-marker" && it.kind == null })

        assertEquals(null, MarkdownCodeHighlighter.highlight(" !!! ", "plain").language)
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
