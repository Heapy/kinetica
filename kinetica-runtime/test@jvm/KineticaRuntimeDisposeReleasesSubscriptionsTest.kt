package io.heapy.kinetica

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * R15 — `KineticaRuntime` has no teardown.
 *
 * `render` collects the [ObservableCell]s read during a render and, in
 * `updateRenderSubscriptions`, calls `dependency.observe { invalidate(...) }` for each,
 * storing the returned [Disposable] in `renderSubscriptions`. Nothing ever disposes those
 * subscriptions, and `effectScope` (a `SupervisorJob`) is never cancelled. A one-shot /
 * server-side runtime that reads a shared, long-lived store therefore leaves a live
 * listener on that store forever, and its coroutine scope stays active.
 *
 * Desired/correct behavior: the runtime must expose a teardown (`dispose()`/`close()`)
 * that (1) releases every render subscription — so a long-lived cell has zero remaining
 * observers afterwards — and (2) cancels `effectScope`.
 *
 * This test drives a real render against a shared cell (establishing exactly one live
 * observer), then asks the runtime to tear down. It FAILS on today's code because no such
 * teardown method exists, so the leaked observer can never be released and the scope can
 * never be cancelled.
 */
class KineticaRuntimeDisposeReleasesSubscriptionsTest {
    /**
     * A minimal long-lived [ObservableCell] that exposes its live-observer count, so the
     * render subscription can be observed directly (no atomicfu/reflection into store()).
     */
    private class CountingCell(initial: Int) : ObservableCell<Int> {
        private val liveObservers = AtomicInteger(0)
        private val versionCounter = AtomicLong(0)
        private var current = initial

        val observerCount: Int
            get() = liveObservers.get()

        override val value: Int
            get() {
                ReadTracking.record(this)
                return current
            }

        override val version: Long
            get() = versionCounter.get()

        fun write(next: Int) {
            current = next
            versionCounter.incrementAndGet()
        }

        override fun observe(listener: () -> Unit): Disposable {
            liveObservers.incrementAndGet()
            return Disposable { liveObservers.decrementAndGet() }
        }
    }

    @Test
    fun disposeReleasesRenderSubscriptionsAndCancelsEffectScope() {
        val shared = CountingCell(0)
        val runtime = KineticaRuntime(debug = false, journalSampleInterval = null)

        // Render a component that reads the shared cell, so the runtime subscribes to it.
        runtime.render {
            // Reading .value records the cell as a render dependency, which drives
            // updateRenderSubscriptions -> shared.observe { invalidate(...) }.
            require(shared.value == 0)
        }

        // Sanity: the render established exactly one live observer on the shared cell.
        // (Passes on current code — this is the leaked subscription.)
        assertEquals(
            1,
            shared.observerCount,
            "expected the render to establish exactly one observer on the shared cell",
        )

        val effectJob = runtime.effectScope.coroutineContext[Job]
        assertNotNull(effectJob, "effectScope must carry a Job")

        // Correct behavior: the runtime must expose a teardown method that releases its
        // render subscriptions and cancels effectScope. Today it does not.
        val teardown = KineticaRuntime::class.java.methods.firstOrNull { method ->
            method.parameterCount == 0 && (method.name == "dispose" || method.name == "close")
        }
        assertNotNull(
            teardown,
            "R15: KineticaRuntime exposes no dispose()/close() teardown — the observer " +
                "leaked onto the shared cell (observerCount=${shared.observerCount}) can " +
                "never be released and effectScope can never be cancelled.",
        )

        teardown.isAccessible = true
        teardown.invoke(runtime)

        assertEquals(
            0,
            shared.observerCount,
            "R15: after teardown the shared cell must have no remaining observers, but " +
                "${shared.observerCount} render subscription(s) were left live.",
        )
        assertFalse(
            effectJob.isActive,
            "R15: after teardown effectScope must be cancelled, but its Job is still active.",
        )
    }
}
