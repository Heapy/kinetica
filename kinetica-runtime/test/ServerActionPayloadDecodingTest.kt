package io.heapy.kinetica

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ServerActionPayloadDecodingTest {
    @Test
    fun invalidIntegersAreRejectedBeforeInvokingHandler() = runTest {
        var calls = 0
        val stub = payloadStub(Int.serializer()) { input ->
            calls++
            input
        }

        for (payload in listOf(JsonPrimitive(3.5), JsonPrimitive(2147483648L), JsonPrimitive(-2147483649L))) {
            assertEquals(
                ServerActionResponse.Failure("Invalid server action payload."),
                stub.dispatch(KineticaJson, payload),
            )
        }
        assertEquals(0, calls)
        assertEquals(
            ServerActionResponse.Success(JsonPrimitive(42)),
            stub.dispatch(KineticaJson, JsonPrimitive(42)),
        )
        assertEquals(1, calls)
    }

    @Test
    fun inputConstructorFailureIsRejectedBeforeInvokingHandler() = runTest {
        var calls = 0
        val stub = payloadStub(GuardedActionInput.serializer()) { input ->
            calls++
            input.n
        }

        assertEquals(
            ServerActionResponse.Failure("Invalid server action payload."),
            stub.dispatch(KineticaJson, JsonObject(mapOf("n" to JsonPrimitive(-5)))),
        )
        assertEquals(0, calls)
    }

    @Test
    fun cancellationDuringDecodingIsRethrown() = runTest {
        val cancellation = CancellationException("stop decoding")
        val serializer = object : KSerializer<Int> by Int.serializer() {
            override fun deserialize(decoder: Decoder): Int = throw cancellation
        }
        var calls = 0
        val stub = payloadStub(serializer) { input ->
            calls++
            input
        }

        assertSame(cancellation, assertFailsWith<CancellationException> {
            stub.dispatch(KineticaJson, JsonPrimitive(1))
        })
        assertEquals(0, calls)
    }

    private fun <I> payloadStub(serializer: KSerializer<I>, handler: suspend (I) -> Int): ServerActionStub =
        serverActionStub(
            registration = ServerActionRegistration(actionId = "count.set", functionFqName = "app.setCount"),
            inputSerializer = serializer,
            outputSerializer = Int.serializer(),
            handler = handler,
        )
}

@Serializable
private data class GuardedActionInput(val n: Int) {
    init {
        require(n >= 0) { "input constructor detail" }
    }
}
