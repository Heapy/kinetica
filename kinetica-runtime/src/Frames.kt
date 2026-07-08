package io.heapy.kinetica

/**
 * Static slot layout of one numbering region — a `@UiComponent` body or a nested region
 * lambda (an `each` row, `keyed` content, a boundary branch) — emitted by the Kinetica
 * compiler plugin as a file-level value shared by every instance of the region.
 *
 * Ordinals are dense, assigned in source evaluation order, and separate per family:
 * slots, host events, and child regions each count from zero. `transientSlotOrdinals`
 * lists the slot ordinals whose values expire when a committed render of the frame does
 * not touch them (effects, refs, resources, transient state). `persistentSlotIds` maps a
 * slot ordinal to its durable persistence identity, present only for persistent state.
 */
public class FrameTable(
    public val functionFqName: String,
    public val slotCount: Int,
    public val eventCount: Int,
    public val childCount: Int,
    public val transientSlotOrdinals: IntArray = EMPTY_ORDINALS,
    public val persistentSlotIds: Array<SlotId?>? = null,
    public val slotDebugNames: Array<String>? = null,
)

private val EMPTY_ORDINALS = IntArray(0)

/**
 * Thrown when a slot-consuming construct runs without a compiler-staged ordinal.
 * Kinetica requires every module that authors `@UiComponent` functions to compile with
 * the `io.heapy.kinetica.compiler` plugin; a call site the plugin never rewrote is the
 * only way to reach this.
 */
public class MissingKineticaPluginException internal constructor(construct: String) :
    IllegalStateException(
        "A Kinetica $construct ran without a compiler-assigned ordinal. " +
            "Apply the io.heapy.kinetica.compiler plugin to the module declaring the " +
            "@UiComponent function (Kinetica does not support plugin-less builds).",
    )

/** Runtime event ids registered by one event-consuming call site (a frame event ordinal). */
internal class HostEventGroup {
    internal var primary: String? = null
    internal var secondary: String? = null

    internal fun remove(runtime: KineticaRuntime) {
        primary?.let(runtime::removeEvent)
        secondary?.let(runtime::removeEvent)
        primary = null
        secondary = null
    }
}

/**
 * One instance of a numbering region: the ordinal-addressed storage cell tree that
 * replaces the string-keyed `slots`/`hostEvents` maps. Frames form a tree — component
 * calls and boundary branches create fixed children (addressed by a static child
 * ordinal), `keyed {}` and `each` rows create keyed children (child ordinal + user key).
 *
 * A frame with `table == null` runs in growable mode: arrays expand on demand and
 * transient flags are tracked per slot. Growable mode backs scope roots, where content
 * arrives as compiler-wrapped regions but harnesses may also drive storage directly.
 *
 * Lifecycle: [markEntered] stamps a render generation; [commitChecks] runs after a
 * committed render for every frame entered by it, expiring untouched transient slots and
 * events and deactivating child frames the render stopped keeping. [markKept] lets
 * memoization (skipped rows, skippable component hits) keep a subtree alive without
 * re-entering it. [deactivate] disposes transients recursively but keeps state so a
 * branch can come back; [dispose] tears the subtree down for good.
 */
