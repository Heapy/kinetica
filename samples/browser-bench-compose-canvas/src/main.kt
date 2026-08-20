package app.browser.bench.compose.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window

// Same keyed-table contract as bench/frameworks/* and samples/browser-bench-compose, rendered
// by Compose Multiplatform onto a canvas instead of into the DOM. The operations, the data
// generator and the animate loop are identical; only the output medium differs, which is the
// whole point of the comparison. Two list variants share this file:
//   ?list=column  Column inside verticalScroll — every row is composed and laid out, the
//                 variant comparable to a DOM framework materialising 10,000 nodes.
//   ?list=lazy    LazyColumn — only the visible window is composed, how Compose is written
//                 in practice. Faster for reasons that have nothing to do with render speed.

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

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun randomIndex(max: Int): Int = js("Math.round(Math.random() * 1000) % max")

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

// Calibration hook (?spin=<ms>), driven by bench/calibrate.mjs: burn a known number of
// milliseconds inside the click handler before making one minimal visible change, so a
// measurement path can be checked against a quantity known in advance instead of only against
// other measurements. Off unless asked for.
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun nowMs(): Double = js("performance.now()")

private fun spin(ms: Int) {
    if (ms <= 0) return
    val end = nowMs() + ms
    while (nowMs() < end) {
        // busy-wait: the point is to occupy the main thread for a known duration
    }
}

// Fixed column widths (the contract constrains cells, not their size): with them the driver's
// click point for row N is pure arithmetic off one published origin, so the app needs no
// per-row position callback.
private val ROW_HEIGHT = 24.dp
private val ID_COLUMN_WIDTH = 90.dp
private val LABEL_COLUMN_WIDTH = 400.dp
private val REMOVE_COLUMN_WIDTH = 48.dp
private val CELL_PADDING = 8.dp

private val rowTextStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF212529))
private val selectedRowColor = Color(0xFFD9534F)
private val toolbarButtonColor = Color(0xFF337AB7)

// indication = null: the app contract bans hover/press visuals, because an extra repaint on
// press lands inside the paint-anchored measurement window.
@Composable
private fun Modifier.benchClickable(onClick: () -> Unit): Modifier =
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )

// Compose lays out in device pixels (its density is the page's devicePixelRatio), while the
// driver clicks in CSS pixels. Everything published as geometry is divided by density.
@Composable
private fun ToolbarButton(tag: String, label: String, handleClick: () -> Unit) {
    val density = LocalDensity.current.density
    Box(
        Modifier
            .padding(4.dp)
            .background(toolbarButtonColor)
            .benchClickable(handleClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                Bridge.rect(
                    tag,
                    bounds.left / density,
                    bounds.top / density,
                    bounds.width / density,
                    bounds.height / density,
                )
            },
    ) {
        BasicText(label, style = TextStyle(fontSize = 13.sp, color = Color.White))
    }
}

// A separate composable with only stable parameters, for the same reason the Compose HTML and
// React implementations wrap their row: Compose can then skip rows whose id/label/selected did
// not change. `selected` is passed as a plain Boolean computed by the caller, not read from
// state here — deferring it individually invalidates every keyed child scope, which measured
// dramatically worse in the Compose HTML app.
@Composable
private fun RowItem(id: Int, label: String, selected: Boolean, onSelect: (Int) -> Unit, onRemove: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) selectedRowColor else Color.White),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(Modifier.width(ID_COLUMN_WIDTH)) {
            BasicText(id.toString(), style = rowTextStyle, maxLines = 1)
        }
        Cell(Modifier.width(LABEL_COLUMN_WIDTH).benchClickable { onSelect(id) }) {
            BasicText(label, style = rowTextStyle, maxLines = 1)
        }
        Cell(Modifier.width(REMOVE_COLUMN_WIDTH).benchClickable { onRemove(id) }) {
            Box(Modifier.width(10.dp).height(10.dp).background(Color(0xFF999999)))
        }
        Cell(Modifier.weight(1f)) {}
    }
}

@Composable
private fun Cell(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.height(ROW_HEIGHT).padding(horizontal = CELL_PADDING),
        contentAlignment = Alignment.CenterStart,
    ) {
        content()
    }
}

