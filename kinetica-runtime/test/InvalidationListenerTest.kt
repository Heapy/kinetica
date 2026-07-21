package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * KNT-0045: the invalidation-listener hook renderer schedulers build on (browser rAF loop
 * KNT-0040, AppKit main-thread marshal). The runtime only notifies — it never re-renders.
 */
class InvalidationListenerTest {
    @Test
    fun listenerFiresOnManualInvalidateWithCause() {
        val runtime = KineticaRuntime(debug = false)
        val causes = mutableListOf<String>()
        runtime.onInvalidation { cause -> causes += cause }

        runtime.invalidate("manual-test")

        assertEquals(listOf("manual-test"), causes)
        assertTrue(runtime.hasPendingInvalidation)
        runtime.dispose()
    }

    @Test
    fun listenerFiresOnRenderSubscribedCellWrite() {
        val runtime = KineticaRuntime(debug = false)
        val cell = store(0)
        val causes = mutableListOf<String>()
        runtime.onInvalidation { cause -> causes += cause }

        runtime.render {
            // Read the cell inside the render pass so the runtime subscribes to it.
            cell.value
        }
        cell.value = 1

        assertEquals(listOf("cell write"), causes)
        assertTrue(runtime.hasPendingInvalidation)
        runtime.dispose()
    }

    @Test
    fun listenerRunsOutsideTheRuntimeLockAndMayReenter() {
        val runtime = KineticaRuntime(debug = false)
        val causes = mutableListOf<String>()
        runtime.onInvalidation { cause ->
            causes += cause
            // Re-entering the runtime from the listener must not deadlock: the notification
            // happens outside the runtime lock by contract.
            assertTrue(runtime.hasPendingInvalidation)
            if (cause == "outer") {
                runtime.invalidate("nested")
            }
        }

        runtime.invalidate("outer")

        assertEquals(listOf("outer", "nested"), causes)
        runtime.dispose()
    }

    @Test
    fun disposedRegistrationStopsNotifications() {
        val runtime = KineticaRuntime(debug = false)
        var fired = 0
        val registration = runtime.onInvalidation { fired += 1 }

        runtime.invalidate("before")
        registration.dispose()
        runtime.invalidate("after")

        assertEquals(1, fired)
        runtime.dispose()
    }

    @Test
    fun runtimeDisposeClearsListeners() {
        val runtime = KineticaRuntime(debug = false)
        var fired = 0
        runtime.onInvalidation { fired += 1 }

        runtime.dispose()
        runtime.invalidate("after-dispose")

        assertEquals(0, fired)
    }
}
