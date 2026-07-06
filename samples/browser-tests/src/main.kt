package app.browser.tests

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TemplateDefinition
import io.heapy.kinetica.TemplateHole
import io.heapy.kinetica.TemplateHoleKinds
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.browser.mountKineticaApp
import io.heapy.kinetica.button
import io.heapy.kinetica.column
import io.heapy.kinetica.derived
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.hostEvent
import io.heapy.kinetica.launchEffect
import io.heapy.kinetica.state
import io.heapy.kinetica.store
import io.heapy.kinetica.templateNode
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement

fun ComponentScope.BrowserTestApp() {
    var draft by state(key = "draft") { "" }
    var committed by state(key = "committed") { "none" }
    var reverseItems by state(key = "reverseItems") { false }
    val commit = event {
        committed = draft.ifBlank { "empty" }
        draft = ""
    }
    val orderedItems = if (reverseItems) listOf("b", "a") else listOf("a", "b")

    column(semantics = Semantics(testTag = "browser-test-app")) {
        textInput(
            value = draft,
            onInput = event<String> { draft = it },
            onSubmit = commit,
            placeholder = "Message",
            semantics = Semantics(role = Role.TextInput, label = "Draft", testTag = "draft", focusable = true),
        )
        button(
            onClick = commit,
            semantics = Semantics(role = Role.Button, testTag = "commit", focusable = true),
        ) {
            text("Commit")
        }
        text("Committed: $committed")
        host(
            tag = "column",
            props = mapOf("id" to "layout-column", "title" to "Layout column", "data-extra" to "column-prop"),
            semantics = Semantics(testTag = "layout-column"),
        ) {
            text("Layout column")
        }
        host(
            tag = "row",
            props = mapOf("id" to "layout-row", "title" to "Layout row", "data-extra" to "row-prop"),
            semantics = Semantics(testTag = "layout-row"),
        ) {
            text("Layout row")
        }
        text("Status glyph", semantics = Semantics(role = Role.Image, label = "Status image"))
        column(semantics = Semantics(testTag = "reorder-list")) {
            each(orderedItems, key = { it }) { item ->
                textInput(
                    value = item,
                    onInput = event<String> { reverseItems = !reverseItems },
                    semantics = Semantics(role = Role.TextInput, testTag = "item-$item", focusable = true),
                    key = item,
                )
            }
        }
    }
}

fun ComponentScope.AsyncFocusApp() {
    var status by state(key = "status") { "loading" }
    launchEffect {
        delay(25)
        status = "ready"
    }

    column {
        textInput(
            value = "focus",
            semantics = Semantics(role = Role.TextInput, testTag = "async-input", focusable = true),
        )
        text("Async: $status")
    }
}

fun ComponentScope.SingleTextFastPathApp() {
    var label by state(key = "label") { "one" }

    column {
        button(
            onClick = { label = "two" },
            semantics = Semantics(role = Role.Button, testTag = "text-toggle", focusable = true),
        ) {
            text("Toggle")
        }
        host("p", semantics = Semantics(testTag = "single-text")) {
            text(label, semantics = null)
        }
    }
}

fun ComponentScope.SlotKindToggleApp() {
    var useDerived by state(key = "useDerived") { false }
    var clicks by state(key = "clicks") { 0 }

    column {
        if (useDerived) {
            val label by derived { "derived-arm" }
            host("p") { text(label, semantics = null) }
        } else {
            val n by state { 1 }
            host("p") { text("state-arm:$n", semantics = null) }
        }
        button(
            onClick = event { useDerived = !useDerived },
            semantics = Semantics(role = Role.Button, testTag = "kind-toggle", focusable = true),
        ) {
            text("Toggle arm")
        }
        button(
            onClick = event { clicks += 1 },
            semantics = Semantics(role = Role.Button, testTag = "kind-count", focusable = true),
        ) {
            text("Count")
        }
        host("p") { text("clicks:$clicks", semantics = null) }
    }
}

