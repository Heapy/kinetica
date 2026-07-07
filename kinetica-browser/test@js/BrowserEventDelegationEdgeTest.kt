package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.hostEvent
import io.heapy.kinetica.state
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class BrowserEventDelegationEdgeTest {
    /**
     * KSND-046 (sources: INF-064, SVL-060, RCT-219).
     */
    @Test
    fun innermostHandlerConsumesClickWithoutAncestorDispatch() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = InnermostClickProbe()
        val app = mountKineticaApp(root) {
            InnermostClickApp(probe)
        }

        try {
            val button = elementByTag(root, tag = "button", index = 0)
            button.asDynamic().dispatchEvent(testDomEvent("click"))

            assertEquals(1, probe.innerClicks)
            assertEquals(0, probe.outerClicks)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-047 (sources: INF-067, INF-068).
     */
    @Test
    fun handlerlessIntermediateElementDoesNotTerminateWalk() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = HandlerlessIntermediateProbe()
        val app = mountKineticaApp(root) {
            HandlerlessIntermediateApp(probe)
        }

        try {
            val bold = elementByTag(root, tag = "b", index = 0)
            bold.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probe.outerClicks)

            probe.armed.value = false
            app.render()
            assertSame(bold, elementByTag(root, tag = "b", index = 0))
            bold.asDynamic().dispatchEvent(testDomEvent("click"))

            assertEquals(1, probe.outerClicks)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-048 (sources: INF-062, SVL-059).
     */
    @Test
    fun clickOnNonInteractiveChildDispatchesEnclosingButtonEvent() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = ChildClickProbe()
        val app = mountKineticaApp(root) {
            ChildClickApp(probe)
        }

        try {
            val span = elementByTag(root, tag = "span", index = 0)
            span.asDynamic().dispatchEvent(testDomEvent("click"))

            assertEquals(1, probe.clicks)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-049 (sources: INF-062, RCT-213).
     */
    @Test
    fun disabledButtonSwallowsChildClicksAndReenableRestoresDispatch() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = DisabledButtonProbe()
        val app = mountKineticaApp(root) {
            DisabledButtonApp(probe)
        }

        try {
            val span = elementByTag(root, tag = "span", index = 0)
            span.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(0, probe.innerClicks)
            assertEquals(0, probe.outerClicks)

            probe.enabled.value = true
            app.render()
            assertSame(span, elementByTag(root, tag = "span", index = 0))
            span.asDynamic().dispatchEvent(testDomEvent("click"))

            assertEquals(1, probe.innerClicks)
            assertEquals(0, probe.outerClicks)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-050 (sources: INF-061, RCT-215, SVL-063, VUE-152).
     */
    @Test
    fun handlerClosureStaysFreshAcrossSelfReplacingRenders() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = ClosureFreshnessProbe()
        val app = mountKineticaApp(root) {
            ClosureFreshnessApp(probe)
        }

        try {
            val button = firstElement(root)
            assertEquals("Count: 0", button.textContent)

            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals("Count: 1", button.textContent)
            assertSame(button, firstElement(root))

            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals("Count: 2", button.textContent)
            assertSame(button, firstElement(root))

            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals("Count: 3", button.textContent)
            assertSame(button, firstElement(root))
            assertEquals(1, probe.stateInits)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-051 (sources: INF-060, INF-068, RCT-215, PRE-069).
     */
    @Test
    fun handlerRemovedOnRerenderStopsFiringAndReaddedRestoresDispatch() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = HandlerToggleProbe()
        val app = mountKineticaApp(root) {
            HandlerToggleApp(probe)
        }

        try {
            val button = firstElement(root)
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probe.clicks)

            probe.armed.value = false
            app.render()
            assertSame(button, firstElement(root))
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probe.clicks)

            probe.armed.value = true
            app.render()
            assertSame(button, firstElement(root))
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(2, probe.clicks)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-052 (sources: RCT-222, INF-077, INF-154).
     */
    @Test
    fun dispatchRemovingTargetSubtreeCompletesAndDetachedNodeIsInert() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = RemoveTargetProbe()
        val app = mountKineticaApp(root) {
            RemoveTargetApp(probe)
        }

        try {
            val button = firstElement(root)
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probe.clicks)
            assertEquals("Removed", root.textContent)
            assertEquals(null, button.asDynamic().__kinetica)

            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probe.clicks)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-053 (sources: RCT-205, INF-060, SOL-086).
     */
    @Test
    fun disposeRemovesRootListenersAndPostDisposeDispatchDoesNothing() {
        installTestDocument()
        val tally = installListenerTally()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = SingleButtonProbe()
        val app = mountKineticaApp(root) {
            SingleButtonApp(probe)
        }

        try {
            val button = firstElement(root)
            assertEquals(4, tally.adds.unsafeCast<Int>())

            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probe.clicks)

            app.dispose()
            assertEquals(tally.adds.unsafeCast<Int>(), tally.removes.unsafeCast<Int>())

            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probe.clicks)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-054 (sources: KNT-0014, PRE-069).
     */
    @Test
    fun disposeAndRemountOnSameRootDispatchesExactlyOncePerClick() {
        installTestDocument()
        val tally = installListenerTally()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val oldProbe = SingleButtonProbe()
        val firstApp = mountKineticaApp(root) {
            SingleButtonApp(oldProbe)
        }

        try {
            firstApp.dispose()

            val newProbe = SingleButtonProbe()
            val secondApp = mountKineticaApp(root) {
                SingleButtonApp(newProbe)
            }
            try {
                firstElement(root).asDynamic().dispatchEvent(testDomEvent("click"))

                assertEquals(1, newProbe.clicks)
                assertEquals(0, oldProbe.clicks)
            } finally {
                secondApp.dispose()
            }

            assertEquals(tally.adds.unsafeCast<Int>(), tally.removes.unsafeCast<Int>())
        } finally {
            firstApp.dispose()
        }
    }

    /**
     * KSND-055 (sources: RCT-220, INF-069).
     */
    @Test
    fun siblingRootAppsAreIsolated() {
        installTestDocument()
        val rootA = testDocument().createElement("div").unsafeCast<Element>()
        val rootB = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(rootA, null)
        testDocument().body.insertBefore(rootB, null)
        val probeA = SingleButtonProbe()
        val probeB = SingleButtonProbe()
        val appA = mountKineticaApp(rootA) {
            SingleButtonApp(probeA)
        }
        val appB = mountKineticaApp(rootB) {
            SingleButtonApp(probeB)
        }

        try {
            val buttonA = firstElement(rootA)
            val buttonB = firstElement(rootB)

            buttonA.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probeA.clicks)
            assertEquals(0, probeB.clicks)

            buttonB.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probeA.clicks)
            assertEquals(1, probeB.clicks)

            appA.dispose()
            buttonB.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probeA.clicks)
            assertEquals(2, probeB.clicks)
        } finally {
            appA.dispose()
            appB.dispose()
        }
    }

    /**
     * KSND-056 (sources: VUE-160, INF-071, RCT-207).
     */
    @Test
    fun inputDispatchCarriesLiveElementValueAndDoesNotDedupeIdenticalEvents() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = InputDispatchProbe()
        val app = mountKineticaApp(root) {
            InputDispatchApp(probe)
        }

        try {
            val input = firstElement(root).unsafeCast<HTMLInputElement>()
            input.value = "abc"
            input.asDynamic().dispatchEvent(testDomEvent("input"))
            assertEquals(listOf("abc"), probe.log)

            input.value = "abc"
            input.asDynamic().dispatchEvent(testDomEvent("input"))
            assertEquals(listOf("abc", "abc"), probe.log)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-057 (sources: infra gap, RCT-216).
     */
    @Test
    fun enterKeydownSubmitsWithPreventDefaultAndOtherKeysAreIgnored() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = SubmitProbe()
        val app = mountKineticaApp(root) {
            SubmitApp(probe)
        }

        try {
            val input = firstElement(root).unsafeCast<HTMLInputElement>()
            val enter = testDomEvent("keydown", key = "Enter")
            input.asDynamic().dispatchEvent(enter)
            assertEquals(1, probe.submits)
            assertEquals(true, enter.preventDefaultCalled.unsafeCast<Boolean>())

            val other = testDomEvent("keydown", key = "a")
            input.asDynamic().dispatchEvent(other)
            assertEquals(1, probe.submits)
            assertEquals(false, other.preventDefaultCalled.unsafeCast<Boolean>())
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-058 (sources: KNT-0006, KNT-0010, KNT-0014, INF-076, SVL-028).
     */
    @Test
    fun templateEventHolesStayRowCorrectAfterKeyedReorder() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = TemplateRowProbe()
        val app = mountKineticaApp(root) {
            TemplateRowApp(probe)
        }

        try {
            val bButton = elementByTag(root, tag = "button", index = 1)
            bButton.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(listOf("b"), probe.hits)

            probe.rows.value = listOf("c", "a", "b")
            app.render()

            val firstButton = elementByTag(root, tag = "button", index = 0)
            firstButton.asDynamic().dispatchEvent(testDomEvent("click"))
            assertSame(bButton, elementByTag(root, tag = "button", index = 2))
            bButton.asDynamic().dispatchEvent(testDomEvent("click"))

            assertEquals(listOf("b", "c", "b"), probe.hits)
        } finally {
            app.dispose()
        }
    }
}

