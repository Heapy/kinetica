package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TemplateDefinition
import io.heapy.kinetica.TemplateHole
import io.heapy.kinetica.TemplateHoleKinds
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.hostEvent
import io.heapy.kinetica.store
import io.heapy.kinetica.templateNode
import io.heapy.kinetica.text
import org.w3c.dom.Element
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class BrowserHostPropPatchTest {
    /**
     * KSND-129 (sources: RCT-307, RCT-314, INF-140, PRE-074, VUE-148).
     */
    @Test
    fun arbitraryHostPropAddUpdateRemoveRoundTripsInPlace() {
        installTestDocument()
        val root = attachedRoot()
        val probe = HostPropsProbe(mapOf("title" to "x"))
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            RawHostPropsPatchApp(probe)
        }

        try {
            val element = firstElement(root)
            assertEquals("x", element.getAttribute("title"))

            probe.props.value = mapOf("title" to "y")
            app.render()
            assertSame(element, firstElement(root))
            assertEquals("y", element.getAttribute("title"))

            probe.props.value = emptyMap()
            app.render()
            assertSame(element, firstElement(root))
            assertNull(element.getAttribute("title"))

            probe.props.value = mapOf("title" to "z")
            app.render()
            assertSame(element, firstElement(root))
            assertEquals("z", element.getAttribute("title"))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-130 (sources: RCT-314, PRE-096).
     */
    @Test
    fun dataAndAriaPropsPatchVerbatimAndDropRemovedAttributes() {
        installTestDocument()
        val root = attachedRoot()
        val probe = HostPropsProbe(
            mapOf(
                "data-foo" to "bar",
                "aria-checked" to "false",
                "title" to "keep",
            ),
        )
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            RawHostPropsPatchApp(probe)
        }

        try {
            val element = firstElement(root)
            assertEquals("bar", element.getAttribute("data-foo"))
            assertEquals("false", element.getAttribute("aria-checked"))
            assertEquals("keep", element.getAttribute("title"))

            probe.props.value = mapOf(
                "data-foo" to "baz",
                "title" to "keep",
            )
            app.render()

            assertSame(element, firstElement(root))
            assertEquals("baz", element.getAttribute("data-foo"))
            assertNull(element.getAttribute("aria-checked"))
            assertEquals("keep", element.getAttribute("title"))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-131 (sources: RCT-309, PRE-077, INF-143).
     */
    @Test
    fun buttonEnabledTogglingSetsDisabledForDslAndRawHostNodes() {
        installTestDocument()
        val dslRoot = attachedRoot()
        val dslProbe = EnabledButtonProbe()
        val dslApp = mountKineticaApp(dslRoot, runtime = KineticaRuntime(debug = false)) {
            HostDslEnabledButtonApp(dslProbe)
        }

        try {
            val button = firstElement(dslRoot)
            assertEquals("", button.getAttribute("disabled"))
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(0, dslProbe.clicks)

            dslProbe.enabled.value = "true"
            dslApp.render()
            assertSame(button, firstElement(dslRoot))
            assertNull(button.getAttribute("disabled"))
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, dslProbe.clicks)

            dslProbe.enabled.value = null
            dslApp.render()
            assertSame(button, firstElement(dslRoot))
            assertNull(button.getAttribute("disabled"))
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(2, dslProbe.clicks)
        } finally {
            dslApp.dispose()
        }

        val rawRoot = attachedRoot()
        val rawProbe = EnabledButtonProbe()
        val rawApp = mountKineticaApp(rawRoot, runtime = KineticaRuntime(debug = false)) {
            RawEnabledButtonApp(rawProbe)
        }

        try {
            val button = firstElement(rawRoot)
            assertEquals("", button.getAttribute("disabled"))
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(0, rawProbe.clicks)

            rawProbe.enabled.value = "true"
            rawApp.render()
            assertSame(button, firstElement(rawRoot))
            assertNull(button.getAttribute("disabled"))
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(1, rawProbe.clicks)

            rawProbe.enabled.value = null
            rawApp.render()
            assertSame(button, firstElement(rawRoot))
            assertNull(button.getAttribute("disabled"))
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            assertEquals(2, rawProbe.clicks)
        } finally {
            rawApp.dispose()
        }
    }

    /**
     * KSND-132 (sources: BrowserMappingTest).
     */
    @Test
    fun semanticsTestTagRoleAndLabelPatchAcrossRenders() {
        installTestDocument()
        val root = attachedRoot()
        val probe = SemanticsProbe(
            Semantics(testTag = "a", role = Role.Button, label = "L1"),
        )
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            RawHostSemanticsPatchApp(probe)
        }

        try {
            val element = firstElement(root)
            assertEquals("a", element.getAttribute("data-testid"))
            assertEquals("a", element.getAttribute("data-kinetica-test-tag"))
            assertEquals("button", element.getAttribute("role"))
            assertEquals("L1", element.getAttribute("aria-label"))

            probe.semantics.value = Semantics(testTag = "b", role = Role.Dialog, label = "L2")
            app.render()

            assertSame(element, firstElement(root))
            assertEquals("b", element.getAttribute("data-testid"))
            assertEquals("b", element.getAttribute("data-kinetica-test-tag"))
            assertEquals("dialog", element.getAttribute("role"))
            assertEquals("L2", element.getAttribute("aria-label"))

            probe.semantics.value = Semantics(testTag = "c", role = Role.ListItem)
            app.render()

            assertSame(element, firstElement(root))
            assertEquals("c", element.getAttribute("data-testid"))
            assertEquals("c", element.getAttribute("data-kinetica-test-tag"))
            assertEquals("listitem", element.getAttribute("role"))
            assertNull(element.getAttribute("aria-label"))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-133 (sources: VUE-135, SVL-048, SVL-049, PRE-081).
     */
    @Test
    fun unchangedPropsCauseZeroConnectedAttributeWrites() {
        installTestDocument()
        val ops = installAttributeWriteLog()
        val root = attachedRoot()
        val probe = AttributeWriteProbe()
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            AttributeWriteApp(probe)
        }

        try {
            val element = firstElement(root)
            clearAttributeOps(ops)

            app.render()

            assertSame(element, firstElement(root))
            assertEquals(0, attributeOpCount(ops))

            probe.dataFoo.value = "baz"
            app.render()

            assertSame(element, firstElement(root))
            assertEquals(1, attributeOpCount(ops))
            assertEquals(1, countAttributeOps(ops, "setAttribute"))
            assertEquals(0, countAttributeOps(ops, "removeAttribute"))
            assertEquals("data-foo", attributeOpAt(ops, 0).name.unsafeCast<String>())
            assertEquals("baz", attributeOpAt(ops, 0).value.unsafeCast<String>())
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-134 (sources: BrowserMappingTest, RCT-322).
     */
    @Test
    fun unsafePropsStayFilteredWhenIntroducedByPatch() {
        installTestDocument()
        val root = attachedRoot()
        val probe = HostPropsProbe(mapOf("href" to "https://example.test/cart"))
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            RawHostPropsPatchApp(probe)
        }

        try {
            val element = firstElement(root)
            assertEquals("https://example.test/cart", element.getAttribute("href"))

            probe.props.value = mapOf("href" to "javascript:alert(1)")
            app.render()

            assertSame(element, firstElement(root))
            assertNull(element.getAttribute("href"))

            probe.props.value = mapOf(
                "srcdoc" to "<script>alert(1)</script>",
                "event:onClick" to "event-0",
                "frame:translateX" to "frame-0",
            )
            app.render()

            assertSame(element, firstElement(root))
            assertNull(element.getAttribute("href"))
            assertNull(element.getAttribute("srcdoc"))
            assertNull(element.getAttribute("event:onClick"))
            assertNull(element.getAttribute("frame:translateX"))
        } finally {
            app.dispose()
        }
    }

    /**
     * KSND-135 (sources: KNT-0010, SVL-049).
     */
    // KSND-135: Prop-hole null removal is pinned to patchTemplateValues nextValue == null removeAttribute (BrowserKineticaApp.kt:613-616); key holes patch the effective template reconcile key in patchTemplate (BrowserKineticaApp.kt:550-562), so prop/key null fallback behavior intentionally differs.
    @Test
    fun templatePropHoleRemovalClearsAttributeInsteadOfRestoringSkeletonDefault() {
        installTestDocument()
        val root = attachedRoot()
        val definition = templatePropHoleDefinition(id = "browser-template-prop-hole-removal")
        val probe = TemplatePropHoleProbe(definition)
        val app = mountKineticaApp(root, runtime = KineticaRuntime(debug = false)) {
            TemplatePropHoleApp(probe)
        }

        try {
            val element = firstElement(root)
            assertEquals("big", element.getAttribute("class"))

            probe.values.value = listOf("wide")
            app.render()

            assertSame(element, firstElement(root))
            assertEquals("wide", element.getAttribute("class"))

            probe.values.value = listOf<String?>(null)
            app.render()

            assertSame(element, firstElement(root))
            assertNull(element.getAttribute("class"))
        } finally {
            app.dispose()
        }
    }
}

private class HostPropsProbe(
    initial: Map<String, String>,
) {
    val props = store(initial)
}

private class EnabledButtonProbe {
    val enabled = store<String?>("false")
    var clicks = 0
}

private class SemanticsProbe(
    initial: Semantics,
) {
    val semantics = store(initial)
}

private class AttributeWriteProbe {
    val title = store("x")
    val dataFoo = store("bar")
    val ariaLabel = store("Label")
}

private class TemplatePropHoleProbe(
    val definition: TemplateDefinition,
) {
    val values = store(listOf<String?>("big"))
}

@UiComponent(skippable = false)
private fun ComponentScope.RawHostPropsPatchApp(probe: HostPropsProbe) {
    emit(HostNode(tag = "box", props = probe.props.value))
}

@UiComponent(skippable = false)
private fun ComponentScope.HostDslEnabledButtonApp(probe: EnabledButtonProbe) {
    val click = hostEvent(onEvent = event { probe.clicks += 1 })
    host("button", props = enabledButtonProps(probe.enabled.value, click)) {
        text("Host", semantics = null)
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.RawEnabledButtonApp(probe: EnabledButtonProbe) {
    val click = hostEvent(onEvent = event { probe.clicks += 1 })
    emit(
        HostNode(
            tag = "button",
            props = enabledButtonProps(probe.enabled.value, click),
            children = listOf(TextNode("Raw", semantics = null)),
        ),
    )
}

@UiComponent(skippable = false)
private fun ComponentScope.RawHostSemanticsPatchApp(probe: SemanticsProbe) {
    emit(HostNode(tag = "box", semantics = probe.semantics.value))
}

@UiComponent(skippable = false)
private fun ComponentScope.AttributeWriteApp(probe: AttributeWriteProbe) {
    emit(
        HostNode(
            tag = "box",
            props = mapOf(
                "title" to probe.title.value,
                "data-foo" to probe.dataFoo.value,
                "aria-label" to probe.ariaLabel.value,
            ),
        ),
    )
}

@UiComponent(skippable = false)
private fun ComponentScope.TemplatePropHoleApp(probe: TemplatePropHoleProbe) {
    emit(templateNode(definition = probe.definition, values = probe.values.value))
}

private fun enabledButtonProps(enabled: String?, click: String): Map<String, String> =
    if (enabled == null) {
        mapOf("event:onClick" to click)
    } else {
        mapOf(
            "enabled" to enabled,
            "event:onClick" to click,
        )
    }

private fun templatePropHoleDefinition(id: String): TemplateDefinition =
    TemplateDefinition(
        id = id,
        skeleton = HostNode(
            tag = "box",
            props = mapOf("class" to "small"),
        ),
        holes = listOf(TemplateHole(path = "", kind = TemplateHoleKinds.Prop, propName = "class")),
    )

private fun attachedRoot(): Element {
    val root = testDocument().createElement("div").unsafeCast<Element>()
    testDocument().body.insertBefore(root, null)
    return root
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")

private fun installAttributeWriteLog(): dynamic = js(
    """
    (function () {
      var ops = [];
      var proto = globalThis.Element.prototype;
      var origSet = proto.setAttribute;
      var origRemove = proto.removeAttribute;
      proto.setAttribute = function (name, value) {
        if (this.isConnected) {
          ops.push({ op: "setAttribute", name: String(name), value: String(value), target: this });
        }
        return origSet.call(this, name, value);
      };
      proto.removeAttribute = function (name) {
        if (this.isConnected) {
          ops.push({ op: "removeAttribute", name: String(name), target: this });
        }
        return origRemove.call(this, name);
      };
      return ops;
    })()
    """,
)

private fun attributeOpCount(ops: dynamic): Int = ops.length.unsafeCast<Int>()

private fun clearAttributeOps(ops: dynamic) {
    ops.length = 0
}

private fun attributeOpAt(ops: dynamic, i: Int): dynamic = ops[i]

private fun countAttributeOps(ops: dynamic, kind: String): Int {
    var count = 0
    for (index in 0 until attributeOpCount(ops)) {
        if (attributeOpAt(ops, index).op.unsafeCast<String>() == kind) {
            count += 1
        }
    }
    return count
}
