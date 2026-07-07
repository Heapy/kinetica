package io.heapy.kinetica

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.coroutines.cancellation.CancellationException

public val KineticaJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
public data class ClientComponentManifest(
    val components: List<ClientComponentRegistration> = emptyList(),
    val actions: List<ServerActionRegistration> = emptyList(),
)

@Serializable
public data class ClientComponentRegistration(
    val componentId: String,
    val componentFqName: String,
    val serializablePropsType: String? = null,
)

@Serializable
public data class ServerActionRegistration(
    val actionId: String,
    val functionFqName: String,
    val invalidates: List<String> = emptyList(),
    val inputSchema: ServerActionPayloadSchema? = null,
)

@Serializable
public data class ServerActionPayloadSchema(
    val kind: JsonValueKind,
    val nullable: Boolean = false,
    val fields: List<ServerActionFieldSchema> = emptyList(),
    val allowUnknownFields: Boolean = true,
)

@Serializable
public data class ServerActionFieldSchema(
    val name: String,
    val kind: JsonValueKind,
    val required: Boolean = true,
    val nullable: Boolean = false,
)

@Serializable
public enum class JsonValueKind {
    Object,
    Array,
    String,
    Number,
    Boolean,
    Null,
}

@Serializable
public sealed interface ServerRenderChunk {
    public val sequence: Long

    @Serializable
    @SerialName("tree")
    public data class Tree(
        override val sequence: Long,
        val node: Node,
        val manifest: ClientComponentManifest = ClientComponentManifest(),
    ) : ServerRenderChunk

    @Serializable
    @SerialName("patch")
    public data class Patch(
        override val sequence: Long,
        val path: List<Int>,
        val node: Node?,
    ) : ServerRenderChunk

    @Serializable
    @SerialName("boundaryError")
    public data class BoundaryError(
        override val sequence: Long,
        val boundaryId: String,
        val message: String,
    ) : ServerRenderChunk

    @Serializable
    @SerialName("end")
    public data class End(
        override val sequence: Long,
    ) : ServerRenderChunk
}

public data class ServerRenderDeferredSubtree(
    val path: List<Int>,
    val boundaryId: String = path.toServerBoundaryId(),
    val render: suspend () -> Node,
) {
    init {
        require(path.all { index -> index >= 0 }) { "path indexes must be non-negative." }
        require(boundaryId.isNotBlank()) { "boundaryId must not be blank." }
    }
}

@Serializable
public data class ClientIsland(
    val componentId: String,
    val path: List<Int>,
    val props: JsonObject,
)

@Serializable
public data class HydrationPlan(
    val initialTree: Node,
    val clientIslands: List<ClientIsland>,
    val patchesFromMountedTree: List<NodeDiff> = emptyList(),
)

@Serializable
public data class CapabilityToken(val value: String)

@Serializable
public data class CsrfToken(val value: String)

/**
 * A non-cryptographic checksum (FNV-1a-64) over a chunk's encoded bytes.
 *
 * This detects *accidental* corruption — truncation, encoding drift, partial writes — between
 * encode and decode. It is **not** a signature or MAC: FNV is not collision-resistant and the
 * algorithm is keyless, so an attacker who can modify the bytes can recompute a matching
 * checksum trivially. Do not rely on this for trust or tamper detection; for that, wrap the
 * transport in an HMAC verified with a server-held secret.
 */
@Serializable
public data class ChunkChecksum(
    val algorithm: String,
    val value: String,
)

/**
 * A [ServerRenderChunk] paired with a [ChunkChecksum] over its encoding. See [ChunkChecksum] for
 * the threat model — corruption detection only, not tamper resistance.
 */
@Serializable
public data class ChecksummedServerRenderChunk(
    val chunk: ServerRenderChunk,
    val checksum: ChunkChecksum,
)

@Serializable
public data class ServerActionRequest(
    val actionId: String,
    val payload: JsonElement,
    val token: CapabilityToken,
    val csrfToken: CsrfToken? = null,
)

@Serializable
public sealed interface ServerActionResponse {
    @Serializable
    @SerialName("success")
    public data class Success(
        val payload: JsonElement,
        val invalidated: List<String> = emptyList(),
    ) : ServerActionResponse

