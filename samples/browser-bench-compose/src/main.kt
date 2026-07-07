package app.browser.bench.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Tr
import org.jetbrains.compose.web.renderComposable

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

private fun randomIndex(max: Int): Int =
    js("Math.round(Math.random() * 1000) % max").unsafeCast<Int>()

private fun buildData(count: Int): List<RowData> {
    val adjectiveCount = adjectives.size
    val colourCount = colours.size
    val nounCount = nouns.size
    return List(count) {
        RowData(
            id = nextId++,
            label = "${adjectives[randomIndex(adjectiveCount)]} " +
                "${colours[randomIndex(colourCount)]} " +
                nouns[randomIndex(nounCount)],
        )
    }
}

private var animTick = 0

@Composable
private fun ToolbarButton(tag: String, label: String, handleClick: () -> Unit) {
    Button(attrs = {
        id(tag)
        onClick { handleClick() }
    }) {
        Text(label)
    }
}

// Its own composable function (not inlined into the table's for-loop) with only stable
// parameters (Int/String/Boolean + remember-stable callbacks) so Compose can skip
// recomposing rows whose id/label/selected haven't changed — the same reason the React
// and Preact implementations wrap their row in memo() with useCallback handlers.
// selected is computed in the caller's loop (not deferred/hoisted as State<Int> read here)
// on purpose: measured on this benchmark, hoisting it made select1k/select10k 3x/27x
// SLOWER (individually invalidating N keyed-list child scopes hits the same pathological
// per-key-lookup cost that swap/remove show at this list size) — see main.kt's own note
// on RowItem's caller and bench-harness-findings memory.
@Composable
private fun RowItem(id: Int, label: String, selected: Boolean, onSelect: (Int) -> Unit, onRemove: (Int) -> Unit) {
    Tr(attrs = {
        if (selected) classes("danger")
        attr("data-id", id.toString())
    }) {
        Td(attrs = { classes("col-id") }) { Text(id.toString()) }
        Td(attrs = { classes("col-label") }) {
            A(attrs = {
                classes("lbl")
                onClick { onSelect(id) }
            }) { Text(label) }
        }
        Td(attrs = { classes("col-remove") }) {
            A(attrs = {
                classes("remove")
                onClick { onRemove(id) }
            }) {
                Span(attrs = {
                    classes("remove-icon")
                    attr("aria-hidden", "true")
                }) {}
            }
        }
        Td(attrs = { classes("col-rest") }) {}
    }
}

@Composable
fun BenchApp() {
    // referentialEqualityPolicy: rows is always wholesale-replaced (never mutated in place),
    // so the default structural equals — an O(n) element-wise List compare on every write,
    // including every animate-loop frame — is pure waste; we already know it changed.
    var rows by remember { mutableStateOf(emptyList<RowData>(), referentialEqualityPolicy()) }
    var selectedId by remember { mutableStateOf(0) }
    var animating by remember { mutableStateOf(false) }
    // remember (no keys) creates each callback exactly once so RowItem's parameters stay
    // referentially stable across recompositions — required for the skip above to trigger.
    val onSelect = remember { { id: Int -> selectedId = id } }
    val onRemove = remember { { id: Int -> rows = rows.filterNot { it.id == id } } }
    // Toolbar handlers are remembered too, for the same reason as onSelect/onRemove above —
    // otherwise they're fresh closures on every BenchApp recomposition.
    val onCreate1k = remember { { rows = buildData(1_000); selectedId = 0 } }
    val onCreate10k = remember { { rows = buildData(10_000); selectedId = 0 } }
    val onAppend1k = remember { { rows = rows + buildData(1_000) } }
    val onUpdateEvery10th = remember {
        {
            rows = rows.mapIndexed { index, row ->
                if (index % 10 == 0) row.copy(label = row.label + " !!!") else row
            }
        }
    }
    val onClear = remember { { rows = emptyList(); selectedId = 0 } }
    val onSwapRows = remember {
        {
            if (rows.size > 998) {
                val next = rows.toMutableList()
                val tmp = next[1]
                next[1] = next[998]
                next[998] = tmp
                rows = next
            }
        }
    }
    val onToggleAnimate = remember { { animating = !animating } }

    // Drives the sustained-update ("animate") loop via a plain rAF chain — Compose's
    // snapshot system schedules recomposition on any state write, from any callback,
    // so no explicit "request render" call is needed (unlike Kinetica's event-only model).
    DisposableEffect(animating) {
        if (!animating) return@DisposableEffect onDispose {}
        var rafId = 0
        fun frame(timestamp: Double) {
            animTick++
            rows = rows.mapIndexed { index, row ->
                if (index % 10 == 0) {
                    row.copy(label = row.label.substringBefore(" !") + " !$animTick")
                } else {
                    row
                }
            }
            rafId = window.requestAnimationFrame(::frame)
        }
        rafId = window.requestAnimationFrame(::frame)
        onDispose { window.cancelAnimationFrame(rafId) }
    }

    Div {
        Div(attrs = { classes("jumbotron") }) {
            H1 { Text("Compose HTML (keyed)") }
            Div(attrs = { classes("toolbar") }) {
                ToolbarButton("run", "Create 1,000 rows", onCreate1k)
                ToolbarButton("runlots", "Create 10,000 rows", onCreate10k)
                ToolbarButton("add", "Append 1,000 rows", onAppend1k)
                ToolbarButton("update", "Update every 10th row", onUpdateEvery10th)
                ToolbarButton("clear", "Clear", onClear)
                ToolbarButton("swaprows", "Swap Rows", onSwapRows)
                ToolbarButton("animate", "Animate", onToggleAnimate)
            }
        }
        Table(attrs = { classes("test-data") }) {
            Tbody {
                for (row in rows) {
                    key(row.id) {
                        RowItem(row.id, row.label, row.id == selectedId, onSelect, onRemove)
                    }
                }
            }
        }
    }
}

