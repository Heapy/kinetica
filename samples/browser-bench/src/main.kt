package app.browser.bench

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.MutableCell
import io.heapy.kinetica.propsOf
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.browser.BrowserKineticaApp
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

private fun requestFrame(callback: () -> Unit): Int =
    js("window.requestAnimationFrame(callback)").unsafeCast<Int>()

private fun cancelFrame(id: Int) {
    js("window.cancelAnimationFrame(id)")
}

// Drives the sustained-update ("animate") loop. Cell writes are legal outside event
// dispatch, but only dispatch triggers a render, so each frame ends with an explicit
// requestRender() from the app's render callback.
private class Animator {
    var running = false
        private set
    private var rafId = 0

    fun toggle(onFrame: () -> Unit) {
        if (running) {
            running = false
            cancelFrame(rafId)
        } else {
            running = true
            loop(onFrame)
        }
    }

    fun stop() {
        running = false
        cancelFrame(rafId)
    }

    private fun loop(onFrame: () -> Unit) {
        rafId = requestFrame {
            if (running) {
                onFrame()
                loop(onFrame)
            }
        }
    }
}

private var animTick = 0

fun ComponentScope.BenchApp(requestRender: () -> Unit = {}) {
    var rows by state(key = "rows") { emptyList<RowData>() }
    val selection = state(key = "selection") { SelectionHolder() }.value
    val animator = state(key = "animator") { Animator() }.value

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
    val animate = event {
        animator.toggle {
            animTick++
            rows = rows.mapIndexed { index, row ->
                if (index % 10 == 0) {
                    row.copy(label = row.label.substringBefore(" !") + " !$animTick")
                } else {
                    row
                }
            }
            requestRender()
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
                toolbarButton("animate", "Animate", animate)
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

// --- tree benchmark app (UIBench-style; served from the same page with ?app=tree) ---
//
// Contract shared with bench/frameworks/*/tree.*: depth 4, fanout 6 => 1555 nodes,
// 1296 leaves. "run" rebuilds the tree with fresh ids, "update" re-labels every 10th
// leaf (preorder) with " !<tick>", "reverse" reverses the root's children, "noop"
// bumps only the status counter so the tree re-renders with unchanged data.

data class TreeNodeData(val id: Int, val label: String, val children: List<TreeNodeData>)

private const val TREE_DEPTH = 4
private const val TREE_FANOUT = 6

private var treeNextId = 1

private fun buildTree(depth: Int = TREE_DEPTH, fanout: Int = TREE_FANOUT): TreeNodeData {
    val id = treeNextId++
    return TreeNodeData(
        id = id,
        label = "node $id",
        children = if (depth == 0) emptyList() else List(fanout) { buildTree(depth - 1, fanout) },
    )
}

private fun updateTreeLeaves(node: TreeNodeData, tick: Int, counter: IntArray): TreeNodeData {
    if (node.children.isEmpty()) {
        val index = counter[0]++
        return if (index % 10 == 0) {
            node.copy(label = node.label.substringBefore(" !") + " !$tick")
        } else {
            node
        }
    }
    return node.copy(children = node.children.map { updateTreeLeaves(it, tick, counter) })
}

private fun ComponentScope.treeNode(node: TreeNodeData, depth: Int) {
    host(
        "div",
        props = propsOf("class", "tree-node", "data-id", node.id.toString(), "data-depth", depth.toString()),
        key = node.id,
    ) {
        if (node.children.isEmpty()) {
            host("span", props = propsOf("class", "tree-leaf")) { text(node.label, semantics = null) }
        } else {
            host("span", props = propsOf("class", "tree-label")) { text(node.label, semantics = null) }
            each(node.children, key = { it.id }) { child -> treeNode(child, depth + 1) }
        }
    }
}

fun ComponentScope.TreeApp() {
    var tree by state(key = "tree") { TreeNodeData(id = 0, label = "empty", children = emptyList()) }
    var tick by state(key = "tick") { 0 }

    val run = event { tree = buildTree() }
    val update = event {
        tick += 1
        tree = updateTreeLeaves(tree, tick, intArrayOf(0))
    }
    val reverse = event { tree = tree.copy(children = tree.children.reversed()) }
    val noop = event { tick += 1 }

    host("div") {
        host("div", props = propsOf("class", "jumbotron")) {
            host("h1") { text("Kinetica tree", semantics = null) }
            host("div", props = propsOf("class", "toolbar")) {
                toolbarButton("run", "Create tree", run)
                toolbarButton("update", "Update leaves", update)
                toolbarButton("reverse", "Reverse", reverse)
                toolbarButton("noop", "No-op render", noop)
                host("span", semantics = Semantics(testTag = "status")) {
                    text(tick.toString(), semantics = null)
                }
            }
        }
        host("div", props = propsOf("class", "tree-root")) {
            if (tree.id != 0) {
                treeNode(tree, depth = 0)
            }
        }
    }
}

fun main() {
    val isTree = js("window.location.search.indexOf('tree') >= 0").unsafeCast<Boolean>()
    var app: BrowserKineticaApp? = null
    fun mountApp() {
        app = mountKineticaApp("#main", runtime = KineticaRuntime(debug = false)) {
            if (isTree) TreeApp() else BenchApp { app?.render() }
        }
    }
    mountApp()
    val window: dynamic = js("window")
    window.__mount = { mountApp() }
    window.__unmount = {
        app?.dispose()
        app = null
    }
}
