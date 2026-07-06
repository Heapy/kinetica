package io.heapy.kinetica

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/*
 * Frame-era port of the RuntimeSmokeTest resource & boundary sections: resources, actions,
 * loading/error boundaries, suspendSubtree, and effect-error routing. Explicit
 * `suspendSubtree(key = ...)` arguments were dropped: the compiler only frames the keyless
 * form (explicit keys were part of the deleted string-keyed model).
 */

private data object SmokeTodosKey : ResourceKey

private var smokeTodoLoads = 0
private var smokeTodosA: Resource<List<String>>? = null
private var smokeTodosB: Resource<List<String>>? = null
private var smokeAddAction: Action<String, String>? = null
private var smokeEchoAction: Action<String, String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeTodoResources() {
    smokeTodosA = resource(SmokeTodosKey) {
        smokeTodoLoads += 1
        listOf("one")
    }
    smokeTodosB = resource(SmokeTodosKey) {
        smokeTodoLoads += 1
        listOf("two")
    }
    smokeAddAction = action(invalidates = { listOf(SmokeTodosKey) }) { title: String -> title }
    smokeEchoAction = action { title: String -> "echo:$title" }
}

private data class SmokeReadStateKey(val id: Int) : ResourceKey

private var smokeReadStateKey = SmokeReadStateKey(0)
private var smokeReadStateRelease = CompletableDeferred<String>()
private var smokeReadStateResource: Resource<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeReadStateProbe() {
    smokeReadStateResource = resource(smokeReadStateKey) {
        smokeReadStateRelease.await()
    }
}

private data class SmokeAwaitCancellationKey(val id: Int) : ResourceKey

private var smokeAwaitCancellationKey = SmokeAwaitCancellationKey(0)
private var smokeAwaitCancellationLoads = 0
private var smokeAwaitCancellationResource: Resource<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeAwaitCancellationProbe() {
    smokeAwaitCancellationResource = resource(smokeAwaitCancellationKey) {
        smokeAwaitCancellationLoads += 1
        if (smokeAwaitCancellationLoads == 1) {
            throw CancellationException("transient await")
        }
        "await-ready"
    }
}

private data class SmokeReadCancellationKey(val id: Int) : ResourceKey

private var smokeReadCancellationKey = SmokeReadCancellationKey(0)
private var smokeReadCancellationLoads = 0

@UiComponent(skippable = false)
private fun ComponentScope.SmokeReadCancellationProbe() {
    loadingBoundary(fallback = { text("Loading") }) {
        val value = resource(smokeReadCancellationKey) {
            smokeReadCancellationLoads += 1
            if (smokeReadCancellationLoads == 1) {
                throw CancellationException("transient read")
            }
            "read-ready"
        }.read()
        text(value)
    }
}

private data class SmokeInFlightKey(val id: Int) : ResourceKey

private var smokeInFlightKey = SmokeInFlightKey(0)
private var smokeInFlightGate = CompletableDeferred<String>()
private var smokeInFlightLoads = 0
private var smokeInFlightFirst: Resource<String>? = null
private var smokeInFlightSecond: Resource<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeInFlightResources() {
    smokeInFlightFirst = resource(smokeInFlightKey) {
        smokeInFlightLoads += 1
        smokeInFlightGate.await()
    }
    smokeInFlightSecond = resource(smokeInFlightKey) {
        smokeInFlightLoads += 1
        smokeInFlightGate.await()
    }
}

// The barebones Node runner does not await runTest promises, so async tests in one file
// run interleaved. The two stale-load tests used to share gate/loads/current vars; those
// collaborators now live in a per-test probe (with a per-test resource key) passed as a
// component parameter.
private data class SmokeStaleKey(val id: Int) : ResourceKey

