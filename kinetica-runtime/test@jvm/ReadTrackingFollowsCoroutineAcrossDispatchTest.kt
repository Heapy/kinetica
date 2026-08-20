package io.heapy.kinetica

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertTrue

class ReadTrackingFollowsCoroutineAcrossDispatchTest {
    @Test
    fun collectSuspendTracksCellReadAfterCoroutineThreadHop() = runBlocking {
        val cell = store(0)
        val recorded = Collections.synchronizedList(mutableListOf<Cell<*>>())
        val pushThread = Thread.currentThread().name
        var readThread = ""

        ReadTracking.collectSuspend(observer = { recorded += it }) {
            withContext(Dispatchers.Default) {
                readThread = Thread.currentThread().name
                cell.value
            }
        }

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
