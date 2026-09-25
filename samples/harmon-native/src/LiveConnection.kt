package io.heapy.kinetica.samples.harmon

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

enum class ConnectionStatus { Connecting, Live, Warming, Stale, Disconnected, AuthenticationFailed, Incompatible, Paused }

data class ConnectionInfo(val status: ConnectionStatus, val message: String)

class LiveFailure(val status: ConnectionStatus, message: String) : Exception(message)

interface LiveSource {
    suspend fun fetch(): LivePayload
    fun close()
}

/** A single cancellable polling loop. The caller owns its coroutine and callback dispatcher. */
class LiveConnection(
    private val source: LiveSource,
    private val onSnapshot: (ProcessSnapshot) -> Unit,
    private val onConnection: (ConnectionInfo) -> Unit,
) {
    private data class Request(val active: Boolean = true, val revision: Int = 0)
    private val request = MutableStateFlow(Request())

    fun setActive(active: Boolean) { request.value = request.value.copy(active = active) }
    fun retry() { request.value = request.value.copy(revision = request.value.revision + 1) }

    suspend fun run() {
        try {
            request.collectLatest { intent ->
                if (!intent.active) {
                    onConnection(ConnectionInfo(ConnectionStatus.Paused, "Updates paused"))
                    return@collectLatest
                }
                onConnection(ConnectionInfo(ConnectionStatus.Connecting, "Connecting to Harmon…"))
                var failures = 0
                while (true) {
                    val wait = try {
                        val payload = source.fetch()
                        val snapshot = try { payload.snapshot() } catch (_: IllegalArgumentException) {
                            throw LiveFailure(ConnectionStatus.Disconnected, "Harmon returned an invalid process tree. Retrying…")
                        }
                        currentCoroutineContext().ensureActive()
                        snapshot?.let(onSnapshot)
                        onConnection(when (payload.status) {
                            "READY" -> ConnectionInfo(ConnectionStatus.Live, "Connected to Harmon · live process data")
                            "WARMING" -> ConnectionInfo(ConnectionStatus.Warming, "Harmon is warming up · waiting for a complete measurement")
                            else -> ConnectionInfo(ConnectionStatus.Stale, "Harmon reports stale data · showing the last available snapshot")
                        })
                        failures = 0
                        payload.pollMillis
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: LiveFailure) {
                        onConnection(ConnectionInfo(failure.status, failure.message.orEmpty()))
                        failures = (failures + 1).coerceAtMost(5)
                        (1_000L shl (failures - 1)).coerceAtMost(15_000)
                    }
                    delay(wait)
                }
            }
        } finally {
            source.close()
        }
    }
}