    @Serializable
    @SerialName("failure")
    public data class Failure(
        val message: String,
        val retryable: Boolean = false,
    ) : ServerActionResponse
}

public interface ServerActionStub {
    public val registration: ServerActionRegistration

    public suspend fun dispatch(
        json: Json,
        payload: JsonElement,
    ): ServerActionResponse
}

/**
 * Thrown by a server-action handler to reject an input with a specific, client-safe message.
 *
 * The dispatcher's serializer-derived schema only checks a payload's *shape*; it cannot express
 * business invariants (range bounds, allowed enum values, cross-field rules). Handlers validate
 * those themselves and throw this exception — its [message] is returned verbatim in the
 * [ServerActionResponse.Failure], so it must not contain secrets or internals. Any other throwable
 * surfaces only a generic "Server action failed." so server stack traces never leak.
 */
public class ServerActionRejection(override val message: String) : RuntimeException(message)

public fun <I, O> serverActionStub(
    registration: ServerActionRegistration,
    inputSerializer: KSerializer<I>,
    outputSerializer: KSerializer<O>,
    inputSchema: ServerActionPayloadSchema = registration.inputSchema ?: serverActionPayloadSchema(inputSerializer),
    handler: suspend (I) -> O,
): ServerActionStub =
    object : ServerActionStub {
        override val registration: ServerActionRegistration =
            registration.copy(inputSchema = registration.inputSchema ?: inputSchema)

        override suspend fun dispatch(
            json: Json,
            payload: JsonElement,
        ): ServerActionResponse {
            val validationErrors = inputSchema.validate(payload)
            if (validationErrors.isNotEmpty()) {
                return ServerActionResponse.Failure(
                    message = "Invalid server action payload: ${validationErrors.joinToString(separator = "; ")}",
                    retryable = false,
                )
            }
            val input = try {
                json.decodeFromJsonElement(inputSerializer, payload)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Any failure decoding the client-supplied payload into the input type - a kotlinx
                // SerializationException, or an IllegalArgumentException thrown by an @Serializable
                // init/require block (which kotlinx does NOT wrap) - is a client input error, never a
                // server fault, and must not escape dispatch() as a thrown exception.
                return ServerActionResponse.Failure(
                    message = "Invalid server action payload.",
                    retryable = false,
                )
            }
            return try {
                ServerActionResponse.Success(
                    payload = json.encodeToJsonElement(outputSerializer, handler(input)),
                    invalidated = registration.invalidates,
                )
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                // A handler-thrown ServerActionRejection surfaces its client-safe message; any other
                // throwable stays generic so server internals never reach a response body.
                ServerActionResponse.Failure(
                    message = if (throwable is ServerActionRejection) throwable.message else "Server action failed.",
                    retryable = false,
                )
            }
        }
    }

public fun serverActionPayloadSchema(serializer: KSerializer<*>): ServerActionPayloadSchema =
    serializer.descriptor.toServerActionPayloadSchema()

public fun ServerActionPayloadSchema.validate(payload: JsonElement): List<String> {
    val errors = mutableListOf<String>()
    if (payload is JsonNull) {
        if (!nullable && kind != JsonValueKind.Null) {
            errors += "expected ${kind.name.lowercase()} payload"
        }
        return errors
    }
    if (!payload.matches(kind)) {
        errors += "expected ${kind.name.lowercase()} payload"
        return errors
    }
    if (kind != JsonValueKind.Object || payload !is JsonObject || fields.isEmpty()) {
        return errors
    }

    fields.forEach { field ->
        val value = payload[field.name]
        if (value == null) {
            if (field.required) {
                errors += "missing required field '${field.name}'"
            }
            return@forEach
        }
        if (value is JsonNull) {
            if (!field.nullable && field.kind != JsonValueKind.Null) {
                errors += "field '${field.name}' expected ${field.kind.name.lowercase()}"
            }
            return@forEach
        }
        if (!value.matches(field.kind)) {
            errors += "field '${field.name}' expected ${field.kind.name.lowercase()}"
        }
    }

    if (!allowUnknownFields) {
        val known = fields.mapTo(mutableSetOf()) { field -> field.name }
        payload.keys.filterNot { it in known }.forEach { field ->
            errors += "unknown field '$field'"
        }
    }
    return errors
}

