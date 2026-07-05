package io.heapy.kinetica.browser

import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaJson
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.toSafeHtml
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.Document
import org.w3c.dom.DocumentFragment
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Node as DomNode
import org.w3c.dom.events.Event

public class BrowserKineticaApp(
    private val rootElement: Element,
    private val runtime: KineticaRuntime = KineticaRuntime(debug = true),
    private val content: ComponentScope.() -> Unit,
) {
    private val scope = ComponentScope(runtime)
    private var currentTree: Node? = null

    public fun render() {
        render(restoredFocus = null)
    }

    public suspend fun awaitIdle() {
        while (true) {
            runtime.awaitIdle()
            if (runtime.hasPendingInvalidation) {
                render(restoredFocus = BrowserFocus.capture())
                continue
            }
            return
        }
    }

    public fun tree(): Node =
        currentTree ?: error("Browser app has not rendered.")

    public fun innerHtml(): String =
        rootElement.innerHTML

    public fun snapshot(): BrowserUiSnapshot =
        BrowserUiSnapshot(
            innerHtml = innerHtml(),
            treeHtml = tree().toSafeHtml(),
            treeJson = BrowserSnapshotJson.encodeToString(Node.serializer(), tree()),
        )

    public fun assertInnerHtmlSnapshot(expected: String) {
        assertSnapshotEquals(expected, innerHtml(), label = "Browser DOM snapshot")
    }

    public fun elementByTestTag(testTag: String): Element =
        rootElement.querySelector("[data-testid=\"$testTag\"]")
            ?: error("No browser element found for data-testid=\"$testTag\".")

    public fun clickTestTag(testTag: String) {
        elementByTestTag(testTag).asDynamic().click()
    }

    public fun inputTestTag(testTag: String, value: String) {
        val input = elementByTestTag(testTag) as? HTMLInputElement
            ?: error("Element with data-testid=\"$testTag\" is not an input.")
        input.value = value
        input.dispatchEvent(browserEvent("input"))
    }

    public fun dispose() {
        clearRoot()
        currentTree = null
        scope.dispose()
    }

    private fun render(restoredFocus: BrowserFocus?) {
        currentTree = runtime.render(scope, content).tree
        clearRoot()
        rootElement.appendChild(renderNode(tree(), path = emptyList()))
        restoredFocus?.restore(rootElement)
    }

    private fun renderNode(node: Node, path: List<Int>): DomNode =
        when (node) {
            is FragmentNode -> renderFragment(node, path)
            is HostNode -> renderHost(node, path)
            is TextNode -> renderText(node, path)
            is ClientRef -> renderClientRef(node, path)
        }

    private fun renderFragment(node: FragmentNode, path: List<Int>): DocumentFragment {
        val fragment = browserDocument.createDocumentFragment()
        node.children.forEachIndexed { index, child ->
            fragment.appendChild(renderNode(child, path + index))
        }
        return fragment
    }

    private fun renderHost(node: HostNode, path: List<Int>): Element {
        val element = browserDocument.createElement(browserTagNameFor(node.tag))
        element.setAttribute(DATA_KINETICA_TAG, node.tag)
        element.setAttribute(DATA_KINETICA_PATH, path.toDomPath())
        node.key?.let { key -> element.setAttribute(DATA_KINETICA_KEY, key) }
        node.semantics.applyTo(element, nativeTag = node.tag)

        when (node.tag) {
            "column" -> {
                element.applyPublicProps(node)
                element.applyFlex(direction = "column")
            }
            "row" -> {
                element.applyPublicProps(node)
                element.applyFlex(direction = "row")
                if (node.props["direction"] == "Rtl") {
                    element.setAttribute("dir", "rtl")
                }
            }
            "button" -> element.configureButton(node)
            "textInput" -> element.configureTextInput(node)
            "checkbox" -> element.configureCheckbox(node)
            else -> element.applyPublicProps(node)
        }

        node.children.forEachIndexed { index, child ->
            element.appendChild(renderNode(child, path + index))
        }
        return element
    }

    private fun renderText(node: TextNode, path: List<Int>): DomNode {
        val semantics = node.semantics
        val needsElement = node.strikethrough || semantics.hasElementAttributes()
        if (!needsElement) {
            return browserDocument.createTextNode(node.value)
        }

        return browserDocument.createElement("span").also { element ->
            element.textContent = node.value
            element.setAttribute(DATA_KINETICA_PATH, path.toDomPath())
            if (node.strikethrough) {
                element.setAttribute("style", "text-decoration: line-through;")
            }
            semantics.applyTo(element, nativeTag = "text")
        }
    }

    private fun renderClientRef(node: ClientRef, path: List<Int>): Element =
        browserDocument.createElement("template").also { element ->
            element.setAttribute(DATA_CLIENT_REF, node.componentId)
            element.setAttribute(DATA_KINETICA_PATH, path.toDomPath())
            element.setAttribute(DATA_CLIENT_PROPS, KineticaJson.encodeToString(JsonObject.serializer(), node.props))
        }

    private fun Element.configureButton(node: HostNode) {
        applyPublicProps(node)
        val enabled = node.props["enabled"]?.toBooleanStrictOrNull() ?: true
        if (!enabled) {
            setAttribute("disabled", "")
        }
        node.props["event:onClick"]?.takeIf { enabled }?.let { eventId ->
            addEventListener("click", {
                dispatchAndRender(eventId, Unit)
            })
        }
    }

    private fun Element.configureTextInput(node: HostNode) {
        setAttribute("type", "text")
        applyPublicProps(node)
        val input = this as HTMLInputElement
        input.value = node.props["value"].orEmpty()
        node.props["placeholder"]?.let { placeholder -> input.placeholder = placeholder }
        node.props["event:onInput"]?.let { eventId ->
            addEventListener("input", {
                dispatchAndRender(eventId, input.value)
            })
        }
        node.props["event:onSubmit"]?.let { eventId ->
            addEventListener("keydown", { event ->
                if (event.asDynamic().key == "Enter") {
                    event.preventDefault()
                    dispatchAndRender(eventId, Unit)
                }
            })
        }
    }

    private fun Element.configureCheckbox(node: HostNode) {
        setAttribute("type", "checkbox")
        applyPublicProps(node)
        val input = this as HTMLInputElement
        input.checked = node.props["checked"]?.toBooleanStrictOrNull() ?: false
        node.props["event:onToggle"]?.let { eventId ->
            addEventListener("change", {
                dispatchAndRender(eventId, Unit)
            })
        }
    }

    private fun Element.applyPublicProps(node: HostNode) {
        node.props
            .filter { (name, value) -> isPublicBrowserAttribute(name, value) }
            .forEach { (name, value) -> setAttribute(name, value) }
    }

    private fun Element.applyFlex(direction: String) {
        setAttribute(
            "style",
            listOf(
                "display: flex",
                "flex-direction: $direction",
                "gap: 0.5rem",
                "align-items: ${if (direction == "row") "center" else "stretch"}",
            ).joinToString(separator = "; ", postfix = ";"),
        )
    }

    private fun dispatchAndRender(eventId: String, payload: Any?) {
        val focus = BrowserFocus.capture()
        runtime.dispatch(eventId, payload)
        render(restoredFocus = focus)
    }

    private fun clearRoot() {
        while (rootElement.firstChild != null) {
            rootElement.removeChild(rootElement.firstChild!!)
        }
    }
}

