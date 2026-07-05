package app.browser.bench

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.browser.mountKineticaApp
import io.heapy.kinetica.button
import io.heapy.kinetica.each
import io.heapy.kinetica.event
import io.heapy.kinetica.host
import io.heapy.kinetica.state
import io.heapy.kinetica.text
import kotlin.random.Random

data class RowData(val id: Int, val label: String)

private val adjectives = listOf(
    "pretty", "large", "big", "small", "tall", "short", "long", "handsome", "plain",
    "quaint", "clean", "elegant", "easy", "angry", "crazy", "helpful", "mushy", "odd",
    "unsightly", "adorable", "important", "inexpensive", "cheap", "expensive", "fancy",
)
private val colours = listOf(
    "red", "yellow", "blue", "green", "pink", "brown", "purple", "brown", "white", "black", "orange",
)
private val nouns = listOf(
    "table", "chair", "house", "bbq", "desk", "car", "pony", "cookie", "sandwich", "burger",
    "pizza", "mouse", "keyboard",
)

private var nextId = 1

private fun buildData(count: Int): List<RowData> = List(count) {
    RowData(
        id = nextId++,
        label = "${adjectives[Random.nextInt(adjectives.size)]} " +
            "${colours[Random.nextInt(colours.size)]} " +
            nouns[Random.nextInt(nouns.size)],
    )
}

private fun ComponentScope.toolbarButton(tag: String, label: String, onClick: () -> Unit) {
    button(
        onClick = onClick,
        semantics = Semantics(role = Role.Button, testTag = tag, focusable = true),
    ) {
        text(label, semantics = null)
    }
}

fun ComponentScope.BenchApp() {
    var rows by state(key = "rows") { emptyList<RowData>() }
    var selected by state(key = "selected") { 0 }

    val run = event { rows = buildData(1_000); selected = 0 }
    val runLots = event { rows = buildData(10_000); selected = 0 }
    val add = event { rows = rows + buildData(1_000) }
    val update = event {
        rows = rows.mapIndexed { index, row ->
            if (index % 10 == 0) row.copy(label = row.label + " !!!") else row
        }
    }
    val clear = event { rows = emptyList(); selected = 0 }
    val swapRows = event {
        if (rows.size > 998) {
            val next = rows.toMutableList()
            val tmp = next[1]
            next[1] = next[998]
            next[998] = tmp
            rows = next
        }
    }

    host("div") {
        host("div", props = mapOf("class" to "jumbotron")) {
            host("h1") { text("Kinetica (keyed)", semantics = null) }
            host("div", props = mapOf("class" to "toolbar")) {
                toolbarButton("run", "Create 1,000 rows", run)
                toolbarButton("runlots", "Create 10,000 rows", runLots)
                toolbarButton("add", "Append 1,000 rows", add)
                toolbarButton("update", "Update every 10th row", update)
                toolbarButton("clear", "Clear", clear)
                toolbarButton("swaprows", "Swap Rows", swapRows)
            }
        }
        host("table", props = mapOf("class" to "test-data")) {
            host("tbody") {
                each(rows, key = { it.id }) { row ->
                    val danger = if (row.id == selected) "danger" else ""
                    host("tr", props = mapOf("class" to danger, "data-id" to row.id.toString()), key = row.id) {
                        host("td", props = mapOf("class" to "col-id")) {
                            text(row.id.toString(), semantics = null)
                        }
                        host("td", props = mapOf("class" to "col-label")) {
                            button(onClick = event { selected = row.id }, semantics = null) {
                                text(row.label, semantics = null)
                            }
                        }
                        host("td", props = mapOf("class" to "col-remove")) {
                            button(
                                onClick = event { rows = rows.filterNot { it.id == row.id } },
                                semantics = null,
                            ) {
                                host("span", props = mapOf("class" to "remove-icon", "aria-hidden" to "true"))
                            }
                        }
                        host("td", props = mapOf("class" to "col-rest"))
                    }
                }
            }
        }
    }
}

fun main() {
    mountKineticaApp("#main", runtime = KineticaRuntime(debug = false)) {
        BenchApp()
    }
}
