package io.heapy.kinetica

import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable

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
)

public class ComponentScope public constructor(
    internal val runtime: KineticaRuntime = KineticaRuntime(),
    internal val instanceId: String = "root",
) {
    private val nodeStack = mutableListOf<MutableList<Node>>(mutableListOf())
    private val slots = mutableMapOf<String, Any?>()
    private val slotMetadata = mutableMapOf<String, SlotMetadata>()
    private val touchedSlots = mutableSetOf<String>()
    private val hostEventIds = mutableMapOf<String, String>()
    private val touchedHostEvents = mutableSetOf<String>()
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
    private val resourceKeyCounts = mutableMapOf<String, Int>()
    private val layoutEffects = mutableListOf<() -> Unit>()
    private val postCommitEffects = mutableListOf<() -> Unit>()

    public val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal fun beginRender() {
        slotCursor = 0
        eventCursor = 0
        effectCursor = 0
        eachOrdinals.clear()
        eachCaptures.clear()
        resourceKeyCounts.clear()
        touchedSlots.clear()
        touchedHostEvents.clear()
        layoutEffects.clear()
        postCommitEffects.clear()
        nodeStack.clear()
        nodeStack.add(mutableListOf())
    }

    internal fun commitRender(): Node {
        val expiredTransientSlots = slotMetadata.values
            .filter { it.transient && it.key !in touchedSlots }
            .map { it.key }
        expiredTransientSlots.forEach(::removeSlot)
        evictUntouchedHostEvents()
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
            cache.touchedEventKeys.all(hostEventIds::containsKey) &&
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
        slotCursor += cache.slotCursorDelta
        eventCursor += cache.eventCursorDelta
        for (eventKey in cache.touchedEventKeys) {
            touchedHostEvents += eventKey
            for (capture in eachCaptures) {
                capture.recordEventKey(eventKey)
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
                touchedEventKeys = capture.touchedEventKeys,
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
        slots.keys.toList().forEach(::removeSlot)
        touchedSlots.clear()
        hostEventIds.values.forEach(runtime::removeEvent)
        hostEventIds.clear()
        touchedHostEvents.clear()
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

    internal fun nextSlotKey(explicitKey: String?): String {
        val localKey = explicitKey ?: "slot-${slotCursor++}"
        val prefix = keyScopePrefix()
        val key = if (prefix.isEmpty()) localKey else "$prefix/$localKey"
        touchedSlots += key
        return key
    }

    internal fun nextEventKey(): String {
        val localKey = "event-${eventCursor++}"
        val prefix = keyScopePrefix()
        return if (prefix.isEmpty()) localKey else "$prefix/$localKey"
    }

    internal fun registerHostEvent(key: String, callback: (Any?) -> Unit): String {
        touchedHostEvents += key
        for (capture in eachCaptures) {
            capture.recordEventKey(key)
        }
        val existing = hostEventIds[key]
        if (existing != null && runtime.updateEvent(existing, callback)) {
            return existing
        }
        val id = runtime.registerEvent(callback)
        hostEventIds[key] = id
        return id
    }

    private fun evictUntouchedHostEvents() {
        if (hostEventIds.size == touchedHostEvents.size) {
            return
        }
        val iterator = hostEventIds.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in touchedHostEvents) {
                runtime.removeEvent(entry.value)
                iterator.remove()
            }
        }
    }

    internal fun nextEffectKey(): String {
        val localKey = "effect-${effectCursor++}"
        val prefix = keyScopePrefix()
        val key = if (prefix.isEmpty()) localKey else "$prefix/$localKey"
        touchedSlots += key
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
        touchedSlots += key
        return key
    }

    internal fun <T> slot(key: String, initial: () -> T): T {
        @Suppress("UNCHECKED_CAST")
        return slots.getOrPut(key) { initial() } as T
    }

    internal fun registerSlot(metadata: SlotMetadata) {
        if (metadata.transient) {
            // Transient slots (effects, suspend subtrees, transient state) expire when a
            // render does not touch them — a skipped row would silently cancel them.
            markEachCapturesUnsafe()
        }
        slotMetadata[metadata.key] = metadata
    }

    private fun removeSlot(key: String) {
        when (val value = slots[key]) {
            is ManagedEffectState -> value.cancel()
            is Disposable -> value.dispose()
            is Ref<*> -> value.clear()
        }
        slots.remove(key)
        slotMetadata.remove(key)
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
        slotMetadata[key] = SlotMetadata(key, slotId, persistent, transient)
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
            touchedEventKeys = frame.touchedEventKeys ?: emptyList(),
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
            touchedEventKeys = frame.touchedEventKeys ?: emptyList(),
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
            val rowKey = itemKey.toString()
            pushKeyScope(rowKey)
            try {
                val rowCertified = if (emitCachedEachRow(callsite, rowKey, item)) {
                    reused += 1
                    callsite.rows[rowKey]?.certifiedKeyedHost == true
                } else {
                    captureEachRow(callsite, rowKey, item, content)
                }
                allRowsCertified = allRowsCertified && rowCertified
            } finally {
                popKeyScope()
            }
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

    private fun emitCachedEachRow(callsite: EachCallsiteCache, rowKey: String, item: Any?): Boolean {
        val cache = callsite.rows[rowKey] ?: return false
        if (
            cache.item != item ||
            // A cached node references host-event ids; if any were evicted (the row's last
            // committed render did not touch them, e.g. after an aborted render), the row
            // must rebuild so its handlers re-register.
            !cache.touchedEventKeys.all(hostEventIds::containsKey) ||
            !cache.dependenciesUnchanged() ||
            !contextReadsUnchanged(cache.contextReads)
        ) {
            return false
        }
        // Reserve the positional cursors the skipped content would have consumed so
        // sibling rows that DO rebuild derive the same slot/event keys as a full render.
        slotCursor += cache.slotCursorDelta
        eventCursor += cache.eventCursorDelta
        for (eventKey in cache.touchedEventKeys) {
            touchedHostEvents += eventKey
            for (capture in eachCaptures) {
                capture.recordEventKey(eventKey)
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
            callsite.rows[rowKey] = EachRowCache(
                item = item,
                nodes = nodes,
                dependencies = dependencies.associateWith { dependency -> dependency.version },
                contextReads = capture.contextReads ?: emptyList(),
                touchedEventKeys = capture.touchedEventKeys ?: emptyList(),
                slotCursorDelta = slotCursor - slotCursorBefore,
                eventCursorDelta = eventCursor - eventCursorBefore,
                renderGeneration = callsite.renderGeneration,
                certifiedKeyedHost = certifiedKeyedHost,
            )
        } else {
            // The row may have been cached in an earlier render and only now turned
            // non-memoizable (e.g. a conditional effect); a stale entry must not revive.
            callsite.rows.remove(rowKey)
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

    private class EachContextRead(
        val context: Context<*>,
        val value: Any?,
    )

    private class EachRowCapture {
        // Lazily allocated: most rows read no contexts, and rows without handlers
        // register no events — the create path should not pay for empty lists.
        var touchedEventKeys: MutableList<String>? = null
        var contextReads: MutableList<EachContextRead>? = null
        var memoizable = true

        fun recordEventKey(key: String) {
            val keys = touchedEventKeys ?: mutableListOf<String>().also { touchedEventKeys = it }
            keys += key
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
        val touchedEventKeys: List<String>,
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
        val rows = HashMap<String, EachRowCache>()
        var renderGeneration = 0
    }

    private class SkippableCapture(
        val node: Node,
        val dependencies: Map<ObservableCell<*>, Long>,
        val touchedEventKeys: List<String>,
        val contextReads: List<EachContextRead>,
        val slotCursorDelta: Int,
        val eventCursorDelta: Int,
        val memoizable: Boolean,
    )

    private class SkippableNodeCache(
        val inputs: List<Any?>,
        val node: Node,
        val dependencies: Map<ObservableCell<*>, Long>,
        val touchedEventKeys: List<String>,
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
    initial: () -> T,
): MutableCell<T> {
    val slotKey = nextSlotKey(key)
    registerSlot(SlotMetadata(slotKey, slotId = null, persistent = persistent, transient = transient))
    val cell = slot(slotKey) {
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

public fun <T> ComponentScope.state(
    slotId: SlotId,
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
    persistent: Boolean = false,
    transient: Boolean = false,
    initial: () -> T,
): MutableCell<T> {
    val slotKey = nextSlotKey(slotId.stableKey())
    migrateRestoredSlot(slotId, slotKey)
    registerSlot(SlotMetadata(slotKey, slotId = slotId, persistent = persistent, transient = transient))
    val cell = slot(slotKey) {
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
    compute: () -> T,
): Cell<T> {
    // Back derived{} with a positional slot, exactly like state(): the DerivedCell is
    // allocated once and reused across renders, so its lazy cache and version counter
    // survive instead of restarting from zero on every render. The compute closure is
    // refreshed because it may capture render-local inputs from the current invocation.
    val slotKey = nextSlotKey(null)
    registerSlot(SlotMetadata(slotKey, slotId = null, persistent = false, transient = false))
    val cell = slot(slotKey) {
        DerivedCell(policy, compute)
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
