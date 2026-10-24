package app.annotated

import io.heapy.kinetica.CapabilityToken
import io.heapy.kinetica.CsrfToken
import io.heapy.kinetica.ServerActionRequest
import io.heapy.kinetica.ServerActionResponse
import io.heapy.kinetica.generated.KineticaGeneratedCompilerPluginId
import io.heapy.kinetica.generated.KineticaGeneratedCompilerPluginVersion
import io.heapy.kinetica.generated.KineticaGeneratedClientManifest
import io.heapy.kinetica.generated.KineticaGeneratedComponentTransforms
import io.heapy.kinetica.generated.KineticaGeneratedPreviews
import io.heapy.kinetica.generated.KineticaGeneratedServerActionStubs
import io.heapy.kinetica.generated.KineticaGeneratedServerActions
import io.heapy.kinetica.generated.kineticaGeneratedServerActionDispatcher
import io.heapy.kinetica.testing.KineticaTest
import io.heapy.kinetica.testing.hasText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals

class AnnotatedAppTest {
    @Test
    fun generatedDispatcherUsesBothSuppliedVerifiers() = runTest {
        val dispatcher = kineticaGeneratedServerActionDispatcher(
            verifyCapabilityToken = { it.value == "test-capability" },
            verifyCsrfToken = { it?.value == "test-csrf" },
        )
        val request = ServerActionRequest("missing", JsonNull, CapabilityToken("invalid"))
        assertEquals(ServerActionResponse.Failure("Invalid capability token."), dispatcher.dispatch(request))

        val authorized = request.copy(token = CapabilityToken("test-capability"))
        assertEquals(ServerActionResponse.Failure("Invalid CSRF token."), dispatcher.dispatch(authorized))
        assertEquals(
            ServerActionResponse.Failure("Unknown server action."),
            dispatcher.dispatch(authorized.copy(csrfToken = CsrfToken("test-csrf"))),
        )
    }

    @Test
    fun compilerPluginGeneratesMetadataAndAnnotatedAppRenders() {
        assertEquals("io.heapy.kinetica.compiler", KineticaGeneratedCompilerPluginId)
        assertEquals("0.4.0", KineticaGeneratedCompilerPluginVersion)
        assertEquals(emptyList(), KineticaGeneratedServerActions)
        assertEquals(emptyList(), KineticaGeneratedServerActionStubs)
        assertEquals(emptyList(), KineticaGeneratedClientManifest.components)
        assertEquals(KineticaGeneratedServerActions, KineticaGeneratedClientManifest.actions)
        assertEquals(
            0,
            kineticaGeneratedServerActionDispatcher(
                verifyCapabilityToken = { it.value == "test-capability" },
                verifyCsrfToken = { it?.value == "test-csrf" },
            ).actions.size,
        )

        assertEquals(emptyList(), KineticaGeneratedPreviews)
        assertEquals(emptyList(), KineticaGeneratedComponentTransforms)

        val root = KineticaTest.render {
            emit(AnnotatedApp())
        }

        root.node(hasText("Static badge"))
        root.node(hasText("Inbox"))
    }
}
