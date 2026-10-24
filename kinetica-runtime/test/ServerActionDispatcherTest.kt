package io.heapy.kinetica

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerActionDispatcherTest {
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
