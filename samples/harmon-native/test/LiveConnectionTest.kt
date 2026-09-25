@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.heapy.kinetica.samples.harmon

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class LiveConnectionTest {
    @Test
    fun retriesRetainSnapshotAndResumeWithTheServersCadence() = runTest {
        val source = ScriptedSource()
        val snapshots = mutableListOf<ProcessSnapshot>()
        val states = mutableListOf<ConnectionInfo>()
        val connection = LiveConnection(source, snapshots::add, states::add)
        val job = launch { connection.run() }
        runCurrent()
        assertEquals(1, snapshots.size)
        assertEquals(ConnectionStatus.Live, states.last().status)
        source.failure = LiveFailure(ConnectionStatus.AuthenticationFailed, "Token rotated")
        advanceTimeBy(999); runCurrent(); assertEquals(1, source.requests)
        advanceTimeBy(1); runCurrent()
        assertEquals(ConnectionStatus.AuthenticationFailed, states.last().status)
        assertEquals(1, snapshots.size)
        advanceTimeBy(1_000); runCurrent(); assertEquals(3, source.requests)
        advanceTimeBy(1_999); runCurrent(); assertEquals(3, source.requests)
        source.failure = null
        source.payload = source.payload.copy(sequence = "2", sampleIntervalSeconds = 2.0)
        advanceTimeBy(1); runCurrent()
        assertEquals("2", snapshots.last().sourceSequence)
        assertEquals(ConnectionStatus.Live, states.last().status)
        advanceTimeBy(1_999); runCurrent(); assertEquals(4, source.requests)
        connection.retry(); runCurrent(); assertEquals(5, source.requests)
        job.cancelAndJoin()
        assertTrue(source.closed)
    }

    @Test
    fun pauseCancelsInFlightFetchAndResumeStartsOnlyOnePoller() = runTest {
        var cancelled = 0
        var requests = 0
        var closed = false
        val source = object : LiveSource {
            override suspend fun fetch(): LivePayload {
                requests++
                try { awaitCancellation() } finally { cancelled++ }
            }
            override fun close() { closed = true }
        }
        val states = mutableListOf<ConnectionInfo>()
        val connection = LiveConnection(source, { fail("Cancelled request must not publish a snapshot") }, states::add)
        val job = launch { connection.run() }
        runCurrent()
        assertEquals(1, requests)
        connection.setActive(false); runCurrent()
        assertEquals(1, cancelled)
        assertEquals(ConnectionStatus.Paused, states.last().status)
        advanceTimeBy(60_000); runCurrent(); assertEquals(1, requests)
        connection.setActive(true); runCurrent(); assertEquals(2, requests)
        connection.setActive(true); runCurrent(); assertEquals(2, requests)
        job.cancelAndJoin()
        assertEquals(2, cancelled)
        assertTrue(closed)
    }

    @Test
    fun noTreeKeepsTheLastSnapshotAndUnknownSchemaDoesNotFallbackToFixtures() = runTest {
        val source = ScriptedSource()
        val snapshots = mutableListOf<ProcessSnapshot>()
        val states = mutableListOf<ConnectionInfo>()
        val connection = LiveConnection(source, snapshots::add, states::add)
        val job = launch { connection.run() }
        runCurrent()
        source.payload = source.payload.copy(status = "WARMING", processTree = null)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, snapshots.size)
        assertEquals(ConnectionStatus.Warming, states.last().status)
        source.failure = LiveFailure(ConnectionStatus.Incompatible, "Unsupported schema")
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, snapshots.size)
        assertEquals(ConnectionStatus.Incompatible, states.last().status)
        job.cancelAndJoin()
    }
}

private class ScriptedSource : LiveSource {
    var requests = 0
    var closed = false
    var failure: LiveFailure? = null
    var payload = decodeLivePayload(wireFixture())
    override suspend fun fetch(): LivePayload {
        requests++
        failure?.let { throw it }
        return payload
    }
    override fun close() { closed = true }
}
