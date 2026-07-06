package io.heapy.kinetica

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.cancellation.CancellationException

public interface ResourceKey

public enum class CacheScope {
    Component,
    App,
    Request,
}

internal data class ResourceCacheNamespace(
    val scope: CacheScope,
    val ownerId: String,
)

public sealed interface ResourceState<out T> {
    public data object Idle : ResourceState<Nothing>
    public data object Loading : ResourceState<Nothing>
    public data class Ready<T>(val value: T) : ResourceState<T>
    public data class Failed(val error: Throwable) : ResourceState<Nothing>
}

public interface Resource<T> {
    public suspend fun await(): T
    public fun read(): T
    public val state: ResourceState<T>
    public fun invalidate()
}

internal class ResourcePendingException(
    val key: String,
) : RuntimeException("Resource is pending: $key")

private class ResourceEntry<T> {
    var deferred: CompletableDeferred<T>? = null
    var state: ResourceState<T> = ResourceState.Idle
    var generation: Long = 0
    var updatedAtMillis: Long = 0
    var taskHandle: RuntimeTaskHandle? = null
}

private data class ResourceFlight<T>(
    val deferred: CompletableDeferred<T>,
    val generation: Long,
    val startedByCaller: Boolean,
)

private data class ResourceFlightCancellation(
    val taskHandle: RuntimeTaskHandle?,
    val deferred: CompletableDeferred<*>?,
    val error: CancellationException,
) {
    fun cancel() {
        taskHandle?.cancel()
        deferred?.completeExceptionally(error)
    }
}

private fun interface ResourceInvalidationListener {
    fun invalidate()
}

private object ResourceRegistry {
    private val lock = SynchronizedObject()
    private val entries = mutableMapOf<Pair<ResourceCacheNamespace, ResourceKey>, ResourceEntry<Any?>>()
    private val generations = mutableMapOf<Pair<ResourceCacheNamespace, ResourceKey>, Long>()
    private val listeners = mutableMapOf<Pair<ResourceCacheNamespace, ResourceKey>, MutableSet<ResourceInvalidationListener>>()

    fun <K : ResourceKey, T> state(
        namespace: ResourceCacheNamespace,
        key: K,
        nowMillis: Long,
        ttlMillis: Long?,
    ): ResourceState<T> = synchronized(lock) {
        val entry = entries[namespace to key] ?: return ResourceState.Idle
        if (entry.isExpired(namespace, nowMillis, ttlMillis)) {
            entries.remove(namespace to key)
            return ResourceState.Idle
        }
        @Suppress("UNCHECKED_CAST")
        (entry as ResourceEntry<T>).state
    }

    fun <K : ResourceKey, T> flight(
        namespace: ResourceCacheNamespace,
        key: K,
        nowMillis: Long,
        ttlMillis: Long?,
    ): ResourceFlight<T> = synchronized(lock) {
        flightLocked(namespace, key, nowMillis, ttlMillis)
    }

    private fun <K : ResourceKey, T> flightLocked(
        namespace: ResourceCacheNamespace,
        key: K,
        nowMillis: Long,
        ttlMillis: Long?,
    ): ResourceFlight<T> {
        val entry = entry<T>(namespace, key)
        if (entry.isExpired(namespace, nowMillis, ttlMillis)) {
            entries.remove(namespace to key)
            return flightLocked(namespace, key, nowMillis, ttlMillis)
        }
        val existing = entry.deferred
        if (existing != null) {
            return ResourceFlight(existing, entry.generation, startedByCaller = false)
        }

        val cacheKey = namespace to key
        val nextGeneration = generations.getOrElse(cacheKey) { 0L } + 1L
        generations[cacheKey] = nextGeneration
        val deferred = CompletableDeferred<T>()
        entry.deferred = deferred
        entry.state = ResourceState.Loading
        entry.generation = nextGeneration
        return ResourceFlight(deferred, entry.generation, startedByCaller = true)
    }

