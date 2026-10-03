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
    val isTextInput = node.tag == "textInput"
    val tagName = if (isTextInput) "input" else node.tag.takeIf(::isSafeHtmlName) ?: "div"
    val inputType = if (isTextInput) {
        node.props.entries.firstOrNull { (name, _) -> name.equals("type", ignoreCase = true) }?.value ?: "text"
    } else {
        null
    }
    val isPassword = inputType.equals("password", ignoreCase = true)
    append('<')
    append(tagName)
    if (tagName != node.tag && !isTextInput) {
        appendAttribute("data-kinetica-tag", node.tag)
    }
    inputType?.let { type -> appendAttribute("type", type) }
    node.props
        .filter { (name, value) ->
            isPublicHtmlAttribute(name, value) &&
                !(isTextInput && name.equals("type", ignoreCase = true)) &&
                !(isPassword && name.equals("value", ignoreCase = true))
        }
        .forEach { (name, value) -> appendAttribute(name, value) }
    node.key?.let { key -> appendAttribute("data-kinetica-key", key) }
    append('>')
    if (isTextInput) return
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
