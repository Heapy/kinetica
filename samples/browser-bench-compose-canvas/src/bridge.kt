@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package app.browser.bench.compose.canvas

// The driver bridge. A canvas app renders no DOM, so bench/driver/canvas-harness.mjs cannot
// assert on `tbody tr` the way the DOM frameworks are measured; it reads `window.__bench`
// instead. Two rules keep that honest:
//
// 1. `frame` is published from the DRAW phase (see BenchRoot), with every asserted value read
//    inside the draw scope. That is what subscribes the root's draw to those values, so a
//    published snapshot always describes a frame Compose has actually drawn. Publishing from
//    composition or from the model would let the driver's wait resolve before anything is
//    painted, closing the measured window early and flattering this app.
// 2. Geometry is published once per layout, not per row: rows are fixed-height, so the driver
//    derives a row's click point arithmetically. Attaching a per-row position callback to
//    10,000 rows would be measured work no other framework in the suite performs.

private fun publishFrame(
    rowCount: Int,
    selectedIndex: Int,
    firstLabel: String,
    id1: Int,
    id2: Int,
    id5: Int,
    id999: Int,
) {
    js(
        """
        window.__bench = window.__bench || {};
        window.__bench.frame = {
            rowCount: rowCount,
            selectedIndex: selectedIndex,
            firstLabel: firstLabel,
            ids: { 1: id1, 2: id2, 5: id5, 999: id999 },
        };
        window.__bench.frames = (window.__bench.frames || 0) + 1;
        """
    )
}

private fun publishRect(name: String, x: Double, y: Double, width: Double, height: Double) {
    js(
        """
        window.__bench = window.__bench || {};
        window.__bench.rects = window.__bench.rects || {};
        window.__bench.rects[name] = { x: x, y: y, width: width, height: height };
        """
    )
}

private fun publishRowGeometry(top: Double, height: Double, labelX: Double, removeX: Double) {
    js(
        """
        window.__bench = window.__bench || {};
        window.__bench.rowGeometry = { top: top, height: height, labelX: labelX, removeX: removeX };
        """
    )
}

private fun publishMountMs() {
    js("if (window.__mountMs === undefined) window.__mountMs = performance.now();")
}

/** Mirrors what the driver needs to know about a drawn frame. */
internal object Bridge {
    fun frame(
        rowCount: Int,
        selectedIndex: Int,
        firstLabel: String,
        id1: Int,
        id2: Int,
        id5: Int,
        id999: Int,
    ) = publishFrame(rowCount, selectedIndex, firstLabel, id1, id2, id5, id999)

    fun rect(name: String, x: Float, y: Float, width: Float, height: Float) =
        publishRect(name, x.toDouble(), y.toDouble(), width.toDouble(), height.toDouble())

    fun rowGeometry(top: Float, height: Float, labelX: Float, removeX: Float) =
        publishRowGeometry(top.toDouble(), height.toDouble(), labelX.toDouble(), removeX.toDouble())

    fun mounted() = publishMountMs()
}
