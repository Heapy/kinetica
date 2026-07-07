package io.heapy.kinetica

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
public data class SlotId(
    val moduleId: String,
    val functionFqName: String,
    val declarationOrdinal: Int,
    val disambiguator: String,
) {
    public fun stableKey(): String =
        listOf(moduleId, functionFqName, declarationOrdinal.toString(), disambiguator)
            .joinToString(separator = "#")
}

public class ComponentScope public constructor(
    internal val runtime: KineticaRuntime = KineticaRuntime(),
    internal val instanceId: String = "root",
) {
    private val nodeStack = mutableListOf<MutableList<Node>>(mutableListOf())
    private val regionStack = mutableListOf<MutableList<ChildRegion>?>(null)
    private val contexts = mutableMapOf<Context<*>, MutableList<Any?>>()
    private val exitGroups = mutableMapOf<String, ExitGroupState>()
    private val errorBoundaryStack = mutableListOf<ErrorBoundaryState>()
    private val staticNodes = mutableMapOf<String, Node>()
    // Set by the each construct when it filled the current collect frame from index 0 with
    // rows certified as "exactly one node keyed by the row key"; consumed by host() to
    // stamp NodeFlags.CHILDREN_KEYED. Identity-matched against the frame list, so a stale
    // record can never certify someone else's children.
    private var keyedEmissionFrame: MutableList<Node>? = null
    private var keyedEmissionEnd = 0
    internal var lastCollectedRegions: List<ChildRegion> = emptyList()
        private set
    private val exitGroupStack = mutableListOf<ExitGroupState>()
    private var slotGeneration = 0
    private val layoutEffects = mutableListOf<() -> Unit>()
    private val postCommitEffects = mutableListOf<() -> Unit>()

    // --- Frame kernel: ordinal-addressed storage assigned by the compiler plugin ---
    internal val rootFrame: Frame = Frame(table = null, parent = null)
    internal var currentFrame: Frame = rootFrame
        private set
    // LIFO because a component call can appear in argument position of another component
    // call: the inner callee's prologue pops its own ordinal before the outer one resolves.
    private var ordinalStack = IntArray(8)
    private var ordinalStackSize = 0
    private val enteredFrames = mutableListOf<Frame>()

    public val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Stages the child ordinal of the next `@UiComponent` call. Written exclusively by
     * compiler-generated code immediately before the call it identifies; user code never
     * calls this.
     */
    public fun ordinal(n: Int) {
        if (ordinalStackSize == ordinalStack.size) {
            ordinalStack = ordinalStack.copyOf(ordinalStack.size * 2)
        }
        ordinalStack[ordinalStackSize++] = n
    }

    internal fun consumeStagedOrdinal(construct: String): Int {
        if (ordinalStackSize == 0) {
            throw MissingKineticaPluginException(construct)
        }
        return ordinalStack[--ordinalStackSize]
    }

    /**
     * Enters the frame of a `@UiComponent` call site. Compiler-generated prologue; the
     * child ordinal is staged by the caller via [ordinal]. Paired with [endComponentFrame].
     */
    public fun beginComponentFrame(table: FrameTable) {
        val childOrdinal = consumeStagedOrdinal("component call")
        enterFrame(currentFrame.enterFixedChild(childOrdinal, table, slotGeneration))
    }

    public fun endComponentFrame() {
        currentFrame = currentFrame.parent ?: rootFrame
    }

    /**
     * Enters the region frame of a compiler-wrapped content lambda (render roots, region
     * construct bodies, `@UiComponent`-typed content parameters). Keyed by the identity of
     * the wrapped lambda's [table] static. Paired with [endRegionFrame].
     */
    public fun beginRegionFrame(table: FrameTable) {
        enterFrame(currentFrame.enterRegionChild(table))
    }

    public fun endRegionFrame() {
        currentFrame = currentFrame.parent ?: rootFrame
    }

    internal fun enterFrame(frame: Frame) {
        if (frame.markEntered(slotGeneration)) {
            enteredFrames += frame
        }
        currentFrame = frame
    }

    internal fun exitFrame() {
        currentFrame = currentFrame.parent ?: rootFrame
    }

    internal fun <T> frameSlot(ordinal: Int, transient: Boolean = false, initial: () -> T): T {
        if (transient) {
            // Transient slots (effects, refs, resources) expire when a render skips them —
            // replaying cached output would silently cancel them.
            markEachCapturesUnsafe()
        }
        return currentFrame.slot(ordinal, slotGeneration, transient, initial)
    }

    internal fun frameSlotValueOrNull(ordinal: Int): Any? =
        currentFrame.slotValueOrNull(ordinal)

    internal fun touchFrameSlot(ordinal: Int, transient: Boolean) {
        currentFrame.touchSlot(ordinal, slotGeneration, transient)
    }

    /** Stores [value] in the slot, replacing any previous holder (identity-keyed slots). */
    internal fun <T> frameSlotTouch(ordinal: Int, transient: Boolean, value: T): T {
        currentFrame.setSlotValue(ordinal, slotGeneration, transient, value)
        return value
    }

    internal fun enterKeyedChildFrame(ordinal: Int, key: Any, table: FrameTable? = null) {
        enterFrame(currentFrame.enterKeyedChild(ordinal, key, table = table, generation = slotGeneration))
    }

    internal fun enterFixedChildFrame(ordinal: Int) {
        enterFrame(currentFrame.enterFixedChild(ordinal, table = null, generation = slotGeneration))
    }

    internal fun keyedChildFrameKeys(ordinal: Int): Set<Any> =
        currentFrame.keyedChildKeys(ordinal)

    internal fun disposeKeyedChildFrame(ordinal: Int, key: Any) {
        currentFrame.removeKeyedChild(ordinal, key, runtime)
    }

    internal fun stripKeyedChildFrameForPersistence(ordinal: Int, key: Any) {
        currentFrame.keyedChild(ordinal, key)?.stripForPersistentRetention(runtime)
    }

    private var frameBoundaryCounter = 0

    internal fun nextFrameBoundaryId(): String = "$instanceId:fb${frameBoundaryCounter++}"

    internal fun frameEvent(ordinal: Int, role: Int = EVENT_ROLE_PRIMARY, callback: (Any?) -> Unit): String =
        currentFrame.event(ordinal, role, slotGeneration, runtime, callback)

    internal fun beginRender() {
        slotGeneration += 1
        enteredFrames.clear()
        rootFrame.markEntered(slotGeneration)
        enteredFrames += rootFrame
        currentFrame = rootFrame
        ordinalStackSize = 0
        layoutEffects.clear()
        postCommitEffects.clear()
        nodeStack.clear()
        nodeStack.add(mutableListOf())
        regionStack.clear()
        regionStack.add(null)
        lastCollectedRegions = emptyList()
    }

    internal fun commitRender(): Node {
        for (frame in enteredFrames) {
            frame.commitChecks(slotGeneration, runtime)
        }
        val children = nodeStack.single()
        return when (children.size) {
            0 -> FragmentNode()
            1 -> children.single()
            else -> FragmentNode(children)
        }
    }

    internal fun runLayoutEffects() {
        val effects = layoutEffects.toList()
        layoutEffects.clear()
        effects.forEach { effect -> effect() }
    }

    internal fun runPostCommitEffects() {
        val effects = postCommitEffects.toList()
        postCommitEffects.clear()
        effects.forEach { effect -> effect() }
    }

    internal fun scheduleLayoutEffect(block: () -> Unit) {
        markEachCapturesUnsafe()
        layoutEffects += block
    }

    internal fun schedulePostCommitEffect(block: () -> Unit) {
        markEachCapturesUnsafe()
        postCommitEffects += block
    }

    public fun emit(node: Node) {
        nodeStack.last() += node
    }

    internal fun currentNodeFrameSize(): Int =
        nodeStack.last().size

    internal fun recordChildRegion(ordinal: Int, start: Int) {
        val top = regionStack.lastIndex
        val frame = regionStack[top] ?: mutableListOf<ChildRegion>().also { regionStack[top] = it }
        frame += ChildRegion(ordinal, start, nodeStack.last().size)
    }

    public fun staticNode(
        hoistId: String,
        factory: () -> Node,
    ): Node =
        staticNodes.getOrPut(hoistId, factory)

    /**
     * Same contract each-row memoization has: equal inputs + unchanged cell dependencies
     * + unchanged context values. The cache lives on the component's frame; a hit marks
     * the frame's contents kept so its slots, events and child frames survive commit
     * checks exactly as if the body had run.
     */
    public fun skippableNode(
        componentId: String,
        inputs: List<Any?> = emptyList(),
        factory: () -> Node,
    ): Node {
        val frame = currentFrame
        val hit = frameSkipHit(frame, inputs)
        if (hit != null) {
            frame.markContentsKept(slotGeneration)
            hit.recordDependencies()
            runtime.record(JournalKind.Skipped, "component skipped", mapOf("componentId" to componentId))
            return hit.nodes[0]
        }
        val captured = captureRender { listOf(factory()) }
        frame.skipCache = if (captured.memoizable) {
            FrameSkipCache(
                inputs = inputs,
                nodes = captured.nodes,
                dependencyCells = captured.dependencyCells,
                dependencyVersions = captured.dependencyVersions,
                contextReads = captured.contextReads,
                certifiedKeyedHost = false,
            )
        } else {
            null
        }
        return captured.nodes[0]
    }

    public suspend fun skippableSuspendNode(
        componentId: String,
        inputs: List<Any?> = emptyList(),
        factory: suspend () -> Node,
    ): Node {
        val frame = currentFrame
        val hit = frameSkipHit(frame, inputs)
        if (hit != null) {
            frame.markContentsKept(slotGeneration)
            hit.recordDependencies()
            runtime.record(JournalKind.Skipped, "component skipped", mapOf("componentId" to componentId))
            return hit.nodes[0]
        }
        val capture = FrameCaptureState()
        val dependencies = linkedSetOf<ObservableCell<*>>()
        frameCaptures += capture
        val node = try {
            ReadTracking.collectSuspend(
                observer = { cell ->
                    if (cell is ObservableCell<*>) {
                        dependencies += cell
                    }
                },
                block = factory,
            )
        } finally {
            frameCaptures.removeAt(frameCaptures.lastIndex)
        }
        dependencies.forEach(ReadTracking::record)
        frame.skipCache = if (capture.memoizable) {
            val dependencyCells = dependencies.toTypedArray()
            FrameSkipCache(
                inputs = inputs,
                nodes = listOf(node),
                dependencyCells = dependencyCells,
                dependencyVersions = LongArray(dependencyCells.size) { index ->
                    dependencyCells[index].version
                },
                contextReads = capture.contextReads ?: emptyList(),
                certifiedKeyedHost = false,
            )
        } else {
            null
        }
        return node
    }

    public fun renderNode(content: ComponentScope.() -> Unit): Node =
        collect(content).toNode()

    public suspend fun renderSuspendNode(content: suspend ComponentScope.() -> Unit): Node {
        nodeStack.add(mutableListOf())
        regionStack.add(null)
        return try {
            content()
            regionStack.removeAt(regionStack.lastIndex)
            nodeStack.removeAt(nodeStack.lastIndex).toNode()
        } catch (error: Throwable) {
            regionStack.removeAt(regionStack.lastIndex)
            nodeStack.removeAt(nodeStack.lastIndex)
            throw error
        }
    }

    internal fun collect(content: ComponentScope.() -> Unit): List<Node> {
        nodeStack.add(mutableListOf())
        regionStack.add(null)
        return try {
            content()
            lastCollectedRegions = regionStack.removeAt(regionStack.lastIndex) ?: emptyList()
            nodeStack.removeAt(nodeStack.lastIndex)
        } catch (error: Throwable) {
            regionStack.removeAt(regionStack.lastIndex)
            nodeStack.removeAt(nodeStack.lastIndex)
            throw error
        }
    }

    internal fun collectExitGroup(state: ExitGroupState, content: ComponentScope.() -> Unit): List<Node> {
        exitGroupStack += state
        return try {
            collect(content)
        } finally {
            exitGroupStack.removeAt(exitGroupStack.lastIndex)
        }
    }

    internal fun exitGroupState(key: String): ExitGroupState =
        exitGroups.getOrPut(key) { ExitGroupState(key) }
            .also { markEachCapturesUnsafe() }

    internal fun currentExitGroup(): ExitGroupState? =
        exitGroupStack.lastOrNull()

    internal fun currentErrorBoundary(): ErrorBoundaryState? =
        errorBoundaryStack.lastOrNull()

    internal fun <T> withErrorBoundary(state: ErrorBoundaryState, content: ComponentScope.() -> T): T {
        markEachCapturesUnsafe()
        errorBoundaryStack += state
        try {
            return content()
        } finally {
            errorBoundaryStack.removeAt(errorBoundaryStack.lastIndex)
        }
    }

    public fun keyed(key: Any, content: ComponentScope.() -> Unit) {
        throw MissingKineticaPluginException("keyed")
    }

    public suspend fun suspendKeyed(key: Any, content: suspend ComponentScope.() -> Unit) {
        throw MissingKineticaPluginException("suspendKeyed")
    }

    /**
     * Frame-native `keyed`; called by compiler-generated code with a static child [ordinal].
     * The legacy key scope is still pushed so string-keyed caches (skippable, static nodes)
     * stay disambiguated per key while both storage models coexist.
     */
    public fun keyedRegion(ordinal: Int, key: Any, content: ComponentScope.() -> Unit) {
        enterKeyedChildFrame(ordinal, key)
        try {
            content()
        } finally {
            exitFrame()
        }
    }

    /** Frame-native `suspendKeyed`; called by compiler-generated code. */
    public suspend fun suspendKeyedRegion(ordinal: Int, key: Any, content: suspend ComponentScope.() -> Unit) {
        enterKeyedChildFrame(ordinal, key)
        try {
            content()
        } finally {
            exitFrame()
        }
    }

    internal fun resourceCacheNamespace(scope: CacheScope): ResourceCacheNamespace =
        when (scope) {
            CacheScope.App -> ResourceCacheNamespace(scope, runtime.appResourceCacheId)
            CacheScope.Request -> ResourceCacheNamespace(scope, runtime.requestResourceCacheId)
            CacheScope.Component -> {
                val frame = currentFrame
                val owner = frame.resourceNamespace
                    ?: "res:$instanceId:${frameBoundaryCounter++}".also { frame.resourceNamespace = it }
                ResourceCacheNamespace(scope, owner)
            }
        }

    internal fun disposeKeyedScope(key: Any, keepPersistentSlots: Boolean) {
        rootFrame.removeKeyedDescendants(key, keepPersistentSlots, runtime)
    }

    public fun completeExit(key: Any): Boolean {
        val state = exitGroups[key.toString()] ?: return false
        val completed = synchronizedOn(state.stateLock) {
            if (state.phase != ExitPhase.Leaving) {
                false
            } else {
                state.phase = ExitPhase.Disposed
                state.retained = null
                state.pendingCallbacks = 0
                state.callbacks.clear()
                true
            }
        }
        if (!completed) {
            return false
        }
        state.cancelTasks()
        runtime.record(JournalKind.Leaving, "exit completed", mapOf("key" to key.toString()))
        runtime.invalidate("leaving")
        return true
    }

    internal fun completeExitCallback(key: Any, generation: Int): Boolean {
        val state = exitGroups[key.toString()] ?: return false
        var remaining = 0
        var shouldComplete = false
        val completedCallback = synchronizedOn(state.stateLock) {
            if (state.phase != ExitPhase.Leaving || state.generation != generation) {
                false
            } else if (state.pendingCallbacks <= 0) {
                false
            } else {
                state.pendingCallbacks -= 1
                remaining = state.pendingCallbacks
                if (remaining == 0) {
                    state.phase = ExitPhase.Disposed
                    state.retained = null
                    state.callbacks.clear()
                    shouldComplete = true
                }
                true
            }
        }
        if (!completedCallback) {
            return false
        }
        runtime.record(
            JournalKind.Leaving,
            "onExit completed",
            mapOf("key" to key.toString(), "remaining" to remaining.toString()),
        )
        return if (shouldComplete) {
            state.cancelTasks()
            runtime.record(JournalKind.Leaving, "exit completed", mapOf("key" to key.toString()))
            runtime.invalidate("leaving")
            true
        } else {
            runtime.invalidate("leaving")
            true
        }
    }

    public fun isLeaving(key: Any): Boolean {
        markEachCapturesUnsafe()
        return exitGroups[key.toString()]?.phase == ExitPhase.Leaving
    }

    public fun dispose() {
        rootFrame.dispose(runtime)
        currentFrame = rootFrame
        enteredFrames.clear()
        slotIdCells.clear()
        pendingRestoredValues.clear()
        contexts.clear()
        exitGroups.values.forEach { state -> state.cancelTasks() }
        exitGroups.clear()
        errorBoundaryStack.clear()
        staticNodes.clear()
        exitGroupStack.clear()
        layoutEffects.clear()
        postCommitEffects.clear()
        nodeStack.clear()
        nodeStack.add(mutableListOf())
        regionStack.clear()
        regionStack.add(null)
        lastCollectedRegions = emptyList()
        coroutineScope.cancel()
    }

    // --- Persistence: SlotId-addressed cells registered at slot creation ---

    private class SlotIdCellEntry(
        val cell: MutableCellImpl<*>,
        val persistent: Boolean,
        val transient: Boolean,
    )

    private val slotIdCells = HashMap<SlotId, SlotIdCellEntry>()
    private val pendingRestoredValues = HashMap<SlotId, Any?>()

    public fun containsSlot(slotId: SlotId): Boolean =
        slotId in slotIdCells || slotId in pendingRestoredValues

    public fun <T> readSlot(slotId: SlotId): T? {
        val entry = slotIdCells[slotId]
        @Suppress("UNCHECKED_CAST")
        return if (entry != null) entry.cell.value as T? else pendingRestoredValues[slotId] as T?
    }

    /**
     * Writes a value under a durable slot address. Before the owning component has
     * rendered, the value parks in a restore buffer that seeds the cell on creation.
     */
    public fun <T> writeSlot(
        slotId: SlotId,
        value: T,
        policy: EqualityPolicy<T> = EqualityPolicy.structural(),
        persistent: Boolean = true,
        transient: Boolean = false,
    ) {
        val entry = slotIdCells[slotId]
        if (entry != null) {
            @Suppress("UNCHECKED_CAST")
            (entry.cell as MutableCellImpl<T>).value = value
        } else {
            pendingRestoredValues[slotId] = value
        }
    }

    internal fun registerSlotIdCell(
        slotId: SlotId,
        cell: MutableCellImpl<*>,
        persistent: Boolean,
        transient: Boolean,
        ordinal: Int,
    ) {
        slotIdCells[slotId] = SlotIdCellEntry(cell, persistent, transient)
        if (persistent) {
            currentFrame.markPersistent(ordinal)
        }
    }

    internal fun hasPendingRestoredValue(slotId: SlotId): Boolean =
        slotId in pendingRestoredValues

    /** Returns and clears the parked restore value for [slotId]. */
    internal fun takePendingRestoredValue(slotId: SlotId): Any? =
        pendingRestoredValues.remove(slotId)

    internal fun recordStateWrite() {
        // A cell write while a subtree is being captured means it renders with side
        // effects; replaying its cached output would skip the write.
        markEachCapturesUnsafe()
    }

    public fun persistentSlotIds(): List<SlotId> =
        slotIdCells.mapNotNull { (slotId, entry) -> slotId.takeIf { entry.persistent } }

    internal fun slotSnapshot(sequence: Long): SlotSnapshot =
        SlotSnapshot(
            sequence = sequence,
            slots = slotIdCells.entries
                .sortedBy { it.key.stableKey() }
                .map { (slotId, entry) ->
                    SlotSnapshotEntry(
                        key = slotId.stableKey(),
                        slotId = slotId,
                        persistent = entry.persistent,
                        transient = entry.transient,
                        value = entry.cell.value?.toString() ?: "null",
                    )
                },
        )

    internal fun <T> withContextValue(context: Context<T>, value: T, content: ComponentScope.() -> Unit) {
        // Context reads recorded by a row capture are validated against the AMBIENT stack
        // on later renders; a provide inside the row itself would make them incomparable.
        markEachCapturesUnsafe()
        val stack = contexts.getOrPut(context) { mutableListOf() }
        stack += value
        try {
            content()
        } finally {
            stack.removeAt(stack.lastIndex)
            if (stack.isEmpty()) {
                contexts.remove(context)
            }
        }
    }

    internal fun <T> readContext(context: Context<T>): T {
        @Suppress("UNCHECKED_CAST")
        val value = contexts[context]?.lastOrNull() as T? ?: context.default
        if (frameCaptures.isNotEmpty()) {
            val read = FrameContextRead(context, value)
            for (capture in frameCaptures) {
                capture.recordContextRead(read)
            }
        }
        return value
    }

    private fun List<Node>.toNode(): Node =
        when (size) {
            0 -> FragmentNode()
            1 -> single()
            else -> FragmentNode(this)
        }

    // --- Frame-native memoization: caches validated by cell versions + context reads ---

    private val frameCaptures = mutableListOf<FrameCaptureState>()

    /**
     * Capture-unsafe marker: the subtree rendered something cached replay cannot
     * validate (effects, resources, boundaries, context provides, cell writes), so every
     * capture currently in flight is disqualified from storing a cache.
     */
    internal fun markEachCapturesUnsafe() {
        for (capture in frameCaptures) {
            capture.memoizable = false
        }
    }

    private fun contextReadsUnchanged(reads: List<FrameContextRead>): Boolean =
        reads.all { read -> ambientContextValue(read.context) == read.value }

    private fun ambientContextValue(context: Context<*>): Any? =
        contexts[context]?.lastOrNull() ?: context.default

    private class CapturedRender(
        val nodes: List<Node>,
        val dependencyCells: Array<ObservableCell<*>>,
        val dependencyVersions: LongArray,
        val contextReads: List<FrameContextRead>,
        val memoizable: Boolean,
    )

    private fun captureRender(block: () -> List<Node>): CapturedRender {
        val capture = FrameCaptureState()
        val dependencies = linkedSetOf<ObservableCell<*>>()
        frameCaptures += capture
        val nodes = try {
            ReadTracking.collect(
                observer = { cell ->
                    if (cell is ObservableCell<*>) {
                        dependencies += cell
                    }
                },
                block = block,
            )
        } finally {
            frameCaptures.removeAt(frameCaptures.lastIndex)
        }
        // Reads stay visible to the enclosing render/capture collectors.
        dependencies.forEach(ReadTracking::record)
        val dependencyCells = dependencies.toTypedArray()
        return CapturedRender(
            nodes = nodes,
            dependencyCells = dependencyCells,
            dependencyVersions = LongArray(dependencyCells.size) { index ->
                dependencyCells[index].version
            },
            contextReads = capture.contextReads ?: emptyList(),
            memoizable = capture.memoizable,
        )
    }

    private fun frameSkipHit(frame: Frame, inputs: List<Any?>): FrameSkipCache? {
        val cache = frame.skipCache ?: return null
        if (cache.inputs != inputs) return null
        if (!cache.dependenciesUnchanged()) return null
        if (!contextReadsUnchanged(cache.contextReads)) return null
        return cache
    }

    internal fun <T> renderEachRegion(
        ordinal: Int,
        rowTable: FrameTable,
        items: Iterable<T>,
        key: (T) -> Any,
        memoize: Boolean,
        content: ComponentScope.(T) -> Unit,
    ) {
        // Per-render bookkeeping (row eviction) must run every render, so an enclosing
        // capture cannot cache across this construct; rows still memoize independently.
        markEachCapturesUnsafe()
        val snapshot = keyedLastWins(items, key)
        val outFrame = nodeStack.last()
        val frameStart = outFrame.size
        val seen = HashSet<Any>(snapshot.size)
        var allCertified = true
        var reused = 0
        for (keyed in snapshot) {
            seen += keyed.key
            val rowKey = keyed.key.toString()
            val rowFrame = currentFrame.enterKeyedChild(ordinal, keyed.key, table = rowTable, generation = slotGeneration)
            val hit = if (memoize) frameSkipHit(rowFrame, listOf(keyed.item)) else null
            val rowCertified: Boolean
            if (hit != null) {
                outFrame += hit.nodes
                rowFrame.markKept(slotGeneration)
                rowFrame.markContentsKept(slotGeneration)
                hit.recordDependencies()
                reused += 1
                rowCertified = hit.certifiedKeyedHost
            } else {
                enterFrame(rowFrame)
                val captured = try {
                    captureRender { collect { content(keyed.item) } }
                } finally {
                    exitFrame()
                }
                outFrame += captured.nodes
                rowCertified = captured.nodes.size == 1 &&
                    captured.nodes[0].reconcileKey == rowKey
                rowFrame.skipCache = if (memoize && captured.memoizable) {
                    FrameSkipCache(
                        inputs = listOf(keyed.item),
                        nodes = captured.nodes,
                        dependencyCells = captured.dependencyCells,
                        dependencyVersions = captured.dependencyVersions,
                        contextReads = captured.contextReads,
                        certifiedKeyedHost = rowCertified,
                    )
                } else {
                    null
                }
            }
            allCertified = allCertified && rowCertified
        }
        val top = regionStack.lastIndex
        val frame = regionStack[top] ?: mutableListOf<ChildRegion>().also { regionStack[top] = it }
        frame += ChildRegion(ordinal, frameStart, outFrame.size)
        recordKeyedEmission(outFrame, frameStart, allCertified && snapshot.isNotEmpty())
        currentFrame.keyedChildKeys(ordinal).forEach { existingKey ->
            if (existingKey !in seen) {
                currentFrame.removeKeyedChild(ordinal, existingKey, runtime)
            }
        }
        if (reused > 0) {
            runtime.record(
                JournalKind.Skipped,
                "each rows reused",
                mapOf("reused" to reused.toString(), "total" to snapshot.size.toString()),
            )
        }
    }

    private fun recordKeyedEmission(frame: MutableList<Node>, frameStart: Int, certified: Boolean) {
        if (frameStart == 0 && certified && frame.isNotEmpty()) {
            keyedEmissionFrame = frame
            keyedEmissionEnd = frame.size
        } else {
            keyedEmissionFrame = null
        }
    }

    /**
     * True iff [children] is exactly the frame a certified each() just filled — every child
     * keyed by its unique row key, nothing emitted before or after. Consumed once.
     */
    internal fun consumeKeyedChildren(children: List<Node>): Boolean {
        val hit = keyedEmissionFrame === children && keyedEmissionEnd == children.size
        keyedEmissionFrame = null
        return hit
    }

}

