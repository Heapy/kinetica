@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.appkit

import io.heapy.kinetica.*
import kotlinx.cinterop.useContents
import platform.AppKit.*
import platform.Foundation.*
import kotlin.test.*

class AppKitTextInputTest {
    @Test
    fun passwordMountsSecurelyAndKeepsControlledValueAndInputBinding() {
        val type = store(TextInputType.Password)
        val value = store("secret")
        val unrelated = store(0)
        val window = window()
        val renderer = renderAppKitApp(window.contentView!!) {
            InputProbe(type, value, unrelated, tagged = true)
        }
        try {
            val field = input(window.contentView!!)
            assertTrue(field is NSSecureTextField)
            assertTrue(field.cell is NSSecureTextFieldCell)
            assertEquals("secret", field.stringValue)
            assertEquals("Password", field.placeholderString)

            field.setStringValue("changed")
            field.delegate!!.controlTextDidChange(NSNotification.notificationWithName(NSControlTextDidChangeNotification, field))
            assertEquals("changed", value.value)
            assertEquals(field, input(window.contentView!!))

            field.setStringValue("unaccepted edit")
            unrelated.value++
            renderer.renderUntilSettled()
            assertEquals(field, input(window.contentView!!))
            assertEquals("changed", field.stringValue)
        } finally {
            renderer.dispose()
            window.close()
        }
    }

    @Test
    fun secureAndPlainSwitchesRebindAndRestoreFocusWithOrWithoutTestTag() {
        for (tagged in listOf(true, false)) {
            val type = store(TextInputType.Text)
            val value = store("secret")
            val unrelated = store(0)
            var submits = 0
            val window = window()
            val renderer = renderAppKitApp(window.contentView!!) {
                InputProbe(type, value, unrelated, tagged, onSubmit = { submits++ })
            }
            try {
                var previous = input(window.contentView!!)
                assertFalse(previous is NSSecureTextField)
                assertTrue(window.makeFirstResponder(previous))
                requireNotNull(previous.currentEditor()).setSelectedRange(NSMakeRange(2u, 2u))
                val initialEditor = previous.currentEditor()
                unrelated.value++
                renderer.renderUntilSettled()
                assertEquals(previous, input(window.contentView!!))
                assertEquals(initialEditor, window.firstResponder)
                requireNotNull(previous.currentEditor()).selectedRange.useContents {
                    assertEquals(2uL, location)
                    assertEquals(2uL, length)
                }

                for (next in listOf(TextInputType.Password, TextInputType.Text)) {
                    type.value = next
                    renderer.renderUntilSettled()
                    val field = input(window.contentView!!)
                    assertNotEquals(previous, field)
                    assertEquals(next == TextInputType.Password, field is NSSecureTextField)
                    assertEquals("secret", field.stringValue)
                    assertEquals("Password", field.placeholderString)
                    assertNull(previous.delegate)
                    assertNull(previous.target)
                    val editor = requireNotNull(field.currentEditor())
                    assertEquals(editor, window.firstResponder)
                    editor.selectedRange.useContents {
                        assertEquals(2uL, location)
                        assertEquals(2uL, length)
                    }
                    (field.target as AppKitEventDispatcher).clicked(field)
                    previous = field
                }
                assertEquals(2, submits)

                type.value = TextInputType.Email
                renderer.renderUntilSettled()
                assertEquals(previous, input(window.contentView!!), "Nonsecure input types reuse the ordinary field")
                previous.setStringValue("new value")
                previous.delegate!!.controlTextDidChange(NSNotification.notificationWithName(NSControlTextDidChangeNotification, previous))
                assertEquals("new value", value.value)
            } finally {
                renderer.dispose()
                window.close()
            }
        }
    }

    private fun window(): NSWindow {
        NSApplication.sharedApplication()
        return NSWindow(NSMakeRect(0.0, 0.0, 400.0, 200.0), NSWindowStyleMaskTitled, NSBackingStoreBuffered, false).apply {
            setReleasedWhenClosed(false)
        }
    }

    private fun input(view: NSView): NSTextField = requireNotNull(findInput(view))

    private fun findInput(view: NSView): NSTextField? =
        if (view is NSTextField && view.delegate != null) view
        else view.subviews.filterIsInstance<NSView>().firstNotNullOfOrNull(::findInput)
}

@UiComponent
private fun ComponentScope.InputProbe(
    type: Cell<TextInputType>,
    value: MutableCell<String>,
    unrelated: Cell<Int>,
    tagged: Boolean,
    onSubmit: (() -> Unit)? = null,
) {
    column {
        textInput(
            value.value,
            onInput = { value.value = it },
            onSubmit = onSubmit,
            placeholder = "Password",
            semantics = if (tagged) Semantics(testTag = "input") else null,
            type = type.value,
        )
        text("Other state: ${unrelated.value}")
    }
}
