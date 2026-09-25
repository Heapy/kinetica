package io.heapy.kinetica.appkit

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.host
import io.heapy.kinetica.hostEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public data class OutlineColumn(
    val id: String,
    val title: String,
    val width: Double = 120.0,
    val minWidth: Double = 70.0,
    val numeric: Boolean = false,
)

@Serializable
public data class OutlineCell(
    val text: String,
    val tooltip: String? = null,
    val secondary: Boolean = false,
)

/** Keys identify entities across snapshots; use PID plus start time for processes. */
@Serializable
public data class OutlineRow(
    val key: String,
    val cells: Map<String, OutlineCell>,
    val children: List<OutlineRow> = emptyList(),
)

@Serializable
public data class OutlineSort(val column: String, val ascending: Boolean = false)

/**
 * Row order belongs to the caller. Sorting emits a request, never guesses numeric values from
 * formatted text. The first column carries the disclosure control and cannot be reordered.
 */
@Serializable
public data class OutlineTableModel(
    val columns: List<OutlineColumn>,
    val rows: List<OutlineRow>,
    val expandedKeys: Set<String> = emptySet(),
    val selectedKey: String? = null,
    val sort: OutlineSort? = null,
) {
    public fun validate() {
        require(columns.isNotEmpty()) { "An outline table needs at least one column" }
        require(columns.map { it.id }.toSet().size == columns.size) { "Duplicate outline column ID" }
        columns.forEach {
            require(it.id.isNotBlank()) { "Outline column ID must not be blank" }
            require(it.width.isFinite() && it.minWidth.isFinite() && it.minWidth > 0 && it.width >= it.minWidth) {
                "Invalid width for outline column ${it.id}"
            }
        }
        require(sort == null || columns.any { it.id == sort.column }) { "Unknown sort column" }
        val keys = HashSet<String>()
        fun visit(rows: List<OutlineRow>) {
            for (row in rows) {
                require(row.key.isNotEmpty() && keys.add(row.key)) { "Duplicate or empty outline row key: ${row.key}" }
                visit(row.children)
            }
        }
        visit(rows)
    }
}

public sealed interface OutlineTableEvent {
    public data class SelectionChanged(val key: String?) : OutlineTableEvent
    public data class ExpansionChanged(val key: String, val expanded: Boolean) : OutlineTableEvent
    public data class SortChanged(val sort: OutlineSort) : OutlineTableEvent
}

@UiComponent
public fun ComponentScope.outlineTable(
    model: OutlineTableModel,
    semantics: Semantics = Semantics(label = "Outline table"),
    key: String? = null,
    onEvent: (OutlineTableEvent) -> Unit,
) {
    val eventId = hostEvent<OutlineTableEvent>(onEvent = onEvent)
    host(
        tag = OUTLINE_TABLE_TAG,
        props = mapOf("model" to Json.encodeToString(model), "event:onOutline" to eventId),
        semantics = semantics,
        key = key,
    )
}

internal const val OUTLINE_TABLE_TAG = "appkit:outlineTable"
