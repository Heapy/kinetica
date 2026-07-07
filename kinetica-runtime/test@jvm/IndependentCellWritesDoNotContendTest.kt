package io.heapy.kinetica

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R12 — Global `cellWriteLock` serializes all writes and runs user code under locks.
 *
 * The buggy version acquired one module-global lock and ran the user `transform`/equality while
 * that lock was held. As a consequence a slow/blocking transform on one cell froze writes to
 * every OTHER, completely independent cell process-wide.
 *
 * Desired behavior (asserted here): two DIFFERENT cells must be writable concurrently. A write
 * to cell B must make progress even while an unrelated in-flight write to cell A is parked
 * inside its own transform. With a per-instance write lock this holds; with the single shared
 * monitor the B write blocks behind A's transform and this test's bounded wait fails.
 *
 * Determinism: cell A's transform announces it is running (so B starts only once A truly holds
 * whatever lock guards its write) and then parks on a latch that only B's completed write can
 * release. Under today's global lock that is a deadlock, so every wait is bounded (2s) — the
 * test fails with an assertion rather than hanging the suite.
 */
class IndependentCellWritesDoNotContendTest {
    @Test
    fun writesToDifferentCellsDoNotSerializeOnAGlobalLock() {
        val cellA = store(0)
        val cellB = store(0)

        val aInsideTransform = CountDownLatch(1)
        val bWriteCompleted = CountDownLatch(1)

        // Thread A enters cellA's write and parks INSIDE its transform, waiting for an
        // unrelated write to cellB to complete. Under buggy code A holds the global
        // cellWriteLock for the whole duration of this transform.
        val threadA = thread(name = "writer-A") {
            cellA.update { previous ->
                aInsideTransform.countDown()
                // Bounded wait so a deadlock (global lock) cannot hang the suite forever.
                bWriteCompleted.await(2, TimeUnit.SECONDS)
                previous + 1
            }
        }

        // Make sure A is genuinely mid-write before B starts, so this is a real
        // "concurrent write to a different cell", not a race we happened to win.
        assertTrue(
            aInsideTransform.await(2, TimeUnit.SECONDS),
            "Writer A never entered its transform",
        )

        // Thread B writes a COMPLETELY DIFFERENT cell. This must not depend on A.
        val threadB = thread(name = "writer-B") {
            cellB.value = 42
            bWriteCompleted.countDown()
        }

        // The core assertion: B's write to an independent cell must complete promptly,
        // even though A is still parked inside its own in-progress write. This is false
        // today because both writes contend on the single global cellWriteLock.
        val bMadeProgress = bWriteCompleted.await(2, TimeUnit.SECONDS)

        threadA.join()
        threadB.join()

        assertTrue(
            bMadeProgress,
            "Writing an independent cell B blocked on cell A's in-progress write: " +
                "all writes are serialized by one global cellWriteLock",
        )
        assertEquals(1, cellA.value)
        assertEquals(42, cellB.value)
    }
}