internal class Frame(
    internal val table: FrameTable?,
    internal val parent: Frame?,
) {
    private var slots: Array<Any?> = arrayOfNulls(table?.slotCount ?: GROWABLE_INITIAL)
    private var slotTouch: IntArray? =
        if (table == null || table.transientSlotOrdinals.isNotEmpty()) IntArray(slots.size) else null
    private var growableTransient: BooleanArray? = if (table == null) BooleanArray(slots.size) else null
    private var events: Array<HostEventGroup?>? = null
    private var eventTouch: IntArray? = null
    private var children: Array<Any?>? = null
    private var childEnterStamp: IntArray? = null
    private var childForks: IntArray? = null
    private var childRegionOrdinals: IntArray? = null

    // Region frames created by compiler-wrapped content lambdas, keyed by the identity of
    // their FrameTable static — one table per wrapped lambda literal, so table identity is
    // call-site identity.
    private var regions: HashMap<FrameTable, Frame>? = null

    internal var enteredGeneration: Int = -1
        private set

    // Ordinals of slots registered with a persistence identity; needed when a keyed frame
    // is removed with keepPersistentSlots so scroll-restore style state survives removal.
    private var persistentOrdinals: MutableList<Int>? = null

    /** Lazily assigned namespace for component-scoped resource caches. */
    internal var resourceNamespace: String? = null

    /** Memoized output of this frame's subtree (skippable component / each row). */
    internal var skipCache: FrameSkipCache? = null
    internal var partialRenderTargetId: Long = NO_PARTIAL_RENDER_TARGET
    internal var keptGeneration: Int = -1
        private set
    internal var deactivated: Boolean = false
        private set

    /** Returns true when this is the first entry in [generation], so the scope records the frame once. */
    internal fun markEntered(generation: Int): Boolean {
        val first = enteredGeneration != generation
        enteredGeneration = generation
        keptGeneration = generation
        deactivated = false
        return first
    }

    internal fun markKept(generation: Int) {
        keptGeneration = generation
    }

    /**
     * Marks everything this frame holds as alive for [generation] without re-rendering:
     * a memoized skip replayed the subtree's output, so its slots, events, and child
     * frames must survive the commit checks exactly as if the body had run.
     */
    internal fun markContentsKept(generation: Int) {
        slotTouch?.let { touch ->
            for (ordinal in slots.indices) {
                if (slots[ordinal] != null) touch[ordinal] = generation
            }
        }
        events?.let { groups ->
            val touch = eventTouch!!
            for (ordinal in groups.indices) {
                if (groups[ordinal] != null) touch[ordinal] = generation
            }
        }
        forEachChildFrame { it.markKept(generation) }
    }

    // --- Slots ---

    internal fun <T> slot(ordinal: Int, generation: Int, transient: Boolean, initial: () -> T): T {
        ensureSlotCapacity(ordinal)
        slotTouch?.set(ordinal, generation)
        growableTransient?.set(ordinal, transient)
        val existing = slots[ordinal]
        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return existing as T
        }
        val created = initial()
        slots[ordinal] = created
        return created
    }

    internal fun touchSlot(ordinal: Int, generation: Int, transient: Boolean) {
        ensureSlotCapacity(ordinal)
        slotTouch?.set(ordinal, generation)
        growableTransient?.set(ordinal, transient)
    }

    internal fun slotValueOrNull(ordinal: Int): Any? = slots.getOrNull(ordinal)

    internal fun markPersistent(ordinal: Int) {
        val list = persistentOrdinals ?: mutableListOf<Int>().also { persistentOrdinals = it }
        if (ordinal !in list) list += ordinal
    }

    /** Replaces a slot's holder (identity-keyed slots such as resources); disposes nothing. */
    internal fun setSlotValue(ordinal: Int, generation: Int, transient: Boolean, value: Any?) {
        ensureSlotCapacity(ordinal)
        slotTouch?.set(ordinal, generation)
        growableTransient?.set(ordinal, transient)
        slots[ordinal] = value
    }

    // --- Events ---

    internal fun event(
        ordinal: Int,
        role: Int,
        generation: Int,
        runtime: KineticaRuntime,
        callback: (Any?) -> Unit,
    ): String {
        ensureEventCapacity(ordinal)
        eventTouch!![ordinal] = generation
        val group = events!![ordinal] ?: HostEventGroup().also { events!![ordinal] = it }
        val existing = if (role == EVENT_ROLE_PRIMARY) group.primary else group.secondary
        if (existing != null && runtime.updateEvent(existing, callback)) {
            return existing
        }
        val id = runtime.registerEvent(callback)
        if (role == EVENT_ROLE_PRIMARY) group.primary = id else group.secondary = id
        return id
    }

    // --- Children ---

    internal fun enterFixedChild(ordinal: Int, table: FrameTable?, generation: Int): Frame {
        ensureChildCapacity(ordinal)
        val stamps = childEnterStamp!!
        val firstThisRender = stamps[ordinal] != generation
        stamps[ordinal] = generation
        if (firstThisRender) {
            childForks?.set(ordinal, 0)
            return when (val entry = children!![ordinal]) {
                null -> Frame(table, this).also { children!![ordinal] = it }
                is Frame -> entry
                else -> childMap(entry).getOrPut(FIRST_INVOCATION) { Frame(table, this) }
            }
        }
        // Re-entered within one render (a content wrapper invoked twice): fork to an
        // invocation-indexed sibling so repeated invocations cannot alias state.
        val forks = childForks ?: IntArray(children!!.size).also { childForks = it }
        val invocation = ++forks[ordinal]
        val map = when (val entry = children!![ordinal]) {
            null -> HashMap<Any, Frame>().also { children!![ordinal] = it }
            is Frame -> HashMap<Any, Frame>().also {
                it[FIRST_INVOCATION] = entry
                children!![ordinal] = it
            }
            else -> childMap(entry)
        }
        return map.getOrPut(invocation) { Frame(table, this) }
    }

    internal fun enterKeyedChild(ordinal: Int, key: Any, table: FrameTable?, generation: Int): Frame {
        ensureChildCapacity(ordinal)
        childEnterStamp!![ordinal] = generation
        val map = when (val entry = children!![ordinal]) {
            null -> HashMap<Any, Frame>().also { children!![ordinal] = it }
            is Frame -> HashMap<Any, Frame>().also {
                it[FIRST_INVOCATION] = entry
                children!![ordinal] = it
            }
            else -> childMap(entry)
        }
        return map.getOrPut(key) { Frame(table, this) }
    }

    internal fun enterRegionChild(table: FrameTable): Frame {
        val map = regions ?: HashMap<FrameTable, Frame>(4).also { regions = it }
        return map.getOrPut(table) { Frame(table, this) }
    }

    /** Keeps a fixed child alive for this render without entering it (memoized skip hit). */
    internal fun touchFixedChild(ordinal: Int, generation: Int) {
        ensureChildCapacity(ordinal)
        childEnterStamp!![ordinal] = generation
        when (val entry = children!![ordinal]) {
            is Frame -> entry.markKept(generation)
            is HashMap<*, *> -> childMap(entry).values.forEach { it.markKept(generation) }
            else -> {}
        }
    }

    internal fun keyedChild(ordinal: Int, key: Any): Frame? =
        (children?.getOrNull(ordinal) as? HashMap<*, *>)?.let { childMap(it)[key] }

    internal fun keyedChildKeys(ordinal: Int): Set<Any> =
        (children?.getOrNull(ordinal) as? HashMap<*, *>)?.let { childMap(it).keys.toSet() } ?: emptySet()

    internal fun childRegionOrdinal(ordinal: Int, allocate: () -> Int): Int {
        ensureChildRegionOrdinalCapacity(ordinal)
        val ordinals = childRegionOrdinals!!
        val existing = ordinals[ordinal]
        if (existing != UNASSIGNED_REGION_ORDINAL) {
            return existing
        }
        val assigned = allocate()
        ordinals[ordinal] = assigned
        return assigned
    }

    internal fun removeKeyedChild(ordinal: Int, key: Any, runtime: KineticaRuntime) {
        val map = (children?.getOrNull(ordinal) as? HashMap<*, *>)?.let(::childMap) ?: return
        map.remove(key)?.dispose(runtime)
    }

    /**
     * Removes keyed children matching [key] anywhere in this subtree (the public
     * `disposeKeyScope` contract). With [keepPersistentSlots] the frame is retained but
     * stripped: everything except persistence-addressed cells is disposed, so the state
     * a router wants to restore (scroll positions) survives while the rest resets.
     */
    internal fun removeKeyedDescendants(key: Any, keepPersistentSlots: Boolean, runtime: KineticaRuntime) {
        children?.let { entries ->
            for (entry in entries) {
                when (entry) {
                    is Frame -> entry.removeKeyedDescendants(key, keepPersistentSlots, runtime)
                    is HashMap<*, *> -> {
                        val map = childMap(entry)
                        val match = map[key]
                        if (match != null) {
                            if (keepPersistentSlots) {
                                match.stripForPersistentRetention(runtime)
                            } else {
                                map.remove(key)?.dispose(runtime)
                            }
                        }
                        map.values.forEach { it.removeKeyedDescendants(key, keepPersistentSlots, runtime) }
                    }
                    else -> {}
                }
            }
        }
        regions?.values?.forEach { it.removeKeyedDescendants(key, keepPersistentSlots, runtime) }
    }

    /**
     * Disposes everything except persistence-addressed slots, recursively. Persistent
     * cells can live on this frame or in nested child frames, so state at any depth must
     * survive while sibling transients, events, and caches are torn down.
     */
    internal fun stripForPersistentRetention(runtime: KineticaRuntime) {
        skipCache = null
        forEachChildFrame { it.stripForPersistentRetention(runtime) }
        val keep = persistentOrdinals
        for (ordinal in slots.indices) {
            val value = slots[ordinal] ?: continue
            if (keep != null && ordinal in keep) continue
            disposeFrameSlotValue(value)
            slots[ordinal] = null
        }
        removeAllEvents(runtime)
        deactivated = true
    }

    // --- Lifecycle ---

    internal fun commitChecks(generation: Int, runtime: KineticaRuntime) {
        val transientFlags = growableTransient
        if (transientFlags != null) {
            val touch = slotTouch!!
            for (ordinal in slots.indices) {
                if (transientFlags[ordinal] && slots[ordinal] != null && touch[ordinal] != generation) {
                    disposeFrameSlotValue(slots[ordinal])
                    slots[ordinal] = null
                }
            }
        } else {
            slotTouch?.let { touch ->
                for (ordinal in table!!.transientSlotOrdinals) {
                    if (slots[ordinal] != null && touch[ordinal] != generation) {
                        disposeFrameSlotValue(slots[ordinal])
                        slots[ordinal] = null
                    }
                }
            }
        }
        events?.let { groups ->
            val touch = eventTouch!!
            for (ordinal in groups.indices) {
                val group = groups[ordinal] ?: continue
                if (touch[ordinal] != generation) {
                    group.remove(runtime)
                    groups[ordinal] = null
                }
            }
        }
        children?.let { entries ->
            for (entry in entries) {
                when (entry) {
                    is Frame -> if (entry.keptGeneration != generation) entry.deactivate(runtime)
                    is HashMap<*, *> -> childMap(entry).values.forEach { child ->
                        if (child.keptGeneration != generation) child.deactivate(runtime)
                    }
                    else -> {}
                }
            }
        }
        regions?.values?.forEach { region ->
            if (region.keptGeneration != generation) region.deactivate(runtime)
        }
    }

    internal fun deactivate(runtime: KineticaRuntime) {
        if (deactivated) return
        deactivated = true
        runtime.removePartialRenderTarget(partialRenderTargetId)
        partialRenderTargetId = NO_PARTIAL_RENDER_TARGET
        skipCache = null
        val transientFlags = growableTransient
        if (transientFlags != null) {
            for (ordinal in slots.indices) {
                if (transientFlags[ordinal] && slots[ordinal] != null) {
                    disposeFrameSlotValue(slots[ordinal])
                    slots[ordinal] = null
                }
            }
        } else {
            for (ordinal in table!!.transientSlotOrdinals) {
                slots[ordinal]?.let { value ->
                    disposeFrameSlotValue(value)
                    slots[ordinal] = null
                }
            }
        }
        removeAllEvents(runtime)
        forEachChildFrame { it.deactivate(runtime) }
    }

    internal fun dispose(runtime: KineticaRuntime) {
        runtime.removePartialRenderTarget(partialRenderTargetId)
        partialRenderTargetId = NO_PARTIAL_RENDER_TARGET
        skipCache = null
        forEachChildFrame { it.dispose(runtime) }
        children = null
        regions = null
        for (ordinal in slots.indices) {
            slots[ordinal]?.let { value ->
                disposeFrameSlotValue(value)
                slots[ordinal] = null
            }
        }
        removeAllEvents(runtime)
        deactivated = true
    }

    // --- Internals ---

    private fun removeAllEvents(runtime: KineticaRuntime) {
        events?.let { groups ->
            for (ordinal in groups.indices) {
                groups[ordinal]?.remove(runtime)
                groups[ordinal] = null
            }
        }
    }

    private inline fun forEachChildFrame(action: (Frame) -> Unit) {
        children?.let { entries ->
            for (entry in entries) {
                when (entry) {
                    is Frame -> action(entry)
                    is HashMap<*, *> -> childMap(entry).values.forEach(action)
                    else -> {}
                }
            }
        }
        regions?.values?.forEach(action)
    }

    @Suppress("UNCHECKED_CAST")
    private fun childMap(entry: Any?): HashMap<Any, Frame> = entry as HashMap<Any, Frame>

    private fun ensureSlotCapacity(ordinal: Int) {
        if (ordinal < slots.size) return
        require(table == null) {
            "slot ordinal $ordinal out of range for ${table?.functionFqName} (slotCount=${table?.slotCount})"
        }
        val capacity = newCapacity(ordinal, slots.size)
        slots = slots.copyOf(capacity)
        slotTouch = slotTouch?.copyOf(capacity)
        growableTransient = growableTransient!!.copyOf(capacity)
    }

    private fun ensureEventCapacity(ordinal: Int) {
        val existing = events
        if (existing == null) {
            val capacity = maxOf(table?.eventCount ?: GROWABLE_INITIAL, ordinal + 1)
            events = arrayOfNulls(capacity)
            eventTouch = IntArray(capacity)
            return
        }
        if (ordinal < existing.size) return
        require(table == null) {
            "event ordinal $ordinal out of range for ${table?.functionFqName} (eventCount=${table?.eventCount})"
        }
        val capacity = newCapacity(ordinal, existing.size)
        events = existing.copyOf(capacity)
        eventTouch = eventTouch!!.copyOf(capacity)
    }

    private fun ensureChildCapacity(ordinal: Int) {
        val existing = children
        if (existing == null) {
            val capacity = maxOf(table?.childCount ?: GROWABLE_INITIAL, ordinal + 1)
            children = arrayOfNulls(capacity)
            childEnterStamp = IntArray(capacity)
            return
        }
        if (ordinal < existing.size) return
        require(table == null) {
            "child ordinal $ordinal out of range for ${table?.functionFqName} (childCount=${table?.childCount})"
        }
        val capacity = newCapacity(ordinal, existing.size)
        children = existing.copyOf(capacity)
        childEnterStamp = childEnterStamp!!.copyOf(capacity)
        childForks = childForks?.copyOf(capacity)
    }

    private fun ensureChildRegionOrdinalCapacity(ordinal: Int) {
        val existing = childRegionOrdinals
        if (existing == null) {
            val capacity = maxOf(table?.childCount ?: GROWABLE_INITIAL, ordinal + 1)
            childRegionOrdinals = IntArray(capacity) { UNASSIGNED_REGION_ORDINAL }
            return
        }
        if (ordinal < existing.size) return
        require(table == null) {
            "child region ordinal $ordinal out of range for ${table?.functionFqName} (childCount=${table?.childCount})"
        }
        val capacity = newCapacity(ordinal, existing.size)
        childRegionOrdinals = existing.copyOf(capacity).also { expanded ->
            for (index in existing.size until expanded.size) {
                expanded[index] = UNASSIGNED_REGION_ORDINAL
            }
        }
    }

    private fun newCapacity(ordinal: Int, current: Int): Int = maxOf(ordinal + 1, current * 2)

    private companion object {
        private const val GROWABLE_INITIAL = 4
        private const val FIRST_INVOCATION = 0
        private const val UNASSIGNED_REGION_ORDINAL = -1
    }
}

internal const val EVENT_ROLE_PRIMARY: Int = 0
internal const val EVENT_ROLE_SECONDARY: Int = 1
internal const val NO_PARTIAL_RENDER_TARGET: Long = -1L

internal fun disposeFrameSlotValue(value: Any?) {
    when (value) {
        is ManagedEffectState -> value.cancel()
        is Disposable -> value.dispose()
        is Ref<*> -> value.clear()
    }
}