private fun SerialDescriptor.toServerActionPayloadSchema(): ServerActionPayloadSchema {
    val jsonKind = toJsonValueKind()
    return ServerActionPayloadSchema(
        kind = jsonKind,
        nullable = isNullable,
        // A map serializes as an open JSON object with arbitrary keys; its synthetic key/value
        // descriptor elements are not named fields, so they must not be derived as required fields.
        fields = if (jsonKind == JsonValueKind.Object && kind != StructureKind.MAP) {
            elementSchemas()
        } else {
            emptyList()
        },
        allowUnknownFields = true,
    )
}

private fun SerialDescriptor.elementSchemas(): List<ServerActionFieldSchema> =
    (0 until elementsCount).map { index ->
        val element = getElementDescriptor(index)
        ServerActionFieldSchema(
            name = getElementName(index),
            kind = element.toJsonValueKind(),
            required = !isElementOptional(index),
            nullable = element.isNullable,
        )
    }

private fun SerialDescriptor.toJsonValueKind(): JsonValueKind =
    when (kind) {
        PrimitiveKind.BOOLEAN -> JsonValueKind.Boolean
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG,
        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE,
        -> JsonValueKind.Number
        PrimitiveKind.CHAR,
        PrimitiveKind.STRING,
        -> JsonValueKind.String
        StructureKind.LIST -> JsonValueKind.Array
        StructureKind.MAP -> JsonValueKind.Object
        SerialKind.ENUM -> JsonValueKind.String
        else -> JsonValueKind.Object
    }

private fun JsonElement.matches(kind: JsonValueKind): Boolean =
    when (kind) {
        JsonValueKind.Object -> this is JsonObject
        JsonValueKind.Array -> this is JsonArray
        JsonValueKind.String -> this is JsonPrimitive && isString
        JsonValueKind.Number -> this is JsonPrimitive && !isString && doubleOrNull != null
        JsonValueKind.Boolean -> this is JsonPrimitive && !isString && booleanOrNull != null
        JsonValueKind.Null -> this is JsonNull
    }

public class KineticaServerActionDispatcher(
    stubs: List<ServerActionStub>,
    private val json: Json = KineticaJson,
    // Fail closed by default: a dispatcher constructed without verifiers rejects every action
    // rather than silently accepting unauthenticated ones. Real deployments must supply a
    // capability verifier and a per-session CSRF verifier.
    private val verifyCapabilityToken: (CapabilityToken) -> Boolean = { false },
    private val verifyCsrfToken: (CsrfToken?) -> Boolean = { false },
) {
    private val stubsByActionId: Map<String, ServerActionStub> =
        stubs.associateBy { stub -> stub.registration.actionId }

    public val actions: List<ServerActionRegistration> =
        stubs.map { stub -> stub.registration }

    public suspend fun dispatch(request: ServerActionRequest): ServerActionResponse {
        if (!verifyCapabilityToken(request.token)) {
            return ServerActionResponse.Failure(
                message = "Invalid capability token.",
                retryable = false,
            )
        }
        if (!verifyCsrfToken(request.csrfToken)) {
            return ServerActionResponse.Failure(
                message = "Invalid CSRF token.",
                retryable = false,
            )
        }
        val stub = stubsByActionId[request.actionId]
            ?: return ServerActionResponse.Failure(
                message = "Unknown server action.",
                retryable = false,
            )
        return stub.dispatch(json, request.payload)
    }
}

/**
 * The HTTP status and already-encoded JSON body for a server-action request, produced by
 * [dispatchHttp].
 *
 * [status] is `200` when the request was dispatched — [body] then carries the handler's
 * [ServerActionResponse], which may itself be a [ServerActionResponse.Failure] (an invalid token, a
 * validation rejection). It is `400` for a malformed request envelope and `413` when the caller's
 * bounded read rejected an oversized body. Unexpected server faults stay the caller's concern: log
 * them server-side and return a generic `500`.
 */
public class ServerActionHttpResponse(
    public val status: Int,
    public val body: String,
)