public fun ComponentScope.disposeKeyScope(
    key: Any,
    keepPersistentSlots: Boolean = false,
) {
    disposeKeyedScope(key, keepPersistentSlots)
}

public fun <T> ComponentScope.state(
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
    persistent: Boolean = false,
    transient: Boolean = false,
    ordinal: Int = -1,
    initial: () -> T,
): MutableCell<T> {
    if (ordinal < 0) throw MissingKineticaPluginException("state")
    return stateCellSlot(ordinal, policy, persistent, transient, initial)
}

private fun <T> ComponentScope.stateCellSlot(
    ordinal: Int,
    policy: EqualityPolicy<T>,
    persistent: Boolean,
    transient: Boolean,
    initial: () -> T,
): MutableCellImpl<T> {
    if (transient) {
        markEachCapturesUnsafe()
    }
    val existing = frameSlotValueOrNull(ordinal)
    if (existing != null) {
        touchFrameSlot(ordinal, transient)
        @Suppress("UNCHECKED_CAST")
        return existing as MutableCellImpl<T>
    }
    return frameSlotTouch(
        ordinal = ordinal,
        transient = transient,
        value = StateCellImpl(
            initial = initial(),
            policy = policy,
            scope = this,
            slotLabel = "slot:$ordinal",
            persistent = persistent,
            transient = transient,
        ),
    )
}

