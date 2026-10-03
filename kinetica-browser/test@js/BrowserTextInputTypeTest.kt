package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.TextInputType
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.column
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BrowserTextInputTypeTest {
    @Test
    fun publicTextInputMountsEverySupportedType() {
        for (type in TextInputType.entries) {
            installTestDocument()
            val root = testDocument().createElement("div").unsafeCast<Element>()
            val probe = TypedInputProbe(type)
            val app = mountKineticaApp(root) { TypedInputApp(probe) }

            try {
                val input = firstInput(root)
                assertEquals("INPUT", input.tagName)
                assertEquals(type.htmlValue, input.getAttribute("type"))
                assertEquals("initial", input.value)
                assertNull(input.getAttribute("autocomplete"))
            } finally {
                app.dispose()
            }
        }
    }

    @Test
    fun defaultTextInputKeepsTextType() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val app = mountKineticaApp(root) { DefaultTextInputApp() }

        try {
            val input = firstInput(root)
            assertEquals("text", input.getAttribute("type"))
            app.render()
            assertSame(input, firstInput(root))
            assertEquals("text", input.getAttribute("type"))
            assertEquals("default", input.value)
        } finally {
            app.dispose()
        }
    }

    @Test
    fun passwordInputAcceptsEditsAndSurvivesUnrelatedRenders() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = TypedInputProbe(TextInputType.Password)
        val app = mountKineticaApp(root) { TypedInputWithSiblingApp(probe) }

        try {
            val panel = root.firstChild!!.unsafeCast<Element>()
            val input = firstInput(panel)
            input.value = "new-secret"
            input.asDynamic().dispatchEvent(testDomEvent("input"))

            assertEquals("new-secret", probe.value.value)
            assertEquals("new-secret", input.value)
            assertEquals("password", input.getAttribute("type"))

            probe.counter.value += 1
            app.render()

            assertSame(input, firstInput(panel))
            assertEquals("Count: 1", panel.childNodes.item(1)?.textContent)
            assertEquals("new-secret", input.value)
            assertEquals("password", input.getAttribute("type"))

            input.value = "uncommitted-dom-drift"
            probe.counter.value += 1
            app.render()

            assertEquals("new-secret", input.value)
            assertEquals("password", input.getAttribute("type"))
        } finally {
            app.dispose()
        }
    }

    @Test
    fun typeAndAutocompletePatchInPlaceAndResetToDefaults() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = TypedInputProbe(TextInputType.Password)
        probe.autocomplete.value = "current-password"
        val app = mountKineticaApp(root) { TypedInputApp(probe) }

        try {
            val input = firstInput(root)
            assertEquals("password", input.getAttribute("type"))
            assertEquals("current-password", input.getAttribute("autocomplete"))

            probe.type.value = TextInputType.Text
            probe.autocomplete.value = "new-password"
            app.render()

            assertSame(input, firstInput(root))
            assertEquals("text", input.getAttribute("type"))
            assertEquals("new-password", input.getAttribute("autocomplete"))
            assertEquals("initial", input.value)

            probe.type.value = TextInputType.Password
            probe.autocomplete.value = null
            app.render()

            assertSame(input, firstInput(root))
            assertEquals("password", input.getAttribute("type"))
            assertNull(input.getAttribute("autocomplete"))
            assertEquals("initial", input.value)
        } finally {
            app.dispose()
        }
    }

    @Test
    fun removingRawTypeRestoresExplicitTextDefault() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val props = store(mapOf("type" to "password", "value" to "secret"))
        val app = mountKineticaApp(root) { host("textInput", props = props.value) }

        try {
            val input = firstInput(root)
            assertEquals("password", input.getAttribute("type"))
            props.value = mapOf("value" to "secret")
            app.render()

            assertSame(input, firstInput(root))
            assertEquals("text", input.getAttribute("type"))
            assertEquals("secret", input.value)
        } finally {
            app.dispose()
        }
    }

    @Test
    fun focusedEmailInputRendersWithoutReadingUnsupportedSelection() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = TypedInputProbe(TextInputType.Email)
        val app = mountKineticaApp(root) { TypedInputApp(probe) }

        try {
            val input = firstInput(root)
            rejectSelectionAccess(input)
            testDocument().activeElement = input
            probe.value.value = "user@example.test"
            app.render()

            assertSame(input, firstInput(root))
            assertEquals("email", input.getAttribute("type"))
            assertEquals("user@example.test", input.value)
        } finally {
            app.dispose()
        }
    }
}

private class TypedInputProbe(initialType: TextInputType) {
    val type = store(initialType)
    val autocomplete = store<String?>(null)
    val value = store("initial")
    val counter = store(0)
}

@UiComponent
private fun ComponentScope.DefaultTextInputApp() {
    textInput(value = "default")
}

@UiComponent
private fun ComponentScope.TypedInputApp(probe: TypedInputProbe) {
    textInput(
        value = probe.value.value,
        onInput = event<String> { probe.value.value = it },
        type = probe.type.value,
        autocomplete = probe.autocomplete.value,
    )
}

@UiComponent
private fun ComponentScope.TypedInputWithSiblingApp(probe: TypedInputProbe) {
    column {
        TypedInputApp(probe)
        text("Count: ${probe.counter.value}")
    }
}

private fun firstInput(root: Element): HTMLInputElement =
    root.firstChild?.unsafeCast<HTMLInputElement>() ?: error("Expected an input.")

private fun rejectSelectionAccess(input: HTMLInputElement) {
    js(
        """
        Object.defineProperties(input, {
          type: { get: function () { return this.getAttribute("type") || "text"; } },
          selectionStart: { get: function () { throw new Error("Unexpected selectionStart access"); } },
          selectionEnd: { get: function () { throw new Error("Unexpected selectionEnd access"); } }
        });
        """,
    )
}
