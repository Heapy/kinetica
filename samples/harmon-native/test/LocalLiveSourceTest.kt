@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.samples.harmon

import kotlinx.cinterop.toKString
import kotlinx.coroutines.*
import platform.posix.getenv
import platform.Foundation.NSFileManager
import kotlin.test.*

class LocalLiveSourceTest {
    /** scripts/verify-harmon-live.py supplies synthetic local servers; never uses the installed agent. */
    @Test
    fun foundationTransportContract() = runBlocking {
        val directory = getenv("HARMON_NATIVE_TEST_ENDPOINTS")?.toKString() ?: run {
            println("Transport fixtures not started; run python3 scripts/verify-harmon-live.py for this check")
            return@runBlocking
        }
        val source = LocalLiveSource("$directory/current.endpoint")
        try {
            val first = source.fetch()
            assertEquals(2, first.schemaVersion)
            assertEquals("first", first.sequence)
            val files = NSFileManager.defaultManager
            assertTrue(files.removeItemAtPath("$directory/current.endpoint", null))
            assertTrue(files.copyItemAtPath("$directory/rotated.endpoint", "$directory/current.endpoint", null))
            val next = source.fetch()
            assertEquals(3, next.schemaVersion)
            assertEquals("rotated", next.sequence)
        } finally { source.close() }

        for ((file, status) in listOf("missing" to ConnectionStatus.Disconnected,
            "unauthorized" to ConnectionStatus.AuthenticationFailed, "redirect" to ConnectionStatus.Disconnected)) {
            val client = LocalLiveSource("$directory/$file.endpoint")
            try { assertEquals(status, assertFailsWith<LiveFailure> { client.fetch() }.status) }
            finally { client.close() }
        }
        val slow = LocalLiveSource("$directory/slow.endpoint")
        try { assertFailsWith<TimeoutCancellationException> { withTimeout(200) { slow.fetch() } } }
        finally { slow.close() }
    }
}