@Composable
fun BenchApp(lazyList: Boolean, spinMs: Int = 0) {
    // referentialEqualityPolicy: rows is always replaced wholesale, so the default structural
    // equals would run an O(n) element-wise compare on every write — including every frame of
    // the animate loop — to confirm what we already know.
    var rows by remember { mutableStateOf(emptyList<RowData>(), referentialEqualityPolicy()) }
    var selectedId by remember { mutableStateOf(0) }
    var animating by remember { mutableStateOf(false) }
    val density = LocalDensity.current.density

    val onSelect = remember { { id: Int -> selectedId = id } }
    val onRemove = remember { { id: Int -> rows = rows.filterNot { it.id == id } } }
    val onCreate1k = remember { { rows = buildData(1_000); selectedId = 0 } }
    val onCreate10k = remember { { rows = buildData(10_000); selectedId = 0 } }
    val onAppend1k = remember { { rows = rows + buildData(1_000) } }
    val onUpdateEvery10th = remember(spinMs) {
        {
            if (spinMs > 0) {
                // calibration mode: spin, then touch a single row
                spin(spinMs)
                rows = rows.mapIndexed { index, row ->
                    if (index == 0) row.copy(label = row.label + " !!!") else row
                }
            } else {
                rows = rows.mapIndexed { index, row ->
                    if (index % 10 == 0) row.copy(label = row.label + " !!!") else row
                }
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

    Box(
        Modifier.fillMaxSize().background(Color.White).drawWithContent {
            drawContent()
            // Reads below happen inside the draw scope on purpose: that is what subscribes this
            // draw to `rows`/`selectedId`, so the bridge only ever reports a frame Compose has
            // drawn (see bridge.kt). The indexOfFirst scan is guarded on a selection existing,
            // so the animate loop — which never has one — stays O(1) per frame.
            val current = rows
            val selected = selectedId
            Bridge.frame(
                rowCount = current.size,
                selectedIndex = if (selected == 0) -1 else current.indexOfFirst { it.id == selected } + 1,
                firstLabel = current.firstOrNull()?.label ?: "",
                id1 = current.getOrNull(0)?.id ?: -1,
                id2 = current.getOrNull(1)?.id ?: -1,
                id5 = current.getOrNull(4)?.id ?: -1,
                id999 = current.getOrNull(998)?.id ?: -1,
            )
            Bridge.mounted()
        },
    ) {
        Column {
            Row {
                ToolbarButton("run", "Create 1,000 rows", onCreate1k)
                ToolbarButton("runlots", "Create 10,000 rows", onCreate10k)
                ToolbarButton("add", "Append 1,000 rows", onAppend1k)
                ToolbarButton("update", "Update every 10th row", onUpdateEvery10th)
                ToolbarButton("clear", "Clear", onClear)
                ToolbarButton("swaprows", "Swap Rows", onSwapRows)
                ToolbarButton("animate", "Animate", onToggleAnimate)
            }
            Box(
                Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                    // Published once per layout instead of per row: a position callback on
                    // 10,000 rows would be measured work no other framework in the suite does.
                    // Rows are fixed-height, so the driver derives row N's click point from this.
                    val bounds = coordinates.boundsInWindow()
                    val left = bounds.left / density
                    Bridge.rowGeometry(
                        top = bounds.top / density,
                        height = ROW_HEIGHT.value,
                        labelX = left + ID_COLUMN_WIDTH.value + LABEL_COLUMN_WIDTH.value / 2f,
                        removeX = left + ID_COLUMN_WIDTH.value + LABEL_COLUMN_WIDTH.value +
                            REMOVE_COLUMN_WIDTH.value / 2f,
                    )
                },
            ) {
                if (lazyList) {
                    LazyColumn {
                        items(rows, key = { it.id }) { row ->
                            RowItem(row.id, row.label, row.id == selectedId, onSelect, onRemove)
                        }
                    }
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        for (row in rows) {
                            key(row.id) {
                                RowItem(row.id, row.label, row.id == selectedId, onSelect, onRemove)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val lazyList = window.location.search.contains("lazy")
    val spinMs = Regex("spin=(\\d+)").find(window.location.search)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    ComposeViewport(document.body!!) {
        BenchApp(lazyList, spinMs)
    }
}