fun ComponentScope.ReplaceAllFastPathApp() {
    var generation by state(key = "generation") { 0 }
    val items = if (generation == 0) listOf(1, 2, 3, 4) else listOf(11, 12, 13, 14)

    column {
        button(
            onClick = { generation += 1 },
            semantics = Semantics(role = Role.Button, testTag = "replace-all", focusable = true),
        ) {
            text("Replace")
        }
        host("ul", semantics = Semantics(testTag = "replace-list")) {
            each(items, key = { it }) { item ->
                host("li", key = item) {
                    text("item $item", semantics = null)
                }
            }
        }
    }
}

fun ComponentScope.ClearFastPathApp() {
    var showItems by state(key = "showItems") { true }

    column {
        button(
            onClick = { showItems = false },
            semantics = Semantics(role = Role.Button, testTag = "clear-all", focusable = true),
        ) {
            text("Clear")
        }
        host("ul", semantics = Semantics(testTag = "clear-list")) {
            if (showItems) {
                each(listOf(1, 2, 3, 4), key = { it }) { item ->
                    host("li", key = item) {
                        text("item $item", semantics = null)
                    }
                }
            }
        }
    }
}

private val BrowserTemplateDefinition = TemplateDefinition(
    id = "browser-template-test",
    skeleton = HostNode(
        tag = "div",
        props = mapOf("data-testid" to "template-row", "class" to ""),
        children = listOf(
            HostNode(
                tag = "button",
                props = mapOf("data-testid" to "template-button"),
                children = listOf(TextNode("", semantics = null)),
            ),
        ),
    ),
    holes = listOf(
        TemplateHole(path = "", kind = TemplateHoleKinds.Prop, propName = "class"),
        TemplateHole(path = "0", kind = TemplateHoleKinds.EventProp, propName = "event:onClick"),
        TemplateHole(path = "0.0", kind = TemplateHoleKinds.Text),
    ),
)

fun ComponentScope.TemplateFastPathApp() {
    var selected by state(key = "selected") { false }
    var label by state(key = "label") { "one" }
    val click = hostEvent(event {
        val next = !selected
        selected = next
        label = if (next) "two" else "one"
    })

    emit(
        templateNode(
            definition = BrowserTemplateDefinition,
            values = listOf(if (selected) "hot" else "cold", click, label),
            key = "template-row",
        ),
    )
}

