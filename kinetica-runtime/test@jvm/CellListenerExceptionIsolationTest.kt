package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R07 — Listener notification is not exception-isolated.
 *
 * MutableCellImpl.notifyWrite does `listeners.value.forEach { it() }`, so the first
 * listener that throws aborts notification of all remaining listeners (after the
 * version has already been bumped). The desired behavior is that every registered
 * listener is invoked exactly once even when an earlier listener throws.
 *
 * This test asserts the CORRECT behavior and therefore FAILS on today's buggy code.
 */
class CellListenerExceptionIsolationTest {
    @Test
    fun throwingListenerDoesNotSuppressLaterListeners() {
        val cell = store(0) as ObservableCell<Int>
        val mutable = cell as MutableCell<Int>

        val secondFired = AtomicBoolean(false)

        // First registered listener throws; second must still be notified.
        cell.observe { throw RuntimeException("boom from first listener") }
        cell.observe { secondFired.set(true) }

        // Trigger a write. The throwing listener will propagate, so guard the write
        // itself — the point under test is whether the second listener ran, not
        // whether the exception escaped.
        try {
            mutable.value = 1
        } catch (_: Throwable) {
            // Expected under buggy code: the first listener's exception escapes here.
        }

        assertTrue(
            secondFired.get(),
            "The second listener must be notified even though the first listener threw",
        )
    }
}
