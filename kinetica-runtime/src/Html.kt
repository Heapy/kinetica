package io.heapy.kinetica

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

public fun Node.toSafeHtml(): String =
    buildString {
        appendSafeHtml(this@toSafeHtml)
    }

public fun escapeHtmlText(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(character)
            }
        }
    }

public fun escapeHtmlAttribute(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(character)
            }
        }
    }

private fun StringBuilder.appendSafeHtml(node: Node) {
    when (node) {
        is FragmentNode -> node.children.forEach(::appendSafeHtml)
        is TextNode -> append(escapeHtmlText(node.value))
        is HostNode -> appendHostNode(node)
        is ClientRef -> appendClientRef(node)
        is TemplateNode -> appendHostNode(node.materialize())
    }
}

private fun StringBuilder.appendHostNode(node: HostNode) {
    val tagName = safeHtmlTagName(node.tag)
    append('<')
    append(tagName)
    if (tagName != node.tag) {
        appendAttribute("data-kinetica-tag", node.tag)
    }
    node.props
        .filter { (name, value) -> isPublicHtmlAttribute(name, value) }
        .forEach { (name, value) -> appendAttribute(name, value) }
    node.key?.let { key -> appendAttribute("data-kinetica-key", key) }
    append('>')
    node.children.forEach(::appendSafeHtml)
    append("</")
    append(tagName)
    append('>')
}

private fun StringBuilder.appendClientRef(node: ClientRef) {
    append("<template")
    appendAttribute("data-kinetica-client-ref", node.componentId)
    appendAttribute("data-kinetica-props", KineticaJson.encodeToString(JsonObject.serializer(), node.props))
    append("></template>")
}

private fun StringBuilder.appendAttribute(name: String, value: String) {
    append(' ')
    append(name)
    append("=\"")
    append(escapeHtmlAttribute(value))
    append('"')
}

public fun isPublicHtmlAttribute(name: String, value: String): Boolean =
    isPublicHtmlAttributeName(name) &&
        isSafeHtmlAttributeValue(name, value)

public fun isSafeHtmlAttributeValue(name: String, value: String): Boolean {
    val normalizedName = name.lowercase()
    if (normalizedName in HtmlContentAttributes) {
        return false
    }
    if (normalizedName !in UrlValuedAttributes) {
        return true
    }

    val trimmed = value.trimStart()
    if (trimmed.isEmpty() ||
        trimmed.startsWith("#") ||
        trimmed.startsWith("/") ||
        trimmed.startsWith("./") ||
        trimmed.startsWith("../") ||
        trimmed.startsWith("?")
    ) {
        return true
    }

    val colon = trimmed.indexOf(':')
    if (colon < 0) {
        return true
    }
    val firstPathSeparator = listOf(
        trimmed.indexOf('/'),
        trimmed.indexOf('?'),
        trimmed.indexOf('#'),
    ).filter { index -> index >= 0 }.minOrNull() ?: Int.MAX_VALUE
    if (colon > firstPathSeparator) {
        return true
    }

    val scheme = trimmed.substring(0, colon).lowercase()
    return scheme in SafeUrlSchemes
}

private fun isPublicHtmlAttributeName(name: String): Boolean =
    isSafeHtmlName(name) &&
        !name.startsWith("event:") &&
        !name.startsWith("frame:") &&
        !name.startsWith("on", ignoreCase = true)

private fun isSafeHtmlName(name: String): Boolean =
    name.isNotEmpty() &&
        name.first().isLetter() &&
        name.all { character ->
            character.isLetterOrDigit() ||
                character == '-' ||
                character == '_' ||
                character == ':' ||
                character == '.'
        }

/**
 * Maps a [HostNode] tag to the concrete HTML element name emitted by [toSafeHtml].
 *
 * Abstract layout tags (`column`/`row`) map to `div`, matching the browser renderer's flex layout.
 * Anything else not on the explicit allowlist also falls back to `div`. The allowlist is the
 * security boundary — a hand-built `HostNode(tag = "script")` or `"iframe"` serializes as
 * `<div data-kinetica-tag="script">…</div>` rather than a live element, so SSR cannot introduce a
 * script-injection sink even if attacker-influenced data ever reaches a tag name.
 *
 * This is intentionally **narrower** than `browserTagNameFor` (kinetica-browser): the browser
 * renderer maps the interactive DSL tags `textInput`/`checkbox` to `<input>` for a live hydrated
 * app, but SSR does not hydrate, so those tags render as a neutral `<div>` here. Pages that need
 * an interactive input must use a `ClientRef` island rather than relying on SSR for form controls.
 */
internal fun safeHtmlTagName(tag: String): String =
    when (tag) {
        // Abstract layout tags — rendered as a styled div, same as the browser renderer.
        "column", "row" -> "div"
        // Allowlisted concrete HTML tags safe to emit from SSR. Anything that executes content
        // (script, iframe, object, embed, svg, math, template, style, link, meta, base, …) is
        // intentionally absent and falls through to the div fallback below.
        "a",
        "abbr",
        "address",
        "article",
        "aside",
        "b",
        "blockquote",
        "br",
        "button",
        "caption",
        "cite",
        "code",
        "col",
        "colgroup",
        "data",
        "dd",
        "del",
        "details",
        "dfn",
        "div",
        "dl",
        "dt",
        "em",
        "figcaption",
        "figure",
        "footer",
        "form",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "header",
        "hgroup",
        "hr",
        "i",
        "img",
        "input",
        "ins",
        "kbd",
        "label",
        "li",
        "main",
        "mark",
        "nav",
        "ol",
        "optgroup",
        "option",
        "p",
        "pre",
        "q",
        "s",
        "samp",
        "section",
        "small",
        "span",
        "strong",
        "sub",
        "summary",
        "sup",
        "table",
        "tbody",
        "td",
        "tfoot",
        "th",
        "thead",
        "time",
        "tr",
        "u",
        "ul",
        "var",
        "wbr",
        -> tag
        // Anything else (script, iframe, object, embed, svg, template, style, …, as well as any
        // malformed name) falls back to a plain div — never an executable element.
        else -> "div"
    }

private val UrlValuedAttributes = setOf(
    "action",
    "cite",
    "formaction",
    "href",
    "poster",
    "src",
    "xlink:href",
)

private val HtmlContentAttributes = setOf("srcdoc")

private val SafeUrlSchemes = setOf("http", "https", "mailto", "tel")
