# Markdown

<!-- code: kinetica-markdown/src/Markdown.kt (parseMarkdown), kinetica-markdown/src/MarkdownRenderer.kt (markdown, MarkdownOptions) -->

`kinetica-markdown` parses Markdown into an AST and renders it as Kinetica nodes — which is how
**this site works**: every page is a `.md` resource, parsed on the JVM, rendered through the same
`ComponentScope` DSL as any component, and serialized with `toSafeHtml()`.

## Usage

<!-- code: kinetica-markdown/src/MarkdownRenderer.kt (ComponentScope.markdown) -->

```kotlin
import io.heapy.kinetica.markdown.markdown

@UiComponent
fun ComponentScope.Article(source: String) {
    host("article") {
        markdown(source)
    }
}
```

Because output is ordinary nodes, markdown content composes with everything else: it can sit
inside a router entry, be server-rendered, be snapshot-tested, and inherit page CSS.

## Supported syntax

<!-- code: kinetica-markdown/src/Markdown.kt (parseMarkdown, parseInlines), kinetica-markdown/src/CodeHighlight.kt (MarkdownCodeHighlighter) -->

| Feature | Notes |
|---------|-------|
| Headings `#`–`######` | h2+ get `id` anchors derived from their text |
| Paragraphs, `**bold**`, `*italic*`, `` `code` `` | `__`/`_` delimiters too; inline nesting supported |
| Links `[label](href)` | hrefs pass the runtime's URL sanitizer — `javascript:` never renders |
| Fenced code blocks ```` ```lang ```` | server-side spans for Kotlin, JSON, HTML, and YAML; unsupported languages keep `class="language-…"` |
| Lists `-` / `*` / `1.` / `1)` | single level, continuation lines |
| Blockquotes, `---` rules, tables | GFM-style pipe tables |
| HTML comments `<!-- … -->` | skipped entirely — these docs use them to link sections to source files |
| Directives `::: name argument` | extension point, see below |

Parsing is available standalone: `parseMarkdown(source): List<MdBlock>` with a small typed AST
(`MdHeading`, `MdParagraph`, `MdCodeBlock`, `MdList`, `MdQuote`, `MdTable`, `MdRule`,
`MdDirective`, inline `MdText` / `MdCode` / `MdEmphasis` / `MdLink`).

## Directives — embedding live components

<!-- code: kinetica-markdown/src/MarkdownRenderer.kt (MarkdownOptions.directive), docs/docs-site/src/Layout.kt (renderDirective), docs/docs-client/src/main.kt (main) -->

A directive line hands control back to your code mid-document:

```kotlin
markdown(source, MarkdownOptions(directive = { name, argument ->
    when (name) {
        "example" -> { LiveExample(argument); true }
        else -> false                       // render nothing — an unhandled directive emits no nodes
    }
}))
```

This page's live widgets use exactly this: the source contains `::: example counter`, the site's
directive hook renders a placeholder `div[data-example]`, and a small Kinetica browser bundle
mounts an interactive app into it:

::: example counter

## Safety by construction

<!-- code: kinetica-markdown/src/MarkdownRenderer.kt (renderInlines), kinetica-runtime/src/Html.kt (toSafeHtml, isSafeHtmlAttributeValue) -->

The renderer never emits raw HTML. Text becomes `TextNode`s (escaped by every serializer),
attributes go through the same allowlist as hand-written components, and unsafe URL schemes are
stripped by the runtime — a markdown document is exactly as safe as any other component tree.