/**
 * Conservative default byte cap for a server-action request body. Action payloads are small JSON
 * objects, so this bounds a single POST well below any legitimate request while stopping an oversized
 * body from exhausting heap. Pair it with `readBoundedRequestBody` (JVM) or your HTTP stack's own
 * bounded read, passing `null` to [dispatchHttp] when the limit is exceeded.
 */
public const val DEFAULT_MAX_SERVER_ACTION_BODY_BYTES: Int = 64 * 1024

/**
 * Maps a raw request [body] to a [ServerActionHttpResponse] so every server shares one
 * decode → dispatch → encode path instead of re-deriving the status codes (and re-introducing the
 * leaks that come with hand-rolling them):
 *
 * - `body == null` — the caller's bounded read rejected an oversized body — → **413**;
 * - the body is not a decodable [ServerActionRequest] envelope → **400**, a client error that never
 *   reaches the generic 500 catch-all, with a generic message so serializer internals never leak;
 * - otherwise the request is dispatched → **200**, and [ServerActionHttpResponse.body] is the encoded
 *   [ServerActionResponse] (a `Success`, or a `Failure` the dispatcher/handler produced).
 *
 * Read the body with a size-bounded reader (`readBoundedRequestBody` on the JVM) and pass `null` when
 * it overflows; wrap the call in the caller's own try/catch for the unexpected-fault 500.
 */
public suspend fun KineticaServerActionDispatcher.dispatchHttp(
    transport: KineticaServerTransport,
    body: String?,
): ServerActionHttpResponse {
    fun failure(status: Int, message: String): ServerActionHttpResponse =
        ServerActionHttpResponse(
            status = status,
            body = transport.encodeActionResponse(
                ServerActionResponse.Failure(message = message, retryable = false),
            ),
        )

    if (body == null) {
        return failure(status = 413, message = "Request body too large.")
    }
    val request = try {
        transport.decodeActionRequest(body)
    } catch (error: SerializationException) {
        return failure(status = 400, message = "Malformed server action request.")
    }
    return ServerActionHttpResponse(
        status = 200,
        body = transport.encodeActionResponse(dispatch(request)),
    )
}

/**
 * Response headers every Kinetica HTTP server should set, so the reference servers and adopters share
 * one hardened baseline instead of hand-rolling — and drifting on — their own: `nosniff`, `DENY`
 * framing, a `no-referrer` policy, and a strict same-origin [Content-Security-Policy].
 *
 * `style-src` deliberately keeps `'unsafe-inline'`: the Kinetica browser renderer applies layout via
 * inline `style` attributes (`element.setAttribute("style", …)` for `row`/`column` flex), which a
 * `style-src` without `'unsafe-inline'` blocks — silently breaking every hydrated island. Do not drop
 * it without first moving the renderer off inline-style attributes.
 */
public val KineticaSecurityHeaders: Map<String, String> = mapOf(
    "X-Content-Type-Options" to "nosniff",
    "X-Frame-Options" to "DENY",
    "Referrer-Policy" to "no-referrer",
    "Content-Security-Policy" to
        "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self'; connect-src 'self'; frame-ancestors 'none'",
)

