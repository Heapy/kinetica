package io.heapy.kinetica.data

import io.heapy.kinetica.Action
import io.heapy.kinetica.CacheScope
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Resource
import io.heapy.kinetica.ResourceKey
import io.heapy.kinetica.resource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DataSmokeTest {
    @Test
    fun retryPolicyBacksOffAndGenericRetryStopsOnSuccess() = runTest {
        val policy = RetryPolicy(
            attempts = 3,
            delayMillis = 10,
            backoffMultiplier = 2.0,
            maxDelayMillis = 15,
        )
        assertEquals(10, policy.delayBeforeRetry(0))
        assertEquals(15, policy.delayBeforeRetry(1))

        val attempts = mutableListOf<Int>()
        val value = retry(policy.copy(delayMillis = 0)) { attempt ->
            attempts += attempt
            if (attempt < 2) {
                error("try $attempt")
            }
            "ok"
        }

        assertEquals(listOf(0, 1, 2), attempts)
        assertEquals("ok", value)
    }

    @Test
    fun retryUsesConfiguredDelayBeforeNextAttempt() = runTest {
        val attempts = mutableListOf<Int>()
        val value = retry(policy = RetryPolicy(attempts = 2, delayMillis = 1)) { attempt ->
            attempts += attempt
            if (attempt == 0) {
                error("first")
            }
            "delayed-ok"
        }

        assertEquals(listOf(0, 1), attempts)
        assertEquals("delayed-ok", value)
    }

    @Test
    fun retryPolicyValidationAndRetryStopConditionsAreExplicit() = runTest {
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy(attempts = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy(delayMillis = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy(backoffMultiplier = 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy(maxDelayMillis = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            RetryPolicy().delayBeforeRetry(-1)
        }

        val attempts = mutableListOf<Int>()
        val failure = assertFailsWith<IllegalStateException> {
            retry(
                policy = RetryPolicy(attempts = 3, delayMillis = 0),
                shouldRetry = { false },
            ) { attempt ->
                attempts += attempt
                error("stop")
            }
        }
        assertEquals("stop", failure.message)
        assertEquals(listOf(0), attempts)

        val cancellation = assertFailsWith<CancellationException> {
            retry(policy = RetryPolicy(attempts = 3, delayMillis = 0)) {
                throw CancellationException("cancel")
            }
        }
        assertEquals("cancel", cancellation.message)
    }

    @Test
    fun resourceAwaitWithRetryInvalidatesFailedCacheBetweenAttempts() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var resource: Resource<String>
        var loads = 0

        runtime.render(scope) {
            resource = resource(RetryKey, scope = CacheScope.Component) {
                loads += 1
                if (loads < 3) {
                    error("try $loads")
                }
                "loaded"
            }
        }

        assertEquals("loaded", resource.awaitWithRetry(RetryPolicy(attempts = 3, delayMillis = 0)))
        assertEquals(3, loads)
    }

    @Test
    fun resourceAwaitWithRetryStopsAtAttemptLimit() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var resource: Resource<String>
        var loads = 0

        runtime.render(scope) {
            resource = resource(FailingRetryKey, scope = CacheScope.Component) {
                loads += 1
                error("try $loads")
            }
        }

        val failure = assertFailsWith<IllegalStateException> {
            resource.awaitWithRetry(RetryPolicy(attempts = 2, delayMillis = 0))
        }
        assertEquals("try 2", failure.message)
        assertEquals(2, loads)
    }

    @Test
    fun defaultDataHelpersUseConfiguredFallbacksAndInvalidation() = runTest {
        val retried = retry { attempt ->
            assertEquals(0, attempt)
            "ok"
        }
        assertEquals("ok", retried)

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var resource: Resource<String>
        lateinit var paginator: Paginator<Int>
        lateinit var save: Action<String, String>
        val optimisticTitles = mutableListOf<String>()

        runtime.render(scope) {
            resource = resource(DefaultRetryKey, scope = CacheScope.Component) { "cached" }
            paginator = paginator()
            save = optimisticAction(
                invalidates = { emptyList() },
                optimistic = { title -> optimisticTitles += title },
            ) {
                error("rejected")
            }
        }

        assertEquals("cached", resource.awaitWithRetry())
        assertEquals(emptyList(), paginator.items)
        assertTrue(paginator.canLoadMore)

        val failure = assertFailsWith<IllegalStateException> {
            save("draft")
        }
        assertEquals("rejected", failure.message)
        assertEquals(listOf("draft"), optimisticTitles)

        invalidateAll(DefaultRetryKey, DataKey)
        assertTrue(runtime.journal().any { it.message == "resource invalidated" })

        val cache = InMemoryOfflineCache<String, String>()
        val loaded = cache.load("profile") { key -> "$key-network" }
        assertEquals(OfflineSource.Network, loaded.source)
        assertEquals("profile-network", loaded.value)
    }

    @Test
    fun paginatorAppendsPagesAndStopsAtEnd() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var paginator: Paginator<Int>
        val requests = mutableListOf<PageRequest>()

        runtime.render(scope) {
            paginator = paginator(initialRequest = PageRequest(offset = 0, limit = 2))
        }

        assertTrue(
            paginator.loadNext { request ->
                requests += request
                Page(
                    items = listOf(1, 2),
                    next = request.next(loadedCount = 2),
                    totalCount = 3,
                )
            },
        )
        assertEquals(listOf(1, 2), paginator.items)
        assertEquals(3, paginator.totalCount)
        assertEquals(3, paginator.asLazyItems().estimatedSize)
        assertEquals(listOf(1, 2), paginator.asLazyItems().toList())
        assertTrue(paginator.canLoadMore)

        assertTrue(
            paginator.loadNext { request ->
                requests += request
                Page(items = listOf(3), next = null, totalCount = 3)
            },
        )
        assertEquals(listOf(PageRequest(0, 2), PageRequest(2, 2)), requests)
        assertEquals(listOf(1, 2, 3), paginator.items)
        assertEquals(3, paginator.totalCount)
        assertFalse(paginator.canLoadMore)
        assertFalse(paginator.loadNext { error("No more pages should be requested") })

        paginator.reset(PageRequest(offset = 10, limit = 5))
        assertEquals(emptyList(), paginator.items)
        assertEquals(null, paginator.totalCount)
        assertTrue(paginator.canLoadMore)
    }

    @Test
    fun paginatorRecordsFailuresAndPageTypesValidateInputs() = runTest {
        assertFailsWith<IllegalArgumentException> {
            PageRequest(offset = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            PageRequest(limit = 0)
        }
        assertTrue(Page(items = listOf("only"), next = null).isEnd)

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var paginator: Paginator<Int>
        runtime.render(scope) {
            paginator = paginator(initialRequest = PageRequest(offset = 0, limit = 2))
        }

        val failure = assertFailsWith<IllegalStateException> {
            paginator.loadNext {
                error("offline")
            }
        }

        assertEquals("offline", failure.message)
        assertEquals("offline", paginator.error?.message)
        assertFalse(paginator.isLoading)
        assertTrue(paginator.canLoadMore)

        paginator.reset()
        assertEquals(emptyList(), paginator.items)
        assertEquals(null, paginator.error)
        assertEquals(null, paginator.totalCount)
        assertFalse(paginator.isLoading)
    }

    @Test
    fun paginatorRejectsOverlappingLoadsWhileCurrentPageIsInFlight() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var paginator: Paginator<Int>
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        runtime.render(scope) {
            paginator = paginator(initialRequest = PageRequest(offset = 0, limit = 2))
        }

        val firstLoad = async {
            paginator.loadNext { request ->
                started.complete(Unit)
                release.await()
                Page(items = listOf(request.offset), next = null)
            }
        }
        started.await()

        assertTrue(paginator.isLoading)
        assertFalse(
            paginator.loadNext {
                error("overlapping load must not invoke the loader")
            },
        )

        release.complete(Unit)
        assertTrue(firstLoad.await())
        assertEquals(listOf(0), paginator.items)
        assertFalse(paginator.isLoading)
    }

    @Test
    fun optimisticActionRollsBackOnFailureAndInvalidatesOnSuccess() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val titles = mutableListOf<String>()
        lateinit var add: Action<String, String>

        runtime.render(scope) {
            add = optimisticAction(
                invalidates = { listOf(DataKey) },
                optimistic = { title -> titles += title },
                rollback = { title, _ -> titles -= title },
            ) { title ->
                if (title == "bad") {
                    error("rejected")
                }
                title.uppercase()
            }
        }

        assertFailsWith<IllegalStateException> {
            add("bad")
        }
        assertEquals(emptyList(), titles)
        assertFalse(runtime.journal().any { it.message == "action invalidated resource" })

        assertEquals("OK", add("ok"))
        assertEquals(listOf("ok"), titles)
        assertTrue(runtime.journal().any { it.message == "action invalidated resource" })
    }

    @Test
    fun offlineCacheSupportsCacheFirstAndStaleNetworkFallback() = runTest {
        val cache = InMemoryOfflineCache<String, String>()
        var fetches = 0

        val first = cache.load("orders", strategy = OfflineStrategy.CacheFirst) { key ->
            fetches += 1
            "$key-network"
        }
        assertEquals(OfflineSource.Network, first.source)
        assertEquals("orders-network", first.value)
        assertEquals(1, fetches)

        val cached = cache.load("orders", strategy = OfflineStrategy.CacheFirst) {
            error("cache-first should not fetch when cached")
        }
        assertEquals(OfflineSource.Cache, cached.source)
        assertFalse(cached.stale)
        assertEquals("orders-network", cached.value)

        val stale = cache.load("orders", strategy = OfflineStrategy.NetworkFirst) {
            error("offline")
        }
        assertEquals(OfflineSource.Cache, stale.source)
        assertTrue(stale.stale)
        assertEquals("orders-network", stale.value)
        assertEquals("offline", stale.failure?.message)

        val cancellation = assertFailsWith<CancellationException> {
            cache.load("orders", strategy = OfflineStrategy.NetworkFirst) {
                throw CancellationException("cancelled")
            }
        }
        assertEquals("cancelled", cancellation.message)
        assertEquals("orders-network", cache.read("orders"))

        assertEquals(mapOf("orders" to "orders-network"), cache.snapshot())
        cache.remove("orders")
        assertEquals(null, cache.read("orders"))

        val missing = assertFailsWith<IllegalStateException> {
            cache.load("orders", strategy = OfflineStrategy.NetworkFirst) {
                error("still offline")
            }
        }
        assertEquals("still offline", missing.message)
    }
}

private data object DataKey : ResourceKey

private data object RetryKey : ResourceKey

private data object FailingRetryKey : ResourceKey

private data object DefaultRetryKey : ResourceKey
