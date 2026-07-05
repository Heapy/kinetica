package app.browser.tests

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.browser.mountKineticaApp
import io.heapy.kinetica.button
import io.heapy.kinetica.column
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.launchEffect
import io.heapy.kinetica.state
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
                    onInput = event<String> { reverseItems = true },
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
