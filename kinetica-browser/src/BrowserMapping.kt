package io.heapy.kinetica.browser

import io.heapy.kinetica.Role
import io.heapy.kinetica.isSafeHtmlAttributeValue
import io.heapy.kinetica.isValidHtmlName

/**
 * Maps Kinetica's layout/input aliases and otherwise preserves the host tag name.
 * Native `document.createElement` rejects invalid names when the renderer mounts the element.
 * Tag names must come from trusted application code; this is not an element allowlist.
 */
public fun browserTagNameFor(tag: String): String =
    when (tag) {
        "column", "row" -> "div"
        "textInput", "checkbox" -> "input"
        else -> tag
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
    isValidHtmlName(name) &&
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
