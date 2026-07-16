package app.annotated

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
import kotlin.test.Test
import kotlin.test.assertEquals

class AnnotatedAppTest {
    @Test
    fun compilerPluginGeneratesMetadataAndAnnotatedAppRenders() {
        assertEquals("io.heapy.kinetica.compiler", KineticaGeneratedCompilerPluginId)
        assertEquals("0.3.0", KineticaGeneratedCompilerPluginVersion)
        assertEquals(emptyList(), KineticaGeneratedServerActions)
        assertEquals(emptyList(), KineticaGeneratedServerActionStubs)
        assertEquals(emptyList(), KineticaGeneratedClientManifest.components)
        assertEquals(KineticaGeneratedServerActions, KineticaGeneratedClientManifest.actions)
        assertEquals(
            0,
            kineticaGeneratedServerActionDispatcher(
                verifyCapabilityToken = { true },
                verifyCsrfToken = { true },
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
