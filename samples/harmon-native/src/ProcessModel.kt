package io.heapy.kinetica.samples.harmon

import io.heapy.kinetica.appkit.OutlineCell
import io.heapy.kinetica.appkit.OutlineColumn
import io.heapy.kinetica.appkit.OutlineRow
import io.heapy.kinetica.appkit.OutlineSort
import io.heapy.kinetica.appkit.OutlineTableModel
import kotlin.math.roundToLong

enum class Metric(val label: String, val unit: String) {
    Cpu("CPU", "%"), Memory("Memory", " MiB"), Read("Read", " KiB/s"), Write("Write", " KiB/s"),
    Wakeups("Wakeups", "/s"), Threads("Threads", ""), Power("Power", " W"),
}

data class Measurement(val value: Double?, val partial: Boolean = false)

data class ProcessRecord(
    val key: String,
    val pid: Int,
    val name: String,
    val metrics: Map<Metric, Measurement>,
    val children: List<ProcessRecord> = emptyList(),
    val alert: String? = null,
    val totals: Map<Metric, Measurement>? = null,
)

enum class SampleStatus { Ready, Warming, Stale }

data class ProcessSnapshot(
    val sequence: Int,
    val roots: List<ProcessRecord>,
    val status: SampleStatus = SampleStatus.Ready,
    val message: String? = null,
    val sourceSequence: String? = null,
    val capturedAt: String? = null,
    val totalProcessCount: Int? = null,
    val inaccessibleProcessCount: Int = 0,
)

enum class MetricPreset(val label: String, val metrics: List<Metric>) {
    Overview("Overview", listOf(Metric.Cpu, Metric.Memory)),
    Memory("Memory", listOf(Metric.Memory)),
    IO("I/O", listOf(Metric.Read, Metric.Write)),
    Activity("Activity", listOf(Metric.Wakeups, Metric.Threads)),
    Energy("Energy", listOf(Metric.Cpu, Metric.Power)),
}

data class MonitorState(
    val snapshot: ProcessSnapshot = fixtureSnapshot(0),
    val query: String = "",
    val preset: MetricPreset = MetricPreset.Overview,
    val sort: OutlineSort = OutlineSort("Cpu.total"),
    val expanded: Set<String> = snapshot.roots.map { it.key }.toSet(),
    val collapsedSearchKeys: Set<String> = emptySet(),
    val selected: String? = null,
    val frozen: Boolean = false,
) {
    fun nextSample(): MonitorState {
        return withSnapshot(fixtureSnapshot(snapshot.sequence + 1))
    }

    fun withSnapshot(next: ProcessSnapshot): MonitorState {
        val keys = next.allProcesses().map { it.key }.toSet()
        val nextExpanded = if (snapshot.roots.isEmpty()) next.roots.map { it.key }.toSet() else expanded.intersect(keys)
        return copy(snapshot = next, expanded = nextExpanded, collapsedSearchKeys = collapsedSearchKeys.intersect(keys),
            selected = selected?.takeIf { it in keys })
    }

    fun withPreset(next: MetricPreset): MonitorState = copy(preset = next, sort = OutlineSort("${next.metrics.first().name}.total"))

    fun withQuery(next: String): MonitorState = copy(query = next, collapsedSearchKeys = emptySet())

    fun withExpansion(key: String, open: Boolean): MonitorState = copy(
        expanded = if (open) expanded + key else expanded - key,
        collapsedSearchKeys = if (open) collapsedSearchKeys - key else collapsedSearchKeys + key,
    )

    fun withAllExpanded(open: Boolean): MonitorState {
        val keys = snapshot.allProcesses().map { it.key }.toSet()
        return copy(expanded = if (open) keys else emptySet(), collapsedSearchKeys = if (open) emptySet() else keys)
    }
}

fun ProcessSnapshot.allProcesses(): List<ProcessRecord> = buildList {
    fun visit(nodes: List<ProcessRecord>) {
        for (node in nodes) {
            add(node)
            visit(node.children)
        }
    }
    visit(roots)
}

data class MonitorProjection(val table: OutlineTableModel, val matchingCount: Int, val processCount: Int)

