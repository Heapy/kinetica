package io.heapy.kinetica.data

import io.heapy.kinetica.Action
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.LazyItems
import io.heapy.kinetica.Resource
import io.heapy.kinetica.ResourceKey
import io.heapy.kinetica.action
import io.heapy.kinetica.invalidate
import io.heapy.kinetica.lazyItems as kineticaLazyItems
import io.heapy.kinetica.state
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

public data class RetryPolicy(
    val attempts: Int = 3,
    val delayMillis: Long = 250,
    val backoffMultiplier: Double = 1.0,
    val maxDelayMillis: Long = Long.MAX_VALUE,
) {
    init {
        require(attempts > 0) { "attempts must be positive." }
        require(delayMillis >= 0) { "delayMillis must be non-negative." }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be at least 1.0." }
        require(maxDelayMillis >= 0) { "maxDelayMillis must be non-negative." }
    }

    public fun delayBeforeRetry(failureIndex: Int): Long {
        require(failureIndex >= 0) { "failureIndex must be non-negative." }
        var delay = delayMillis.toDouble()
        repeat(failureIndex) {
            delay *= backoffMultiplier
        }
        return delay.coerceAtMost(maxDelayMillis.toDouble()).toLong()
    }
}

public data class PageRequest(
    val offset: Int = 0,
    val limit: Int = 50,
) {
    init {
        require(offset >= 0) { "offset must be non-negative." }
        require(limit > 0) { "limit must be positive." }
    }

    public fun next(loadedCount: Int): PageRequest =
        copy(offset = offset + loadedCount)
}

public data class Page<T>(
    val items: List<T>,
    val next: PageRequest? = null,
    val totalCount: Int? = null,
) {
    public val isEnd: Boolean
        get() = next == null
}

public class Paginator<T> internal constructor(
    initialRequest: PageRequest,
) {
    private val mutableItems = mutableListOf<T>()
    private var nextRequest: PageRequest? = initialRequest

    public val items: List<T>
        get() = mutableItems.toList()

    public val canLoadMore: Boolean
        get() = nextRequest != null

    public var totalCount: Int? = null
        private set

    public var isLoading: Boolean = false
        private set

    public var error: Throwable? = null
        private set

    public suspend fun loadNext(loader: suspend (PageRequest) -> Page<T>): Boolean {
        val request = nextRequest ?: return false
        if (isLoading) {
            return false
        }
        isLoading = true
        error = null
        return try {
            val page = loader(request)
            mutableItems += page.items
            nextRequest = page.next
            totalCount = page.totalCount ?: totalCount
            true
        } catch (failure: Throwable) {
            error = failure
            throw failure
        } finally {
            isLoading = false
        }
    }

    public fun reset(request: PageRequest = PageRequest()) {
        mutableItems.clear()
        nextRequest = request
        totalCount = null
        error = null
        isLoading = false
    }
}

public fun <T> ComponentScope.paginator(
    key: String = "paginator",
    initialRequest: PageRequest = PageRequest(),
): Paginator<T> =
    state(key = key) { Paginator<T>(initialRequest) }.value

public fun <T> Paginator<T>.asLazyItems(): LazyItems<T> =
    kineticaLazyItems(items, estimatedSize = totalCount)

public suspend fun <T> retry(
    policy: RetryPolicy = RetryPolicy(),
    shouldRetry: (Throwable) -> Boolean = { true },
    block: suspend (attempt: Int) -> T,
): T {
    var lastError: Throwable? = null
    repeat(policy.attempts) { attempt ->
        try {
            return block(attempt)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                throw error
            }
            lastError = error
            if (attempt + 1 >= policy.attempts || !shouldRetry(error)) {
                throw error
            }
            val delayMillis = policy.delayBeforeRetry(attempt)
            if (delayMillis > 0) {
                delay(delayMillis)
            }
        }
    }
    throw lastError ?: IllegalStateException("Retry failed without an error")
}

public suspend fun <T> Resource<T>.awaitWithRetry(policy: RetryPolicy = RetryPolicy()): T =
    retry(policy) { attempt ->
        if (attempt > 0) {
            invalidate()
        }
        await()
    }

public fun <I, O> ComponentScope.optimisticAction(
    invalidates: (I) -> List<ResourceKey>,
    optimistic: (I) -> Unit,
    rollback: (I, Throwable) -> Unit = { _, _ -> },
    block: suspend (I) -> O,
): Action<I, O> = action(
    invalidates = invalidates,
    block = { input ->
        optimistic(input)
        try {
            block(input)
        } catch (error: Throwable) {
            rollback(input, error)
            throw error
        }
    },
)

public fun invalidateAll(vararg keys: ResourceKey) {
    keys.forEach(::invalidate)
}

public enum class OfflineSource {
    Cache,
    Network,
}

public enum class OfflineStrategy {
    CacheFirst,
    NetworkFirst,
}

public data class OfflineLoad<T>(
    val value: T,
    val source: OfflineSource,
    val stale: Boolean = false,
    val failure: Throwable? = null,
)

public interface OfflineCache<K, V> {
    public fun read(key: K): V?
    public fun write(key: K, value: V)
    public fun remove(key: K)
    public fun snapshot(): Map<K, V>
}

public class InMemoryOfflineCache<K, V> : OfflineCache<K, V> {
    private val values = mutableMapOf<K, V>()

    override fun read(key: K): V? =
        values[key]

    override fun write(key: K, value: V) {
        values[key] = value
    }

    override fun remove(key: K) {
        values.remove(key)
    }

    override fun snapshot(): Map<K, V> =
        values.toMap()
}

public suspend fun <K, V> OfflineCache<K, V>.load(
    key: K,
    strategy: OfflineStrategy = OfflineStrategy.NetworkFirst,
    fetch: suspend (K) -> V,
): OfflineLoad<V> {
    if (strategy == OfflineStrategy.CacheFirst) {
        val cached = read(key)
        if (cached != null) {
            return OfflineLoad(cached, OfflineSource.Cache)
        }
    }

    return try {
        val value = fetch(key)
        write(key, value)
        OfflineLoad(value, OfflineSource.Network)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        val cached = read(key)
        if (cached != null) {
            OfflineLoad(cached, OfflineSource.Cache, stale = true, failure = error)
        } else {
            throw error
        }
    }
}
