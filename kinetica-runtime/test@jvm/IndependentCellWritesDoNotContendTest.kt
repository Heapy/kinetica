package io.heapy.kinetica

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IndependentCellWritesDoNotContendTest {
    @Test
    fun writesToDifferentCellsDoNotSerializeOnAGlobalLock() {
        val cellA = store(0)
        val cellB = store(0)

        val aInsideTransform = CountDownLatch(1)
        val bWriteCompleted = CountDownLatch(1)

        val threadA = thread(name = "writer-A") {
            cellA.update { previous ->
                aInsideTransform.countDown()
                bWriteCompleted.await(2, TimeUnit.SECONDS)
                previous + 1
            }
        }

        assertTrue(
            aInsideTransform.await(2, TimeUnit.SECONDS),
            "Writer A never entered its transform",
        )

        val threadB = thread(name = "writer-B") {
            cellB.value = 42
            bWriteCompleted.countDown()
        }

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
