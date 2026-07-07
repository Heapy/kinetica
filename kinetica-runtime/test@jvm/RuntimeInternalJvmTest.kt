package io.heapy.kinetica

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RuntimeInternalJvmTest {
    @Test
    fun derivedCellRecomputesAfterLastObserverClearsSubscriptions() {
        val source = store(10)
        val derived = DerivedCell(EqualityPolicy.structural()) {
            source.value * 2
        }
        val subscription = derived.observe {}
        assertEquals(20, derived.value)
        subscription.dispose()

        source.value = 11

        assertEquals(22, derived.value)
    }

    @Test
    fun readTrackingKeepsConcurrentCollectorsIsolatedByThread() {
        val sourceA = store("a")
        val sourceB = store("b")
        val dependenciesA = Collections.synchronizedList(mutableListOf<Cell<*>>())
        val dependenciesB = Collections.synchronizedList(mutableListOf<Cell<*>>())
        val collectorAEntered = CountDownLatch(1)
        val collectorBEntered = CountDownLatch(1)
        val releaseCollectorB = CountDownLatch(1)

        val threadA = thread {
            ReadTracking.collect(observer = { cell -> dependenciesA += cell }) {
                collectorAEntered.countDown()
                collectorBEntered.await()
                sourceA.value
                releaseCollectorB.countDown()
            }
        }
        val threadB = thread {
            collectorAEntered.await()
            ReadTracking.collect(observer = { cell -> dependenciesB += cell }) {
                collectorBEntered.countDown()
                releaseCollectorB.await()
                sourceB.value
            }
        }

        threadA.join()
        threadB.join()

        assertTrue(sourceA in dependenciesA)
        assertFalse(sourceB in dependenciesA)
        assertTrue(sourceB in dependenciesB)
        assertFalse(sourceA in dependenciesB)
    }

    @Test
    fun runtimeTaskHandleMarksIdleAtMostOnceUnderConcurrentCalls() = runTest {
        val markedIdle = AtomicInteger(0)
        val handle = RuntimeTaskHandle {
            markedIdle.incrementAndGet()
        }
        val gate = CompletableDeferred<Unit>()

        coroutineScope {
            val jobs = List(1024) {
                launch(Dispatchers.Default) {
                    gate.await()
                    handle.markIdle()
                }
            }
            gate.complete(Unit)
            jobs.joinAll()
        }

        assertEquals(1, markedIdle.get())
    }

    @Test
    fun runtimeInternalEventsWarningsAndTrackedTasksCoverDefaults() = runTest {
        val runtime = KineticaRuntime()
        var payload: Any? = null
        val eventId = runtime.registerEvent { value -> payload = value }

        runtime.dispatch("missing-event")
        runtime.dispatch(eventId, "payload")

        assertEquals("payload", payload)

        val warning = runtime.warn("TEST_WARNING", "warning emitted")
        assertEquals(warning, runtime.warnings().single())

        val taskStarted = CompletableDeferred<Unit>()
        val releaseTask = CompletableDeferred<Unit>()
        runtime.launchTrackedTask { handle ->
            taskStarted.complete(Unit)
            releaseTask.await()
            handle.markIdle()
        }
        taskStarted.await()

        val idleReturned = CompletableDeferred<Unit>()
        val idleJob = launch {
            runtime.awaitIdle()
            idleReturned.complete(Unit)
        }
        delay(25)
        assertFalse(idleReturned.isCompleted)
        releaseTask.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                idleReturned.await()
            }
        }
        idleJob.join()
    }

    @Test
    fun exitGroupCompletesConcurrentOnExitCallbacksAtomically() = runTest {
        val runtime = KineticaRuntime(exitTimeoutMillis = null)
        val scope = ComponentScope(runtime)
        val callbackCount = 1024
        exitPanelCallbackCount = callbackCount
        exitPanelVisible = true
        var visible = true

        fun render(): Node {
            exitPanelVisible = visible
            return runtime.render(scope) {
                ExitPanelProbe()
            }.tree
        }

        assertIs<TextNode>(render())

        visible = false
        assertIs<TextNode>(render())
        assertTrue(scope.isLeaving("panel"))

        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            val jobs = List(callbackCount) {
                launch(Dispatchers.Default) {
                    gate.await()
                    scope.completeExitCallback("panel", generation = 2)
                }
            }
            gate.complete(Unit)
            jobs.joinAll()
        }

        assertFalse(scope.isLeaving("panel"))
        assertEquals(FragmentNode(), render())
    }

    @Test
    fun completeExitCallbackRejectsStaleGenerationAndEmptyPendingState() {
        val scope = ComponentScope()
        val state = scope.exitGroupState("panel")
        state.phase = ExitPhase.Leaving
        state.generation = 2
        state.pendingCallbacks = 1

        assertFalse(scope.completeExitCallback("panel", generation = 1))
        assertTrue(scope.isLeaving("panel"))

        state.pendingCallbacks = 0

        assertFalse(scope.completeExitCallback("panel", generation = 2))
        assertTrue(scope.isLeaving("panel"))
    }
}

private var exitPanelVisible = true
private var exitPanelCallbackCount = 0

@UiComponent(skippable = false)
private fun ComponentScope.ExitPanelProbe() {
    exitGroup(key = "panel", visible = exitPanelVisible) {
        repeat(exitPanelCallbackCount) {
            onExit {}
        }
        text("Panel", semantics = Semantics(testTag = "panel"))
    }
}
