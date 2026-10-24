package io.heapy.kinetica

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ServerActionHttpTest {
    private val transport = KineticaServerTransport()
    private var calls = 0
    private var verifications = 0
    private val dispatcher = KineticaServerActionDispatcher(
        stubs = listOf(
            serverActionStub(
                registration = ServerActionRegistration("count.set", "app.setCount"),
                inputSerializer = Int.serializer(),
                outputSerializer = Int.serializer(),
            ) { input ->
                calls++
                input
            },
        ),
        verifyCapabilityToken = {
            verifications++
            it.value == "valid"
        },
        verifyCsrfToken = { it?.value == "csrf" },
    )
    private val request = ServerActionRequest(
        actionId = "count.set",
        payload = JsonPrimitive(42),
        token = CapabilityToken("valid"),
        csrfToken = CsrfToken("csrf"),
    )

    @Test
    fun oversizedBodyIsRejectedWithoutDispatching() = runTest {
        val response = dispatcher.dispatchHttp(transport, null)

        assertEquals(413, response.status)
        assertEquals(ServerActionResponse.Failure("Request body too large."), transport.decodeActionResponse(response.body))
        assertEquals(0, verifications)
        assertEquals(0, calls)
    }

    @Test
    fun dispatchedSuccessAndFailureBothUse200() = runTest {
        val success = dispatcher.dispatchHttp(transport, transport.encodeActionRequest(request))
        assertEquals(200, success.status)
        assertEquals(ServerActionResponse.Success(JsonPrimitive(42)), transport.decodeActionResponse(success.body))

        val denied = dispatcher.dispatchHttp(
            transport,
            transport.encodeActionRequest(request.copy(token = CapabilityToken("invalid"))),
        )
        assertEquals(200, denied.status)
        assertEquals(ServerActionResponse.Failure("Invalid capability token."), transport.decodeActionResponse(denied.body))
        assertEquals(1, calls)
    }

    @Test
    fun malformedEnvelopesAreRejectedWithoutDispatching() = runTest {
        for (body in listOf("", "{", "{}", "[]", "null")) {
            val response = dispatcher.dispatchHttp(transport, body)

            assertEquals(400, response.status, "Body: $body")
            assertEquals(
                ServerActionResponse.Failure("Malformed server action request."),
                transport.decodeActionResponse(response.body),
            )
        }
        assertEquals(0, verifications)
        assertEquals(0, calls)
    }

    @Test
    fun serializationFaultDuringDispatchStillPropagatesToServerErrorHandler() = runTest {
        val fault = SerializationException("Verifier storage failed")
        val brokenDispatcher = KineticaServerActionDispatcher(
            stubs = emptyList(),
            verifyCapabilityToken = { throw fault },
        )

        val thrown = assertFailsWith<SerializationException> {
            brokenDispatcher.dispatchHttp(transport, transport.encodeActionRequest(request))
        }
        assertSame(fault, thrown)
    }
}
