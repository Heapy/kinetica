package io.heapy.kinetica

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * R02 — collectSuspend uses thread-confined read-tracking; deps lost after a coroutine
 * thread-hop.
 *
 * ReadTracking.collectSuspend (Cell.kt:53-60) pushes the observer onto a ThreadLocal stack
 * (src@jvm/ReadTrackingLocal.kt) on whichever thread first runs the coroutine, then awaits
 * a suspend block. When that block hops dispatchers (e.g. withContext(Dispatchers.Default)),
 * the resume thread has a DIFFERENT ThreadLocal stack that never received the observer, so
 * any cell read after the hop records no dependency — ReadTracking.record() finds
 * local.current() == null on the resume thread. (The symmetric consequence is that the
 * finally pop() can run on the wrong thread, leaking the observer on the origin thread.)
 *
 * Desired/correct behavior (asserted here, currently FAILS): a cell read anywhere inside the
 * collectSuspend scope — including after a coroutine thread-hop — must be recorded as a
 * dependency, so renderSuspend/suspendSubtree subtrees that read a cell across a suspension
 * still re-render when it changes.
 *
 * This is deterministic: runBlocking confines the push to the test thread, and
 * withContext(Dispatchers.Default) always resumes on a ForkJoinPool worker that is never the
 * runBlocking thread, so the read provably lands on a different thread than the push.
 */
class ReadTrackingFollowsCoroutineAcrossDispatchTest {
    @Test
    fun collectSuspendTracksCellReadAfterCoroutineThreadHop() = runBlocking {
        val cell = store(0)
        val recorded = Collections.synchronizedList(mutableListOf<Cell<*>>())
        val pushThread = Thread.currentThread().name
        var readThread = ""

        ReadTracking.collectSuspend(observer = { recorded += it }) {
            // Hop to a different dispatcher/thread, then read the cell there. The observer was
            // pushed onto the ORIGIN thread's ThreadLocal, so this read resumes on a thread
            // whose stack has no observer.
            withContext(Dispatchers.Default) {
                readThread = Thread.currentThread().name
                cell.value
            }
        }

        // Guard: the read genuinely happened on a different thread than the push, so a failure
        // below is the thread-confinement bug and not an accidental same-thread run.
        assertTrue(
            pushThread != readThread,
            "test precondition: read must run on a different thread than push " +
                "(push=$pushThread read=$readThread)",
        )

        assertTrue(
            cell in recorded,
            "collectSuspend must track cell reads after a coroutine thread-hop, but the read on " +
                "'$readThread' was untracked because the observer stayed thread-confined to the " +
                "origin thread '$pushThread' (ThreadLocal read-tracking does not follow the coroutine)",
        )
    }
}
