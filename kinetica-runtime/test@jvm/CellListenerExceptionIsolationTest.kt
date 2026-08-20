package io.heapy.kinetica

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertTrue

class CellListenerExceptionIsolationTest {
    @Test
    fun throwingListenerDoesNotSuppressLaterListeners() {
        val cell = store(0) as ObservableCell<Int>
        val mutable = cell as MutableCell<Int>

        val secondFired = AtomicBoolean(false)

        cell.observe { throw RuntimeException("boom from first listener") }
        cell.observe { secondFired.set(true) }

        try {
            mutable.value = 1
        } catch (_: Throwable) {
        }

        assertTrue(
            secondFired.get(),
            "The second listener must be notified even though the first listener threw",
        )
    }
}
