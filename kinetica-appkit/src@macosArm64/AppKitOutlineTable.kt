@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.heapy.kinetica.appkit

import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import platform.AppKit.*
import platform.Foundation.NSIndexSet
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotification
import platform.Foundation.NSSortDescriptor
import platform.darwin.NSObject

/** Native scrolling and row reuse keep widget allocation proportional to the viewport. */
internal class AppKitOutlineTable(onEvent: (OutlineTableEvent) -> Unit) : NSScrollView(NSMakeRect(0.0, 0.0, 800.0, 400.0)) {
    val controller = OutlineController(onEvent)
    val outline: NSOutlineView get() = controller.outline
    private var lastModel: OutlineTableModel? = null

    init {
        setHasVerticalScroller(true)
        setHasHorizontalScroller(true)
        setAutohidesScrollers(true)
        setBorderType(NSBezelBorder)
        documentView = outline
        translatesAutoresizingMaskIntoConstraints = false
        setContentHuggingPriority(1f, NSUserInterfaceLayoutOrientationVertical)
        setContentCompressionResistancePriority(1f, NSUserInterfaceLayoutOrientationVertical)
    }

    fun update(model: OutlineTableModel) {
        if (lastModel == model) return
        model.validate()
        val position = contentView().bounds.useContents { origin.x to origin.y }
        val topRow = outline.rowAtPoint(NSMakePoint(0.0, position.second))
        val anchor = (outline.itemAtRow(topRow) as? OutlineItem)?.row?.key
        val offset = if (topRow >= 0) position.second - outline.rectOfRow(topRow).useContents { origin.y } else 0.0
        controller.update(model)
        layoutSubtreeIfNeeded()
        val anchorRow = anchor?.let(controller.items::get)?.let(outline::rowForItem) ?: -1
        val y = if (anchorRow >= 0) outline.rectOfRow(anchorRow).useContents { origin.y } + offset else position.second
        contentView().scrollToPoint(NSMakePoint(position.first, y))
        reflectScrolledClipView(contentView())
        lastModel = model
    }

    fun dispose() = controller.dispose()
}

internal class OutlineItem(var row: OutlineRow) : NSObject() {
    var children: List<OutlineItem> = emptyList()
}

