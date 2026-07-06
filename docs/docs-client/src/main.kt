package docs.client

import io.heapy.kinetica.CacheScope
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.ResourceKey
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.action
import io.heapy.kinetica.browser.BrowserKineticaApp
import io.heapy.kinetica.browser.mountKineticaApp
import io.heapy.kinetica.button
import io.heapy.kinetica.derived
import io.heapy.kinetica.each
import io.heapy.kinetica.errorBoundary
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.loadingBoundary
import io.heapy.kinetica.resource
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import io.heapy.kinetica.watch
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.events.Event
import kotlin.js.Promise

/** Mounts the live examples embedded in documentation pages via `::: example <name>`. */
fun main() {
    val slots = document.querySelectorAll("[data-example]")
    for (index in 0 until slots.length) {
        val container = slots.item(index) as? Element ?: continue
        val name = container.getAttribute("data-example") ?: continue
        val slot = container.querySelector(".live-example-slot") ?: continue
        slot.textContent = ""
        val app = mountKineticaApp(slot, KineticaRuntime(debug = false)) {
            when (name) {
                "counter" -> CounterExample()
                "keyed-list" -> KeyedListExample()
                "input-mirror" -> InputMirrorExample()
                "resource-fetch" -> ResourceFetchExample()
                else -> text("Unknown example: $name")
            }
        }
        keepAsyncWorkRendered(container, app)
    }
}

/**
 * The browser renderer re-renders synchronously on dispatched DOM events; async completions
 * (resource loads, watch effects) only flag the runtime as invalidated. Drain them through
 * [BrowserKineticaApp.awaitIdle] after mount and after every interaction inside the example so
 * examples that fetch data make it back onto the screen.
 */
private fun keepAsyncWorkRendered(container: Element, app: BrowserKineticaApp) {
    val scope = MainScope()
    var pumping = false
    fun pump() {
        if (pumping) return
        pumping = true
        scope.launch {
            try {
                app.awaitIdle()
            } finally {
                pumping = false
            }
        }
    }
    listOf("click", "input", "keydown", "change").forEach { type ->
        container.addEventListener(type, { _: Event -> pump() })
    }
    pump()
}

private fun ComponentScope.CounterExample() {
    var count by state(key = "count") { 0 }
    val label by derived { if (count == 1) "1 click" else "$count clicks" }

    host("div", props = mapOf("class" to "ex")) {
        host("p", props = mapOf("class" to "ex-value")) {
            text(label, semantics = null)
        }
        host("div", props = mapOf("class" to "ex-row")) {
            button(onClick = event { count += 1 }, semantics = Semantics(role = Role.Button, testTag = "ex-increment")) {
                text("Increment", semantics = null)
            }
            button(onClick = event { count = 0 }, enabled = count != 0) {
                text("Reset", semantics = null)
            }
        }
    }
}

private data class ExampleRow(val id: Int, val label: String)

private fun ComponentScope.KeyedListExample() {
    var nextId by state(key = "nextId") { 4 }
    var rows by state(key = "rows") {
        listOf(ExampleRow(1, "alpha"), ExampleRow(2, "beta"), ExampleRow(3, "gamma"))
    }
    var selected by state(key = "selected") { 0 }

    host("div", props = mapOf("class" to "ex")) {
        host("div", props = mapOf("class" to "ex-row")) {
            button(onClick = event {
                rows = rows + ExampleRow(nextId, "row $nextId")
                nextId += 1
            }) {
                text("Add", semantics = null)
            }
            button(onClick = event { rows = rows.reversed() }, enabled = rows.size > 1) {
                text("Reverse", semantics = null)
            }
            button(onClick = event { rows = emptyList(); selected = 0 }, enabled = rows.isNotEmpty()) {
                text("Clear", semantics = null)
            }
        }
        host("ul", props = mapOf("class" to "ex-list")) {
            each(rows, key = { it.id }) { row ->
                val cls = if (row.id == selected) "ex-item selected" else "ex-item"
                host("li", props = mapOf("class" to cls), key = row.id) {
                    button(onClick = event { selected = row.id }, semantics = null) {
                        text(row.label, semantics = null)
                    }
                    button(onClick = event { rows = rows.filterNot { it.id == row.id } }, semantics = null) {
                        text("✕", semantics = null)
                    }
                }
            }
        }
        host("p", props = mapOf("class" to "ex-note")) {
            text(
                "Rows are keyed by id: reversing moves the same DOM elements instead of recreating them.",
                semantics = null,
            )
        }
    }
}