public data class BrowserUiSnapshot(
    val innerHtml: String,
    val treeHtml: String,
    val treeJson: String,
)

public fun mountKineticaApp(
    root: Element,
    runtime: KineticaRuntime = KineticaRuntime(debug = true),
    content: ComponentScope.() -> Unit,
): BrowserKineticaApp =
    BrowserKineticaApp(root, runtime, content).also { app -> app.render() }

public fun mountKineticaApp(
    selector: String,
    runtime: KineticaRuntime = KineticaRuntime(debug = true),
    content: ComponentScope.() -> Unit,
): BrowserKineticaApp {
    val root = browserDocument.querySelector(selector)
        ?: error("No element matches selector: $selector")
    return mountKineticaApp(root, runtime, content)
}

public fun assertBrowserSnapshotEquals(
    expected: String,
    actual: String,
    label: String = "Browser snapshot",
) {
    assertSnapshotEquals(expected, actual, label)
}

private fun Semantics?.applyTo(element: Element, nativeTag: String) {
    val semantics = this ?: return
    semantics.testTag?.let { testTag ->
        element.setAttribute("data-testid", testTag)
        element.setAttribute("data-kinetica-test-tag", testTag)
    }
    semantics.role?.let { role ->
        val roleAttribute = browserRoleFor(role)
        if (roleAttribute != null && nativeTag !in NativeRoleTags) {
            element.setAttribute("role", roleAttribute)
        }
    }
    semantics.label?.let { label -> element.setAttribute("aria-label", label) }
    semantics.stateDescription?.let { description -> element.setAttribute("aria-description", description) }
    if (semantics.focusable && nativeTag !in NativeFocusableTags) {
        element.setAttribute("tabindex", "0")
    }
    if (semantics.traversalIndex != null) {
        element.setAttribute("data-kinetica-traversal-index", semantics.traversalIndex.toString())
    }
    if (semantics.leaving) {
        element.setAttribute("data-kinetica-leaving", "true")
    }
}

