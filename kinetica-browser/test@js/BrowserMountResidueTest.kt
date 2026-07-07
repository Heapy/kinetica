package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.button
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.state
import io.heapy.kinetica.store
import io.heapy.kinetica.text
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class BrowserMountResidueTest {
    /**
     * KSND-100 (sources: SVL-113).
     */
    @Test
    fun repeatedMountDisposeLeavesNoChildrenAndBalancesListeners() {
        installTestDocument()
        val tally = installListenerTally()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)

        for (index in 0 until 50) {
            val probe = StatefulCounterProbe()
            val app = mountKineticaApp(root) {
                StatefulCounterApp(probe)
            }

            try {
                assertEquals(1, probe.inits)
            } finally {
                app.dispose()
            }
            assertEquals(0, root.childNodes.length)
        }

        assertEquals(0, root.childNodes.length)
        assertEquals(tally.adds.unsafeCast<Int>(), tally.removes.unsafeCast<Int>())
    }

    /**
     * KSND-101 (sources: SOL-086).
     */
    @Test
    fun disposeRemovesDelegatedListenersAndPostDisposeClickIsInert() {
        installTestDocument()
        val tally = installListenerTally()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = MountResidueSingleButtonProbe()
        val app = mountKineticaApp(root) {
            MountResidueSingleButtonApp(probe)
        }

        try {
            val button = firstElement(root)
            val adds = tally.adds.unsafeCast<Int>()
            assertEquals(4, adds)
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probe.clicks)

            app.dispose()

            assertEquals(adds, tally.removes.unsafeCast<Int>())
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            dispatchClickThroughForgedParent(
                target = button,
                detachedRoot = button,
                forgedParent = root,
                dispatchElement = root,
            )
            assertEquals(1, probe.clicks)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-102 (sources: SOL-092).
     */
    @Test
    fun disposeToleratesExternallyClearedRootAndIsIdempotent() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = MountResidueSingleButtonProbe()
        val app = mountKineticaApp(root) {
            MountResidueSingleButtonApp(probe)
        }

        try {
            root.asDynamic().textContent = ""
            assertEquals(0, root.childNodes.length)

            app.dispose()
            assertEquals(0, root.childNodes.length)

            app.dispose()
            assertEquals(0, root.childNodes.length)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-103 (sources: RCT-417, SOL-093).
     */
    @Test
    fun remountAfterDisposeCreatesFreshInstance() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val firstProbe = StatefulCounterProbe()
        val firstApp = mountKineticaApp(root) {
            StatefulCounterApp(firstProbe)
        }

        try {
            val firstButton = firstElement(root)
            for (index in 0 until 3) {
                firstButton.asDynamic().dispatchEvent(testDomEvent("click"))
            }
            assertEquals("Count: 3", firstButton.textContent)
            assertEquals(1, firstProbe.inits)

            firstApp.dispose()

            val secondProbe = StatefulCounterProbe()
            val secondApp = mountKineticaApp(root) {
                StatefulCounterApp(secondProbe)
            }
            try {
                val secondButton = firstElement(root)
                assertNotSame(firstButton, secondButton)
                assertEquals("Count: 0", secondButton.textContent)
                assertEquals(1, secondProbe.inits)
            } finally {
                secondApp.dispose()
            }
        } finally {
            firstApp.dispose()
        }
    }

    /**
     * KSND-104 (sources: SVL-115, INF-113).
     */
    @Test
    fun removedKeyedSubtreeClearsBookkeeping() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = KeyedRowsProbe(listOf("a", "b", "c"))
        val app = mountKineticaApp(root) {
            KeyedRowsApp(probe)
        }

        try {
            val section = firstElement(root)
            val a = childElement(section, index = 0)
            val b = childElement(section, index = 1)
            val c = childElement(section, index = 2)
            assertHasBookkeeping(a)
            assertHasBookkeeping(b)
            assertHasBookkeeping(c)

            probe.rows.value = listOf("a", "c")
            app.render()

            assertSame(a, childElement(section, index = 0))
            assertSame(c, childElement(section, index = 1))
            assertNull(b.parentNode)
            assertNull(b.asDynamic().__kinetica)
            assertHasBookkeeping(a)
            assertHasBookkeeping(c)
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-105 (sources: SOL-090).
     */
    @Test
    fun bulkClearThenDisposeLeavesRootEmptyAndListenersBalanced() {
        installTestDocument()
        val tally = installListenerTally()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = KeyedRowsProbe((0 until 20).map { index -> "row-$index" })
        val app = mountKineticaApp(root) {
            KeyedRowsApp(probe)
        }

        try {
            val section = firstElement(root)
            assertEquals(20, section.childNodes.length)

            probe.rows.value = emptyList()
            app.render()
            assertEquals(0, section.childNodes.length)

            app.dispose()
            assertEquals(0, root.childNodes.length)
            assertEquals(tally.adds.unsafeCast<Int>(), tally.removes.unsafeCast<Int>())
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-106 (sources: RCT-205, INF-060).
     */
    @Test
    fun clickOnDetachedElementAfterRemovalIsInert() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        testDocument().body.insertBefore(root, null)
        val probe = KeyedButtonProbe()
        val app = mountKineticaApp(root) {
            KeyedButtonRowsApp(probe)
        }

        try {
            val section = firstElement(root)
            val keep = childElement(section, index = 0)
            val row = childElement(section, index = 1)
            val button = childElement(row, index = 0)
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, probe.clicks)

            probe.rows.value = listOf("keep")
            app.render()
            assertEquals(1, section.childNodes.length)
            assertSame(keep, childElement(section, index = 0))
            assertNull(row.parentNode)
            assertNull(row.asDynamic().__kinetica)
            assertNull(button.asDynamic().__kinetica)

            button.asDynamic().dispatchEvent(testDomEvent("click"))
            dispatchClickThroughForgedParent(
                target = button,
                detachedRoot = row,
                forgedParent = section,
                dispatchElement = root,
            )

            assertEquals(1, probe.clicks)
        } finally {
            app.dispose()
        }
    }
}

private class StatefulCounterProbe {
    var inits = 0
}

@UiComponent
private fun ComponentScope.StatefulCounterApp(probe: StatefulCounterProbe) {
    var count by state {
        probe.inits += 1
        0
    }
    button(onClick = event { count += 1 }) {
        text("Count: $count")
    }
}

private class MountResidueSingleButtonProbe {
    var clicks = 0
}

@UiComponent
private fun ComponentScope.MountResidueSingleButtonApp(probe: MountResidueSingleButtonProbe) {
    button(onClick = event { probe.clicks += 1 }) {
        text("Click")
    }
}

private class KeyedRowsProbe(initialRows: List<String>) {
    val rows = store(initialRows)
}

@UiComponent
private fun ComponentScope.KeyedRowsApp(probe: KeyedRowsProbe) {
    host("section") {
        each(probe.rows.value, key = { row -> row }) { row ->
            host("div", key = row) {
                text(row)
            }
        }
    }
}

private class KeyedButtonProbe {
    val rows = store(listOf("keep", "remove"))
    var clicks = 0
}

@UiComponent
private fun ComponentScope.KeyedButtonRowsApp(probe: KeyedButtonProbe) {
    host("section") {
        each(probe.rows.value, key = { row -> row }) { row ->
            host("div", key = row) {
                button(onClick = event { probe.clicks += 1 }) {
                    text(row)
                }
            }
        }
    }
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun childElement(parent: Element, index: Int): Element =
    parent.childNodes.item(index)?.unsafeCast<Element>()
        ?: error("Expected child element at index $index.")

private fun assertHasBookkeeping(element: Element) {
    assertNotNull(element.asDynamic().__kinetica)
}

private fun dispatchClickThroughForgedParent(
    target: Element,
    detachedRoot: Element,
    forgedParent: Element,
    dispatchElement: Element,
) {
    val previousParent = detachedRoot.asDynamic().parentNode
    detachedRoot.asDynamic().parentNode = forgedParent
    try {
        val event = testDomEvent("click")
        event.target = target
        dispatchElement.asDynamic().dispatchEvent(event)
    } finally {
        detachedRoot.asDynamic().parentNode = previousParent
    }
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