/** Filters ancestors without changing the totals, which still describe the complete subtree. */
fun projectMonitor(state: MonitorState): MonitorProjection {
    val totals = mutableMapOf<String, Map<Metric, Measurement>>()
    val keys = HashSet<String>()
    fun aggregate(node: ProcessRecord): Map<Metric, Measurement> {
        require(keys.add(node.key)) { "Duplicate process identity: ${node.key}" }
        val children = node.children.map(::aggregate)
        node.totals?.let { return it.also { totals[node.key] = it } }
        return Metric.entries.associateWith { metric ->
            val readings = listOf(node.metrics[metric] ?: Measurement(null)) + children.map { it.getValue(metric) }
            val available = readings.mapNotNull { it.value }
            Measurement(available.takeIf { it.isNotEmpty() }?.sum(), readings.any { it.value == null || it.partial })
        }.also { totals[node.key] = it }
    }
    state.snapshot.roots.forEach(::aggregate)
    val columns = listOf(OutlineColumn("name", "Process", 280.0, 160.0), OutlineColumn("pid", "PID", 80.0, 60.0, numeric = true)) +
        state.preset.metrics.flatMap { metric ->
            listOf("self", "total").map { scope ->
                OutlineColumn("${metric.name}.$scope", "${metric.label} ${scope.replaceFirstChar(Char::uppercase)}", 126.0, 95.0, numeric = true)
            }
        }
    val sort = state.sort.takeIf { sort -> columns.any { it.id == sort.column } } ?: OutlineSort("pid", true)
    fun reading(node: ProcessRecord, metric: Metric, total: Boolean): Measurement =
        (if (total) totals.getValue(node.key)[metric] else node.metrics[metric]) ?: Measurement(null)

    fun numeric(node: ProcessRecord): Double? {
        if (sort.column == "pid") return node.pid.toDouble()
        val parts = sort.column.split('.')
        val metric = Metric.entries.firstOrNull { it.name == parts.first() } ?: return null
        return reading(node, metric, parts.last() == "total").value
    }
    val comparator = Comparator<ProcessRecord> { left, right ->
        val comparison = if (sort.column == "name") {
            val value = left.name.lowercase().compareTo(right.name.lowercase())
            if (sort.ascending) value else -value
        } else {
            val a = numeric(left)
            val b = numeric(right)
            when {
                a == null && b == null -> 0
                a == null -> 1
                b == null -> -1
                sort.ascending -> a.compareTo(b)
                else -> b.compareTo(a)
            }
        }
        if (comparison != 0) comparison else left.key.compareTo(right.key)
    }
    val query = state.query.trim()
    val searchExpansion = mutableSetOf<String>()
    var matchingCount = 0
    fun rows(nodes: List<ProcessRecord>, ancestorMatched: Boolean = false): List<OutlineRow> =
        nodes.sortedWith(comparator).mapNotNull { node ->
            val matches = query.isEmpty() || node.name.contains(query, ignoreCase = true) || node.pid.toString().contains(query)
            val children = rows(node.children, ancestorMatched || matches)
            if (!matches && !ancestorMatched && children.isEmpty()) return@mapNotNull null
            matchingCount++
            if (query.isNotEmpty() && children.isNotEmpty()) searchExpansion += node.key
            val cells = mutableMapOf(
                "name" to OutlineCell((if (node.alert != null) "⚠ " else "") + node.name, node.alert ?: node.name),
                "pid" to OutlineCell(node.pid.toString()),
            )
            for (metric in state.preset.metrics) {
                for (scope in listOf("self", "total")) {
                    val measurement = reading(node, metric, scope == "total")
                    cells["${metric.name}.$scope"] = measurement.cell(metric)
                }
            }
            OutlineRow(node.key, cells, children)
        }
    val visible = rows(state.snapshot.roots)
    return MonitorProjection(
        OutlineTableModel(columns, visible, state.expanded + (searchExpansion - state.collapsedSearchKeys), state.selected, sort),
        matchingCount,
        state.snapshot.totalProcessCount ?: keys.size,
    )
}

fun Measurement.cell(metric: Metric): OutlineCell {
    val amount = value ?: return OutlineCell("—", "Unavailable; this is not zero", secondary = true)
    val formatted = if (metric == Metric.Threads) amount.roundToLong().toString() else decimal(amount)
    return OutlineCell(
        (if (partial) "≥ " else "") + formatted + metric.unit,
        if (partial) "Partial total: some processes have unavailable measurements" else formatted + metric.unit,
    )
}

fun decimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}

/** Repeatable 1,000-process workload, including missing readings, churn and PID reuse. */
fun fixtureSnapshot(sequence: Int, count: Int = 1_000): ProcessSnapshot {
    require(count >= 20)
    val names = listOf("Xcode", "Safari", "Terminal", "WindowServer", "Finder", "Harmon", "Mail", "Music", "Spotlight", "Calendar",
        "Notes", "Preview", "Messages", "Photos", "System Settings", "Dock", "Control Center", "Quick Look", "launchd", "Kernel tasks")
    fun process(index: Int, name: String, children: List<ProcessRecord> = emptyList()): ProcessRecord {
        val pid = 100 + index
        val epoch = if (index == 21) sequence / 5 else 0
        val missing = index % 43 == 0 && index > 0
        val cpu = (index % 19) * 0.03 + (sequence % 7) * 0.01
        return ProcessRecord(
            key = "$pid:start-$epoch",
            pid = pid,
            name = name,
            metrics = mapOf(
                Metric.Cpu to Measurement(if (missing) null else cpu),
                Metric.Memory to Measurement(if (missing) null else 12.0 + index % 180 + sequence % 3),
                Metric.Read to Measurement(if (missing) null else (index % 13) * 0.5),
                Metric.Write to Measurement(if (missing) null else (index % 7) * 0.3),
                Metric.Wakeups to Measurement(if (missing) null else (index % 8).toDouble()),
                Metric.Threads to Measurement(if (missing) null else (index % 12 + 1).toDouble()),
                Metric.Power to Measurement(null),
            ),
            children = children,
            alert = if (index == 0) "High CPU activity in Xcode and its helper processes" else null,
        )
    }
    val roots = names.mapIndexed { index, name ->
        val children = (20 + index until count step 20).map { child -> process(child, "$name Helper ${child / 20}") }
            .filterNot { sequence % 4 == 3 && it.pid == 122 }
        process(index, name, children)
    }
    return ProcessSnapshot(sequence, roots)
}