private fun <T> ComponentScope.stateCellSlot(
    ordinal: Int,
    slotId: SlotId,
    policy: EqualityPolicy<T>,
    persistent: Boolean,
    transient: Boolean,
    initial: () -> T,
): MutableCellImpl<T> {
    if (transient) {
        markEachCapturesUnsafe()
    }
    val existing = frameSlotValueOrNull(ordinal)
    if (existing != null) {
        touchFrameSlot(ordinal, transient)
        @Suppress("UNCHECKED_CAST")
        return existing as MutableCellImpl<T>
    }
    return frameSlotTouch(
        ordinal = ordinal,
        transient = transient,
        value = StateCellImpl(
            initial = if (hasPendingRestoredValue(slotId)) {
                @Suppress("UNCHECKED_CAST")
                takePendingRestoredValue(slotId) as T
            } else {
                initial()
            },
            policy = policy,
            scope = this,
            slotLabel = slotId.stableKey(),
            persistent = persistent,
            transient = transient,
        ),
    )
}

private class StateCellImpl<T>(
    initial: T,
    policy: EqualityPolicy<T>,
    private val scope: ComponentScope,
    private val slotLabel: String,
    private val persistent: Boolean,
    private val transient: Boolean,
) : MutableCellImpl<T>(initial, policy) {
    override fun onCommittedWrite(next: T) {
        scope.recordStateWrite()
        if (scope.runtime.isRecording) {
            scope.runtime.record(
                JournalKind.CellWrite,
                "cell write",
                mapOf(
                    "slot" to slotLabel,
                    "persistent" to persistent.toString(),
                    "transient" to transient.toString(),
                    "value" to next.toString(),
                ),
            )
        }
        scope.runtime.invalidate("cell write")
    }
}