internal class OutlineController(
    private var onEvent: ((OutlineTableEvent) -> Unit)?,
) : NSObject(), NSOutlineViewDataSourceProtocol, NSOutlineViewDelegateProtocol {
    val outline = NSOutlineView()
    val items = mutableMapOf<String, OutlineItem>()
    private var roots: List<OutlineItem> = emptyList()
    private var model: OutlineTableModel? = null
    private var applying = false
    private val columnWidths = mutableMapOf<String, Double>()
    internal var createdCellCount = 0
        private set
    internal var structureReloadCount = 0
        private set

    init {
        outline.setDataSource(this)
        outline.setDelegate(this)
        outline.setHeaderView(NSTableHeaderView())
        outline.setUsesAlternatingRowBackgroundColors(true)
        outline.setAllowsMultipleSelection(false)
        outline.setAllowsEmptySelection(true)
        outline.setAllowsColumnReordering(false)
        outline.setAllowsColumnResizing(true)
        outline.setColumnAutoresizingStyle(NSTableViewNoColumnAutoresizing)
        outline.setRowHeight(26.0)
        outline.setIndentationPerLevel(16.0)
        outline.setStronglyReferencesItems(true)
    }

    fun update(next: OutlineTableModel) {
        val previous = model
        applying = true
        try {
            val columnsChanged = previous?.columns != next.columns
            if (columnsChanged) updateColumns(next.columns)
            var structureChanged = previous == null || roots.map { it.row.key } != next.rows.map { it.key }
            val retained = HashSet<String>()
            fun reconcile(rows: List<OutlineRow>): List<OutlineItem> = rows.map { row ->
                retained += row.key
                val item = items.getOrPut(row.key) {
                    structureChanged = true
                    OutlineItem(row)
                }
                if (item.children.map { it.row.key } != row.children.map { it.key }) structureChanged = true
                item.row = row
                item.children = reconcile(row.children)
                item
            }
            roots = reconcile(next.rows)
            model = next
            if (structureChanged || columnsChanged) {
                outline.reloadData()
                structureReloadCount++
            }
            // Parents before children, so previously hidden descendants can be expanded too.
            fun restoreExpansion(rows: List<OutlineItem>) {
                for (item in rows) {
                    if (item.children.isNotEmpty()) {
                        val expanded = item.row.key in next.expandedKeys
                        if (expanded != outline.isItemExpanded(item)) {
                            if (expanded) outline.expandItem(item) else outline.collapseItem(item)
                        }
                        if (expanded) restoreExpansion(item.children)
                    }
                }
            }
            restoreExpansion(roots)
            items.keys.retainAll(retained)
            val selectedRow = next.selectedKey?.let(items::get)?.let(outline::rowForItem) ?: -1
            if (selectedRow != outline.selectedRow) {
                if (selectedRow < 0) outline.deselectAll(null)
                else outline.selectRowIndexes(NSIndexSet.indexSetWithIndex(selectedRow.toULong()), false)
            }
            outline.setSortDescriptors(next.sort?.let { listOf(NSSortDescriptor(it.column, it.ascending)) } ?: emptyList<Any>())
            if (!structureChanged && !columnsChanged && previous.rows != next.rows) {
                val visible = outline.rowsInRect(outline.visibleRect)
                val indexes = visible.useContents { NSIndexSet.indexSetWithIndexesInRange(NSMakeRange(location, length)) }
                outline.reloadDataForRowIndexes(indexes, NSIndexSet.indexSetWithIndexesInRange(NSMakeRange(0u, next.columns.size.toULong())))
            }
        } finally {
            applying = false
        }
    }

    private fun updateColumns(columns: List<OutlineColumn>) {
        val previous = outline.tableColumns.filterIsInstance<NSTableColumn>()
        for (column in previous) {
            column.identifier?.let { columnWidths[it] = column.width }
        }
        val replacements = columns.map { definition ->
            val column = NSTableColumn(definition.id)
            column.setTitle(definition.title)
            column.setMinWidth(definition.minWidth)
            column.setWidth(columnWidths[definition.id] ?: definition.width)
            column.setResizingMask(NSTableColumnUserResizingMask)
            column.setSortDescriptorPrototype(NSSortDescriptor(definition.id, !definition.numeric))
            outline.addTableColumn(column)
            column
        }
        // AppKit ignores a null outline column and refuses to remove the active one.
        outline.setOutlineTableColumn(replacements.first())
        previous.forEach(outline::removeTableColumn)
    }

    @ObjCSignatureOverride
    override fun outlineView(outlineView: NSOutlineView, numberOfChildrenOfItem: Any?): Long =
        children(numberOfChildrenOfItem).size.toLong()

    override fun outlineView(outlineView: NSOutlineView, child: Long, ofItem: Any?): Any =
        children(ofItem)[child.toInt()]

    @ObjCSignatureOverride
    override fun outlineView(outlineView: NSOutlineView, isItemExpandable: Any): Boolean =
        children(isItemExpandable).isNotEmpty()

    override fun outlineView(outlineView: NSOutlineView, viewForTableColumn: NSTableColumn?, item: Any): NSView? {
        val column = viewForTableColumn ?: return null
        val row = (item as? OutlineItem)?.row ?: return null
        val definition = model?.columns?.firstOrNull { it.id == column.identifier } ?: return null
        val view = (outline.makeViewWithIdentifier(column.identifier, this) as? NSTableCellView) ?: makeCell(definition)
        val cell = row.cells[column.identifier] ?: OutlineCell("—", "Unavailable", secondary = true)
        view.textField?.apply {
            setFont(if (definition.numeric) NSFont.monospacedDigitSystemFontOfSize(12.0, NSFontWeightRegular) else NSFont.systemFontOfSize(13.0))
            setAlignment(if (definition.numeric) NSTextAlignmentRight else NSTextAlignmentLeft)
            setStringValue(cell.text)
            setTextColor(if (cell.secondary) NSColor.secondaryLabelColor else NSColor.labelColor)
            setAccessibilityLabel("${definition.title}: ${cell.text}")
        }
        view.setToolTip(cell.tooltip ?: cell.text)
        view.setAccessibilityIdentifier("${row.key}:${column.identifier}")
        return view
    }

    private fun makeCell(column: OutlineColumn): NSTableCellView {
        createdCellCount++
        val cell = NSTableCellView()
        cell.setIdentifier(column.id)
        val label = NSTextField().apply {
            setEditable(false)
            setSelectable(false)
            setBordered(false)
            setDrawsBackground(false)
            setFont(if (column.numeric) NSFont.monospacedDigitSystemFontOfSize(12.0, NSFontWeightRegular) else NSFont.systemFontOfSize(13.0))
            setAlignment(if (column.numeric) NSTextAlignmentRight else NSTextAlignmentLeft)
            setLineBreakMode(NSLineBreakByTruncatingTail)
            setUsesSingleLineMode(true)
            translatesAutoresizingMaskIntoConstraints = false
        }
        cell.textField = label
        cell.addSubview(label)
        label.leadingAnchor.constraintEqualToAnchor(cell.leadingAnchor, constant = 5.0).setActive(true)
        label.trailingAnchor.constraintEqualToAnchor(cell.trailingAnchor, constant = -7.0).setActive(true)
        label.centerYAnchor.constraintEqualToAnchor(cell.centerYAnchor).setActive(true)
        return cell
    }

    override fun outlineView(outlineView: NSOutlineView, sortDescriptorsDidChange: List<*>) {
        if (applying) return
        val sort = outline.sortDescriptors.firstOrNull() as? NSSortDescriptor ?: return
        val key = sort.key ?: return
        onEvent?.invoke(OutlineTableEvent.SortChanged(OutlineSort(key, sort.ascending)))
    }

    override fun outlineViewSelectionDidChange(notification: NSNotification) {
        if (applying) return
        val key = (outline.itemAtRow(outline.selectedRow) as? OutlineItem)?.row?.key
        onEvent?.invoke(OutlineTableEvent.SelectionChanged(key))
    }

    override fun outlineViewItemDidExpand(notification: NSNotification) = expansionChanged(notification, true)
    override fun outlineViewItemDidCollapse(notification: NSNotification) = expansionChanged(notification, false)

    private fun expansionChanged(notification: NSNotification, expanded: Boolean) {
        if (applying) return
        val key = (notification.userInfo?.get("NSObject") as? OutlineItem)?.row?.key ?: return
        onEvent?.invoke(OutlineTableEvent.ExpansionChanged(key, expanded))
    }

    private fun children(item: Any?): List<OutlineItem> = if (item == null) roots else (item as OutlineItem).children

    fun dispose() {
        onEvent = null
        outline.setDelegate(null)
        outline.setDataSource(null)
        roots = emptyList()
        items.clear()
        model = null
    }
}
