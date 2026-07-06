package io.heapy.kinetica

import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/**
 * Discriminates every slot-consuming construct in its key, so two different constructs can
 * never address the same slot even when divergent render paths (boundary fallback vs content,
 * if/else branches) consume the same positional cursor sequence. Suffixes are folded into the
 * key inside the key factories — callers never concatenate them — so a future per-scope key
 * interner can key on (kind, cursor) directly.
 */
internal enum class SlotKind(val suffix: String) {
    State(":state"),
    Derived(":derived"),
    ErrorBoundary(":errb"),
    LoadingBoundary(":loadb"),
    SuspendSubtree(":suspend"),
    Frame(":frame"),
    HostRef(":ref"),
    Handle(":handle"),
    Launch(":launch"),
    Watch(":watch"),
    TypedEvent(""),
    UnitEvent(":unit"),
    HostEvent(""),
}

/** A slot key with its scope-relative local form, for deriving stable sub-scope names. */
internal class SlotKeyPair(val key: String, val local: String)

/** Snapshot of the positional cursors, used to make boundaries cursor-neutral for siblings. */
internal class CursorMark(val slot: Int, val event: Int, val effect: Int)

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

public data class SlotMetadata(
    val key: String,
    val slotId: SlotId?,
    val persistent: Boolean,
    val transient: Boolean,
) {
    internal var touchedGeneration: Int = 0
}