private class StaleProbe(salt: Int) {
    val key = SmokeStaleKey(salt)
    var gate = CompletableDeferred<String>()
    var loads = 0
    var current: Resource<String>? = null
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeStaleResource(probe: StaleProbe) {
    loadingBoundary(fallback = { text("Loading") }) {
        val value = resource(probe.key) {
            probe.loads += 1
            probe.gate.await()
        }.also { probe.current = it }.read()
        text(value)
    }
}

private data class SmokeLiveProductsKey(val id: Int) : ResourceKey

private var smokeLiveProductsKey = SmokeLiveProductsKey(0)
private var smokeLiveProductsLoads = 0

@UiComponent(skippable = false)
private fun ComponentScope.SmokeLiveProducts() {
    loadingBoundary(fallback = { text("Loading") }) {
        val value = resource(smokeLiveProductsKey) {
            smokeLiveProductsLoads += 1
            "Products $smokeLiveProductsLoads"
        }.read()
        text(value)
    }
}

private data class SmokePredicateKey(val id: String, val salt: Int) : ResourceKey

private var smokePredicateFirstKey = SmokePredicateKey("first", 0)
private var smokePredicateSecondKey = SmokePredicateKey("second", 0)
private var smokePredicateFirstLoads = 0
private var smokePredicateSecondLoads = 0
private var smokePredicateFirst: Resource<String>? = null
private var smokePredicateSecond: Resource<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokePredicateResources() {
    smokePredicateFirst = resource(smokePredicateFirstKey) {
        smokePredicateFirstLoads += 1
        "first-$smokePredicateFirstLoads"
    }
    smokePredicateSecond = resource(smokePredicateSecondKey) {
        smokePredicateSecondLoads += 1
        "second-$smokePredicateSecondLoads"
    }
}

private data object SmokeComponentScopedKey : ResourceKey

private var smokeComponentScopedCaptured: Resource<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeComponentScopedResource(value: String) {
    smokeComponentScopedCaptured = resource(SmokeComponentScopedKey, scope = CacheScope.Component) {
        value
    }
}

private data class SmokeLongRunningComponentKey(val id: Int) : ResourceKey

private var smokeLongRunningKey = SmokeLongRunningComponentKey(0)
private var smokeLongRunningVisible = true
private var smokeLongRunningStarted = CompletableDeferred<Unit>()
private var smokeLongRunningCancelled = CompletableDeferred<Unit>()
private var smokeLongRunningNever = CompletableDeferred<String>()

@UiComponent(skippable = false)
private fun ComponentScope.SmokeLongRunningComponentResource() {
    if (smokeLongRunningVisible) {
        loadingBoundary(fallback = { text("Loading") }) {
            val value = resource(smokeLongRunningKey, scope = CacheScope.Component) {
                smokeLongRunningStarted.complete(Unit)
                try {
                    smokeLongRunningNever.await()
                } catch (error: CancellationException) {
                    smokeLongRunningCancelled.complete(Unit)
                    throw error
                }
            }.read()
            text(value)
        }
    } else {
        text("Closed")
    }
}

private data class SmokeTemporaryScopedKey(val scope: String, val id: Int) : ResourceKey

private var smokeEvictionKey = SmokeTemporaryScopedKey("app", 0)
private var smokeEvictionScope = CacheScope.App
private var smokeEvictionVisible = true
private var smokeEvictionLoads = 0
private var smokeEvictionPrefix = "app"
private var smokeEvictionResource: Resource<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeEvictionResource() {
    if (smokeEvictionVisible) {
        smokeEvictionResource = resource(smokeEvictionKey, scope = smokeEvictionScope) {
            smokeEvictionLoads += 1
            "$smokeEvictionPrefix-$smokeEvictionLoads"
        }
    } else {
        text("gone")
    }
}

private data class SmokeRuntimeAppKey(val id: Int) : ResourceKey

private var smokeRuntimeAppKey = SmokeRuntimeAppKey(0)
private var smokeRuntimeAppFirstLoads = 0
private var smokeRuntimeAppSecondLoads = 0
private var smokeRuntimeAppFirst: Resource<String>? = null
private var smokeRuntimeAppSecond: Resource<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeRuntimeAppFirstResource() {
    smokeRuntimeAppFirst = resource(smokeRuntimeAppKey, scope = CacheScope.App) {
        smokeRuntimeAppFirstLoads += 1
        "first-$smokeRuntimeAppFirstLoads"
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeRuntimeAppSecondResource() {
    smokeRuntimeAppSecond = resource(smokeRuntimeAppKey, scope = CacheScope.App) {
        smokeRuntimeAppSecondLoads += 1
        "second-$smokeRuntimeAppSecondLoads"
    }
}

private data object SmokeAppTtlKey : ResourceKey

private var smokeTtlLoads = 0
private var smokeTtlOrders: Resource<Int>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeTtlResource() {
    smokeTtlOrders = resource(SmokeAppTtlKey, scope = CacheScope.App) {
        smokeTtlLoads += 1
        smokeTtlLoads
    }
}

private data object SmokeAsyncBoundaryKey : ResourceKey

private var smokeAsyncGate = CompletableDeferred<String>()
private var smokeAsyncResource: Resource<String>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeLoadingBoundaryApp() {
    loadingBoundary(fallback = { text("Loading") }) {
        val current = resource(SmokeAsyncBoundaryKey) {
            smokeAsyncGate.await()
        }
        smokeAsyncResource = current
        text("Value: ${current.read()}")
    }
}

private data class SmokeErrorBoundaryPendingKey(val id: Int) : ResourceKey

private var smokeErrorPendingKey = SmokeErrorBoundaryPendingKey(0)
private var smokeErrorPendingRelease = CompletableDeferred<String>()

@UiComponent(skippable = false)
private fun ComponentScope.SmokeErrorBoundaryPending() {
    loadingBoundary(fallback = { text("Loading") }) {
        errorBoundary(
            fallback = { error, _, _ -> text("Error: ${error.message}") },
        ) {
            val value = resource(smokeErrorPendingKey) {
                smokeErrorPendingRelease.await()
            }.read()
            text("Value: $value")
        }
    }
}

private var smokeRetryFail = true
private var smokeRetryHandle: BoundaryRetry? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeRetryBoundary() {
    errorBoundary(
        fallback = { error, _, boundaryRetry ->
            smokeRetryHandle = boundaryRetry
            text("Error: ${error.message}")
        },
    ) {
        if (smokeRetryFail) {
            error("boom")
        }
        text("Recovered")
        text("Again")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeEmptyBoundary() {
    errorBoundary(fallback = { _, _, _ -> text("unused") }) {
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeCancellingBoundary() {
    errorBoundary(fallback = { _, _, _ -> text("unused") }) {
        throw CancellationException("cancelled")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokePartialContentBoundary() {
    errorBoundary(
        fallback = { error, _, _ -> text("Error: ${error.message}") },
    ) {
        text("Header")
        error("boom")
    }
}

// Shared by the two suspendSubtree tests below that used to race on one release gate.
private class SubtreeProbe {
    val release = CompletableDeferred<Unit>()
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeSuspendSubtreeApp(probe: SubtreeProbe) {
    column {
        text("Before")
        suspendSubtree(
            fallback = { text("Loading async") },
        ) {
            probe.release.await()
            text("Async ready")
        }
        text("After")
    }
}

private var smokeSubtreeVisible = true
private var smokeSubtreeEntered = CompletableDeferred<Unit>()
private var smokeSubtreeCancelled = CompletableDeferred<Unit>()
private var smokeSubtreeNever = CompletableDeferred<Unit>()

@UiComponent(skippable = false)
private fun ComponentScope.SmokeSuspendSubtreeCancellable() {
    if (smokeSubtreeVisible) {
        suspendSubtree(
            fallback = { text("Loading async") },
        ) {
            smokeSubtreeEntered.complete(Unit)
            try {
                smokeSubtreeNever.await()
            } catch (cancellation: CancellationException) {
                smokeSubtreeCancelled.complete(Unit)
                throw cancellation
            }
            text("unreachable")
        }
    } else {
        text("Hidden")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeSuspendSubtreeFailing(probe: SubtreeProbe) {
    errorBoundary(
        fallback = { error, _, _ -> text("Async error: ${error.message}") },
    ) {
        suspendSubtree(
            fallback = { text("Loading async") },
        ) {
            probe.release.await()
            error("bad async")
        }
    }
}

private data object SmokeFailingBoundaryKey : ResourceKey

@UiComponent(skippable = false)
private fun ComponentScope.SmokeFailingResourceBoundary() {
    errorBoundary(
        fallback = { error, _, _ -> text("Error: ${error.message}") },
    ) {
        loadingBoundary(fallback = { text("Loading") }) {
            val value: String = resource(SmokeFailingBoundaryKey) {
                throw IllegalStateException("boom")
            }.read()
            text(value)
        }
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeFailingEffectBoundary() {
    errorBoundary(
        fallback = { error, _, _ -> text("Effect error: ${error.message}") },
    ) {
        launchEffect {
            throw IllegalStateException("boom")
        }
        text("Body")
    }
}

@UiComponent(skippable = false)
private fun ComponentScope.SmokeUnboundedFailingEffect() {
    launchEffect {
        throw IllegalStateException("outside boundary")
    }
    text("Body")
}

private var smokeWatchErrorCountCell: MutableCell<Int>? = null

@UiComponent(skippable = false)
private fun ComponentScope.SmokeFailingWatchBoundary() {
    errorBoundary(
        fallback = { error, _, _ -> text("Watch error: ${error.message}") },
    ) {
        val count = state { 0 }
        smokeWatchErrorCountCell = count
        watch({ count.value }) { value ->
            throw IllegalStateException("bad value $value")
        }
        text("Count: ${count.value}")
    }
}

class RuntimeSmokeResourceTest {
    @Test
    fun resourcesAreSingleFlightAndActionsInvalidateKeys() = runTest {
        smokeTodoLoads = 0
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        runtime.render(scope) { SmokeTodoResources() }

        assertEquals(listOf("one"), smokeTodosA!!.await())
        assertEquals(listOf("one"), smokeTodosB!!.await())
        assertEquals(1, smokeTodoLoads)
        assertEquals("new", smokeAddAction!!("new"))
        assertEquals("echo:new", smokeEchoAction!!("new"))
        assertEquals(listOf("one"), smokeTodosA!!.await())
        assertEquals(2, smokeTodoLoads)
    }

    @Test
    fun resourceReadReportsLoadingReadyFailedAndInvalidatedStates() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val key = SmokeReadStateKey(runtime.hashCode() + scope.hashCode())
        smokeReadStateKey = key
        smokeReadStateRelease = CompletableDeferred()

        fun render() {
            runtime.render(scope) { SmokeReadStateProbe() }
        }

        render()
        val current = smokeReadStateResource!!
        assertIs<ResourceState.Idle>(current.state)
        val firstPending = assertFailsWith<RuntimeException> {
            current.read()
        }
        assertEquals("Resource is pending: $key", firstPending.message)
        assertIs<ResourceState.Loading>(current.state)
        val secondPending = assertFailsWith<RuntimeException> {
            current.read()
        }
        assertEquals("Resource is pending: $key", secondPending.message)

        smokeReadStateRelease.complete("ready")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        val readyState = assertIs<ResourceState.Ready<String>>(current.state)
        assertEquals("ready", readyState.value)
        assertEquals("ready", current.read())
        assertIs<ResourceState.Ready<String>>(current.state)

        current.invalidate()
        assertIs<ResourceState.Idle>(current.state)

        smokeReadStateRelease = CompletableDeferred()
        render()
        val thirdPending = assertFailsWith<RuntimeException> {
            smokeReadStateResource!!.read()
        }
        assertEquals("Resource is pending: $key", thirdPending.message)
        smokeReadStateRelease.completeExceptionally(IllegalStateException("unavailable"))
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        val failure = assertFailsWith<IllegalStateException> {
            smokeReadStateResource!!.read()
        }
        assertEquals("unavailable", failure.message)
        val failedState = assertIs<ResourceState.Failed>(smokeReadStateResource!!.state)
        assertEquals("unavailable", failedState.error.message)
    }

    @Test
    fun resourceCancellationDoesNotCachePermanentFailure() = runTest {
        val awaitRuntime = KineticaRuntime()
        val awaitScope = ComponentScope(awaitRuntime)
        smokeAwaitCancellationKey = SmokeAwaitCancellationKey(awaitRuntime.hashCode() + awaitScope.hashCode())
        smokeAwaitCancellationLoads = 0

        awaitRuntime.render(awaitScope) { SmokeAwaitCancellationProbe() }

        val awaitCancellation = assertFailsWith<CancellationException> {
            smokeAwaitCancellationResource!!.await()
        }
        assertEquals("transient await", awaitCancellation.message)
        assertIs<ResourceState.Idle>(smokeAwaitCancellationResource!!.state)
        assertEquals("await-ready", smokeAwaitCancellationResource!!.await())
        assertEquals(2, smokeAwaitCancellationLoads)

        val readRuntime = KineticaRuntime()
        val readScope = ComponentScope(readRuntime)
        smokeReadCancellationKey = SmokeReadCancellationKey(readRuntime.hashCode() + readScope.hashCode())
        smokeReadCancellationLoads = 0

        fun renderRead(): Node = readRuntime.render(readScope) { SmokeReadCancellationProbe() }.tree

        assertEquals("Loading", renderRead().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                readRuntime.awaitIdle()
            }
        }

        assertTrue(readRuntime.hasPendingInvalidation)
        assertEquals("Loading", renderRead().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                readRuntime.awaitIdle()
            }
        }
        assertEquals("read-ready", renderRead().findText().value)
        assertEquals(2, smokeReadCancellationLoads)
    }

    @Test
    fun resourcesShareInFlightAwaitsAndIgnoreStaleCompletionAfterInvalidation() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeInFlightKey = SmokeInFlightKey(runtime.hashCode() + scope.hashCode())
        smokeInFlightGate = CompletableDeferred()
        smokeInFlightLoads = 0

        fun render() {
            runtime.render(scope) { SmokeInFlightResources() }
        }

        render()
        var firstValue = ""
        var secondValue = ""
        val firstAwait = launch { firstValue = smokeInFlightFirst!!.await() }
        delay(1)
        assertEquals(1, smokeInFlightLoads)

        val secondAwait = launch { secondValue = smokeInFlightSecond!!.await() }
        delay(1)
        assertEquals(1, smokeInFlightLoads)

        smokeInFlightGate.complete("shared")
        joinAll(firstAwait, secondAwait)
        assertEquals("shared", firstValue)
        assertEquals("shared", secondValue)

        val first = smokeInFlightFirst!!
        first.invalidate()
        assertIs<ResourceState.Idle>(first.state)
        smokeInFlightGate = CompletableDeferred()
        render()
        assertFailsWith<RuntimeException> {
            smokeInFlightFirst!!.read()
        }
        assertIs<ResourceState.Loading>(smokeInFlightFirst!!.state)
        smokeInFlightFirst!!.invalidate()
        assertIs<ResourceState.Idle>(smokeInFlightFirst!!.state)
        smokeInFlightGate.complete("stale-success")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertIs<ResourceState.Idle>(smokeInFlightFirst!!.state)

        smokeInFlightGate = CompletableDeferred()
        render()
        assertFailsWith<RuntimeException> {
            smokeInFlightFirst!!.read()
        }
        smokeInFlightFirst!!.invalidate()
        smokeInFlightGate.completeExceptionally(IllegalStateException("stale-failure"))
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertIs<ResourceState.Idle>(smokeInFlightFirst!!.state)
        assertEquals(3, smokeInFlightLoads)
    }

    @Test
    fun staleResourceCompletionCannotOverwriteFreshLoadAfterInvalidation() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = StaleProbe(runtime.hashCode() + scope.hashCode())
        val staleGate = probe.gate

        suspend fun awaitLoads(expected: Int) {
            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    while (probe.loads < expected) {
                        delay(10)
                    }
                }
            }
        }

        fun render(): Node = runtime.render(scope) { SmokeStaleResource(probe) }.tree

        assertEquals("Loading", render().findText().value)
        awaitLoads(1)
        assertEquals(1, probe.loads)

        probe.current!!.invalidate()
        val freshGate = CompletableDeferred<String>()
        probe.gate = freshGate
        assertEquals("Loading", render().findText().value)
        awaitLoads(2)
        assertEquals(2, probe.loads)

        freshGate.complete("fresh")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (!runtime.hasPendingInvalidation) {
                    delay(10)
                }
            }
        }
        assertEquals("fresh", render().findText().value)

        staleGate.complete("stale")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertEquals("fresh", render().findText().value)
        assertEquals(2, probe.loads)
        scope.dispose()
    }

    @Test
    fun staleResourceFailuresAndCancellationsCannotOverwriteFreshLoads() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = StaleProbe(runtime.hashCode() + scope.hashCode())

        suspend fun awaitLoads(expected: Int) {
            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    while (probe.loads < expected) {
                        delay(10)
                    }
                }
            }
        }

        suspend fun yieldDefaultDispatcher() {
            withContext(Dispatchers.Default) {
                delay(50)
            }
        }

        fun render(): Node = runtime.render(scope) { SmokeStaleResource(probe) }.tree

        assertEquals("Loading", render().findText().value)
        awaitLoads(1)

        val staleFailure = probe.gate
        probe.current!!.invalidate()
        probe.gate = CompletableDeferred()
        assertEquals("Loading", render().findText().value)
        awaitLoads(2)

        staleFailure.completeExceptionally(IllegalStateException("stale failure"))
        yieldDefaultDispatcher()
        assertIs<ResourceState.Loading>(probe.current!!.state)

        probe.gate.complete("fresh")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertEquals("fresh", render().findText().value)

        probe.current!!.invalidate()
        probe.gate = CompletableDeferred()
        assertEquals("fresh", render().findText().value)
        assertIs<ResourceState.Loading>(probe.current!!.state)
        awaitLoads(3)

        val staleCancellation = probe.gate
        probe.current!!.invalidate()
        probe.gate = CompletableDeferred()
        assertEquals("fresh", render().findText().value)
        assertIs<ResourceState.Loading>(probe.current!!.state)
        awaitLoads(4)

        staleCancellation.completeExceptionally(CancellationException("stale cancellation"))
        yieldDefaultDispatcher()
        assertIs<ResourceState.Loading>(probe.current!!.state)

        probe.gate.complete("fresh after cancellation")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertEquals("fresh after cancellation", render().findText().value)
        assertEquals(4, probe.loads)
        scope.dispose()
    }

    @Test
    fun topLevelInvalidateNotifiesLiveResourcesAndReloadsOnNextRender() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeLiveProductsKey = SmokeLiveProductsKey(runtime.hashCode() + scope.hashCode())
        smokeLiveProductsLoads = 0

        fun render(): Node = runtime.render(scope) { SmokeLiveProducts() }.tree

        assertEquals("Loading", render().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertEquals("Products 1", render().findText().value)

        invalidate(smokeLiveProductsKey)
        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Products 1", render().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }
        assertEquals("Products 2", render().findText().value)
    }

    @Test
    fun predicateInvalidateOnlyReloadsMatchingLiveResources() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokePredicateFirstKey = SmokePredicateKey("first", runtime.hashCode())
        smokePredicateSecondKey = SmokePredicateKey("second", scope.hashCode())
        smokePredicateFirstLoads = 0
        smokePredicateSecondLoads = 0

        runtime.render(scope) { SmokePredicateResources() }

        assertEquals("first-1", smokePredicateFirst!!.await())
        assertEquals("second-1", smokePredicateSecond!!.await())

        invalidate { key -> key is SmokePredicateKey && key.id == "second" }

        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("first-1", smokePredicateFirst!!.await())
        assertEquals("second-2", smokePredicateSecond!!.await())
        assertEquals(1, smokePredicateFirstLoads)
        assertEquals(2, smokePredicateSecondLoads)
        assertTrue(
            runtime.journal().any { entry ->
                entry.kind == JournalKind.ResourceInvalidated &&
                    entry.attributes["key"] == smokePredicateSecondKey.toString()
            },
        )
    }

    @Test
    fun componentScopedResourcesAreIsolatedByComponentInstance() = runTest {
        val runtime = KineticaRuntime()
        val firstScope = ComponentScope(runtime, instanceId = "first")
        val secondScope = ComponentScope(runtime, instanceId = "second")

        runtime.render(firstScope) { SmokeComponentScopedResource("first") }
        val first = smokeComponentScopedCaptured!!
        runtime.render(secondScope) { SmokeComponentScopedResource("second") }
        val second = smokeComponentScopedCaptured!!

        assertEquals("first", first.await())
        assertEquals("second", second.await())
    }

    @Test
    fun componentScopedResourceLoadIsCancelledWhenSlotIsDisposed() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeLongRunningKey = SmokeLongRunningComponentKey(runtime.hashCode() + scope.hashCode())
        smokeLongRunningVisible = true
        smokeLongRunningStarted = CompletableDeferred()
        smokeLongRunningCancelled = CompletableDeferred()
        smokeLongRunningNever = CompletableDeferred()

        fun render(): Node = runtime.render(scope) { SmokeLongRunningComponentResource() }.tree

        try {
            assertEquals("Loading", render().findText().value)
            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    smokeLongRunningStarted.await()
                }
            }

