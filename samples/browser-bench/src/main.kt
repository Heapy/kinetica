package app.browser.bench

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.MutableCell
import io.heapy.kinetica.propsOf
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

// Selection is a per-row boolean cell so selecting a row touches exactly two rows'
// dependencies (the newly and previously selected) and every other row stays memoized.
// The holder is stable state that is only mutated from event handlers, never read during
// render, so flipping the selection does not invalidate the app subtree itself.
private class SelectionHolder {
    var selected: MutableCell<Boolean>? = null

    fun select(cell: MutableCell<Boolean>) {
        selected?.value = false
        cell.value = true
        selected = cell
    }

    fun clear() {
        selected?.value = false
        selected = null
    }
}

fun ComponentScope.BenchApp() {
    var rows by state(key = "rows") { emptyList<RowData>() }
    val selection = state(key = "selection") { SelectionHolder() }.value

    val run = event { rows = buildData(1_000); selection.clear() }
    val runLots = event { rows = buildData(10_000); selection.clear() }
    val add = event { rows = rows + buildData(1_000) }
    val update = event {
        rows = rows.mapIndexed { index, row ->
            if (index % 10 == 0) row.copy(label = row.label + " !!!") else row
        }
    }
    val clear = event { rows = emptyList(); selection.clear() }
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
        host("div", props = propsOf("class", "jumbotron")) {
            host("h1") { text("Kinetica (keyed)", semantics = null) }
            host("div", props = propsOf("class", "toolbar")) {
                toolbarButton("run", "Create 1,000 rows", run)
                toolbarButton("runlots", "Create 10,000 rows", runLots)
                toolbarButton("add", "Append 1,000 rows", add)
                toolbarButton("update", "Update every 10th row", update)
                toolbarButton("clear", "Clear", clear)
                toolbarButton("swaprows", "Swap Rows", swapRows)
            }
        }
        host("table", props = propsOf("class", "test-data")) {
            host("tbody") {
                each(rows, key = { it.id }) { row ->
                    val isSelected = state(key = "selected") { false }
                    val danger = if (isSelected.value) "danger" else ""
                    host("tr", props = propsOf("class", danger, "data-id", row.id.toString()), key = row.id) {
                        host("td", props = propsOf("class", "col-id")) {
                            text(row.id.toString(), semantics = null)
                        }
                        host("td", props = propsOf("class", "col-label")) {
                            button(onClick = event { selection.select(isSelected) }, semantics = null) {
                                text(row.label, semantics = null)
                            }
                        }
                        host("td", props = propsOf("class", "col-remove")) {
                            button(
                                onClick = event { rows = rows.filterNot { it.id == row.id } },
                                semantics = null,
                            ) {
                                host("span", props = propsOf("class", "remove-icon", "aria-hidden", "true"))
                            }
                        }
                        host("td", props = propsOf("class", "col-rest"))
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
