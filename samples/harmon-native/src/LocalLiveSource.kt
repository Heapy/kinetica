@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.heapy.kinetica.samples.harmon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.Foundation.*
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocalEndpoint(val port: Int, private val token: String) {
    init {
        require(port in 1..65_535 && token.matches(Regex("[0-9a-f]{64}"))) { "Invalid Harmon endpoint" }
    }
    val watchUrl: String get() = "http://127.0.0.1:$port/api/live?watch=1"
    fun authorization(): String = "Bearer $token"
    override fun toString(): String = "LocalEndpoint(port=$port, token=<redacted>)"

    companion object {
        fun parse(text: String): LocalEndpoint {
            require(text.length <= 512) { "Invalid Harmon endpoint" }
            val entries = text.lineSequence().filter { it.isNotBlank() }.map { line ->
                val split = line.indexOf('=')
                require(split > 0) { "Invalid Harmon endpoint" }
                line.take(split) to line.substring(split + 1)
            }.toList()
            require(entries.size == 2 && entries.map { it.first }.toSet() == setOf("port", "token")) { "Invalid Harmon endpoint" }
            val values = entries.toMap()
            return LocalEndpoint(requireNotNull(values["port"]?.toIntOrNull()) { "Invalid Harmon endpoint" }, values.getValue("token"))
        }
    }
}

fun defaultEndpointPath(): String = NSHomeDirectory() + "/Library/Application Support/Harmon/live-ui.endpoint"

private class NoRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest, completionHandler: (NSURLRequest?) -> Unit) {
        completionHandler(null)
    }
}

/** Uses only the agent's loopback watch API; the endpoint is re-read after every restart or token rotation. */
class LocalLiveSource(private val endpointPath: String = defaultEndpointPath()) : LiveSource {
    private val session by lazy { NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration.apply {
            timeoutIntervalForRequest = 5.0
            timeoutIntervalForResource = 5.0
            URLCache = null
            HTTPCookieStorage = null
            URLCredentialStorage = null
            HTTPShouldSetCookies = false
            connectionProxyDictionary = emptyMap<Any?, Any>()
        }, NoRedirects(), null) }

    override suspend fun fetch(): LivePayload = withContext(Dispatchers.Default) {
        val data = NSData.dataWithContentsOfFile(endpointPath)
            ?: throw LiveFailure(ConnectionStatus.Disconnected, "Harmon is not running. Start the Harmon agent; this window will reconnect automatically.")
        val endpoint = try {
            require(data.length <= 512u)
            LocalEndpoint.parse(requireNotNull(data.utf8()))
        } catch (_: IllegalArgumentException) {
            throw LiveFailure(ConnectionStatus.Disconnected, "Harmon's endpoint file is invalid. Restart the Harmon agent.")
        }
        val request = NSMutableURLRequest.requestWithURL(requireNotNull(NSURL.URLWithString(endpoint.watchUrl))).apply {
            setCachePolicy(NSURLRequestReloadIgnoringLocalCacheData)
            setValue(endpoint.authorization(), forHTTPHeaderField = "Authorization")
            setValue("application/json", forHTTPHeaderField = "Accept")
        }
        val body = suspendCancellableCoroutine<String> { continuation ->
            val task = session.dataTaskWithRequest(request) { bytes, response, error ->
                if (error != null) {
                    continuation.resumeWithException(LiveFailure(ConnectionStatus.Disconnected, "Cannot reach Harmon. Retrying automatically…"))
                } else {
                    val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt()
                    val failure = when {
                        status == 401 || status == 403 -> LiveFailure(ConnectionStatus.AuthenticationFailed, "Harmon rejected authentication. Re-reading its endpoint and retrying…")
                        status != 200 -> LiveFailure(ConnectionStatus.Disconnected, "Harmon returned HTTP ${status ?: "error"}. Retrying…")
                        bytes == null || bytes.length > 16_777_216u -> LiveFailure(ConnectionStatus.Disconnected, "Harmon returned an invalid response size. Retrying…")
                        else -> null
                    }
                    val text = if (failure == null) bytes!!.utf8() else null
                    if (failure != null || text == null) continuation.resumeWithException(failure
                        ?: LiveFailure(ConnectionStatus.Disconnected, "Harmon returned invalid text. Retrying…"))
                    else continuation.resume(text)
                }
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
        decodeLivePayload(body)
    }

    override fun close() { session.invalidateAndCancel() }
}

private fun NSData.utf8(): String? = try {
    bytes?.reinterpret<ByteVar>()?.readBytes(length.toInt())?.decodeToString(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    null
}
