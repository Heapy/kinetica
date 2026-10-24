package io.heapy.kinetica

import java.io.InputStream

/**
 * Reads at most [limit] + 1 bytes, returning UTF-8 text or `null` if the byte limit is exceeded.
 * Does not close [input] or drain the remaining body. Pass the result to [dispatchHttp].
 */
public fun readBoundedRequestBody(
    input: InputStream,
    limit: Int = DEFAULT_MAX_SERVER_ACTION_BODY_BYTES,
): String? {
    require(limit in 1 until Int.MAX_VALUE) { "limit must be positive and less than Int.MAX_VALUE." }
    val bytes = input.readNBytes(limit + 1)
    return if (bytes.size > limit) null else bytes.decodeToString()
}
