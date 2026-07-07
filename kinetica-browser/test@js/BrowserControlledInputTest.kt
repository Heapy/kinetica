package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.checkbox
import io.heapy.kinetica.column
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.state
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserControlledInputTest {
    /**
     * KSND-059 (sources: RCT-101, INF-147, PRE-082).
     */
    @Test
    fun driftAndUnrelatedRenderSnapsBackToRenderedValue() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val app = mountKineticaApp(root) {
            ConstantInputCounterApp()
        }

        try {
            val panel = firstElement(root)
            val input = childInput(panel, index = 0)
            val button = childElement(panel, index = 1)

            input.asDynamic().value = "giraffe"
            button.asDynamic().dispatchEvent(testDomEvent("click"))

            assertEquals("lion", input.value)
            assertEquals("Count: 1", button.textContent)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-060 (sources: VUE-160, SVL-053).
     */
    @Test
    fun inputEventWritesCellAndDomEqualsCellAfterCommit() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val app = mountKineticaApp(root) {
            StatefulInputMirrorApp()
        }

        try {
            val panel = firstElement(root)
            val input = childInput(panel, index = 0)

            input.asDynamic().value = "abc"
            input.asDynamic().dispatchEvent(testDomEvent("input"))

            assertEquals("Mirror: abc", childText(panel, index = 1))
            assertEquals("abc", input.value)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-061 (sources: RCT-101, RCT-104 (adapted), INF-147).
     */
    @Test
    fun rejectingInputHandlerRestoresPreviousValueOnCommit() {
        run {
            installTestDocument()
            val root = testDocument().createElement("div").unsafeCast<Element>()
            val app = mountKineticaApp(root) {
                RejectingInputApp()
            }

            try {
                val panel = firstElement(root)
                val input = childInput(panel, index = 0)

                input.asDynamic().value = "abcdef"
                input.asDynamic().dispatchEvent(testDomEvent("input"))

                assertEquals("cat", input.value)
                assertEquals("Attempts: 1", childText(panel, index = 1))
            } finally {
                app.dispose()
            }
        }

        run {
            installTestDocument()
            val root = testDocument().createElement("div").unsafeCast<Element>()
            val probe = NoCellWriteInputProbe()
            val app = mountKineticaApp(root) {
                NoCellWriteInputApp(probe)
            }

            try {
                val input = childInput(firstElement(root), index = 0)

                input.asDynamic().value = "abcdef"
                input.asDynamic().dispatchEvent(testDomEvent("input"))

                assertEquals(listOf("abcdef"), probe.payloads)
                assertEquals("cat", input.value)
            } finally {
                app.dispose()
            }
        }
    }

    /**
     * KSND-062 (sources: RCT-116, RCT-207, RCT-210).
     */
    @Test
    fun revertingToPreviouslyRenderedValueStillDispatchesInputEvents() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = InputLogProbe()
        val app = mountKineticaApp(root) {
            LoggedInputApp(probe)
        }

        try {
            val panel = firstElement(root)
            val input = childInput(panel, index = 0)

            input.asDynamic().value = "a"
            input.asDynamic().dispatchEvent(testDomEvent("input"))
            input.asDynamic().value = ""
            input.asDynamic().dispatchEvent(testDomEvent("input"))

            assertEquals(listOf("a", ""), probe.payloads)
            assertEquals("", input.value)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-063 (sources: RCT-110, PRE-075).
     */
    @Test
    fun emptyStringAndZeroStringTransitionsAreApplied() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = ProgrammaticTextProbe(initial = "")
        val app = mountKineticaApp(root) {
            ProgrammaticTextInputApp(probe)
        }

        try {
            val input = childInput(firstElement(root), index = 0)
            assertEquals("", input.value)

            probe.text.value = "0"
            app.render()
            assertEquals("0", input.value)

            probe.text.value = ""
            app.render()
            assertEquals("", input.value)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-064 (sources: INF-074, INF-148, RCT-208).
     */
    @Test
    fun controlledCheckboxRevertsUnlessHandlerChangesState() {
        installTestDocument()
        val rejectingRoot = testDocument().createElement("div").unsafeCast<Element>()
        val rejectingProbe = CheckboxRejectProbe()
        val rejectingApp = mountKineticaApp(rejectingRoot) {
            RejectingCheckboxApp(rejectingProbe)
        }

        try {
            val checkbox = childInput(firstElement(rejectingRoot), index = 0)
            checkbox.checked = true
            checkbox.asDynamic().dispatchEvent(testDomEvent("change"))

            assertFalse(checkbox.checked)
            assertEquals(1, rejectingProbe.toggles)
        } finally {
            rejectingApp.dispose()
        }

        val acceptingRoot = testDocument().createElement("div").unsafeCast<Element>()
        val acceptingApp = mountKineticaApp(acceptingRoot) {
            AcceptingCheckboxApp()
        }

        try {
            val checkbox = childInput(firstElement(acceptingRoot), index = 0)
            checkbox.checked = true
            checkbox.asDynamic().dispatchEvent(testDomEvent("change"))

            assertTrue(checkbox.checked)
        } finally {
            acceptingApp.dispose()
        }
    }

    /**
     * KSND-065 (sources: INF-153).
     */
    // KSND-065: CONFIRMED FRAMEWORK BUG (triage 2026-07-07): identity short-circuit (mounted.currentNode === next, BrowserKineticaApp.kt:351) skips patchHost controlled-input resync for memoized surviving rows.
    @Test
    fun shrinkingControlledCheckboxListAppliesFreshCheckedToSurvivors() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = CheckboxListProbe()
        val app = mountKineticaApp(root) {
            CheckboxListApp(probe)
        }

        try {
            val panel = firstElement(root)
            childInput(panel, index = 0).checked = true

            probe.rows.value = listOf(1, 2)
            app.render()

            assertEquals(2, panel.childNodes.length)
            assertFalse(childInput(panel, index = 0).checked)
            assertFalse(childInput(panel, index = 1).checked)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-066 (sources: SVL-053, RCT-119 (adapted)).
     */
    @Test
    fun programmaticWriteUpdatesInputAndSiblingPropPatchDoesNotClobberValue() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val probe = ProgrammaticTextWithPlaceholderProbe()
        val app = mountKineticaApp(root) {
            ProgrammaticTextInputWithPlaceholderApp(probe)
        }

        try {
            val input = childInput(firstElement(root), index = 0)
            input.asDynamic().value = "1x"

            probe.text.value = "2"
            probe.placeholder.value = "two"
            app.render()

            assertEquals("2", input.value)
            assertEquals("two", input.placeholder)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-067 (sources: RCT-103 (adapted), SVL-099 (adapted)).
     */
    @Test
    fun twoInputsBoundToOneCellStayInSync() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val app = mountKineticaApp(root) {
            SharedCellInputsApp()
        }

        try {
            val panel = firstElement(root)
            val first = childInput(panel, index = 0)
            val second = childInput(panel, index = 1)

            first.asDynamic().value = "hi"
            first.asDynamic().dispatchEvent(testDomEvent("input"))
            assertEquals("hi", first.value)
            assertEquals("hi", second.value)

            second.asDynamic().value = "yo"
            second.asDynamic().dispatchEvent(testDomEvent("input"))
            assertEquals("yo", first.value)
            assertEquals("yo", second.value)
        } finally {
            app.dispose()
        }
    }
}

private class InputLogProbe {
    val payloads = mutableListOf<String>()
}

private class ProgrammaticTextProbe(initial: String) {
    val text = store(initial)
}

private class NoCellWriteInputProbe {
    val payloads = mutableListOf<String>()
}

private class CheckboxRejectProbe {
    var toggles = 0
}

private class CheckboxListProbe {
    val rows = store(listOf(1, 2, 3))
}

private class ProgrammaticTextWithPlaceholderProbe {
    val text = store("1")
    val placeholder = store("one")
}

@UiComponent
private fun ComponentScope.ConstantInputCounterApp() {
    var count by state { 0 }

    column {
        textInput(value = "lion", semantics = null)
        button(onClick = event { count += 1 }, semantics = null) {
            text("Count: $count", semantics = null)
        }
    }
}

@UiComponent
private fun ComponentScope.StatefulInputMirrorApp() {
    var text by state { "" }

    column {
        textInput(
            value = text,
            onInput = event<String> { value -> text = value },
            semantics = null,
        )
        text("Mirror: $text", semantics = null)
    }
}

@UiComponent
private fun ComponentScope.RejectingInputApp() {
    var text by state { "cat" }
    var attempts by state { 0 }

    column {
        textInput(
            value = text,
            onInput = event<String> { value ->
                attempts += 1
                if (value.length <= 3) {
                    text = value
                }
            },
            semantics = null,
        )
        text("Attempts: $attempts", semantics = null)
    }
}

@UiComponent
private fun ComponentScope.NoCellWriteInputApp(probe: NoCellWriteInputProbe) {
    column {
        textInput(
            value = "cat",
            onInput = event<String> { value -> probe.payloads += value },
            semantics = null,
        )
    }
}

@UiComponent
private fun ComponentScope.LoggedInputApp(probe: InputLogProbe) {
    var text by state { "" }

    column {
        textInput(
            value = text,
            onInput = event<String> { value ->
                probe.payloads += value
                text = value
            },
            semantics = null,
        )
        text("Mirror: $text", semantics = null)
    }
}

@UiComponent
private fun ComponentScope.ProgrammaticTextInputApp(probe: ProgrammaticTextProbe) {
    column {
        textInput(value = probe.text.value, semantics = null)
    }
}

@UiComponent
private fun ComponentScope.RejectingCheckboxApp(probe: CheckboxRejectProbe) {
    column {
        checkbox(
            checked = false,
            onToggle = event { probe.toggles += 1 },
            semantics = null,
        )
    }
}

@UiComponent
private fun ComponentScope.AcceptingCheckboxApp() {
    var checked by state { false }

    column {
        checkbox(
            checked = checked,
            onToggle = event { checked = !checked },
            semantics = null,
        )
    }
}

@UiComponent
private fun ComponentScope.CheckboxListApp(probe: CheckboxListProbe) {
    column {
        each(probe.rows.value, key = { row -> row }) { row ->
            checkbox(checked = false, key = row, semantics = null)
        }
    }
}

@UiComponent
private fun ComponentScope.ProgrammaticTextInputWithPlaceholderApp(probe: ProgrammaticTextWithPlaceholderProbe) {
    column {
        textInput(
            value = probe.text.value,
            placeholder = probe.placeholder.value,
            semantics = null,
        )
    }
}

@UiComponent
private fun ComponentScope.SharedCellInputsApp() {
    var text by state { "" }
    val update = event<String> { value -> text = value }

    column {
        textInput(value = text, onInput = update, semantics = null)
        textInput(value = text, onInput = update, semantics = null)
    }
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private fun childInput(parent: Element, index: Int): HTMLInputElement =
    childElement(parent, index).unsafeCast<HTMLInputElement>()

private fun childText(parent: Element, index: Int): String =
    parent.childNodes.item(index)?.textContent ?: error("Expected child text at index $index.")
