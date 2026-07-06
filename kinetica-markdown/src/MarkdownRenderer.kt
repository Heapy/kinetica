package io.heapy.kinetica.markdown

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.host
import io.heapy.kinetica.text

public data class MarkdownOptions(
    /** Give h2–h6 headings an `id` derived from their text so pages can deep-link. */
    val headingAnchors: Boolean = true,
    /**
     * Hook for `::: name argument` directive blocks. Return true when handled;
     * unhandled directives render nothing.
     */
    val directive: (ComponentScope.(name: String, argument: String) -> Boolean)? = null,
    /** Server-side fenced-code highlighter. Unsupported languages render as plain escaped text. */
    val codeHighlighter: CodeHighlighter = MarkdownCodeHighlighter,
)

/** Parses [source] and emits it as Kinetica host nodes. */
public fun ComponentScope.markdown(
    source: String,
    options: MarkdownOptions = MarkdownOptions(),
) {
    markdownBlocks(parseMarkdown(source), options)
}

public fun ComponentScope.markdownBlocks(
    blocks: List<MdBlock>,
    options: MarkdownOptions = MarkdownOptions(),
) {
    blocks.forEach { block -> renderBlock(block, options) }
}

private fun ComponentScope.renderBlock(block: MdBlock, options: MarkdownOptions) {
    when (block) {
        is MdHeading -> {
            val props = if (options.headingAnchors && block.level >= 2) {
                mapOf("id" to headingSlug(block.inlines))
            } else {
                emptyMap()
            }
            host("h${block.level}", props = props) { renderInlines(block.inlines) }
        }

        is MdParagraph -> host("p") { renderInlines(block.inlines) }

        is MdCodeBlock -> highlightedCodeBlock(block.code, block.language, options.codeHighlighter)

        is MdList -> host(if (block.ordered) "ol" else "ul") {
            block.items.forEach { item ->
                host("li") { renderInlines(item) }
            }
        }

        is MdQuote -> host("blockquote") {
            markdownBlocks(
                block.blocks,
                MarkdownOptions(
                    headingAnchors = false,
                    directive = null,
                    codeHighlighter = options.codeHighlighter,
                ),
            )
        }

        is MdRule -> host("hr")

        is MdTable -> host("table") {
            host("thead") {
                host("tr") {
                    block.header.forEach { cell -> host("th") { renderInlines(cell) } }
                }
            }
            host("tbody") {
                block.rows.forEach { row ->
                    host("tr") {
                        row.forEach { cell -> host("td") { renderInlines(cell) } }
                    }
                }
            }
        }

        is MdDirective -> {
            options.directive?.invoke(this, block.name, block.argument)
        }
    }
}

private fun ComponentScope.renderInlines(inlines: List<MdInline>) {
    inlines.forEach { inline ->
        when (inline) {
            is MdText -> text(inline.text, semantics = null)
            is MdCode -> host("code") { text(inline.text, semantics = null) }
            is MdEmphasis -> host(if (inline.strong) "strong" else "em") {
                renderInlines(inline.inlines)
            }
            is MdLink -> host("a", props = mapOf("href" to inline.href)) {
                renderInlines(inline.label)
            }
        }
    }
}