public fun <T> ComponentScope.state(
    slotId: SlotId,
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
    persistent: Boolean = false,
    transient: Boolean = false,
    ordinal: Int = -1,
    initial: () -> T,
): MutableCell<T> {
    if (ordinal < 0) throw MissingKineticaPluginException("state")
    val cell = stateCellSlot(ordinal, slotId, policy, persistent, transient, initial)
    registerSlotIdCell(slotId, cell, persistent, transient, ordinal)
    return cell
}

public fun <T> ComponentScope.derived(
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
    ordinal: Int = -1,
    compute: () -> T,
): Cell<T> {
    // Back derived{} with a slot, exactly like state(): the DerivedCell is allocated once
    // and reused across renders, so its lazy cache and version counter survive instead of
    // restarting from zero on every render. The compute closure is refreshed because it
    // may capture render-local inputs from the current invocation.
    if (ordinal < 0) throw MissingKineticaPluginException("derived")
    val cell = frameSlot(ordinal) { DerivedCell(policy, compute) }
    cell.updateDefinition(policy, compute)
    return cell
}

public class Context<T> internal constructor(
    internal val default: T,
    public val name: String?,
)

public fun <T> context(default: T, name: String? = null): Context<T> = Context(default, name)

public fun <T> ComponentScope.provide(context: Context<T>, value: T, content: ComponentScope.() -> Unit) {
    withContextValue(context, value, content)
}

