package io.heapy.kinetica

import kotlinx.atomicfu.atomic
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
    private val contexts = mutableMapOf<Context<*>, MutableList<Any?>>()
    private val exitGroups = mutableMapOf<String, ExitGroupState>()
    private val errorBoundaryStack = mutableListOf<ErrorBoundaryState>()
    private val staticNodes = mutableMapOf<String, Node>()
    private val skippableNodes = mutableMapOf<String, SkippableNodeCache>()
    private val exitGroupStack = mutableListOf<ExitGroupState>()
    private val keyScopeStack = mutableListOf<String>()
    private val stateWriteVersion = atomic(0L)
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
        resourceKeyCounts.clear()
        touchedSlots.clear()
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
        layoutEffects += block
    }

    internal fun schedulePostCommitEffect(block: () -> Unit) {
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
        val cache = skippableNodes[componentId]
        val currentStateWriteVersion = stateWriteVersion.value
        if (
            cache != null &&
            cache.inputs == inputs &&
            cache.stateWriteVersion == currentStateWriteVersion &&
            cache.dependenciesUnchanged()
        ) {
            cache.recordDependencies()
            runtime.record(JournalKind.Skipped, "component skipped", mapOf("componentId" to componentId))
            return cache.node
        }
        val capture = captureSkippableDependencies(factory)
        skippableNodes[componentId] = SkippableNodeCache(
            inputs = inputs.toList(),
            stateWriteVersion = currentStateWriteVersion,
            node = capture.node,
            dependencies = capture.dependencies,
        )
        return capture.node
    }

    public suspend fun skippableSuspendNode(
        componentId: String,
        inputs: List<Any?> = emptyList(),
        factory: suspend () -> Node,
    ): Node {
        val cache = skippableNodes[componentId]
        val currentStateWriteVersion = stateWriteVersion.value
        if (
            cache != null &&
            cache.inputs == inputs &&
            cache.stateWriteVersion == currentStateWriteVersion &&
            cache.dependenciesUnchanged()
        ) {
            cache.recordDependencies()
            runtime.record(JournalKind.Skipped, "component skipped", mapOf("componentId" to componentId))
            return cache.node
        }
        val capture = captureSkippableDependencies(factory)
        skippableNodes[componentId] = SkippableNodeCache(
            inputs = inputs.toList(),
            stateWriteVersion = currentStateWriteVersion,
            node = capture.node,
            dependencies = capture.dependencies,
        )
        return capture.node
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

    internal fun currentExitGroup(): ExitGroupState? =
        exitGroupStack.lastOrNull()

    internal fun currentErrorBoundary(): ErrorBoundaryState? =
        errorBoundaryStack.lastOrNull()

    internal fun <T> withErrorBoundary(state: ErrorBoundaryState, content: ComponentScope.() -> T): T {
        errorBoundaryStack += state
        try {
            return content()
        } finally {
            errorBoundaryStack.removeAt(errorBoundaryStack.lastIndex)
        }
    }

    internal fun withKeyScope(key: Any, content: ComponentScope.() -> Unit) {
        keyScopeStack += key.toString()
        try {
            content()
        } finally {
            keyScopeStack.removeAt(keyScopeStack.lastIndex)
        }
    }

    internal fun keyScopePrefix(extraKey: Any? = null): String {
        val keys = if (extraKey == null) keyScopeStack else keyScopeStack + extraKey.toString()
        return keys.joinToString(separator = "/")
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
        val prefix = keyScopePrefix(key)
        val prefixWithSeparator = "$prefix/"
        val removable = slots.keys.filter { slotKey ->
            slotKey == prefix || slotKey.startsWith(prefixWithSeparator)
        }.filter { slotKey ->
            !keepPersistentSlots || slotMetadata[slotKey]?.persistent != true
        }
        removable.forEach(::removeSlot)
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

    public fun isLeaving(key: Any): Boolean =
        exitGroups[key.toString()]?.phase == ExitPhase.Leaving

    public fun dispose() {
        slots.keys.toList().forEach(::removeSlot)
        touchedSlots.clear()
        contexts.clear()
        exitGroups.values.forEach { state -> state.cancelTasks() }
        exitGroups.clear()
        errorBoundaryStack.clear()
        staticNodes.clear()
        skippableNodes.clear()
        exitGroupStack.clear()
        keyScopeStack.clear()
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

    internal fun nextEffectKey(): String {
        val localKey = "effect-${effectCursor++}"
        val prefix = keyScopePrefix()
        val key = if (prefix.isEmpty()) localKey else "$prefix/$localKey"
        touchedSlots += key
        return key
    }

    internal fun nextResourceKey(baseKey: String): String {
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
        stateWriteVersion.incrementAndGet()
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
        return contexts[context]?.lastOrNull() as T? ?: context.default
    }

    private fun List<Node>.toNode(): Node =
        when (size) {
            0 -> FragmentNode()
            1 -> single()
            else -> FragmentNode(this)
        }

    private fun captureSkippableDependencies(factory: () -> Node): SkippableCapture {
        val dependencies = linkedSetOf<ObservableCell<*>>()
        val node = ReadTracking.collect(
            observer = { cell ->
                if (cell is ObservableCell<*>) {
                    dependencies += cell
                }
            },
            block = factory,
        )
        dependencies.forEach(ReadTracking::record)
        return SkippableCapture(node, dependencies.associateWith { dependency -> dependency.version })
    }

    private suspend fun captureSkippableDependencies(factory: suspend () -> Node): SkippableCapture {
        val dependencies = linkedSetOf<ObservableCell<*>>()
        val node = ReadTracking.collectSuspend(
            observer = { cell ->
                if (cell is ObservableCell<*>) {
                    dependencies += cell
                }
            },
            block = factory,
        )
        dependencies.forEach(ReadTracking::record)
        return SkippableCapture(node, dependencies.associateWith { dependency -> dependency.version })
    }

    private data class SkippableCapture(
        val node: Node,
        val dependencies: Map<ObservableCell<*>, Long>,
    )

    private data class SkippableNodeCache(
        val inputs: List<Any?>,
        val stateWriteVersion: Long,
        val node: Node,
        val dependencies: Map<ObservableCell<*>, Long>,
    ) {
        fun dependenciesUnchanged(): Boolean =
            dependencies.all { (dependency, version) -> dependency.version == version }

        fun recordDependencies() {
            dependencies.keys.forEach(ReadTracking::record)
        }
    }
}

public fun ComponentScope.keyed(
    key: Any,
    content: ComponentScope.() -> Unit,
) {
    withKeyScope(key, content)
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
    // captured on first allocation; because it reads through stable slot-backed cells,
    // the memoized cell stays correct across renders while avoiding reallocation.
    val slotKey = nextSlotKey(null)
    registerSlot(SlotMetadata(slotKey, slotId = null, persistent = false, transient = false))
    return slot(slotKey) {
        DerivedCell(policy, compute)
    }
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

public fun <T> ComponentScope.each(
    items: Iterable<T>,
    key: (T) -> Any,
    content: ComponentScope.(T) -> Unit,
) {
    keyedLastWins(items, key).forEach { keyed ->
        withKeyScope(keyed.key) {
            content(keyed.item)
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
    val keyedItems = linkedMapOf<Any, T>()
    items.forEach { item ->
        val itemKey = key(item)
        if (keyedItems.containsKey(itemKey)) {
            duplicateKey(itemKey)
            if (!runtime.debug) {
                keyedItems.remove(itemKey)
            }
        }
        keyedItems[itemKey] = item
    }
    return keyedItems.map { (itemKey, item) -> KeyedItem(itemKey, item) }
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