private fun ComponentScope.InputMirrorExample() {
    var draft by state(key = "draft") { "" }
    val preview by derived { if (draft.isBlank()) "…" else draft.uppercase() }

    host("div", props = mapOf("class" to "ex")) {
        textInput(
            value = draft,
            onInput = event<String> { draft = it },
            placeholder = "Type something",
            semantics = Semantics(role = Role.TextInput, testTag = "ex-input", focusable = true),
        )
        host("p", props = mapOf("class" to "ex-value")) {
            text(preview, semantics = null)
        }
    }
}

private data object TeamStackKey : ResourceKey

private data class StackSubmission(val tick: Int, val language: String)

private fun ComponentScope.ResourceFetchExample() {
    val stack = resource(TeamStackKey, scope = CacheScope.Component) { _ ->
        delay(300) // artificial latency so the loading fallback is visible
        fetchStack()
    }
    val addLanguage = action(invalidates = { _: String -> listOf(TeamStackKey) }) { language: String ->
        submitLanguage(language)
    }
    var draft by state(key = "draft") { "" }
    var submission by state(key = "submission") { StackSubmission(0, "") }

    host("div", props = mapOf("class" to "ex")) {
        errorBoundary(
            fallback = { error, _, retry ->
                host("p", props = mapOf("class" to "ex-error")) {
                    text(error.message ?: "Unknown failure", semantics = null)
                }
                host("div", props = mapOf("class" to "ex-row")) {
                    button(
                        onClick = event {
                            draft = submission.language
                            submission = StackSubmission(0, "")
                            stack.invalidate()
                            retry.retry()
                        },
                        semantics = Semantics(role = Role.Button, testTag = "stack-retry"),
                    ) {
                        text("Try again", semantics = null)
                    }
                }
            },
        ) {
            watch(source = { submission }) { current ->
                if (current.tick > 0) {
                    addLanguage(current.language)
                }
            }
            loadingBoundary(
                fallback = {
                    host("p", props = mapOf("class" to "ex-note")) {
                        text("Loading your stack from the docs server…", semantics = null)
                    }
                },
            ) {
                val languages = stack.read()
                host("ul", props = mapOf("class" to "ex-list")) {
                    each(languages, key = { it }) { language ->
                        host("li", props = mapOf("class" to "ex-item"), key = language) {
                            text(language, semantics = null)
                        }
                    }
                }
                host("div", props = mapOf("class" to "ex-row")) {
                    textInput(
                        value = draft,
                        onInput = event<String> { draft = it },
                        placeholder = "Add a language, e.g. Rust",
                        semantics = Semantics(role = Role.TextInput, testTag = "stack-input", focusable = true),
                    )
                    button(
                        onClick = event {
                            val language = draft.trim()
                            if (language.isNotEmpty()) {
                                draft = ""
                                submission = StackSubmission(submission.tick + 1, language)
                            }
                        },
                        enabled = draft.isNotBlank(),
                        semantics = Semantics(role = Role.Button, testTag = "stack-add"),
                    ) {
                        text("Add", semantics = null)
                    }
                }
            }
        }
    }
}

private suspend fun fetchStack(): List<String> =
    Json.parseToJsonElement(stackRequest(method = "GET", body = null).await())
        .jsonObject
        .getValue("languages")
        .jsonArray
        .map { element -> element.jsonPrimitive.content }

private suspend fun submitLanguage(language: String) {
    stackRequest(
        method = "POST",
        body = JsonObject(mapOf("language" to JsonPrimitive(language))).toString(),
    ).await()
}

private fun stackRequest(method: String, body: String?): Promise<String> {
    val init = js("{}")
    init.method = method
    init.credentials = "same-origin"
    if (body != null) {
        val headers = js("{}")
        headers["Content-Type"] = "application/json"
        init.headers = headers
        init.body = body
    }
    val url = "/demo/api/stack"
    return js(
        "fetch(url, init).then((response) => response.text().then((text) => {" +
            "if (!response.ok) throw new Error(text);" +
            "return text;" +
            "}))",
    ).unsafeCast<Promise<String>>()
}

private val document: Document
    get() = js("document").unsafeCast<Document>()
