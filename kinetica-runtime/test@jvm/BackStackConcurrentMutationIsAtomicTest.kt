package io.heapy.kinetica

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

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

        if (workers.any { it.isAlive }) {
            fail("Some worker threads are still alive after join timeout")
        }
    }
}
