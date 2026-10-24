package io.heapy.kinetica

/** Default byte limit for server-action request bodies. */
public const val DEFAULT_MAX_SERVER_ACTION_BODY_BYTES: Int = 64 * 1024

/** HTTP status and encoded JSON body produced by [dispatchHttp]. */
public class ServerActionHttpResponse(
    public val status: Int,
    public val body: String,
)

/**
 * Pass a size-bounded request [body], or `null` when the reader rejected an oversized body.
 * Oversized bodies produce 413 without dispatching; dispatched actions produce 200, including
 * action failures. Decode errors and unexpected faults propagate to the server's error handler.
 * Serve the result as `application/json` with `Cache-Control: no-store`.
 */
public suspend fun KineticaServerActionDispatcher.dispatchHttp(
    transport: KineticaServerTransport,
    body: String?,
): ServerActionHttpResponse {
    if (body == null) {
        return ServerActionHttpResponse(
            status = 413,
            body = transport.encodeActionResponse(ServerActionResponse.Failure("Request body too large.")),
        )
    }
    val request = transport.decodeActionRequest(body)
    return ServerActionHttpResponse(
        status = 200,
        body = transport.encodeActionResponse(dispatch(request)),
    )
}