// --- tree benchmark app (UIBench-style; served from the same page with ?app=tree) ---
//
// Contract shared with bench/frameworks/*/tree.* and samples/browser-bench: depth 4,
// fanout 6 => 1555 nodes, 1296 leaves. "run" rebuilds the tree with fresh ids, "update"
// re-labels every 10th leaf (preorder) with " !<tick>", "reverse" reverses the root's
// children, "noop" bumps only the status counter so the tree re-renders with unchanged data.

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

@Composable
private fun TreeNode(node: TreeNodeData, depth: Int) {
    Div(attrs = {
        classes("tree-node")
        attr("data-id", node.id.toString())
        attr("data-depth", depth.toString())
    }) {
        if (node.children.isEmpty()) {
            Span(attrs = { classes("tree-leaf") }) { Text(node.label) }
        } else {
            Span(attrs = { classes("tree-label") }) { Text(node.label) }
            for (child in node.children) {
                key(child.id) { TreeNode(child, depth + 1) }
            }
        }
    }
}

@Composable
fun TreeApp() {
    // Same referentialEqualityPolicy reasoning as BenchApp.rows above — tree is always
    // replaced wholesale, never mutated in place.
    var tree by remember { mutableStateOf<TreeNodeData?>(null, referentialEqualityPolicy()) }
    var tick by remember { mutableStateOf(0) }

    Div {
        Div(attrs = { classes("jumbotron") }) {
            H1 { Text("Compose HTML tree") }
            Div(attrs = { classes("toolbar") }) {
                ToolbarButton("run", "Create tree") { tree = buildTree() }
                ToolbarButton("update", "Update leaves") {
                    tick += 1
                    tree?.let { tree = updateTreeLeaves(it, tick, intArrayOf(0)) }
                }
                ToolbarButton("reverse", "Reverse") {
                    tree?.let { current -> tree = current.copy(children = current.children.reversed()) }
                }
                ToolbarButton("noop", "No-op render") { tick += 1 }
                Span(attrs = { id("status") }) { Text(tick.toString()) }
            }
        }
        Div(attrs = { classes("tree-root") }) {
            tree?.let { TreeNode(it, depth = 0) }
        }
    }
}

fun main() {
    val isTree = window.location.search.contains("tree")
    var composition: Composition? = null
    fun mountApp() {
        composition = renderComposable(rootElementId = "main") {
            if (isTree) TreeApp() else BenchApp()
        }
    }
    mountApp()
    window.asDynamic().__mount = { mountApp() }
    window.asDynamic().__unmount = {
        composition?.dispose()
        composition = null
    }
}