public fun <T> ComponentScope.read(context: Context<T>): T = readContext(context)

/**
 * Renders [items] as a keyed list. Each row's output is memoized per key: when the item is
 * `==` to the previous render's, every cell the row read reports an unchanged version, and
 * every context value it read is `==` unchanged, the row emits the SAME [Node] references —
 * so the retained renderer skips the whole subtree with one identity comparison.
 *
 * The contract is that [content] is a pure function of the item, the cells it reads, and
 * the context values it reads. Rows that read other ambient state during the render
 * (time, randomness, mutable singletons) must pass `memoize = false`. Rows that use
 * effects, resources, boundaries, exit groups, `provide`, or nested list constructs are
 * detected and rebuilt every render automatically.
 */
public fun <T> ComponentScope.each(
    items: Iterable<T>,
    key: (T) -> Any,
    memoize: Boolean = true,
    content: ComponentScope.(T) -> Unit,
) {
    throw MissingKineticaPluginException("each")
}

/**
 * Frame-native `each`; called by compiler-generated code with a static child [ordinal].
 * Every row renders into its own keyed frame with a per-row memoization cache; rows whose
 * keys left the list are disposed at the end of the pass (state does not resurrect when a
 * key returns).
 */
public fun <T> ComponentScope.eachRegion(
    ordinal: Int,
    rowTable: FrameTable,
    items: Iterable<T>,
    key: (T) -> Any,
    memoize: Boolean = true,
    content: ComponentScope.(T) -> Unit,
) {
    renderEachRegion(ordinal, rowTable, items, key, memoize, content)
}

