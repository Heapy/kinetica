package io.heapy.kinetica

import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class KineticaRuntimeDisposeReleasesSubscriptionsTest {
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

        runtime.render {
            require(shared.value == 0)
        }

        assertEquals(
            1,
            shared.observerCount,
            "expected the render to establish exactly one observer on the shared cell",
        )

        val effectJob = runtime.effectScope.coroutineContext[Job]
        assertNotNull(effectJob, "effectScope must carry a Job")

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
