package io.heapy.kinetica.browser

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.TemplateDefinition
import io.heapy.kinetica.TemplateHole
import io.heapy.kinetica.TemplateHoleKinds
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.event
import io.heapy.kinetica.hostEvent
import io.heapy.kinetica.templateNode
import io.heapy.kinetica.textInput
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserTemplateEventDispatchTest {
    @Test
    fun templateClickHonorsEnabledPropHole() {
        installTestDocument()
        val disabledRoot = testDocument().createElement("div").unsafeCast<Element>()
        var disabledClicks = 0
        val disabledApp = mountKineticaApp(disabledRoot) {
            TemplateClickEnabledApp(enabled = "false") {
                disabledClicks += 1
            }
        }

        val disabledAttribute: String?
        val disabledClickCount: Int
        try {
            val button = firstElement(disabledRoot)
            disabledAttribute = button.getAttribute("disabled")
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            disabledClickCount = disabledClicks
        } finally {
            disabledApp.dispose()
        }

        val enabledRoot = testDocument().createElement("div").unsafeCast<Element>()
        var enabledClicks = 0
        val enabledApp = mountKineticaApp(enabledRoot) {
            TemplateClickEnabledApp(enabled = "true") {
                enabledClicks += 1
            }
        }

        val enabledAttribute: String?
        val enabledClickCount: Int
        try {
            val button = firstElement(enabledRoot)
            enabledAttribute = button.getAttribute("disabled")
            button.asDynamic().dispatchEvent(testDomEvent("click"))
            enabledClickCount = enabledClicks
        } finally {
            enabledApp.dispose()
        }

        val failures = mutableListOf<String>()
        if (disabledAttribute != "") {
            failures += "enabled=false disabled attribute was $disabledAttribute"
        }
        if (disabledClickCount != 0) {
            failures += "enabled=false click count was $disabledClickCount"
        }
        if (enabledAttribute != null) {
            failures += "enabled=true disabled attribute was $enabledAttribute"
        }
        if (enabledClickCount != 1) {
            failures += "enabled=true click count was $enabledClickCount"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test
    fun templateInputAndSubmitHolesOnOneElementBothDispatch() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val inputPayloads = mutableListOf<String>()
        var submits = 0
        val app = mountKineticaApp(root) {
            TemplateInputSubmitApp(
                onInput = { value -> inputPayloads += value },
                onSubmit = { submits += 1 },
            )
        }

        try {
            val input = firstElement(root).unsafeCast<HTMLInputElement>()
            input.value = "draft"
            input.asDynamic().dispatchEvent(testDomEvent("input"))
            val keydown = testDomEvent("keydown", key = "Enter")
            input.asDynamic().dispatchEvent(keydown)

            val failures = mutableListOf<String>()
            if (inputPayloads != listOf("draft")) {
                failures += "input payloads were $inputPayloads"
            }
            if (submits != 1) {
                failures += "submit count was $submits"
            }
            if (keydown.preventDefaultCalled != true) {
                failures += "keydown preventDefaultCalled was ${keydown.preventDefaultCalled}"
            }
            assertTrue(failures.isEmpty(), failures.joinToString("; "))
        } finally {
            app.dispose()
        }
    }

    @Test
    fun templateEventBindingsAreClearedOnUnmount() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        var showTemplate = true
        var clicks = 0
        val app = mountKineticaApp(root) {
            if (showTemplate) {
                TemplateClickEnabledApp(enabled = "true") {
                    clicks += 1
                }
            } else {
                emit(HostNode("div"))
            }
        }

        try {
            val button = firstElement(root)
            showTemplate = false
            app.render()
            button.asDynamic().dispatchEvent(testDomEvent("click"))

            assertEquals(0, clicks)
            assertNull(button.asDynamic().__kinetica)
        } finally {
            app.dispose()
        }
    }

    @Test
    fun hostInputStillDispatchesThroughDelegation() {
        installTestDocument()
        val root = testDocument().createElement("div").unsafeCast<Element>()
        val inputPayloads = mutableListOf<String>()
        val app = mountKineticaApp(root) {
            HostInputApp { value -> inputPayloads += value }
        }

        try {
            val input = firstElement(root).unsafeCast<HTMLInputElement>()
            input.value = "host"
            input.asDynamic().dispatchEvent(testDomEvent("input"))

            assertEquals(listOf("host"), inputPayloads)
        } finally {
            app.dispose()
        }
    }
}

private val InputSubmitTemplateDefinition = TemplateDefinition(
    id = "browser-template-input-submit-events",
    skeleton = HostNode(tag = "textInput"),
    holes = listOf(
        TemplateHole(path = "", kind = TemplateHoleKinds.EventProp, propName = "event:onInput"),
        TemplateHole(path = "", kind = TemplateHoleKinds.EventProp, propName = "event:onSubmit"),
    ),
)

private val ClickEnabledTemplateDefinition = TemplateDefinition(
    id = "browser-template-click-enabled",
    skeleton = HostNode(tag = "button"),
    holes = listOf(
        TemplateHole(path = "", kind = TemplateHoleKinds.Prop, propName = "enabled"),
        TemplateHole(path = "", kind = TemplateHoleKinds.EventProp, propName = "event:onClick"),
    ),
)

@UiComponent
private fun ComponentScope.TemplateInputSubmitApp(
    onInput: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val registeredInput = renderNode {
        textInput(
            value = "",
            onInput = event<String> { value -> onInput(value) },
            onSubmit = event { onSubmit() },
            semantics = null,
        )
    } as HostNode
    emit(
        templateNode(
            definition = InputSubmitTemplateDefinition,
            values = listOf(
                registeredInput.props.getValue("event:onInput"),
                registeredInput.props.getValue("event:onSubmit"),
            ),
        ),
    )
}

@UiComponent
private fun ComponentScope.TemplateClickEnabledApp(
    enabled: String?,
    onClick: () -> Unit,
) {
    val click = hostEvent(onEvent = event { onClick() })
    emit(
        templateNode(
            definition = ClickEnabledTemplateDefinition,
            values = listOf(enabled, click),
        ),
    )
}

@UiComponent
private fun ComponentScope.HostInputApp(onInput: (String) -> Unit) {
    textInput(
        value = "",
        onInput = event<String> { value -> onInput(value) },
        semantics = null,
    )
}

private fun firstElement(root: Element): Element =
    root.firstChild?.unsafeCast<Element>() ?: error("Expected a mounted element.")
