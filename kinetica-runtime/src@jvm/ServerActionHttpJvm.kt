package io.heapy.kinetica

import java.io.InputStream

/**
 * Reads at most [limit] bytes from [input] and returns them decoded as UTF-8, or `null` if the
 * stream holds more than [limit] bytes.
 *
 * Bounding the read stops a single oversized request from exhausting heap the way an unbounded
 * `readAllBytes()` would. Pass the result straight to [dispatchHttp], which maps `null` to a `413`.
 * The read is exact at the boundary: a body of exactly [limit] bytes is accepted; the first byte
 * beyond [limit] triggers rejection.
 */
public fun readBoundedRequestBody(
    input: InputStream,
    limit: Int = DEFAULT_MAX_SERVER_ACTION_BODY_BYTES,
): String? {
    require(limit > 0) { "limit must be positive." }
    val buffer = ByteArray(limit)
    var read = 0
    while (read < buffer.size) {
        val n = input.read(buffer, read, buffer.size - read)
        if (n < 0) {
            // EOF within the limit — the whole body fits.
            return buffer.decodeToString(0, read)
        }
        read += n
    }
    // Buffer full (exactly `limit` bytes). The body fits only if there is nothing more to read.
    return if (input.read() < 0) buffer.decodeToString(0, read) else null
}
