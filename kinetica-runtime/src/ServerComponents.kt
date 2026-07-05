package io.heapy.kinetica

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

@Serializable
public data class IntegrityHash(
    val algorithm: String,
    val value: String,
)

@Serializable
public data class SignedServerRenderChunk(
    val chunk: ServerRenderChunk,
    val integrity: IntegrityHash,
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
        ): ServerActionResponse =
            try {
                val validationErrors = inputSchema.validate(payload)
                if (validationErrors.isNotEmpty()) {
                    ServerActionResponse.Failure(
                        message = "Invalid server action payload: ${validationErrors.joinToString(separator = "; ")}",
                        retryable = false,
                    )
                } else {
                    val input = json.decodeFromJsonElement(inputSerializer, payload)
                    val output = handler(input)
                    ServerActionResponse.Success(
                        payload = json.encodeToJsonElement(outputSerializer, output),
                        invalidated = registration.invalidates,
                    )
                }
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) {
                    throw throwable
                }
                ServerActionResponse.Failure(
                    message = "Server action failed.",
                    retryable = false,
                )
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
    private val verifyCapabilityToken: (CapabilityToken) -> Boolean = { true },
    private val verifyCsrfToken: (CsrfToken?) -> Boolean = { true },
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

public class KineticaServerTransport(
    private val json: Json = KineticaJson,
) {
    public fun encodeNode(node: Node): String =
        json.encodeToString(Node.serializer(), node)

    public fun decodeNode(value: String): Node =
        json.decodeFromString(Node.serializer(), value)

    public fun encodeChunk(chunk: ServerRenderChunk): String =
        json.encodeToString(ServerRenderChunk.serializer(), chunk)

    public fun decodeChunk(value: String): ServerRenderChunk =
        json.decodeFromString(ServerRenderChunk.serializer(), value)

    public fun integrityForChunk(chunk: ServerRenderChunk): IntegrityHash =
        integrityForPayload(encodeChunk(chunk))

    public fun encodeSignedChunk(chunk: ServerRenderChunk): String =
        json.encodeToString(
            SignedServerRenderChunk.serializer(),
            SignedServerRenderChunk(
                chunk = chunk,
                integrity = integrityForChunk(chunk),
            ),
        )

    public fun decodeSignedChunk(value: String): ServerRenderChunk {
        val signed = json.decodeFromString(SignedServerRenderChunk.serializer(), value)
        val expected = integrityForChunk(signed.chunk)
        require(signed.integrity == expected) {
            "Server render chunk integrity mismatch: expected $expected, got ${signed.integrity}."
        }
        return signed.chunk
    }

    public fun encodeHydrationPlan(plan: HydrationPlan): String =
        json.encodeToString(plan)

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

    private fun integrityForPayload(payload: String): IntegrityHash =
        IntegrityHash(
            algorithm = INTEGRITY_ALGORITHM,
            value = fnv1a64(payload.encodeToByteArray()).toHex64(),
        )
}

public fun Node.hydrationPlan(mountedTree: Node? = null): HydrationPlan =
    HydrationPlan(
        initialTree = this,
        clientIslands = collectClientIslands(),
        patchesFromMountedTree = mountedTree?.let { diffNodes(it, this) }.orEmpty(),
    )

public fun Node.toInitialServerChunk(
    sequence: Long = 1,
    manifest: ClientComponentManifest = ClientComponentManifest(),
): ServerRenderChunk.Tree =
    ServerRenderChunk.Tree(sequence = sequence, node = this, manifest = manifest)

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
                results.send(
                    ServerRenderDeferredResult.Failed(
                        boundaryId = subtree.boundaryId,
                        message = error.message ?: error.toString(),
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
                    node = result.node,
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
        }
    }

    visit(this, emptyList())
    return islands
}

private const val INTEGRITY_ALGORITHM = "fnv1a64"
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
