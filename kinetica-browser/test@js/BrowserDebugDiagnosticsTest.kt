package io.heapy.kinetica.browser

import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserDebugDiagnosticsTest {
    @Test
    fun debugAttributesFollowBundleConfiguration() {
        verifyAttributes(debug = true)
    }

    @Test
    fun productionRuntimeKeepsOnlyRequiredIslandMetadata() {
        verifyAttributes(debug = false)
    }

    private fun verifyAttributes(debug: Boolean) {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        var addLeadingChild = false
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = debug)) {
            emit(HostNode("section", children = buildList {
                if (addLeadingChild) add(HostNode("p"))
                add(ClientRef(componentId = "test.Island"))
            }))
        }
        // The verification script supplies expectations independently of the production define.
        val bundleKeepsDiagnostics: Boolean = js("globalThis.KINETICA_TEST_EXPECT_DEBUG_ATTRIBUTES !== false")
        try {
            val section = root.firstChild.unsafeCast<Element>()
            assertEquals(if (debug && bundleKeepsDiagnostics) "section" else null, section.getAttribute("data-kinetica-tag"))
            assertEquals(if (debug && bundleKeepsDiagnostics) "" else null, section.getAttribute("data-kinetica-path"))
            assertIslandPath(section, index = 0)

            addLeadingChild = true
            app.render()
            assertIslandPath(section, index = 1)
            if (!debug || !bundleKeepsDiagnostics) {
                val leading = section.childNodes.item(0).unsafeCast<Element>()
                assertNull(leading.getAttribute("data-kinetica-tag"))
                assertNull(leading.getAttribute("data-kinetica-path"))
            }
        } finally {
            app.dispose()
        }
    }

    private fun assertIslandPath(section: Element, index: Int) {
        val island = section.childNodes.item(index).unsafeCast<Element>()
        assertEquals("test.Island", island.getAttribute("data-kinetica-client-ref"))
        assertEquals(index.toString(), island.getAttribute("data-kinetica-path"))
    }
}
