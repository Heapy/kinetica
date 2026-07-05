package io.heapy.kinetica

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R06 — BackStack does a non-atomic read-modify-write.
 *
 * `BackStack.push` / `pop` / `replaceAll` do `value = value + x`, i.e. they READ the
 * current list, compute a new one, then WRITE it back. `MutableCellImpl` only holds
 * its write lock around the assignment, not around the read — and `BackStack` does not
 * use the atomic `MutableCell.update`. So two threads that push concurrently can both
 * read the same list snapshot, each append their own route, and the second write clobbers
 * the first: one push is silently lost.
 *
 * The desired behavior is that N concurrent successful pushes each land: the final stack
 * size equals the initial size plus the number of successful pushes, and every pushed
 * route is present. This test asserts that correct behavior and therefore FAILS on the
 * current buggy code (lost updates make the final size smaller than expected).
 */
class BackStackConcurrentMutationIsAtomicTest {
    private data class TestRoute(val id: Int) : Route

    @Test
    fun concurrentPushesDoNotLoseUpdates() {
        val threadCount = 32
        val pushesPerThread = 64
        val expectedPushes = threadCount * pushesPerThread

        val initial = TestRoute(-1)
        val stack = BackStack(initial)

        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        val workers = (0 until threadCount).map { t ->
            thread(name = "push-$t") {
                ready.countDown()
                // Release all threads simultaneously to maximize contention on the
                // read-modify-write window.
                start.await()
                for (i in 0 until pushesPerThread) {
                    stack.push(TestRoute(t * pushesPerThread + i))
                }
                done.countDown()
            }
        }

        assertTrue(
            ready.await(10, TimeUnit.SECONDS),
            "Worker threads failed to reach the start barrier in time",
        )
        start.countDown()
        assertTrue(
            done.await(30, TimeUnit.SECONDS),
            "Worker threads did not finish pushing in time",
        )
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        val finalStack = stack.value
        val expectedSize = 1 + expectedPushes

        // Every unique route that was pushed must survive (no lost updates).
        val distinctPushed = finalStack.filter { it != initial }.toSet().size
        assertEquals(
            expectedPushes,
            distinctPushed,
            "Concurrent pushes lost updates: expected $expectedPushes distinct routes " +
                "to be pushed but only $distinctPushed survived in the stack.",
        )
        assertEquals(
            expectedSize,
            finalStack.size,
            "BackStack lost updates under concurrent push(): expected final size " +
                "$expectedSize (initial + $expectedPushes pushes) but got ${finalStack.size}.",
        )

        // Sanity: no push should have thrown; if any did, surface it.
        if (workers.any { it.isAlive }) {
            fail("Some worker threads are still alive after join timeout")
        }
    }
}
