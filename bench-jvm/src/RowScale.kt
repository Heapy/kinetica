package bench.jvm

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.each
import io.heapy.kinetica.host

@UiComponent
private fun ComponentScope.keyedRowsOnly(rows: List<Row>) {
    host("tbody") {
        each(rows, key = { it.id }) { row ->
            host("tr", key = row.id)
        }
    }
}

@UiComponent
private fun ComponentScope.keyedRowsOnlyNoMemo(rows: List<Row>) {
    host("tbody") {
        each(rows, key = { it.id }, memoize = false) { row ->
            host("tr", key = row.id)
        }
    }
}

private fun renderedRowCount(node: Node): Int =
    (node as? HostNode)?.children?.size ?: error("Expected row profile root to be a HostNode, got $node")

internal fun parseRowCount(value: String): Int {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty()) { "row size cannot be empty" }
    val multiplier = when (trimmed.last().lowercaseChar()) {
        'k' -> 1_000
        'm' -> 1_000_000
        else -> 1
    }
    val digits = if (multiplier == 1) trimmed else trimmed.dropLast(1)
    val amount = digits.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
    require(amount != null && amount in 1..(Int.MAX_VALUE / multiplier).toLong()) {
        "row size must be a positive Int count: $value"
    }
    return amount.toInt() * multiplier
}

private fun formatRowCount(count: Int): String =
    if (count % 1_000 == 0) "${count / 1_000}k" else count.toString()

internal fun rowScaleBenchmarks(sizes: List<Int>): List<Benchmark> =
    sizes.flatMap { count ->
        val suffix = formatRowCount(count)
        var rows: List<Row> = emptyList()
        var runtime: KineticaRuntime? = null
        var scope: ComponentScope? = null
        var tick = 0

        fun renderRows(): Int {
            val rt = runtime ?: error("row profile runtime was not initialized")
            val sc = scope ?: error("row profile scope was not initialized")
            return renderedRowCount(rt.render(sc) { keyedRowsOnly(rows) }.tree)
        }

        fun renderRowsNoMemo(): Int {
            val rt = runtime ?: error("row profile runtime was not initialized")
            val sc = scope ?: error("row profile scope was not initialized")
            return renderedRowCount(rt.render(sc) { keyedRowsOnlyNoMemo(rows) }.tree)
        }

        fun setupRows(memoize: Boolean = true) {
            rows = buildRows(count)
            runtime = KineticaRuntime(debug = false)
            scope = ComponentScope(runtime!!)
            tick = 0
            val rendered = if (memoize) renderRows() else renderRowsNoMemo()
            check(rendered == count) { "row profile setup expected $count rows, rendered $rendered" }
        }

        fun teardownRows() {
            scope?.dispose()
            runtime?.dispose()
            rows = emptyList()
            scope = null
            runtime = null
            Blackhole.sink = null
        }

        listOf(
            Benchmark(
                id = "row_memo_hit_$suffix",
                label = "re-render unchanged keyed each over $suffix rows (all rows memoized)",
                setup = { setupRows() },
                op = {
                    val rendered = renderRows()
                    check(rendered == count) { "row memo hit expected $count rows, rendered $rendered" }
                    rendered
                },
                teardown = ::teardownRows,
            ),
            Benchmark(
                id = "row_update10th_$suffix",
                label = "update every 10th keyed row across $suffix rows (90% memoized)",
                setup = { setupRows() },
                op = {
                    tick += 1
                    rows = rows.mapIndexed { index, row ->
                        if (index % 10 == 0) {
                            row.copy(label = row.label.substringBefore(" !") + " !$tick")
                        } else {
                            row
                        }
                    }
                    val rendered = renderRows()
                    check(rendered == count) { "row update expected $count rows, rendered $rendered" }
                    rendered
                },
                teardown = ::teardownRows,
            ),
            Benchmark(
                id = "row_no_memo_hit_$suffix",
                label = "re-render unchanged keyed each over $suffix rows (memoize=false)",
                setup = { setupRows(memoize = false) },
                op = {
                    val rendered = renderRowsNoMemo()
                    check(rendered == count) { "row no-memo hit expected $count rows, rendered $rendered" }
                    rendered
                },
                teardown = ::teardownRows,
            ),
            Benchmark(
                id = "row_no_memo_update10th_$suffix",
                label = "update every 10th keyed row across $suffix rows (memoize=false)",
                setup = { setupRows(memoize = false) },
                op = {
                    tick += 1
                    rows = rows.mapIndexed { index, row ->
                        if (index % 10 == 0) {
                            row.copy(label = row.label.substringBefore(" !") + " !$tick")
                        } else {
                            row
                        }
                    }
                    val rendered = renderRowsNoMemo()
                    check(rendered == count) { "row no-memo update expected $count rows, rendered $rendered" }
                    rendered
                },
                teardown = ::teardownRows,
            ),
        )
    }