private class InnermostClickProbe {
    var outerClicks = 0
    var innerClicks = 0
}

@UiComponent
private fun ComponentScope.InnermostClickApp(probe: InnermostClickProbe) {
    val outerClick = hostEvent(onEvent = event { probe.outerClicks += 1 })
    host("div", props = mapOf("event:onClick" to outerClick)) {
        button(onClick = event { probe.innerClicks += 1 }) {
            text("Inner")
        }
    }
}

private class HandlerlessIntermediateProbe {
    val armed = store(true)
    var outerClicks = 0
}

@UiComponent
private fun ComponentScope.HandlerlessIntermediateApp(probe: HandlerlessIntermediateProbe) {
    if (probe.armed.value) {
        button(onClick = event { probe.outerClicks += 1 }) {
            HandlerlessIntermediateChildren()
        }
    } else {
        button {
            HandlerlessIntermediateChildren()
        }
    }
}

@UiComponent
private fun ComponentScope.HandlerlessIntermediateChildren() {
    host("span") {
        host("b") {
            text("x")
        }
    }
}

private class ChildClickProbe {
    var clicks = 0
}

@UiComponent
private fun ComponentScope.ChildClickApp(probe: ChildClickProbe) {
    button(onClick = event { probe.clicks += 1 }) {
        host("span") {
            text("Save")
        }
    }
}