public class KineticaServerTransport(
    private val json: Json = KineticaJson,
) {
    public fun encodeNode(node: Node): String =
        json.encodeToString(Node.serializer(), node.materializeDeep())

    public fun decodeNode(value: String): Node =
        json.decodeFromString(Node.serializer(), value)

    public fun encodeChunk(chunk: ServerRenderChunk): String =
        encodeMaterializedChunk(chunk.materializeDeep())

    public fun decodeChunk(value: String): ServerRenderChunk =
        json.decodeFromString(ServerRenderChunk.serializer(), value)

    public fun checksumForChunk(chunk: ServerRenderChunk): ChunkChecksum =
        checksumForPayload(encodeMaterializedChunk(chunk.materializeDeep()))

    public fun encodeChecksummedChunk(chunk: ServerRenderChunk): String {
        val materialized = chunk.materializeDeep()
        return json.encodeToString(
            ChecksummedServerRenderChunk.serializer(),
            ChecksummedServerRenderChunk(
                chunk = materialized,
                checksum = checksumForPayload(encodeMaterializedChunk(materialized)),
            ),
        )
    }

    public fun decodeChecksummedChunk(value: String): ServerRenderChunk {
        val checksummed = json.decodeFromString(ChecksummedServerRenderChunk.serializer(), value)
        val materialized = checksummed.chunk.materializeDeep()
        val expected = checksumForPayload(encodeMaterializedChunk(materialized))
        require(checksummed.checksum == expected) {
            "Server render chunk checksum mismatch: expected $expected, got ${checksummed.checksum}."
        }
        return materialized
    }

    public fun encodeHydrationPlan(plan: HydrationPlan): String =
        json.encodeToString(plan.materializeDeep())

    public fun decodeHydrationPlan(value: String): HydrationPlan =
        json.decodeFromString(value)

    public fun encodeActionRequest(request: ServerActionRequest): String =
        json.encodeToString(request)

    public fun decodeActionRequest(value: String): ServerActionRequest =
        json.decodeFromString(value)

    public fun encodeActionResponse(response: ServerActionResponse): String =
        json.encodeToString(ServerActionResponse.serializer(), response)

    public fun decodeActionResponse(value: String): ServerActionResponse =
        json.decodeFromString(ServerActionResponse.serializer(), value)

    private fun checksumForPayload(payload: String): ChunkChecksum =
        ChunkChecksum(
            algorithm = CHECKSUM_ALGORITHM,
            value = fnv1a64(payload.encodeToByteArray()).toHex64(),
        )

    private fun encodeMaterializedChunk(chunk: ServerRenderChunk): String =
        json.encodeToString(ServerRenderChunk.serializer(), chunk)
}

private fun ServerRenderChunk.materializeDeep(): ServerRenderChunk =
    when (this) {
        is ServerRenderChunk.Tree -> {
            val materialized = node.materializeDeep()
            if (materialized === node) this else copy(node = materialized)
        }
        is ServerRenderChunk.Patch -> {
            val materialized = node?.materializeDeep()
            if (materialized === node) this else copy(node = materialized)
        }
        is ServerRenderChunk.BoundaryError -> this
        is ServerRenderChunk.End -> this
    }

private fun HydrationPlan.materializeDeep(): HydrationPlan {
    val materializedTree = initialTree.materializeDeep()
    val materializedPatches = patchesFromMountedTree.materializeDeep()
    return if (materializedTree === initialTree && materializedPatches === patchesFromMountedTree) {
        this
    } else {
        copy(
            initialTree = materializedTree,
            patchesFromMountedTree = materializedPatches,
        )
    }
}

private fun List<NodeDiff>.materializeDeep(): List<NodeDiff> {
    var materialized: MutableList<NodeDiff>? = null
    forEachIndexed { index, diff ->
        val materializedDiff = diff.materializeDeep()
        val current = materialized
        when {
            current != null -> current += materializedDiff
            materializedDiff !== diff -> {
                val next = ArrayList<NodeDiff>(size)
                for (previous in 0 until index) {
                    next += this[previous]
                }
                next += materializedDiff
                materialized = next
            }
        }
    }
    return materialized ?: this
}

private fun NodeDiff.materializeDeep(): NodeDiff {
    val materializedBefore = before?.materializeDeep()
    val materializedAfter = after?.materializeDeep()
    return if (materializedBefore === before && materializedAfter === after) {
        this
    } else {
        copy(before = materializedBefore, after = materializedAfter)
    }
}

public fun Node.hydrationPlan(mountedTree: Node? = null): HydrationPlan {
    val initialTree = materializeDeep()
    return HydrationPlan(
        initialTree = initialTree,
        clientIslands = initialTree.collectClientIslands(),
        patchesFromMountedTree = mountedTree?.let { diffNodes(it, initialTree) }.orEmpty(),
    )
}

public fun Node.toInitialServerChunk(
    sequence: Long = 1,
    manifest: ClientComponentManifest = ClientComponentManifest(),
): ServerRenderChunk.Tree =
    ServerRenderChunk.Tree(sequence = sequence, node = materializeDeep(), manifest = manifest)