public interface LazyItems<out T> : Iterable<T> {
    public val estimatedSize: Int?
}

public fun <T> lazyItems(items: Iterable<T>, estimatedSize: Int? = null): LazyItems<T> =
    object : LazyItems<T> {
        override val estimatedSize: Int? = estimatedSize
        override fun iterator(): Iterator<T> = items.iterator()
    }

public enum class RetainPolicy {
    Keyed,
    VisibleOnly,
    PersistentSlots,
}

@Serializable
public data class LazyListState(
    val firstVisibleIndex: Int = 0,
    val visibleCount: Int? = null,
) {
    init {
        require(firstVisibleIndex >= 0) { "firstVisibleIndex must be non-negative." }
        require(visibleCount == null || visibleCount >= 0) { "visibleCount must be non-negative when provided." }
    }

    public fun visibleRange(totalSize: Int): IntRange {
        if (totalSize <= 0) {
            return IntRange.EMPTY
        }
        val start = firstVisibleIndex.coerceAtMost(totalSize)
        val endExclusive = when (visibleCount) {
            null -> totalSize
            else -> (start + visibleCount).coerceAtMost(totalSize)
        }
        return if (start >= endExclusive) IntRange.EMPTY else start until endExclusive
    }

    public fun scrollTo(
        firstVisibleIndex: Int,
        visibleCount: Int? = this.visibleCount,
    ): LazyListState =
        copy(firstVisibleIndex = firstVisibleIndex, visibleCount = visibleCount)
}