private fun Semantics?.hasElementAttributes(): Boolean =
    this != null &&
        (
            testTag != null ||
                role?.let(::browserRoleFor) != null ||
                label != null ||
                stateDescription != null ||
                focusable ||
                traversalIndex != null ||
                leaving
            )

private class BrowserFocus(
    private val stableSelector: String?,
    private val path: String?,
    private val selectionStart: Int?,
    private val selectionEnd: Int?,
) {
    fun restore(root: Element) {
        val element = stableSelector?.let(root::querySelector)
            ?: path?.let { root.querySelector(attributeSelector(DATA_KINETICA_PATH, it)) }
            ?: return
        element.asDynamic().focus()
        val input = (element as? HTMLInputElement)
            ?.takeIf { browserInputTypeSupportsTextSelection(it.type) }
            ?: return
        if (selectionStart != null && selectionEnd != null) {
            input.setSelectionRange(selectionStart, selectionEnd)
        }
    }

    companion object {
        fun capture(): BrowserFocus? {
            val element = browserDocument.activeElement ?: return null
            val stableSelector = element.getAttribute("data-testid")?.let { testTag ->
                attributeSelector("data-testid", testTag)
            } ?: element.getAttribute(DATA_KINETICA_KEY)?.let { key ->
                attributeSelector(DATA_KINETICA_KEY, key)
            }
            val path = element.getAttribute(DATA_KINETICA_PATH)
            if (stableSelector == null && path == null) {
                return null
            }
            val input = element as? HTMLInputElement
            val selectionInput = input?.takeIf { browserInputTypeSupportsTextSelection(it.type) }
            return BrowserFocus(
                stableSelector = stableSelector,
                path = path,
                selectionStart = selectionInput?.selectionStart,
                selectionEnd = selectionInput?.selectionEnd,
            )
        }
    }
}

private fun attributeSelector(name: String, value: String): String =
    "[$name=\"${value.escapeCssAttributeValue()}\"]"

private fun String.escapeCssAttributeValue(): String =
    buildString(length) {
        this@escapeCssAttributeValue.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\a ")
                '\r' -> append("\\d ")
                '\t' -> append("\\9 ")
                else -> append(character)
            }
        }
    }

private fun List<Int>.toDomPath(): String =
    joinToString(separator = ".")

private fun browserEvent(type: String): Event =
    js("new Event(type, { bubbles: true, cancelable: true })").unsafeCast<Event>()

private val browserDocument: Document
    get() = js("document").unsafeCast<Document>()

private fun assertSnapshotEquals(
    expected: String,
    actual: String,
    label: String,
) {
    val normalizedExpected = expected.trimIndent().trim()
    val normalizedActual = actual.trim()
    if (normalizedExpected != normalizedActual) {
        throw AssertionError(
            "$label mismatch.\nExpected:\n$normalizedExpected\nActual:\n$normalizedActual",
        )
    }
}

private val BrowserSnapshotJson = kotlinx.serialization.json.Json(KineticaJson) {
    prettyPrint = true
}

private const val DATA_KINETICA_TAG = "data-kinetica-tag"
private const val DATA_KINETICA_KEY = "data-kinetica-key"
private const val DATA_KINETICA_PATH = "data-kinetica-path"
private const val DATA_CLIENT_REF = "data-kinetica-client-ref"
private const val DATA_CLIENT_PROPS = "data-kinetica-props"

private val NativeRoleTags = setOf("button", "textInput", "checkbox")
private val NativeFocusableTags = setOf("button", "textInput", "checkbox")