            smokeLongRunningVisible = false
            assertEquals("Closed", render().findText().value)

            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    runtime.awaitIdle()
                    smokeLongRunningCancelled.await()
                }
            }
        } finally {
            scope.dispose()
            runtime.dispose()
        }
    }

    @Test
    fun resourceCacheEvictionMatchesScopeLifetime() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val salt = runtime.hashCode() + scope.hashCode()

        fun render() {
            runtime.render(scope) { SmokeEvictionResource() }
        }

        smokeEvictionKey = SmokeTemporaryScopedKey("app", salt)
        smokeEvictionScope = CacheScope.App
        smokeEvictionPrefix = "app"
        smokeEvictionLoads = 0
        smokeEvictionVisible = true

        render()
        assertEquals("app-1", smokeEvictionResource!!.await())

        smokeEvictionVisible = false
        render()

        smokeEvictionVisible = true
        render()
        assertEquals("app-1", smokeEvictionResource!!.await())
        assertEquals(1, smokeEvictionLoads)

        smokeEvictionKey = SmokeTemporaryScopedKey("component", salt)
        smokeEvictionScope = CacheScope.Component
        smokeEvictionPrefix = "component"
        smokeEvictionLoads = 0
        smokeEvictionVisible = true

        render()
        assertEquals("component-1", smokeEvictionResource!!.await())

        smokeEvictionVisible = false
        render()

        smokeEvictionVisible = true
        render()
        assertEquals("component-2", smokeEvictionResource!!.await())
        assertEquals(2, smokeEvictionLoads)

        smokeEvictionKey = SmokeTemporaryScopedKey("request", salt)
        smokeEvictionScope = CacheScope.Request
        smokeEvictionPrefix = "request"
        smokeEvictionLoads = 0
        smokeEvictionVisible = true

        render()
        assertEquals("request-1", smokeEvictionResource!!.await())

        smokeEvictionVisible = false
        render()

        smokeEvictionVisible = true
        render()
        assertEquals("request-2", smokeEvictionResource!!.await())
        assertEquals(2, smokeEvictionLoads)
    }

    @Test
    fun appResourcesAreIsolatedByRuntimeInstance() = runTest {
        smokeRuntimeAppKey = SmokeRuntimeAppKey(Any().hashCode())
        smokeRuntimeAppFirstLoads = 0
        smokeRuntimeAppSecondLoads = 0
        val firstRuntime = KineticaRuntime()
        val firstScope = ComponentScope(firstRuntime)
        val secondRuntime = KineticaRuntime()
        val secondScope = ComponentScope(secondRuntime)

        firstRuntime.render(firstScope) { SmokeRuntimeAppFirstResource() }
        secondRuntime.render(secondScope) { SmokeRuntimeAppSecondResource() }

        assertEquals("first-1", smokeRuntimeAppFirst!!.await())
        assertEquals("second-1", smokeRuntimeAppSecond!!.await())
        assertEquals(1, smokeRuntimeAppFirstLoads)
        assertEquals(1, smokeRuntimeAppSecondLoads)
    }

    @Test
    fun appResourceCacheExpiresByRuntimeVirtualTimeTtl() = runTest {
        val runtime = KineticaRuntime(appResourceTtlMillis = 100)
        val scope = ComponentScope(runtime)
        smokeTtlLoads = 0

        fun render() {
            runtime.render(scope) { SmokeTtlResource() }
        }

        render()
        assertEquals(1, smokeTtlOrders!!.await())
        assertEquals(1, smokeTtlLoads)

        render()
        assertEquals(1, smokeTtlOrders!!.await())
        assertEquals(1, smokeTtlLoads)

        runtime.advanceVirtualTimeBy(99)
        render()
        assertEquals(1, smokeTtlOrders!!.await())
        assertEquals(1, smokeTtlLoads)

        runtime.advanceVirtualTimeBy(1)
        render()
        assertEquals(2, smokeTtlOrders!!.await())
        assertEquals(2, smokeTtlLoads)
    }

    @Test
    fun loadingBoundaryFallsBackRetainsPreviousUiAndResumesResourceReads() = runTest {
        smokeAsyncGate = CompletableDeferred()
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(): Node = runtime.render(scope) { SmokeLoadingBoundaryApp() }.tree

        assertEquals("Loading", render().findText().value)
        smokeAsyncGate.complete("one")
        withTimeout(2_000) {
            while (render().findText().value != "Value: one") {
                delay(10)
            }
        }

        smokeAsyncGate = CompletableDeferred()
        smokeAsyncResource!!.invalidate()
        assertEquals("Value: one", render().findText().value)

        smokeAsyncGate.complete("two")
        withTimeout(2_000) {
            while (render().findText().value != "Value: two") {
                delay(10)
            }
        }
    }

    @Test
    fun errorBoundaryRethrowsPendingResourcesToNearestLoadingBoundary() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeErrorPendingKey = SmokeErrorBoundaryPendingKey(runtime.hashCode() + scope.hashCode())
        smokeErrorPendingRelease = CompletableDeferred()

        fun render(): Node = runtime.render(scope) { SmokeErrorBoundaryPending() }.tree

        assertEquals("Loading", render().findText().value)

        smokeErrorPendingRelease.complete("ready")
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertEquals("Value: ready", render().findText().value)
        assertFalse(runtime.journal().any { it.kind == JournalKind.BoundaryError })
    }

    @Test
    fun errorBoundaryRetryClearsCapturedErrorsAndCancellationEscapes() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeRetryFail = true
        smokeRetryHandle = null

        fun render(): Node = runtime.render(scope) { SmokeRetryBoundary() }.tree

        assertEquals("Error: boom", render().findText().value)
        smokeRetryFail = false
        smokeRetryHandle!!.retry()
        assertEquals(listOf("Recovered", "Again"), render().findTexts().map { it.value })

        val emptyTree = KineticaRuntime().render {
            SmokeEmptyBoundary()
        }.tree
        assertEquals(FragmentNode(), emptyTree)

        val cancellation = assertFailsWith<CancellationException> {
            KineticaRuntime().render {
                SmokeCancellingBoundary()
            }
        }
        assertEquals("cancelled", cancellation.message)
    }

    @Test
    fun errorBoundaryDiscardsPartialContentBeforeRenderingFallback() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        val tree = runtime.render(scope) { SmokePartialContentBoundary() }.tree

        assertEquals(listOf("Error: boom"), tree.findTexts().map { it.value })
        assertTrue(runtime.journal().any { it.kind == JournalKind.BoundaryError })
    }

    @Test
    fun suspendSubtreeCommitsFallbackThenReadyNodeWithoutBlockingSiblings() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = SubtreeProbe()

        fun render(): Node = runtime.render(scope) { SmokeSuspendSubtreeApp(probe) }.tree

        assertEquals(listOf("Before", "Loading async", "After"), render().findTexts().map { it.value })

        probe.release.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertTrue(runtime.hasPendingInvalidation)
        assertEquals(listOf("Before", "Async ready", "After"), render().findTexts().map { it.value })
        assertTrue(runtime.journal().any { it.kind == JournalKind.DeferredSubtree && it.message == "suspend subtree ready" })
        scope.dispose()
    }


    @Test
    fun suspendSubtreeCancellationDisposesPendingStateWhenDeclarationLeavesRender() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeSubtreeVisible = true
        smokeSubtreeEntered = CompletableDeferred()
        smokeSubtreeCancelled = CompletableDeferred()
        smokeSubtreeNever = CompletableDeferred()

        fun render(): Node = runtime.render(scope) { SmokeSuspendSubtreeCancellable() }.tree

        assertEquals("Loading async", render().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                smokeSubtreeEntered.await()
            }
        }

        smokeSubtreeVisible = false
        assertEquals("Hidden", render().findText().value)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                smokeSubtreeCancelled.await()
            }
        }
    }

    @Test
    fun suspendSubtreeErrorsRenderNearestErrorBoundaryFallback() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val probe = SubtreeProbe()

        fun render(): Node = runtime.render(scope) { SmokeSuspendSubtreeFailing(probe) }.tree

        assertEquals("Loading async", render().findText().value)

        probe.release.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Async error: bad async", render().findText().value)
        assertTrue(runtime.journal().any { it.kind == JournalKind.DeferredSubtree && it.message == "suspend subtree failed" })
        scope.dispose()
    }

    @Test
    fun resourceReadFailuresRethrowToErrorBoundaryAfterPendingState() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(): Node = runtime.render(scope) { SmokeFailingResourceBoundary() }.tree

        assertEquals("Loading", render().findText().value)
        withTimeout(2_000) {
            while (render().findText().value != "Error: boom") {
                delay(10)
            }
        }
        assertTrue(runtime.journal().any { it.kind == JournalKind.BoundaryError })
    }

    @Test
    fun launchEffectErrorsRenderNearestErrorBoundaryFallback() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)

        fun render(): Node = runtime.render(scope) { SmokeFailingEffectBoundary() }.tree

        assertEquals("Body", render().findText().value)

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Effect error: boom", render().findText().value)
        val boundaryError = runtime.journal().last { it.kind == JournalKind.BoundaryError }
        assertEquals("errorBoundary caught error", boundaryError.message)
        assertTrue(boundaryError.attributes.getValue("boundaryId").isNotBlank())
    }

    @Test
    fun launchEffectErrorsWithoutBoundaryAreRecordedInJournal() = runTest {
        val runtime = KineticaRuntime()

        runtime.render {
            SmokeUnboundedFailingEffect()
        }

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        val boundaryError = runtime.journal().last { entry ->
            entry.kind == JournalKind.BoundaryError && entry.message == "unhandled effect error"
        }
        assertEquals("launchEffect", boundaryError.attributes["effectKey"])
        assertTrue(boundaryError.attributes.getValue("error").contains("outside boundary"))
    }

    @Test
    fun watchErrorsRenderNearestErrorBoundaryFallback() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        smokeWatchErrorCountCell = null

        fun render(): Node = runtime.render(scope) { SmokeFailingWatchBoundary() }.tree

        assertEquals("Count: 0", render().findText().value)

        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                runtime.awaitIdle()
            }
        }

        assertTrue(runtime.hasPendingInvalidation)
        assertEquals("Watch error: bad value 0", render().findText().value)
        assertTrue(runtime.journal().any { it.kind == JournalKind.BoundaryError })
    }
}

private fun Node.findText(): TextNode = when (this) {
    is TextNode -> this
    is FragmentNode -> children.firstNotNullOf { it.findTextOrNull() }
    is HostNode -> children.firstNotNullOf { it.findTextOrNull() }
    is ClientRef -> error("No text node")
    is TemplateNode -> materialize().findText()
}

private fun Node.findTextOrNull(): TextNode? = when (this) {
    is TextNode -> this
    is FragmentNode -> children.firstNotNullOfOrNull { it.findTextOrNull() }
    is HostNode -> children.firstNotNullOfOrNull { it.findTextOrNull() }
    is ClientRef -> null
    is TemplateNode -> materialize().findTextOrNull()
}

private fun Node.findTexts(): List<TextNode> = when (this) {
    is TextNode -> listOf(this)
    is FragmentNode -> children.flatMap { it.findTexts() }
    is HostNode -> children.flatMap { it.findTexts() }
    is ClientRef -> emptyList()
    is TemplateNode -> materialize().findTexts()
}