@OptIn(DelicateCoroutinesApi::class)
fun main() {
    val results = document.querySelector("#results") ?: error("Missing #results")
    val app = mountKineticaApp("#fixture") {
        BrowserTestApp()
    }

    runBrowserTest(results, "snapshot contains test tags") {
        check("data-testid=\"draft\"" in app.innerHtml())
        check("data-testid=\"commit\"" in app.innerHtml())
    }
    runBrowserTest(results, "input and click update DOM") {
        app.inputTestTag("draft", "Browser")
        app.clickTestTag("commit")
        check("Committed: Browser" in app.innerHtml())
    }
    runBrowserTest(results, "tree snapshot is available") {
        check("\"Committed: Browser\"" in app.snapshot().treeJson)
    }
    runBrowserTest(results, "layout host props survive browser render") {
        val column = app.elementByTestTag("layout-column")
        check(column.getAttribute("id") == "layout-column")
        check(column.getAttribute("title") == "Layout column")
        check(column.getAttribute("data-extra") == "column-prop")

        val row = app.elementByTestTag("layout-row")
        check(row.getAttribute("id") == "layout-row")
        check(row.getAttribute("title") == "Layout row")
        check(row.getAttribute("data-extra") == "row-prop")
    }
    runBrowserTest(results, "semantic text renders role and aria label") {
        check("role=\"img\"" in app.innerHtml())
        check("aria-label=\"Status image\"" in app.innerHtml())
    }
    runBrowserTest(results, "focus follows stable identity across reordered keyed inputs") {
        val input = app.elementByTestTag("item-a") as HTMLInputElement
        input.focus()
        app.inputTestTag("item-a", "flip")

        val active = document.activeElement
        check(active?.getAttribute("data-testid") == "item-a") {
            "Expected item-a to keep focus, got ${active?.getAttribute("data-testid")}"
        }
    }
    runBrowserTest(results, "patch preserves DOM element identity across renders") {
        val button = app.elementByTestTag("commit")
        button.asDynamic().__identityMarker = 42
        app.inputTestTag("draft", "identity")
        app.clickTestTag("commit")
        check("Committed: identity" in app.innerHtml())
        val after = app.elementByTestTag("commit")
        check(after.asDynamic().__identityMarker == 42) { "commit button was recreated instead of patched" }
    }
    runBrowserTest(results, "single text fast path keeps DOM text node identity") {
        val root = isolatedRoot()
        val textApp = mountKineticaApp(root) {
            SingleTextFastPathApp()
        }
        try {
            val paragraph = textApp.elementByTestTag("single-text")
            val textNode = paragraph.firstChild ?: error("single text paragraph had no text node")
            textNode.asDynamic().__identityMarker = "single-text"

            textApp.clickTestTag("text-toggle")

            val updatedText = textApp.elementByTestTag("single-text").firstChild
                ?: error("single text paragraph lost its text node")
            check(updatedText.asDynamic().__identityMarker == "single-text") {
                "single text child was recreated instead of patched"
            }
            check(updatedText.textContent == "two")
        } finally {
            textApp.dispose()
            root.remove()
        }
    }
    runBrowserTest(results, "kind-discriminated slots survive branch toggle") {
        // Regression for the silent-JS slot corruption: a branch swapping state{} for derived{}
        // at the same cursor position used to reuse the state cell's slot for the DerivedCell —
        // mangled property reads turn into undefined and handlers after the branch go dead
        // without any error. Kind-discriminated keys give each construct its own slot.
        val root = isolatedRoot()
        val toggleApp = mountKineticaApp(root) {
            SlotKindToggleApp()
        }
        try {
            check("state-arm:1" in toggleApp.innerHtml())
            toggleApp.clickTestTag("kind-toggle")
            check("derived-arm" in toggleApp.innerHtml())
            toggleApp.clickTestTag("kind-count")
            check("clicks:1" in toggleApp.innerHtml()) { "count handler died after branch toggle" }
            toggleApp.clickTestTag("kind-toggle")
            check("state-arm:1" in toggleApp.innerHtml())
            toggleApp.clickTestTag("kind-count")
            check("clicks:2" in toggleApp.innerHtml())
        } finally {
            toggleApp.dispose()
            root.remove()
        }
    }
    runBrowserTest(results, "replace-all keyed children avoids per-child removeChild") {
        val root = isolatedRoot()
        val replaceApp = mountKineticaApp(root) {
            ReplaceAllFastPathApp()
        }
        try {
            val list = replaceApp.elementByTestTag("replace-list")
            val removeCalls = countRemoveChildCalls(list)

            replaceApp.clickTestTag("replace-all")

            check(removeCalls() == 0) { "replace-all called removeChild ${removeCalls()} times" }
            check(list.children.length == 4)
            check(list.textContent == "item 11item 12item 13item 14")
        } finally {
            replaceApp.dispose()
            root.remove()
        }
    }
    runBrowserTest(results, "clear keyed children avoids per-child removeChild") {
        val root = isolatedRoot()
        val clearApp = mountKineticaApp(root) {
            ClearFastPathApp()
        }
        try {
            val list = clearApp.elementByTestTag("clear-list")
            val removeCalls = countRemoveChildCalls(list)

            clearApp.clickTestTag("clear-all")

            check(removeCalls() == 0) { "clear called removeChild ${removeCalls()} times" }
            check(list.childNodes.length == 0)
        } finally {
            clearApp.dispose()
            root.remove()
        }
    }
    runBrowserTest(results, "template node clones and patches text props and events") {
        val root = isolatedRoot()
        val templateApp = mountKineticaApp(root) {
            TemplateFastPathApp()
        }
        try {
            val row = templateApp.elementByTestTag("template-row")
            val button = templateApp.elementByTestTag("template-button")
            button.asDynamic().__identityMarker = "template-button"

            check(row.getAttribute("class") == "cold")
            check(button.textContent == "one")

            button.asDynamic().click()

            val patchedRow = templateApp.elementByTestTag("template-row")
            val patchedButton = templateApp.elementByTestTag("template-button")
            check(patchedRow.getAttribute("class") == "hot")
            check(patchedButton.textContent == "two")
            check(patchedButton.asDynamic().__identityMarker == "template-button") {
                "template button was recreated instead of patched"
            }
        } finally {
            templateApp.dispose()
            root.remove()
        }
    }
    runBrowserTest(results, "keyed reorder moves existing elements instead of recreating") {
        val a = app.elementByTestTag("item-a")
        val b = app.elementByTestTag("item-b")
        a.asDynamic().__identityMarker = "a"
        b.asDynamic().__identityMarker = "b"
        app.inputTestTag("item-a", "toggle-order")

        val movedA = app.elementByTestTag("item-a")
        val movedB = app.elementByTestTag("item-b")
        check(movedA.asDynamic().__identityMarker == "a") { "item-a was recreated instead of moved" }
        check(movedB.asDynamic().__identityMarker == "b") { "item-b was recreated instead of moved" }
        check(movedA.nextElementSibling == movedB) { "expected order a,b after toggling the reversed list" }
    }
    runBrowserAsyncTest(results, "awaitIdle restores focused input after async render") {
        val root = document.createElement("div")
        document.querySelector("main")?.appendChild(root)
        val asyncApp = mountKineticaApp(root) {
            AsyncFocusApp()
        }
        try {
            val input = asyncApp.elementByTestTag("async-input") as HTMLInputElement
            input.focus()
            input.setSelectionRange(2, 2)

            asyncApp.awaitIdle()

            check("Async: ready" in asyncApp.innerHtml())
            val active = document.activeElement as? HTMLInputElement
            check(active?.getAttribute("data-testid") == "async-input") {
                "Expected async-input to keep focus, got ${active?.getAttribute("data-testid")}"
            }
            check(active.selectionStart == 2)
            check(active.selectionEnd == 2)
        } finally {
            asyncApp.dispose()
            root.remove()
        }
    }
    runBrowserAsyncTest(results, "dispose stops shared store invalidations from remounting app") {
        val root = document.createElement("div")
        document.querySelector("main")?.appendChild(root)
        val shared = store("before")
        val disposedApp = mountKineticaApp(root) {
            text("Shared: ${shared.value}")
        }
        try {
            check("Shared: before" in disposedApp.innerHtml())
            disposedApp.dispose()
            shared.value = "after"

            disposedApp.awaitIdle()

            check(root.innerHTML.isEmpty()) {
                "Disposed app remounted after a shared store write: ${root.innerHTML}"
            }
        } finally {
            disposedApp.dispose()
            root.remove()
        }
    }
}

