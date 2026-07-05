package io.heapy.kinetica.browser

import io.heapy.kinetica.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserMappingTest {
    @Test
    fun mapsKineticaHostTagsToBrowserTags() {
        assertEquals("div", browserTagNameFor("column"))
        assertEquals("div", browserTagNameFor("row"))
        assertEquals("input", browserTagNameFor("textInput"))
        assertEquals("input", browserTagNameFor("checkbox"))
        assertEquals("article", browserTagNameFor("article"))
        assertEquals("div", browserTagNameFor("script>"))
    }

    @Test
    fun mapsSemanticsRolesToBrowserRoles() {
        assertEquals("button", browserRoleFor(Role.Button))
        assertEquals("textbox", browserRoleFor(Role.TextInput))
        assertEquals("listitem", browserRoleFor(Role.ListItem))
        assertEquals(null, browserRoleFor(Role.Text))
    }

    @Test
    fun filtersUnsafeBrowserAttributeValues() {
        assertTrue(isPublicBrowserAttribute("class", "danger"))
        assertTrue(isPublicBrowserAttribute("data-id", "42"))
        assertTrue(isPublicBrowserAttribute("aria-hidden", "true"))
        assertFalse(isPublicBrowserAttribute("enabled", "true"))
        assertFalse(isPublicBrowserAttribute("checked", "true"))
        assertFalse(isPublicBrowserAttribute("value", "draft"))
        assertFalse(isPublicBrowserAttribute("direction", "Rtl"))
        assertTrue(isPublicBrowserAttribute("href", "https://example.test/cart"))
        assertTrue(isPublicBrowserAttribute("href", "/cart/checkout"))
        assertFalse(isPublicBrowserAttribute("href", "javascript:alert(1)"))
        assertFalse(isPublicBrowserAttribute("formaction", " data:text/html,<script>alert(1)</script>"))
        assertFalse(isPublicBrowserAttribute("srcdoc", "<script>alert(1)</script>"))
        assertFalse(isPublicBrowserAttribute("event:onClick", "event-0"))
        assertFalse(isPublicBrowserAttribute("frame:translateX", "frame-0"))
        assertFalse(isPublicBrowserAttribute("onclick", "alert(1)"))
    }

    @Test
    fun detectsInputTypesWithReadableTextSelection() {
        assertTrue(browserInputTypeSupportsTextSelection("text"))
        assertTrue(browserInputTypeSupportsTextSelection("SEARCH"))
        assertTrue(browserInputTypeSupportsTextSelection(""))
        assertFalse(browserInputTypeSupportsTextSelection("checkbox"))
        assertFalse(browserInputTypeSupportsTextSelection("radio"))
        assertFalse(browserInputTypeSupportsTextSelection("button"))
    }
}
