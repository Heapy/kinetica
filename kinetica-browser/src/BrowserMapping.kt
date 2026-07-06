package io.heapy.kinetica.browser

import io.heapy.kinetica.Role
import io.heapy.kinetica.isSafeHtmlAttributeValue

public fun browserTagNameFor(tag: String): String =
    when (tag) {
        "column", "row" -> "div"
        "textInput", "checkbox" -> "input"
        "a",
        "article",
        "button",
        "div",
        "form",
        "h1",
        "h2",
        "h3",
        "img",
        "input",
        "label",
        "li",
        "main",
        "nav",
        "ol",
        "p",
        "section",
        "span",
        "table",
        "tbody",
        "td",
        "th",
        "thead",
        "tr",
        "ul",
        -> tag
        else -> cachedBrowserTagNameFor(tag)
    }

public fun browserRoleFor(role: Role): String? =
    when (role) {
        Role.Button -> "button"
        Role.Checkbox -> "checkbox"
        Role.Text -> null
        Role.TextInput -> "textbox"
        Role.List -> "list"
        Role.ListItem -> "listitem"
        Role.Navigation -> "navigation"
        Role.Dialog -> "dialog"
        Role.Image -> "img"
        Role.None -> "none"
    }

internal fun isPublicBrowserAttribute(name: String, value: String): Boolean =
    when {
        isKnownValueSafePublicBrowserAttributeName(name) -> true
        isKnownPrivateBrowserAttributeName(name) -> false
        else -> isPublicBrowserAttributeName(name) &&
            isSafeHtmlAttributeValue(name, value)
    }

internal fun isRemovablePublicBrowserAttribute(name: String): Boolean =
    when {
        isKnownValueSafePublicBrowserAttributeName(name) -> true
        isKnownPrivateBrowserAttributeName(name) -> false
        else -> isPublicBrowserAttributeName(name) &&
            isSafeHtmlAttributeValue(name, "")
    }

internal fun browserInputTypeSupportsTextSelection(type: String?): Boolean =
    when (type?.lowercase()) {
        null,
        "",
        "password",
        "search",
        "tel",
        "text",
        "url",
        -> true
        else -> false
    }

private fun isPublicBrowserAttributeName(name: String): Boolean =
    publicAttributeNameCache[name]?.let { cached ->
        cached
    } ?: isUncachedPublicBrowserAttributeName(name).also { public ->
        if (publicAttributeNameCache.size < PUBLIC_ATTRIBUTE_NAME_CACHE_MAX_SIZE) {
            publicAttributeNameCache[name] = public
        }
    }

private fun cachedBrowserTagNameFor(tag: String): String =
    browserTagNameCache[tag]?.let { cached ->
        cached
    } ?: (tag.takeIf(::isSafeBrowserName) ?: "div").also { browserTagName ->
        if (browserTagNameCache.size < BROWSER_TAG_NAME_CACHE_MAX_SIZE) {
            browserTagNameCache[tag] = browserTagName
        }
    }

private const val BROWSER_TAG_NAME_CACHE_MAX_SIZE = 128
private const val PUBLIC_ATTRIBUTE_NAME_CACHE_MAX_SIZE = 128

private val browserTagNameCache = mutableMapOf<String, String>()

private val publicAttributeNameCache = mutableMapOf(
    "aria-hidden" to true,
    "class" to true,
    "data-id" to true,
    "checked" to false,
    "direction" to false,
    "enabled" to false,
    "value" to false,
)

private fun isUncachedPublicBrowserAttributeName(name: String): Boolean =
    isSafeBrowserName(name) &&
        !isKnownPrivateBrowserAttributeName(name)

private fun isKnownValueSafePublicBrowserAttributeName(name: String): Boolean =
    when (name) {
        "aria-description",
        "aria-hidden",
        "aria-label",
        "class",
        "data-id",
        "data-kinetica-test-tag",
        "data-testid",
        "id",
        "role",
        "tabindex",
        "title",
        -> true
        else -> false
    }

private fun isKnownPrivateBrowserAttributeName(name: String): Boolean =
    when (name) {
        "checked",
        "direction",
        "enabled",
        "value",
        -> true
        else ->
            name.startsWith("event:") ||
                name.startsWith("frame:") ||
                name.startsWith("on", ignoreCase = true)
    }

private fun isSafeBrowserName(name: String): Boolean =
    name.isNotEmpty() &&
        name.first().isLetter() &&
        name.all { character ->
            character.isLetterOrDigit() ||
                character == '-' ||
                character == '_' ||
                character == ':' ||
                character == '.'
        }