    suspend fun <K : ResourceKey, T> await(
        namespace: ResourceCacheNamespace,
        key: K,
        nowMillis: Long,
        ttlMillis: Long?,
        loader: suspend (K) -> T,
    ): T {
        when (val cached = state<K, T>(namespace, key, nowMillis, ttlMillis)) {
            is ResourceState.Ready -> return cached.value
            is ResourceState.Failed -> throw cached.error
            ResourceState.Idle,
            ResourceState.Loading,
            -> Unit
        }

        val flight = synchronized(lock) {
            flightLocked<K, T>(namespace, key, nowMillis, ttlMillis)
        }
        if (!flight.startedByCaller) {
            return flight.deferred.await()
        }

        try {
            val value = loader(key)
            complete(namespace, key, flight.generation, nowMillis, value)
            return value
        } catch (error: CancellationException) {
            cancel(namespace, key, flight.generation, error)
            throw error
        } catch (error: Throwable) {
            fail(namespace, key, flight.generation, nowMillis, error)
            throw error
        }
    }

    fun <K : ResourceKey, T> complete(
        namespace: ResourceCacheNamespace,
        key: K,
        generation: Long,
        nowMillis: Long,
        value: T,
    ): Boolean {
        val deferred = synchronized(lock) {
            val entry = entries[namespace to key] ?: return false
            @Suppress("UNCHECKED_CAST")
            val typed = entry as ResourceEntry<T>
            if (typed.generation != generation) {
                return false
            }
            typed.state = ResourceState.Ready(value)
            typed.updatedAtMillis = nowMillis
            typed.taskHandle = null
            val deferred = typed.deferred
            typed.deferred = null
            deferred
        }
        deferred?.complete(value)
        return true
    }

    fun <K : ResourceKey> fail(
        namespace: ResourceCacheNamespace,
        key: K,
        generation: Long,
        nowMillis: Long,
        error: Throwable,
    ): Boolean {
        val deferred = synchronized(lock) {
            val entry = entries[namespace to key] ?: return false
            if (entry.generation != generation) {
                return false
            }
            entry.state = ResourceState.Failed(error)
            entry.updatedAtMillis = nowMillis
            entry.taskHandle = null
            val deferred = entry.deferred
            entry.deferred = null
            deferred
        }
        deferred?.completeExceptionally(error)
        return true
    }

    fun <K : ResourceKey> cancel(
        namespace: ResourceCacheNamespace,
        key: K,
        generation: Long,
        error: CancellationException,
    ): Boolean {
        val deferred = synchronized(lock) {
            val entry = entries[namespace to key] ?: return false
            if (entry.generation != generation) {
                return false
            }
            entry.taskHandle = null
            val deferred = entry.deferred
            entry.deferred = null
            entries.remove(namespace to key)
            deferred
        }
        deferred?.completeExceptionally(error)
        return true
    }

    fun attachTask(
        namespace: ResourceCacheNamespace,
        key: ResourceKey,
        generation: Long,
        taskHandle: RuntimeTaskHandle,
    ): Boolean = synchronized(lock) {
        val entry = entries[namespace to key] ?: return@synchronized false
        if (entry.generation != generation || entry.state !is ResourceState.Loading) {
            return@synchronized false
        }
        entry.taskHandle = taskHandle
        true
    }