private fun isolatedRoot(): Element =
    document.createElement("div").also { root ->
        document.querySelector("main")?.appendChild(root) ?: error("Missing <main>")
    }

private fun countRemoveChildCalls(element: Element): () -> Int {
    var calls = 0
    val dynamicElement = element.asDynamic()
    val original = dynamicElement.removeChild
    dynamicElement.removeChild = { child: dynamic ->
        calls += 1
        original.call(dynamicElement, child)
    }
    return { calls }
}

private fun runBrowserTest(
    results: Element,
    name: String,
    block: () -> Unit,
) {
    val row = document.createElement("div")
    try {
        block()
        row.textContent = "PASS $name"
        row.setAttribute("data-status", "pass")
    } catch (error: Throwable) {
        row.textContent = "FAIL $name: ${error.message}"
        row.setAttribute("data-status", "fail")
        throw error
    } finally {
        results.appendChild(row)
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun runBrowserAsyncTest(
    results: Element,
    name: String,
    block: suspend () -> Unit,
) {
    val row = document.createElement("div")
    row.textContent = "PENDING $name"
    row.setAttribute("data-status", "pending")
    results.appendChild(row)

    GlobalScope.promise {
        try {
            block()
            row.textContent = "PASS $name"
            row.setAttribute("data-status", "pass")
        } catch (error: Throwable) {
            row.textContent = "FAIL $name: ${error.message}"
            row.setAttribute("data-status", "fail")
            throw error
        }
    }
}

private val document: Document
    get() = js("document").unsafeCast<Document>()
