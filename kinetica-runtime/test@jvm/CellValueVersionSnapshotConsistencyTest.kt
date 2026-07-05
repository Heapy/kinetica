package io.heapy.kinetica

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * R09 — `versionCounter.incrementAndGet()` runs in `notifyWrite`, OUTSIDE the
 * `synchronized(cellWriteLock)` block that commits `current`. That means the new
 * value becomes visible before the version is bumped, so there is a window in
 * which a reader observes the new value paired with the OLD version.
 *
 * Desired/atomic behavior: for a monotonically-numbered stream of writes where the
 * k-th write sets value == k (and therefore commits version == k), any reader that
 * reads `value` and THEN reads `version` must never see `version < value`. Reading
 * value first and version second can only ever move forward in time, so a smaller
 * version than the value just read is proof that the (value, version) commit is not
 * atomic.
 *
 * This test FAILS on the current buggy code because the reader catches the
 * new-value-with-stale-version window.
 */
class CellValueVersionSnapshotConsistencyTest {
    @Test
    fun readerNeverSeesNewValueWithStaleVersion() {
        // Each distinct Int write advances the version by exactly one, so after the
        // k-th write value == k and version == k. neverEqual guarantees every write
        // is treated as a change.
        val cell = MutableCellImpl(0, EqualityPolicy.neverEqual<Int>())
        val observable: ObservableCell<Int> = cell

        val writes = 5_000_000
        val stop = AtomicBoolean(false)
        // First violation observed: value read that is strictly greater than the
        // version read immediately afterwards.
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