    fun registerInvalidationListener(
        namespace: ResourceCacheNamespace,
        key: ResourceKey,
        listener: ResourceInvalidationListener,
    ): Disposable {
        val listenersForKey = synchronized(lock) {
            listeners.getOrPut(namespace to key) { mutableSetOf() }.also { listenersForKey ->
                listenersForKey += listener
            }
        }
        return Disposable {
            val cancellation = synchronized(lock) {
                listenersForKey -= listener
                if (listenersForKey.isEmpty()) {
                    listeners.remove(namespace to key)
                    if (namespace.scope != CacheScope.App) {
                        entries.remove(namespace to key)
                            ?.cancelInFlight(
                                CancellationException("Resource load was cancelled because its cache scope was disposed: $key"),
                            )
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            cancellation?.cancel()
        }
    }

    fun invalidate(key: ResourceKey) =
        invalidateMatching { it.second == key }

    fun invalidate(predicate: (ResourceKey) -> Boolean) =
        invalidateMatching { predicate(it.second) }

    private fun invalidateMatching(predicate: (Pair<ResourceCacheNamespace, ResourceKey>) -> Boolean) {
        val invalidationListeners = synchronized(lock) {
            val invalidatedKeys = entries.keys.filter(predicate)
            invalidatedKeys.forEach { cacheKey ->
                val entry = entries.remove(cacheKey)
                val currentGeneration = generations.getOrElse(cacheKey) { 0L }
                generations[cacheKey] = maxOf(currentGeneration, entry?.generation ?: 0L) + 1L
            }
            listeners
                .filterKeys(predicate)
                .values
                .flatMap { it.toList() }
        }
        invalidationListeners.forEach { listener -> listener.invalidate() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> entry(namespace: ResourceCacheNamespace, key: ResourceKey): ResourceEntry<T> =
        entries.getOrPut(namespace to key) { ResourceEntry<Any?>() } as ResourceEntry<T>

    private fun <T> ResourceEntry<T>.cancelInFlight(error: CancellationException): ResourceFlightCancellation? {
        if (state !is ResourceState.Loading) {
            return null
        }
        val cancellation = ResourceFlightCancellation(taskHandle, deferred, error)
        taskHandle = null
        deferred = null
        return cancellation
    }

    private fun ResourceEntry<*>.isExpired(
        namespace: ResourceCacheNamespace,
        nowMillis: Long,
        ttlMillis: Long?,
    ): Boolean =
        namespace.scope == CacheScope.App &&
            ttlMillis != null &&
            state !is ResourceState.Idle &&
            state !is ResourceState.Loading &&
            nowMillis - updatedAtMillis >= ttlMillis
}

private class ResourceImpl<K : ResourceKey, T>(
    private val runtime: KineticaRuntime,
    private val key: K,
    private val namespace: ResourceCacheNamespace,
    private val ttlMillis: Long?,
    private var loader: suspend (K) -> T,
) : Resource<T>, Disposable {
    private val currentState = atomic<ResourceState<T>>(ResourceState.Idle)
    private val invalidationRegistration = ResourceRegistry.registerInvalidationListener(namespace, key) {
        currentState.value = ResourceState.Idle
        runtime.record(JournalKind.ResourceInvalidated, "resource invalidated", mapOf("key" to key.toString()))
        runtime.invalidate("resource invalidated")
    }

    override val state: ResourceState<T>
        get() = currentState.value

    override suspend fun await(): T {
        runtime.record(JournalKind.ResourceLoad, "resource load", mapOf("key" to key.toString()))
        currentState.value = ResourceState.Loading
        return try {
            val value = ResourceRegistry.await(
                namespace = namespace,
                key = key,
                nowMillis = runtime.virtualTimeMillis,
                ttlMillis = ttlMillis,
                loader = loader,
            )
            currentState.value = ResourceState.Ready(value)
            value
        } catch (error: CancellationException) {
            currentState.value = ResourceState.Idle
            throw error
        } catch (error: Throwable) {
            currentState.value = ResourceState.Failed(error)
            throw error
        }
    }

    override fun read(): T {
        when (val cached = ResourceRegistry.state<K, T>(
            namespace = namespace,
            key = key,
            nowMillis = runtime.virtualTimeMillis,
            ttlMillis = ttlMillis,
        )) {
            is ResourceState.Ready -> {
                currentState.value = cached
                return cached.value
            }
            is ResourceState.Failed -> {
                currentState.value = cached
                throw cached.error
            }
            ResourceState.Loading -> {
                currentState.value = cached
                throw ResourcePendingException(key.toString())
            }
            ResourceState.Idle -> startLoad()
        }
        throw ResourcePendingException(key.toString())
    }

    override fun invalidate() {
        ResourceRegistry.invalidate(key)
    }

    fun updateLoader(loader: suspend (K) -> T) {
        this.loader = loader
    }

    override fun dispose() {
        invalidationRegistration.dispose()
    }

    private fun startLoad() {
        val flight = ResourceRegistry.flight<K, T>(
            namespace = namespace,
            key = key,
            nowMillis = runtime.virtualTimeMillis,
            ttlMillis = ttlMillis,
        )
        currentState.value = ResourceState.Loading
        if (!flight.startedByCaller) {
            return
        }

        runtime.record(JournalKind.ResourceLoad, "resource load", mapOf("key" to key.toString()))
        val taskHandle = runtime.launchTrackedTask {
            try {
                val value = loader(key)
                if (ResourceRegistry.complete(
                    namespace = namespace,
                    key = key,
                    generation = flight.generation,
                    nowMillis = runtime.virtualTimeMillis,
                    value = value,
                )) {
                    currentState.value = ResourceState.Ready(value)
                    runtime.record(JournalKind.ResourceLoad, "resource resume", mapOf("key" to key.toString()))
                    runtime.invalidate("resource resume")
                }
            } catch (error: CancellationException) {
                if (ResourceRegistry.cancel(
                    namespace = namespace,
                    key = key,
                    generation = flight.generation,
                    error = error,
                )) {
                    currentState.value = ResourceState.Idle
                    runtime.record(
                        JournalKind.ResourceLoad,
                        "resource cancelled",
                        mapOf("key" to key.toString(), "error" to error.toString()),
                    )
                    runtime.invalidate("resource cancelled")
                }
            } catch (error: Throwable) {
                if (ResourceRegistry.fail(
                    namespace = namespace,
                    key = key,
                    generation = flight.generation,
                    nowMillis = runtime.virtualTimeMillis,
                    error = error,
                )) {
                    currentState.value = ResourceState.Failed(error)
                    runtime.record(
                        JournalKind.ResourceLoad,
                        "resource failed",
                        mapOf("key" to key.toString(), "error" to error.toString()),
                    )
                    runtime.invalidate("resource resume")
                }
            }
        }
        if (!ResourceRegistry.attachTask(namespace, key, flight.generation, taskHandle)) {
            taskHandle.cancel()
        }
    }
}

public fun <K : ResourceKey, T> ComponentScope.resource(
    key: K,
    scope: CacheScope = CacheScope.App,
    loader: suspend (K) -> T,
): Resource<T> {
    val namespace = resourceCacheNamespace(scope)
    val slotKey = nextResourceKey("resource:${scope.name}:${key}")
    registerSlot(SlotMetadata(slotKey, slotId = null, persistent = false, transient = true))
    val resource = checkedSlot(slotKey, ResourceImpl::class) {
        ResourceImpl(
            runtime = runtime,
            key = key,
            namespace = namespace,
            ttlMillis = if (scope == CacheScope.App) runtime.appResourceTtlMillis else null,
            loader = loader,
        )
    }
    resource.updateLoader(loader)
    return resource
}

public fun invalidate(key: ResourceKey) {
    ResourceRegistry.invalidate(key)
}

public fun invalidate(predicate: (ResourceKey) -> Boolean) {
    ResourceRegistry.invalidate(predicate)
}

public fun interface Action<I, O> {
    public suspend operator fun invoke(input: I): O
}

public fun <I, O> ComponentScope.action(
    invalidates: (I) -> List<ResourceKey> = { emptyList() },
    block: suspend (I) -> O,
): Action<I, O> = Action { input ->
    val output = block(input)
    invalidates(input).forEach { key ->
        invalidate(key)
        runtime.record(JournalKind.ResourceInvalidated, "action invalidated resource", mapOf("key" to key.toString()))
    }
    runtime.invalidate("action")
    output
}