private class DisabledButtonProbe {
    val enabled = store(false)
    var outerClicks = 0
    var innerClicks = 0
}

@UiComponent
private fun ComponentScope.DisabledButtonApp(probe: DisabledButtonProbe) {
    val outerClick = hostEvent(onEvent = event { probe.outerClicks += 1 })
    host("div", props = mapOf("event:onClick" to outerClick)) {
        button(
            enabled = probe.enabled.value,
            onClick = event { probe.innerClicks += 1 },
        ) {
            host("span") {
                text("x")
            }
        }
    }
}

private class ClosureFreshnessProbe {
    var stateInits = 0
}

@UiComponent
private fun ComponentScope.ClosureFreshnessApp(probe: ClosureFreshnessProbe) {
    var count by state {
        probe.stateInits += 1
        0
    }
    button(onClick = event { count += 1 }) {
        text("Count: $count")
    }
}

private class HandlerToggleProbe {
    val armed = store(true)
    var clicks = 0
}

@UiComponent
private fun ComponentScope.HandlerToggleApp(probe: HandlerToggleProbe) {
    if (probe.armed.value) {
        button(onClick = event { probe.clicks += 1 }) {
            text("Armed")
        }
    } else {
        button {
            text("Armed")
        }
    }
}

private class RemoveTargetProbe {
    val visible = store(true)
    var clicks = 0
}

@UiComponent
private fun ComponentScope.RemoveTargetApp(probe: RemoveTargetProbe) {
    if (probe.visible.value) {
        button(
            onClick = event {
                probe.clicks += 1
                probe.visible.value = false
            },
        ) {
            text("Remove")
        }
    } else {
        text("Removed")
    }
}

private class SingleButtonProbe {
    var clicks = 0
}

@UiComponent
private fun ComponentScope.SingleButtonApp(probe: SingleButtonProbe) {
    button(onClick = event { probe.clicks += 1 }) {
        text("Click")
    }
}

private class InputDispatchProbe {
    val text = store("")
    val log = mutableListOf<String>()
}

@UiComponent
private fun ComponentScope.InputDispatchApp(probe: InputDispatchProbe) {
    textInput(
        value = probe.text.value,
        onInput = event<String> { value -> probe.log.add(value) },
    )
}

private class SubmitProbe {
    var submits = 0
}

@UiComponent
private fun ComponentScope.SubmitApp(probe: SubmitProbe) {
    textInput(
        value = "",
        onSubmit = event { probe.submits += 1 },
    )
}

private class TemplateRowProbe {
    val rows = store(listOf("a", "b", "c"))
    val hits = mutableListOf<String>()
}

@UiComponent
private fun ComponentScope.TemplateRowApp(probe: TemplateRowProbe) {
    host("section") {
        each(probe.rows.value, key = { row -> row }) { row ->
            button(key = row, onClick = event { probe.hits.add(row) }) {
                text(row)
            }
        }
    }
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun elementByTag(root: Element, tag: String, index: Int): Element {
    val expectedTag = tag.lowercase()
    var seen = 0

    fun visitChildren(element: Element): Element? {
        val children = element.asDynamic()._children
        val length = children.length.unsafeCast<Int>()
        for (childIndex in 0 until length) {
            val childElement = children[childIndex].unsafeCast<Element>()
            val childTag = childElement.asDynamic().tagName
            if (childTag != null) {
                val actualTag = childTag.unsafeCast<String>().lowercase()
                if (actualTag == expectedTag) {
                    if (seen == index) {
                        return childElement
                    }
                    seen += 1
                }
                val found = visitChildren(childElement)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    return visitChildren(root) ?: error("Expected <$tag> element at index $index.")
}

private fun installListenerTally(): dynamic = js(
    """
    (function () {
      var tally = { adds: 0, removes: 0 };
      var proto = globalThis.Element.prototype;
      var origAdd = proto.addEventListener, origRemove = proto.removeEventListener;
      proto.addEventListener = function (t, l) { tally.adds++; return origAdd.call(this, t, l); };
      proto.removeEventListener = function (t, l) { tally.removes++; return origRemove.call(this, t, l); };
      return tally;
    })()
    """,
)