public fun Node.toServerRenderStream(
    manifest: ClientComponentManifest = ClientComponentManifest(),
): List<ServerRenderChunk> =
    listOf(
        toInitialServerChunk(sequence = 1, manifest = manifest),
        ServerRenderChunk.End(sequence = 2),
    )

public suspend fun Node.toServerRenderStream(
    subtrees: Iterable<ServerRenderDeferredSubtree>,
    manifest: ClientComponentManifest = ClientComponentManifest(),
): List<ServerRenderChunk> = coroutineScope {
    val pending = subtrees.toList()
    if (pending.isEmpty()) {
        return@coroutineScope toServerRenderStream(manifest)
    }

    val results = Channel<ServerRenderDeferredResult>(capacity = Channel.UNLIMITED)
    val jobs = pending.map { subtree ->
        async {
            try {
                results.send(
                    ServerRenderDeferredResult.Ready(
                        path = subtree.path,
                        node = subtree.render(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                results.close(cancelled)
                throw cancelled
            } catch (error: Throwable) {
                // Log the detail server-side; stream only a generic message so a throwing subtree can never
                // leak JDBC/credential/stack internals to a /stream client (mirrors the action-handler scrub).
                error.printStackTrace()
                results.send(
                    ServerRenderDeferredResult.Failed(
                        boundaryId = subtree.boundaryId,
                        message = "Server render failed.",
                    ),
                )
            }
        }
    }

    var sequence = 1L
    val chunks = mutableListOf<ServerRenderChunk>(
        toInitialServerChunk(sequence = sequence++, manifest = manifest),
    )
    repeat(pending.size) {
        when (val result = results.receive()) {
            is ServerRenderDeferredResult.Ready -> {
                chunks += ServerRenderChunk.Patch(
                    sequence = sequence++,
                    path = result.path,
                    node = result.node.materializeDeep(),
                )
            }
            is ServerRenderDeferredResult.Failed -> {
                chunks += ServerRenderChunk.BoundaryError(
                    sequence = sequence++,
                    boundaryId = result.boundaryId,
                    message = result.message,
                )
            }
        }
    }
    jobs.awaitAll()
    results.close()
    chunks += ServerRenderChunk.End(sequence = sequence)
    chunks
}

public fun Node.collectClientIslands(): List<ClientIsland> {
    val islands = mutableListOf<ClientIsland>()

    fun visit(node: Node, path: List<Int>) {
        when (node) {
            is ClientRef -> {
                islands += ClientIsland(
                    componentId = node.componentId,
                    path = path,
                    props = node.props,
                )
            }
            is FragmentNode -> node.children.forEachIndexed { index, child -> visit(child, path + index) }
            is HostNode -> node.children.forEachIndexed { index, child -> visit(child, path + index) }
            is TextNode -> Unit
            is TemplateNode -> visit(node.materialize(), path)
        }
    }

    visit(this, emptyList())
    return islands
}

private const val CHECKSUM_ALGORITHM = "fnv1a64"
private const val FNV_64_OFFSET_BASIS: Long = -3750763034362895579L
private const val FNV_64_PRIME: Long = 1099511628211L
private const val HEX = "0123456789abcdef"

private sealed interface ServerRenderDeferredResult {
    data class Ready(
        val path: List<Int>,
        val node: Node,
    ) : ServerRenderDeferredResult

    data class Failed(
        val boundaryId: String,
        val message: String,
    ) : ServerRenderDeferredResult
}

private fun List<Int>.toServerBoundaryId(): String =
    if (isEmpty()) {
        "root"
    } else {
        joinToString(separator = ".", prefix = "boundary.")
    }

private fun fnv1a64(bytes: ByteArray): Long {
    var hash = FNV_64_OFFSET_BASIS
    bytes.forEach { byte ->
        hash = (hash xor (byte.toLong() and 0xffL)) * FNV_64_PRIME
    }
    return hash
}

private fun Long.toHex64(): String {
    var value = this
    val chars = CharArray(16)
    for (index in 15 downTo 0) {
        chars[index] = HEX[(value and 0x0fL).toInt()]
        value = value ushr 4
    }
    return chars.concatToString()
}