public class ComponentScope public constructor(
    internal val runtime: KineticaRuntime = KineticaRuntime(),
    internal val instanceId: String = "root",
) {
    private val nodeStack = mutableListOf<MutableList<Node>>(mutableListOf())
    private val slots = mutableMapOf<String, Any?>()
    private val slotMetadata = mutableMapOf<String, SlotMetadata>()
    private val hostEvents = mutableMapOf<String, HostEventEntry>()
    private val contexts = mutableMapOf<Context<*>, MutableList<Any?>>()
    private val exitGroups = mutableMapOf<String, ExitGroupState>()
    private val errorBoundaryStack = mutableListOf<ErrorBoundaryState>()
    private val staticNodes = mutableMapOf<String, Node>()
    private val skippableNodes = mutableMapOf<String, SkippableNodeCache>()
    private val eachCaches = mutableMapOf<String, EachCallsiteCache>()
    private val eachOrdinals = mutableMapOf<String, Int>()
    private val eachCaptures = mutableListOf<EachRowCapture>()
    // Set by renderEach when an each() filled the current collect frame from index 0 with
    // rows certified as "exactly one HostNode keyed by the row key"; consumed by host() to
    // stamp NodeFlags.CHILDREN_KEYED. Identity-matched against the frame list, so a stale
    // record can never certify someone else's children.
    private var keyedEmissionFrame: MutableList<Node>? = null
    private var keyedEmissionEnd = 0
    private val exitGroupStack = mutableListOf<ExitGroupState>()
    // The joined key-scope prefix is maintained incrementally: it is read several times
    // per row (slot/event/effect keys, callsite ids), and rejoining the stack per read
    // was a top allocation source on list-heavy renders.
    private val keyScopeParents = mutableListOf<String>()
    private var keyScopePrefixValue = ""
    private var slotCursor = 0
    private var eventCursor = 0
    private var effectCursor = 0
    private var slotGeneration = 0
    private var eventGeneration = 0
    private val resourceKeyCounts = mutableMapOf<String, Int>()
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

    internal fun <T> frameSlot(ordinal: Int, transient: Boolean = false, initial: () -> T): T =
        currentFrame.slot(ordinal, slotGeneration, transient, initial)

    internal fun frameSlotValueOrNull(ordinal: Int): Any? =
        currentFrame.slotValueOrNull(ordinal)

    /** Stores [value] in the slot, replacing any previous holder (identity-keyed slots). */
    internal fun <T> frameSlotTouch(ordinal: Int, transient: Boolean, value: T): T {
        currentFrame.setSlotValue(ordinal, slotGeneration, transient, value)
        return value
    }

    internal fun enterKeyedChildFrame(ordinal: Int, key: Any) {
        enterFrame(currentFrame.enterKeyedChild(ordinal, key, table = null, generation = slotGeneration))
    }

    internal fun enterFixedChildFrame(ordinal: Int) {
        enterFrame(currentFrame.enterFixedChild(ordinal, table = null, generation = slotGeneration))
    }

    internal fun keyedChildFrameKeys(ordinal: Int): Set<Any> =
        currentFrame.keyedChildKeys(ordinal)

    internal fun disposeKeyedChildFrame(ordinal: Int, key: Any) {
        currentFrame.removeKeyedChild(ordinal, key, runtime)
    }

    private var frameBoundaryCounter = 0

    internal fun nextFrameBoundaryId(): String = "$instanceId:fb${frameBoundaryCounter++}"

    internal fun frameEvent(ordinal: Int, role: Int = EVENT_ROLE_PRIMARY, callback: (Any?) -> Unit): String =
        currentFrame.event(ordinal, role, slotGeneration, runtime, callback)

    internal fun beginRender() {
        slotCursor = 0
        eventCursor = 0
        effectCursor = 0
        slotGeneration += 1
        eventGeneration += 1
        enteredFrames.clear()
        rootFrame.markEntered(slotGeneration)
        enteredFrames += rootFrame
        currentFrame = rootFrame
        ordinalStackSize = 0
        eachOrdinals.clear()
        eachCaptures.clear()
        resourceKeyCounts.clear()
        layoutEffects.clear()
        postCommitEffects.clear()
        nodeStack.clear()
        nodeStack.add(mutableListOf())
    }

    internal fun commitRender(): Node {
        val expiredTransientSlots = slotMetadata.values
            .filter { it.transient && it.touchedGeneration != slotGeneration }
            .map { it.key }
        expiredTransientSlots.forEach(::removeSlot)
        evictUntouchedHostEvents()
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

    public fun staticNode(
        hoistId: String,
        factory: () -> Node,
    ): Node =
        staticNodes.getOrPut(hoistId, factory)

    public fun skippableNode(
        componentId: String,
        inputs: List<Any?> = emptyList(),
        factory: () -> Node,
    ): Node {
        val cacheKey = scopedComponentCacheKey(componentId)
        val cache = skippableNodes[cacheKey]
        if (cache != null && skippableHitLocked(cache, inputs)) {
            emitSkippableHit(cache, componentId)
            return cache.node
        }
        val capture = captureSkippableDependencies(factory)
        storeSkippableCapture(cacheKey, inputs, capture)
        return capture.node
    }

    public suspend fun skippableSuspendNode(
        componentId: String,
        inputs: List<Any?> = emptyList(),
        factory: suspend () -> Node,
    ): Node {
        val cacheKey = scopedComponentCacheKey(componentId)
        val cache = skippableNodes[cacheKey]
        if (cache != null && skippableHitLocked(cache, inputs)) {
            emitSkippableHit(cache, componentId)
            return cache.node
        }
        val capture = captureSkippableDependencies(factory)
        storeSkippableCapture(cacheKey, inputs, capture)
        return capture.node
    }

    // Same contract each-row memoization has proven in production: equal inputs + unchanged
    // cell dependencies + unchanged context values + all captured host events still
    // registered. (The old global stateWriteVersion guard — "any state write anywhere kills
    // every skip" — is gone: it made skips unreachable in any stateful app.)
    private fun skippableHitLocked(cache: SkippableNodeCache, inputs: List<Any?>): Boolean =
        cache.inputs == inputs &&
            cache.touchedEvents.all { entry -> !entry.dead } &&
            cache.dependenciesUnchanged() &&
            contextReadsUnchanged(cache.contextReads)

    /**
     * Replays the skipped subtree's liveness into the current render and into any enclosing
     * capture frames, exactly as a memoized each row does — so a skip COMPOSES with row
     * memoization instead of disabling it: cursors advance as if the factory ran (sibling
     * positional slot/event keys stay aligned), host events are re-touched (and reported to
     * enclosing row captures), and cell/context reads stay visible to outer collectors.
     */
    private fun emitSkippableHit(cache: SkippableNodeCache, componentId: String) {
        // Frame-backed contents of the skipped subtree must stay alive across commit checks.
        currentFrame.markContentsKept(slotGeneration)
        slotCursor += cache.slotCursorDelta
        eventCursor += cache.eventCursorDelta
        for (eventEntry in cache.touchedEvents) {
            touchHostEvent(eventEntry)
            for (capture in eachCaptures) {
                capture.recordEvent(eventEntry)
            }
        }
        for (read in cache.contextReads) {
            for (capture in eachCaptures) {
                capture.recordContextRead(read)
            }
        }
        cache.recordDependencies()
        runtime.record(JournalKind.Skipped, "component skipped", mapOf("componentId" to componentId))
    }

    private fun storeSkippableCapture(cacheKey: String, inputs: List<Any?>, capture: SkippableCapture) {
        if (capture.memoizable) {
            skippableNodes[cacheKey] = SkippableNodeCache(
                inputs = inputs.toList(),
                node = capture.node,
                dependencies = capture.dependencies,
                touchedEvents = capture.touchedEvents,
                contextReads = capture.contextReads,
                slotCursorDelta = capture.slotCursorDelta,
                eventCursorDelta = capture.eventCursorDelta,
            )
        } else {
            // The factory did something replay-unsafe (effect, resource, nested each,
            // render-phase write, ...); a stale entry from an earlier render must not revive.
            skippableNodes.remove(cacheKey)
        }
    }

    public fun renderNode(content: ComponentScope.() -> Unit): Node =
        collect(content).toNode()

    public suspend fun renderSuspendNode(content: suspend ComponentScope.() -> Unit): Node {
        nodeStack.add(mutableListOf())
        return try {
            content()
            nodeStack.removeAt(nodeStack.lastIndex).toNode()
        } catch (error: Throwable) {
            nodeStack.removeAt(nodeStack.lastIndex)
            throw error
        }
    }

    internal fun collect(content: ComponentScope.() -> Unit): List<Node> {
        nodeStack.add(mutableListOf())
        return try {
            content()
            nodeStack.removeAt(nodeStack.lastIndex)
        } catch (error: Throwable) {
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
        withKeyScope(key, content)
    }

    public suspend fun suspendKeyed(key: Any, content: suspend ComponentScope.() -> Unit) {
        withSuspendKeyScope(key, content)
    }

    /**
     * Frame-native `keyed`; called by compiler-generated code with a static child [ordinal].
     * The legacy key scope is still pushed so string-keyed caches (skippable, static nodes)
     * stay disambiguated per key while both storage models coexist.
     */
    public fun keyedRegion(ordinal: Int, key: Any, content: ComponentScope.() -> Unit) {
        enterKeyedChildFrame(ordinal, key)
        pushKeyScope(key.toString())
        try {
            content()
        } finally {
            popKeyScope()
            exitFrame()
        }
    }

    /** Frame-native `suspendKeyed`; called by compiler-generated code. */
    public suspend fun suspendKeyedRegion(ordinal: Int, key: Any, content: suspend ComponentScope.() -> Unit) {
        enterKeyedChildFrame(ordinal, key)
        pushKeyScope(key.toString())
        try {
            content()
        } finally {
            popKeyScope()
            exitFrame()
        }
    }

    internal fun pushKeyScope(key: String) {
        keyScopeParents += keyScopePrefixValue
        keyScopePrefixValue = if (keyScopePrefixValue.isEmpty()) key else "$keyScopePrefixValue/$key"
    }

    internal fun popKeyScope() {
        keyScopePrefixValue = keyScopeParents.removeAt(keyScopeParents.lastIndex)
    }

    internal fun withKeyScope(key: Any, content: ComponentScope.() -> Unit) {
        pushKeyScope(key.toString())
        try {
            content()
        } finally {
            popKeyScope()
        }
    }

    internal suspend fun withSuspendKeyScope(key: Any, content: suspend ComponentScope.() -> Unit) {
        pushKeyScope(key.toString())
        try {
            content()
        } finally {
            popKeyScope()
        }
    }

    internal fun keyScopePrefix(extraKey: Any? = null): String =
        when {
            extraKey == null -> keyScopePrefixValue
            keyScopePrefixValue.isEmpty() -> extraKey.toString()
            else -> "$keyScopePrefixValue/$extraKey"
        }

    private fun scopedComponentCacheKey(componentId: String): String {
        val prefix = keyScopePrefix()
        return if (prefix.isEmpty()) componentId else "$prefix/$componentId"
    }

    internal fun resourceCacheNamespace(scope: CacheScope): ResourceCacheNamespace =
        when (scope) {
            CacheScope.App -> ResourceCacheNamespace(scope, runtime.appResourceCacheId)
            CacheScope.Request -> ResourceCacheNamespace(scope, runtime.requestResourceCacheId)
            CacheScope.Component -> {
                val prefix = keyScopePrefix().ifEmpty { "root" }
                ResourceCacheNamespace(scope, "$instanceId/$prefix")
            }
        }

    internal fun clearKeyScope(key: Any, keepPersistentSlots: Boolean) {
        markEachCapturesUnsafe()
        val prefix = keyScopePrefix(key)
        val prefixWithSeparator = "$prefix/"
        val removable = slots.keys.filter { slotKey ->
            slotKey == prefix || slotKey.startsWith(prefixWithSeparator)
        }.filter { slotKey ->
            !keepPersistentSlots || slotMetadata[slotKey]?.persistent != true
        }
        removable.forEach(::removeSlot)
        // Skippable caches scoped under the cleared key reference slots/events that are
        // going away with the scope — drop them with it.
        skippableNodes.keys.removeAll { cacheKey ->
            cacheKey == prefix || cacheKey.startsWith(prefixWithSeparator)
        }
    }

    public fun completeExit(key: Any): Boolean {
        val state = exitGroups[key.toString()] ?: return false
        val completed = synchronized(state.stateLock) {
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
        val completedCallback = synchronized(state.stateLock) {
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
        slots.keys.toList().forEach(::removeSlot)
        hostEvents.values.forEach { entry ->
            entry.dead = true
            runtime.removeEvent(entry.id)
        }
        hostEvents.clear()
        contexts.clear()
        exitGroups.values.forEach { state -> state.cancelTasks() }
        exitGroups.clear()
        errorBoundaryStack.clear()
        staticNodes.clear()
        skippableNodes.clear()
        eachCaches.clear()
        eachOrdinals.clear()
        eachCaptures.clear()
        exitGroupStack.clear()
        keyScopeParents.clear()
        keyScopePrefixValue = ""
        layoutEffects.clear()
        postCommitEffects.clear()
        nodeStack.clear()
        nodeStack.add(mutableListOf())
        coroutineScope.cancel()
    }

    internal fun nextSlotKey(explicitKey: String?, kind: SlotKind): String {
        val localKey = "${explicitKey ?: "slot-${slotCursor++}"}${kind.suffix}"
        val prefix = keyScopePrefix()
        val key = if (prefix.isEmpty()) localKey else "$prefix/$localKey"
        touchSlot(key)
        return key
    }

    internal fun nextSlotKeyPair(explicitKey: String?, kind: SlotKind): SlotKeyPair {
        val localKey = "${explicitKey ?: "slot-${slotCursor++}"}${kind.suffix}"
        val prefix = keyScopePrefix()
        val key = if (prefix.isEmpty()) localKey else "$prefix/$localKey"
        touchSlot(key)
        return SlotKeyPair(key = key, local = localKey)
    }

    internal fun cursorMark(): CursorMark = CursorMark(slotCursor, eventCursor, effectCursor)

    internal fun resetCursors(mark: CursorMark) {
        slotCursor = mark.slot
        eventCursor = mark.event
        effectCursor = mark.effect
    }

    internal fun nextEventKey(kind: SlotKind): String {
        val localKey = "event-${eventCursor++}${kind.suffix}"
        val prefix = keyScopePrefix()
        return if (prefix.isEmpty()) localKey else "$prefix/$localKey"
    }

    internal fun registerHostEvent(key: String, callback: (Any?) -> Unit): String {
        val existing = hostEvents[key]
        if (existing != null && !existing.dead && runtime.updateEvent(existing.id, callback)) {
            touchHostEvent(existing)
            for (capture in eachCaptures) {
                capture.recordEvent(existing)
            }
            return existing.id
        }
        val id = runtime.registerEvent(callback)
        if (existing != null) {
            existing.dead = true
        }
        val entry = HostEventEntry(id = id, touchedGeneration = eventGeneration)
        hostEvents[key] = entry
        for (capture in eachCaptures) {
            capture.recordEvent(entry)
        }
        return id
    }

    private fun evictUntouchedHostEvents() {
        val iterator = hostEvents.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.touchedGeneration != eventGeneration) {
                entry.value.dead = true
                runtime.removeEvent(entry.value.id)
                iterator.remove()
            }
        }
    }

    private fun touchHostEvent(entry: HostEventEntry) {
        entry.touchedGeneration = eventGeneration
    }

    internal fun nextEffectKey(kind: SlotKind): String {
        val localKey = "effect-${effectCursor++}${kind.suffix}"
        val prefix = keyScopePrefix()
        val key = if (prefix.isEmpty()) localKey else "$prefix/$localKey"
        touchSlot(key)
        return key
    }

    internal fun nextResourceKey(baseKey: String): String {
        // Resource states change outside the tracked cell graph (async loads, TTLs),
        // so a row that reads resources cannot be validated by cell versions alone.
        markEachCapturesUnsafe()
        val prefix = keyScopePrefix()
        val namespacedKey = if (prefix.isEmpty()) baseKey else "$prefix/$baseKey"
        val count = resourceKeyCounts.getOrElse(namespacedKey) { 0 }
        resourceKeyCounts[namespacedKey] = count + 1
        val key = if (count == 0) namespacedKey else "$namespacedKey@$count"
        touchSlot(key)
        return key
    }

    internal fun <T> checkedSlot(key: String, expected: KClass<*>, initial: () -> T): T {
        val existing = slots[key]
        if (existing != null && !expected.isInstance(existing)) {
            slotClassMismatch(key, expected, existing)
        }
        @Suppress("UNCHECKED_CAST")
        return slots.getOrPut(key) { initial() } as T
    }

    private fun slotClassMismatch(key: String, expected: KClass<*>, existing: Any) {
        val expectedName = expected.simpleName ?: "unknown"
        val actualName = existing::class.simpleName ?: "unknown"
        runtime.warn(
            code = "slot-class-mismatch",
            message = "slot class mismatch",
            attributes = mapOf("slot" to key, "expected" to expectedName, "actual" to actualName),
        )
        runtime.record(
            JournalKind.Skipped,
            "slot class mismatch",
            mapOf("slot" to key, "expected" to expectedName, "actual" to actualName),
        )
        if (runtime.debug) {
            error(
                "Slot class mismatch at '$key': expected $expectedName, found $actualName. " +
                    "Two render paths are consuming the same positional slot with different constructs — " +
                    "wrap divergent branches in keyed {} or pass explicit keys.",
            )
        }
        // Production self-heal: drop the stale holder so the caller recreates the right one.
        // Deliberately not removeSlot(): the caller registered this key's SlotMetadata just
        // before fetching, and removing it would orphan the recreated slot from eviction.
        disposeSlotValue(existing)
        slots.remove(key)
    }

    internal fun registerSlot(metadata: SlotMetadata) {
        if (metadata.transient) {
            // Transient slots (effects, suspend subtrees, transient state) expire when a
            // render does not touch them — a skipped row would silently cancel them.
            markEachCapturesUnsafe()
        }
        metadata.touchedGeneration = slotGeneration
        slotMetadata[metadata.key] = metadata
    }

    private fun touchSlot(key: String) {
        slotMetadata[key]?.touchedGeneration = slotGeneration
    }

    private fun removeSlot(key: String) {
        disposeSlotValue(slots[key])
        slots.remove(key)
        slotMetadata.remove(key)
    }

    private fun disposeSlotValue(value: Any?) {
        when (value) {
            is ManagedEffectState -> value.cancel()
            is Disposable -> value.dispose()
            is Ref<*> -> value.clear()
        }
    }

    public fun containsSlot(slotId: SlotId): Boolean =
        keyForSlotId(slotId) != null

    public fun <T> readSlot(slotId: SlotId): T? {
        val slot = slots[keyForSlotId(slotId) ?: return null] ?: return null
        val value = if (slot is Cell<*>) slot.value else slot
        @Suppress("UNCHECKED_CAST")
        return value as T?
    }

    public fun <T> writeSlot(
        slotId: SlotId,
        value: T,
        policy: EqualityPolicy<T> = EqualityPolicy.structural(),
        persistent: Boolean = true,
        transient: Boolean = false,
    ) {
        val key = keyForSlotId(slotId) ?: slotId.stableKey()
        @Suppress("UNCHECKED_CAST")
        val existing = slots[key] as? MutableCell<T>
        if (existing != null) {
            existing.value = value
        } else {
            slots[key] = MutableCellImpl(
                initial = value,
                policy = policy,
                onWrite = { _, next ->
                    recordStateWrite()
                    runtime.record(
                        JournalKind.CellWrite,
                        "cell write",
                        mapOf(
                            "slot" to key,
                            "slotId" to slotId.toString(),
                            "persistent" to persistent.toString(),
                            "transient" to transient.toString(),
                            "value" to next.toString(),
                        ),
                    )
                    runtime.invalidate("cell write")
                },
            )
        }
        slotMetadata[key] = SlotMetadata(key, slotId, persistent, transient).also { metadata ->
            metadata.touchedGeneration = slotGeneration
        }
    }

    internal fun recordStateWrite() {
        // A cell write while a row or skippable component is being captured means it renders
        // with side effects; replaying its cached nodes would skip the write.
        markEachCapturesUnsafe()
    }

    private fun keyForSlotId(slotId: SlotId): String? {
        val stableKey = slotId.stableKey()
        if (stableKey in slots) {
            return stableKey
        }
        return slotMetadata.values.firstOrNull { metadata -> metadata.slotId == slotId }?.key
    }

    internal fun migrateRestoredSlot(slotId: SlotId, scopedKey: String) {
        val stableKey = slotId.stableKey()
        if (stableKey == scopedKey || scopedKey in slots || stableKey !in slots) {
            return
        }
        slots[scopedKey] = slots.remove(stableKey)
        slotMetadata.remove(stableKey)
    }

    public fun persistentSlotIds(): List<SlotId> =
        slotMetadata.values.mapNotNull { metadata ->
            metadata.slotId.takeIf { metadata.persistent }
        }.distinct()

    internal fun slotSnapshot(sequence: Long): SlotSnapshot =
        SlotSnapshot(
            sequence = sequence,
            slots = slotMetadata.values
                .sortedBy { it.key }
                .map { metadata ->
                    val slot = slots[metadata.key]
                    val value = if (slot is Cell<*>) slot.value else slot
                    SlotSnapshotEntry(
                        key = metadata.key,
                        slotId = metadata.slotId,
                        persistent = metadata.persistent,
                        transient = metadata.transient,
                        value = value?.toString() ?: "null",
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
        if (eachCaptures.isNotEmpty()) {
            val read = EachContextRead(context, value)
            for (capture in eachCaptures) {
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

    // Runs the factory inside a capture frame on the SAME stack each rows use, so the
    // existing registration hooks (host events, context reads, transient-slot/effect/
    // render-write unsafe marks) populate it — and simultaneously flow into any enclosing
    // row frames, keeping the two caches composable.
    private fun captureSkippableDependencies(factory: () -> Node): SkippableCapture {
        val frame = EachRowCapture()
        val slotCursorBefore = slotCursor
        val eventCursorBefore = eventCursor
        val dependencies = linkedSetOf<ObservableCell<*>>()
        eachCaptures += frame
        val node = try {
            ReadTracking.collect(
                observer = { cell ->
                    if (cell is ObservableCell<*>) {
                        dependencies += cell
                    }
                },
                block = factory,
            )
        } finally {
            eachCaptures.removeAt(eachCaptures.lastIndex)
        }
        dependencies.forEach(ReadTracking::record)
        return SkippableCapture(
            node = node,
            dependencies = dependencies.associateWith { dependency -> dependency.version },
            touchedEvents = frame.touchedEvents ?: emptyList(),
            contextReads = frame.contextReads ?: emptyList(),
            slotCursorDelta = slotCursor - slotCursorBefore,
            eventCursorDelta = eventCursor - eventCursorBefore,
            memoizable = frame.memoizable,
        )
    }

    private suspend fun captureSkippableDependencies(factory: suspend () -> Node): SkippableCapture {
        val frame = EachRowCapture()
        val slotCursorBefore = slotCursor
        val eventCursorBefore = eventCursor
        val dependencies = linkedSetOf<ObservableCell<*>>()
        eachCaptures += frame
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
            eachCaptures.removeAt(eachCaptures.lastIndex)
        }
        dependencies.forEach(ReadTracking::record)
        return SkippableCapture(
            node = node,
            dependencies = dependencies.associateWith { dependency -> dependency.version },
            touchedEvents = frame.touchedEvents ?: emptyList(),
            contextReads = frame.contextReads ?: emptyList(),
            slotCursorDelta = slotCursor - slotCursorBefore,
            eventCursorDelta = eventCursor - eventCursorBefore,
            memoizable = frame.memoizable,
        )
    }

    // --- each row memoization (P2) ---

    internal fun <T> renderEach(
        items: Iterable<T>,
        key: (T) -> Any,
        memoize: Boolean,
        content: ComponentScope.(T) -> Unit,
    ) {
        // A nested list construct keeps per-render bookkeeping (row eviction, ordinal
        // cursors) that must run every render, so it disqualifies enclosing row captures;
        // its own rows still memoize independently.
        markEachCapturesUnsafe()
        val keyedItems = keyedLastWins(items, key)
        val frame = nodeStack.last()
        val frameStart = frame.size
        var allRowsCertified = true
        if (!memoize) {
            keyedItems.forEach { keyed ->
                val itemKey = keyed.key
                val rowStart = frame.size
                withKeyScope(itemKey) {
                    content(keyed.item)
                }
                if (allRowsCertified) {
                    allRowsCertified = frame.size == rowStart + 1 &&
                        (frame[rowStart] as? HostNode)?.key == itemKey.toString()
                }
            }
            recordKeyedEmission(frame, frameStart, allRowsCertified && keyedItems.isNotEmpty())
            return
        }
        val callsite = eachCallsite()
        var reused = 0
        keyedItems.forEach { keyed ->
            val itemKey = keyed.key
            val item = keyed.item
            val rowCertified = if (emitCachedEachRow(callsite, itemKey, item)) {
                reused += 1
                callsite.rows[itemKey]?.certifiedKeyedHost == true
            } else {
                val rowKey = itemKey.toString()
                pushKeyScope(rowKey)
                try {
                    captureEachRow(callsite, itemKey, rowKey, item, content)
                } finally {
                    popKeyScope()
                }
            }
            allRowsCertified = allRowsCertified && rowCertified
        }
        recordKeyedEmission(frame, frameStart, allRowsCertified && keyedItems.isNotEmpty())
        evictRemovedEachRows(callsite)
        if (reused > 0) {
            runtime.record(
                JournalKind.Skipped,
                "each rows reused",
                mapOf("reused" to reused.toString(), "total" to keyedItems.size.toString()),
            )
        }
    }

    internal fun markEachCapturesUnsafe() {
        for (capture in eachCaptures) {
            capture.memoizable = false
        }
    }

    private fun eachCallsite(): EachCallsiteCache {
        val prefix = keyScopePrefix()
        val ordinal = eachOrdinals.getOrElse(prefix) { 0 }
        eachOrdinals[prefix] = ordinal + 1
        return eachCaches.getOrPut("$prefix#$ordinal") { EachCallsiteCache() }.also { callsite ->
            callsite.renderGeneration += 1
        }
    }

    private fun emitCachedEachRow(callsite: EachCallsiteCache, itemKey: Any, item: Any?): Boolean {
        val cache = callsite.rows[itemKey] ?: return false
        if (
            cache.item != item ||
            // A cached node references host-event ids; if any were evicted (the row's last
            // committed render did not touch them, e.g. after an aborted render), the row
            // must rebuild so its handlers re-register.
            !cache.touchedEvents.all { entry -> !entry.dead } ||
            !cache.dependenciesUnchanged() ||
            !contextReadsUnchanged(cache.contextReads)
        ) {
            return false
        }
        // Reserve the positional cursors the skipped content would have consumed so
        // sibling rows that DO rebuild derive the same slot/event keys as a full render.
        slotCursor += cache.slotCursorDelta
        eventCursor += cache.eventCursorDelta
        for (eventEntry in cache.touchedEvents) {
            touchHostEvent(eventEntry)
            for (capture in eachCaptures) {
                capture.recordEvent(eventEntry)
            }
        }
        // Keep the row's cells visible to the render-level dependency collector: without
        // this, render subscriptions to externally stored cells would be dropped and a
        // later write to them would no longer invalidate the runtime.
        cache.recordDependencies()
        cache.renderGeneration = callsite.renderGeneration
        nodeStack.last() += cache.nodes
        return true
    }

    private fun <T> captureEachRow(
        callsite: EachCallsiteCache,
        itemKey: Any,
        rowKey: String,
        item: T,
        content: ComponentScope.(T) -> Unit,
    ): Boolean {
        val capture = EachRowCapture()
        val slotCursorBefore = slotCursor
        val eventCursorBefore = eventCursor
        val dependencies = linkedSetOf<ObservableCell<*>>()
        eachCaptures += capture
        val nodes = try {
            ReadTracking.collect(
                observer = { cell ->
                    if (cell is ObservableCell<*>) {
                        dependencies += cell
                    }
                },
            ) {
                collect { content(item) }
            }
        } finally {
            eachCaptures.removeAt(eachCaptures.lastIndex)
        }
        nodeStack.last() += nodes
        dependencies.forEach(ReadTracking::record)
        val certifiedKeyedHost = nodes.size == 1 && (nodes[0] as? HostNode)?.key == rowKey
        if (capture.memoizable) {
            callsite.rows[itemKey] = EachRowCache(
                item = item,
                nodes = nodes,
                dependencies = dependencies.associateWith { dependency -> dependency.version },
                contextReads = capture.contextReads ?: emptyList(),
                touchedEvents = capture.touchedEvents ?: emptyList(),
                slotCursorDelta = slotCursor - slotCursorBefore,
                eventCursorDelta = eventCursor - eventCursorBefore,
                renderGeneration = callsite.renderGeneration,
                certifiedKeyedHost = certifiedKeyedHost,
            )
        } else {
            // The row may have been cached in an earlier render and only now turned
            // non-memoizable (e.g. a conditional effect); a stale entry must not revive.
            callsite.rows.remove(itemKey)
        }
        return certifiedKeyedHost
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
     * True iff [children] is exactly the frame a certified each() just filled — every child a
     * HostNode keyed by its unique row key, nothing emitted before or after. Consumed once.
     */
    internal fun consumeKeyedChildren(children: List<Node>): Boolean {
        val hit = keyedEmissionFrame === children && keyedEmissionEnd == children.size
        keyedEmissionFrame = null
        return hit
    }

    private fun contextReadsUnchanged(reads: List<EachContextRead>): Boolean =
        reads.all { read ->
            val current = contexts[read.context]?.lastOrNull() ?: read.context.default
            current == read.value
        }

    private fun evictRemovedEachRows(callsite: EachCallsiteCache) {
        if (callsite.rows.isNotEmpty()) {
            val removedScopePrefixes = mutableListOf<String>()
            val iterator = callsite.rows.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.renderGeneration != callsite.renderGeneration) {
                    val rowKey = entry.key
                    iterator.remove()
                    removedScopePrefixes += keyScopePrefix(rowKey)
                }
            }
            evictEachCallsitesUnder(removedScopePrefixes)
        }
    }

    /**
     * Callsite caches of list constructs NESTED inside a removed row would never be
     * evicted by their own each() (it stops running with the row), so the enclosing
     * each drops every callsite whose id lives under a removed row's key scope.
     */
    private fun evictEachCallsitesUnder(scopePrefixes: List<String>) {
        if (scopePrefixes.isEmpty() || eachCaches.isEmpty()) {
            return
        }
        val iterator = eachCaches.keys.iterator()
        while (iterator.hasNext()) {
            val callsiteId = iterator.next()
            val orphaned = scopePrefixes.any { prefix ->
                callsiteId.length > prefix.length &&
                    callsiteId.startsWith(prefix) &&
                    (callsiteId[prefix.length] == '/' || callsiteId[prefix.length] == '#')
            }
            if (orphaned) {
                iterator.remove()
            }
        }
    }

    private class HostEventEntry(
        val id: String,
        var touchedGeneration: Int,
        var dead: Boolean = false,
    )

    private class EachContextRead(
        val context: Context<*>,
        val value: Any?,
    )

    private class EachRowCapture {
        // Lazily allocated: most rows read no contexts, and rows without handlers
        // register no events — the create path should not pay for empty lists.
        var touchedEvents: MutableList<HostEventEntry>? = null
        var contextReads: MutableList<EachContextRead>? = null
        var memoizable = true

        fun recordEvent(entry: HostEventEntry) {
            val events = touchedEvents ?: mutableListOf<HostEventEntry>().also { touchedEvents = it }
            events += entry
        }

        fun recordContextRead(read: EachContextRead) {
            val reads = contextReads ?: mutableListOf<EachContextRead>().also { contextReads = it }
            reads += read
        }
    }

    private class EachRowCache(
        val item: Any?,
        val nodes: List<Node>,
        val dependencies: Map<ObservableCell<*>, Long>,
        val contextReads: List<EachContextRead>,
        val touchedEvents: List<HostEventEntry>,
        val slotCursorDelta: Int,
        val eventCursorDelta: Int,
        var renderGeneration: Int,
        val certifiedKeyedHost: Boolean,
    ) {
        fun dependenciesUnchanged(): Boolean =
            dependencies.all { (dependency, version) -> dependency.version == version }

        fun recordDependencies() {
            dependencies.keys.forEach(ReadTracking::record)
        }
    }

    private class EachCallsiteCache {
        val rows = HashMap<Any, EachRowCache>()
        var renderGeneration = 0
    }

    private class SkippableCapture(
        val node: Node,
        val dependencies: Map<ObservableCell<*>, Long>,
        val touchedEvents: List<HostEventEntry>,
        val contextReads: List<EachContextRead>,
        val slotCursorDelta: Int,
        val eventCursorDelta: Int,
        val memoizable: Boolean,
    )

    private class SkippableNodeCache(
        val inputs: List<Any?>,
        val node: Node,
        val dependencies: Map<ObservableCell<*>, Long>,
        val touchedEvents: List<HostEventEntry>,
        val contextReads: List<EachContextRead>,
        val slotCursorDelta: Int,
        val eventCursorDelta: Int,
    ) {
        fun dependenciesUnchanged(): Boolean =
            dependencies.all { (dependency, version) -> dependency.version == version }

        fun recordDependencies() {
            dependencies.keys.forEach(ReadTracking::record)
        }
    }
}

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
public fun ComponentScope.keyed(
    key: Any,
    content: ComponentScope.() -> Unit,
) {
    this.keyed(key, content)
}

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
public suspend fun ComponentScope.suspendKeyed(
    key: Any,
    content: suspend ComponentScope.() -> Unit,
) {
    this.suspendKeyed(key, content)
}

public fun ComponentScope.disposeKeyScope(
    key: Any,
    keepPersistentSlots: Boolean = false,
) {
    clearKeyScope(key, keepPersistentSlots)
}

public fun <T> ComponentScope.state(
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
    persistent: Boolean = false,
    transient: Boolean = false,
    key: String? = null,
    ordinal: Int = -1,
    initial: () -> T,
): MutableCell<T> {
    if (ordinal >= 0) {
        return frameSlot(ordinal, transient) {
            newStateCell("slot:$ordinal", policy, persistent, transient, initial)
        }
    }
    val slotKey = nextSlotKey(key, SlotKind.State)
    registerSlot(SlotMetadata(slotKey, slotId = null, persistent = persistent, transient = transient))
    val cell = checkedSlot(slotKey, MutableCellImpl::class) {
        newStateCell(slotKey, policy, persistent, transient, initial)
    }
    return cell
}

private fun <T> ComponentScope.newStateCell(
    slotLabel: String,
    policy: EqualityPolicy<T>,
    persistent: Boolean,
    transient: Boolean,
    initial: () -> T,
): MutableCellImpl<T> =
    MutableCellImpl(
        initial = initial(),
        policy = policy,
        onWrite = { _, next ->
            recordStateWrite()
            runtime.record(
                JournalKind.CellWrite,
                "cell write",
                mapOf(
                    "slot" to slotLabel,
                    "persistent" to persistent.toString(),
                    "transient" to transient.toString(),
                    "value" to next.toString(),
                ),
            )
            runtime.invalidate("cell write")
        },
    )

public fun <T> ComponentScope.state(
    slotId: SlotId,
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
    persistent: Boolean = false,
    transient: Boolean = false,
    ordinal: Int = -1,
    initial: () -> T,
): MutableCell<T> {
    if (ordinal >= 0) {
        return frameSlot(ordinal, transient) {
            newStateCell(slotId.stableKey(), policy, persistent, transient, initial)
        }
    }
    val slotKey = nextSlotKey(slotId.stableKey(), SlotKind.State)
    migrateRestoredSlot(slotId, slotKey)
    registerSlot(SlotMetadata(slotKey, slotId = slotId, persistent = persistent, transient = transient))
    val cell = checkedSlot(slotKey, MutableCellImpl::class) {
        MutableCellImpl(
            initial = initial(),
            policy = policy,
            onWrite = { _, next ->
                recordStateWrite()
                runtime.record(
                    JournalKind.CellWrite,
                    "cell write",
                    mapOf(
                        "slot" to slotKey,
                        "slotId" to slotId.toString(),
                        "persistent" to persistent.toString(),
                        "transient" to transient.toString(),
                        "value" to next.toString(),
                    ),
                )
                runtime.invalidate("cell write")
            },
        )
    }
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
    val cell = if (ordinal >= 0) {
        frameSlot(ordinal) { DerivedCell(policy, compute) }
    } else {
        val slotKey = nextSlotKey(null, SlotKind.Derived)
        registerSlot(SlotMetadata(slotKey, slotId = null, persistent = false, transient = false))
        checkedSlot(slotKey, DerivedCell::class) {
            DerivedCell(policy, compute)
        }
    }
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
    renderEach(items, key, memoize, content)
}

/**
 * Frame-native `each`; called by compiler-generated code with a static child [ordinal].
 * Every row renders into its own keyed frame; rows whose keys left the list are disposed
 * at the end of the pass (state does not resurrect when a key returns). Row memoization
 * for frames lands with the frame-native skippable cache; until then rows re-render.
 */
public fun <T> ComponentScope.eachRegion(
    ordinal: Int,
    items: Iterable<T>,
    key: (T) -> Any,
    memoize: Boolean = true,
    content: ComponentScope.(T) -> Unit,
) {
    // No frame-row caches yet, and the string-keyed capture machinery cannot validate
    // frame-backed subtrees — keep enclosing legacy captures from caching across this.
    markEachCapturesUnsafe()
    val snapshot = keyedLastWins(items, key)
    val seen = HashSet<Any>(snapshot.size)
    snapshot.forEach { keyed ->
        seen += keyed.key
        enterKeyedChildFrame(ordinal, keyed.key)
        pushKeyScope(keyed.key.toString())
        try {
            content(keyed.item)
        } finally {
            popKeyScope()
            exitFrame()
        }
    }
    keyedChildFrameKeys(ordinal).forEach { existingKey ->
        if (existingKey !in seen) {
            disposeKeyedChildFrame(ordinal, existingKey)
        }
    }
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
    // Like each(): retention bookkeeping must run every render, so an enclosing each row
    // cannot cache a subtree containing this construct.
    markEachCapturesUnsafe()
    val snapshot = keyedLastWins(items, key)
    val visibleRange = state.visibleRange(snapshot.size)
    val visibleKeys = mutableSetOf<Any>()
    val allKeys = snapshot.map { it.key }
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
        val itemKey = keyed.key
        if (index in visibleRange) {
            visibleKeys += itemKey
            withKeyScope(itemKey) {
                try {
                    content(keyed.item)
                } catch (pending: ResourcePendingException) {
                    runtime.record(
                        JournalKind.ResourceLoad,
                        "lazyEach item pending",
                        mapOf("key" to itemKey.toString(), "resource" to pending.key),
                    )
                    placeholder(keyed.item)
                }
            }
        }
    }

    val hiddenKeys = allKeys.filterNot { it in visibleKeys }
    when (retain) {
        RetainPolicy.Keyed -> Unit
        RetainPolicy.VisibleOnly -> hiddenKeys.forEach { clearKeyScope(it, keepPersistentSlots = false) }
        RetainPolicy.PersistentSlots -> hiddenKeys.forEach { clearKeyScope(it, keepPersistentSlots = true) }
    }
}

/**
 * Frame-native `lazyEach`; called by compiler-generated code. Visible rows render into
 * keyed frames; hidden rows are retained, disposed, or deactivated (transients dropped,
 * state kept) according to [retain].
 */
public fun <T> ComponentScope.lazyEachRegion(
    ordinal: Int,
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
            enterKeyedChildFrame(ordinal, keyed.key)
            pushKeyScope(keyed.key.toString())
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
                popKeyScope()
                exitFrame()
            }
        }
    }
    if (retain != RetainPolicy.Keyed) {
        keyedChildFrameKeys(ordinal).forEach { existingKey ->
            if (existingKey !in visibleKeys) {
                disposeKeyedChildFrame(ordinal, existingKey)
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
