package docs.client

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.browser.mountKineticaApp
import io.heapy.kinetica.button
import io.heapy.kinetica.derived
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import io.heapy.kinetica.textInput
import org.w3c.dom.Document
import org.w3c.dom.Element

/** Mounts the live examples embedded in documentation pages via `::: example <name>`. */
fun main() {
    val slots = document.querySelectorAll("[data-example]")
    for (index in 0 until slots.length) {
        val container = slots.item(index) as? Element ?: continue
        val name = container.getAttribute("data-example") ?: continue
        val slot = container.querySelector(".live-example-slot") ?: continue
        slot.textContent = ""
        mountKineticaApp(slot, KineticaRuntime(debug = false)) {
            when (name) {
                "counter" -> CounterExample()
                "keyed-list" -> KeyedListExample()
                "input-mirror" -> InputMirrorExample()
                else -> text("Unknown example: $name")
            }
        }
    }
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

private val document: Document
    get() = js("document").unsafeCast<Document>()