public fun lazyListState(
    firstVisibleIndex: Int = 0,
    visibleCount: Int? = null,
): LazyListState = LazyListState(firstVisibleIndex, visibleCount)

public fun <T> ComponentScope.lazyEach(
    items: LazyItems<T>,
    key: (T) -> Any,
    retain: RetainPolicy = RetainPolicy.Keyed,
    state: LazyListState = LazyListState(),
    placeholder: ComponentScope.(T) -> Unit = {},
    content: ComponentScope.(T) -> Unit,
) {
    throw MissingKineticaPluginException("lazyEach")
}

/**
 * Frame-native `lazyEach`; called by compiler-generated code. Visible rows render into
 * keyed frames; hidden rows are retained, disposed, or deactivated (transients dropped,
 * state kept) according to [retain].
 */
public fun <T> ComponentScope.lazyEachRegion(
    ordinal: Int,
    rowTable: FrameTable,
    items: LazyItems<T>,
    key: (T) -> Any,
    retain: RetainPolicy = RetainPolicy.Keyed,
    state: LazyListState = LazyListState(),
    placeholder: ComponentScope.(T) -> Unit = {},
    content: ComponentScope.(T) -> Unit,
) {
    markEachCapturesUnsafe()
    val snapshot = keyedLastWins(items, key)
    val visibleRange = state.visibleRange(snapshot.size)
    val visibleKeys = mutableSetOf<Any>()
    val frameStart = currentNodeFrameSize()
    runtime.record(
        JournalKind.RenderStarted,
        "lazyEach",
        mapOf(
            "retain" to retain.name,
            "firstVisibleIndex" to state.firstVisibleIndex.toString(),
            "visibleCount" to state.visibleCount.toString(),
            "estimatedSize" to items.estimatedSize.toString(),
        ),
    )
    snapshot.forEachIndexed { index, keyed ->
        if (index in visibleRange) {
            visibleKeys += keyed.key
            enterKeyedChildFrame(ordinal, keyed.key, rowTable)
            try {
                content(keyed.item)
            } catch (pending: ResourcePendingException) {
                runtime.record(
                    JournalKind.ResourceLoad,
                    "lazyEach item pending",
                    mapOf("key" to keyed.key.toString(), "resource" to pending.key),
                )
                placeholder(keyed.item)
            } finally {
                exitFrame()
            }
        }
    }
    recordChildRegion(ordinal, frameStart)
    when (retain) {
        RetainPolicy.Keyed -> Unit
        RetainPolicy.VisibleOnly -> keyedChildFrameKeys(ordinal).forEach { existingKey ->
            if (existingKey !in visibleKeys) {
                disposeKeyedChildFrame(ordinal, existingKey)
            }
        }
        RetainPolicy.PersistentSlots -> keyedChildFrameKeys(ordinal).forEach { existingKey ->
            if (existingKey !in visibleKeys) {
                stripKeyedChildFrameForPersistence(ordinal, existingKey)
            }
        }
    }
}

