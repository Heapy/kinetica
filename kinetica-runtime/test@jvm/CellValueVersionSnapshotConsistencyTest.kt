package io.heapy.kinetica

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertNull

class CellValueVersionSnapshotConsistencyTest {
    @Test
    fun readerNeverSeesNewValueWithStaleVersion() {
        val cell = MutableCellImpl(0, EqualityPolicy.neverEqual<Int>())
        val observable: ObservableCell<Int> = cell

        val writes = 5_000_000
        val stop = AtomicBoolean(false)
        val violation = AtomicReference<String?>(null)
        val started = CountDownLatch(2)

        val reader = thread(name = "R09-reader") {
            started.countDown()
            started.await()
            while (!stop.get()) {
                val v = observable.value        // read value first
                val ver = observable.version    // then version (time only moves forward)
                if (ver < v) {
                    violation.compareAndSet(
                        null,
                        "reader saw value=$v paired with stale version=$ver " +
                            "(value committed before version was incremented)",
                    )
                    stop.set(true)
                    return@thread
                }
            }
        }

        val writer = thread(name = "R09-writer") {
            started.countDown()
            started.await()
            var k = 1
            while (k <= writes && !stop.get()) {
                cell.value = k
                k++
            }
            stop.set(true)
        }

        writer.join(30_000)
        reader.join(30_000)
        stop.set(true)
        writer.join(1_000)
        reader.join(1_000)

        assertNull(
            violation.get(),
            "R09: (value, version) commit is not atomic — ${violation.get()}",
        )
    }
}
