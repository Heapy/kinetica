package io.heapy.kinetica.browser

import io.heapy.kinetica.Role
import io.heapy.kinetica.isSafeHtmlAttributeValue

public fun browserTagNameFor(tag: String): String =
    when (tag) {
        "column", "row" -> "div"
        "textInput", "checkbox" -> "input"
        else -> tag.takeIf(::isSafeBrowserName) ?: "div"
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
    isPublicBrowserAttributeName(name) &&
        isSafeHtmlAttributeValue(name, value)

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

private const val PUBLIC_ATTRIBUTE_NAME_CACHE_MAX_SIZE = 128

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
        name != "enabled" &&
        name != "checked" &&
        name != "value" &&
        name != "direction" &&
        !name.startsWith("event:") &&
        !name.startsWith("frame:") &&
        !name.startsWith("on", ignoreCase = true)

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
