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
 * Well-formed tag names render verbatim so SSR snapshots preserve the DSL tree: layout tags like
 * `column`/`row`, form controls, media tags, canvas, dialog, and custom elements keep their
 * original names, matching pre-existing SSR behavior. Malformed names still fall back to `div`
 * through the character gate.
 *
 * The security boundary is a denylist for executable or document-mutating elements, checked
 * case-insensitively before the character gate. A hand-built `HostNode(tag = "script")` serializes
 * as `<div data-kinetica-tag="script">…</div>` rather than a live element, so SSR cannot introduce
 * a script-injection sink even if attacker-influenced data ever reaches a tag name.
 */
internal fun safeHtmlTagName(tag: String): String =
    if (tag.lowercase() in DangerousHtmlTags) "div"
    else tag.takeIf(::isSafeHtmlName) ?: "div"

private val DangerousHtmlTags: Set<String> = setOf(
    "script", "iframe", "object", "embed", "svg", "math", "template",
    "style", "link", "meta", "base", "frame", "frameset", "applet",
    "noscript", "foreignobject", "portal", "xmp", "noembed",
)

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
