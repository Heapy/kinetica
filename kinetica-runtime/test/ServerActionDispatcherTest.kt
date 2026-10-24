package io.heapy.kinetica

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerActionDispatcherTest {
    @Test
    fun handlerRejectionReturnsItsClientSafeMessage() = runTest {
        val stub = serverActionStub(
            registration = ServerActionRegistration("count.set", "app.setCount"),
            inputSerializer = Int.serializer(),
            outputSerializer = Int.serializer(),
        ) { throw ServerActionRejection("Quantity must be positive.") }

        assertEquals(
            ServerActionResponse.Failure("Quantity must be positive."),
            stub.dispatch(KineticaJson, JsonPrimitive(-1)),
        )
    }

    @Test
    fun serializerRejectionsKeepInternalDetailsOutOfResponses() = runTest {
        val rejectingSerializer = object : KSerializer<Int> by Int.serializer() {
            override fun deserialize(decoder: Decoder): Int =
                throw ServerActionRejection("Internal decoder details")

            override fun serialize(encoder: Encoder, value: Int): Unit =
                throw ServerActionRejection("Internal encoder details")
        }
        var calls = 0
        val decodeFailure = serverActionStub(
            registration = ServerActionRegistration("count.set", "app.setCount"),
            inputSerializer = rejectingSerializer,
            outputSerializer = Int.serializer(),
        ) { input ->
            calls++
            input
        }
        assertEquals(
            ServerActionResponse.Failure("Invalid server action payload."),
            decodeFailure.dispatch(KineticaJson, JsonPrimitive(42)),
        )
        assertEquals(0, calls)

        val encodeFailure = serverActionStub(
            registration = ServerActionRegistration("count.set", "app.setCount"),
            inputSerializer = Int.serializer(),
            outputSerializer = rejectingSerializer,
        ) { input -> input }
        assertEquals(
            ServerActionResponse.Failure("Server action failed."),
            encodeFailure.dispatch(KineticaJson, JsonPrimitive(42)),
        )
    }

    @Test
    fun omittedVerifiersRejectBeforeInvokingHandler() = runTest {
        var calls = 0
        val stubs = listOf(
            serverActionStub(
                registration = ServerActionRegistration("count.set", "app.setCount"),
                inputSerializer = Int.serializer(),
                outputSerializer = Int.serializer(),
            ) { input ->
                calls++
                input
            },
        )
        val request = ServerActionRequest(
            actionId = "count.set",
            payload = JsonPrimitive(42),
            token = CapabilityToken("valid"),
            csrfToken = CsrfToken("csrf"),
        )
        val cases = listOf(
            KineticaServerActionDispatcher(stubs) to "Invalid capability token.",
            KineticaServerActionDispatcher(stubs, verifyCsrfToken = { true }) to "Invalid capability token.",
            KineticaServerActionDispatcher(stubs, verifyCapabilityToken = { true }) to "Invalid CSRF token.",
        )
        for ((dispatcher, message) in cases) {
            assertEquals(ServerActionResponse.Failure(message), dispatcher.dispatch(request))
            assertEquals(0, calls)
        }

        val configured = KineticaServerActionDispatcher(
            stubs = stubs,
            verifyCapabilityToken = { it.value == "valid" },
            verifyCsrfToken = { it?.value == "csrf" },
        )
        assertEquals(ServerActionResponse.Success(JsonPrimitive(42)), configured.dispatch(request))
        assertEquals(1, calls)
    }
}