private data class KeyedItem<T>(
    val key: Any,
    val item: T,
)

private fun <T> ComponentScope.keyedLastWins(
    items: Iterable<T>,
    key: (T) -> Any,
): List<KeyedItem<T>> {
    val estimatedSize = (items as? Collection<*>)?.size ?: 10
    val keyedItems = ArrayList<KeyedItem<T>>(estimatedSize)
    val seenKeys = HashSet<Any>(estimatedSize)
    items.forEach { item ->
        val itemKey = key(item)
        if (!seenKeys.add(itemKey)) {
            duplicateKey(itemKey)
            if (!runtime.debug) {
                val previousIndex = keyedItems.indexOfFirst { keyed -> keyed.key == itemKey }
                if (previousIndex >= 0) {
                    keyedItems.removeAt(previousIndex)
                }
            }
        }
        keyedItems += KeyedItem(itemKey, item)
    }
    return keyedItems
}

private fun ComponentScope.duplicateKey(key: Any) {
    runtime.warn(
        code = "duplicate-key",
        message = "duplicate key",
        attributes = mapOf("key" to key.toString()),
    )
    runtime.record(JournalKind.Skipped, "duplicate key", mapOf("key" to key.toString()))
    if (runtime.debug) {
        error("Duplicate key: $key")
    }
}


internal class FrameContextRead(
    val context: Context<*>,
    val value: Any?,
)

internal class FrameCaptureState {
    var memoizable: Boolean = true
    var contextReads: MutableList<FrameContextRead>? = null

    fun recordContextRead(read: FrameContextRead) {
        val list = contextReads ?: mutableListOf<FrameContextRead>().also { contextReads = it }
        list += read
    }
}

internal class FrameSkipCache(
    val inputs: List<Any?>,
    val nodes: List<Node>,
    val dependencyCells: Array<ObservableCell<*>>,
    val dependencyVersions: LongArray,
    val contextReads: List<FrameContextRead>,
    val certifiedKeyedHost: Boolean,
) {
    fun dependenciesUnchanged(): Boolean {
        for (i in dependencyCells.indices) {
            if (dependencyCells[i].version != dependencyVersions[i]) return false
        }
        return true
    }

    fun recordDependencies() {
        dependencyCells.forEach(ReadTracking::record)
    }
}
