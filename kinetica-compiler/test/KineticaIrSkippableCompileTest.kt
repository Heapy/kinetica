package io.heapy.kinetica.compiler

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.JournalKind
import io.heapy.kinetica.KineticaRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KineticaIrSkippableCompileTest {
    private val harness = KineticaCompilationHarness()

    @Test
    fun skippableTransformFiresAndSkipsEqualInputsAtRuntime() {
        harness.compile(
            mapOf(
                "app/SkippableSample.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.host
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge(label: String) {
                        host("span") {
                            text(label)
                        }
                    }

                    fun renderBadge(runtime: KineticaRuntime, scope: ComponentScope, label: String): Node =
                        runtime.render(scope) {
                            Badge(label)
                        }.tree
                """,
            ),
        ).use { compiled ->
            compiled.assertTransformFired("app.Badge: wrapped in skippableNode")
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)

            val first = compiled.invokeRender(
                "app.SkippableSampleKt",
                "renderBadge",
                String::class.java to "Inbox",
                runtime = runtime,
                scope = scope,
            )
            val second = compiled.invokeRender(
                "app.SkippableSampleKt",
                "renderBadge",
                String::class.java to "Inbox",
                runtime = runtime,
                scope = scope,
            )

            assertSame(first, second)
            assertEquals("span", (second as HostNode).tag)
            assertTrue(runtime.journal().any { entry -> entry.kind == JournalKind.Skipped })
        }
    }

    @Test
    fun skippableStabilityMatrixReportsPresenceAndAbsence() {
        harness.compile(
            mapOf(
                "app/StabilityMatrix.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.Stable
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    data class DataInput(val value: String)

                    @Stable
                    class StableOverride(val values: List<String>)

                    @UiComponent
                    fun ComponentScope.DataComponent(input: DataInput) {
                        text(input.value)
                    }

                    @UiComponent
                    fun ComponentScope.ListComponent(items: List<String>) {
                        text(items.joinToString())
                    }

                    @UiComponent
                    fun ComponentScope.LambdaComponent(block: () -> Unit) {
                        block()
                    }

                    @UiComponent
                    fun ComponentScope.OverrideComponent(input: StableOverride) {
                        text(input.values.joinToString())
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.DisabledComponent(label: String) {
                        text(label)
                    }
                """,
            ),
        ).use { compiled ->
            compiled.assertTransformFired("app.DataComponent: wrapped in skippableNode")
            compiled.assertTransformFired("app.OverrideComponent: wrapped in skippableNode")
            compiled.assertTransformFired("app.ListComponent: not skippable")
            compiled.assertTransformFired("parameter 'items'")
            compiled.assertTransformFired("app.LambdaComponent: not skippable")
            compiled.assertTransformFired("parameter 'block'")
            compiled.assertTransformFired("app.DisabledComponent: skipping disabled")
            compiled.assertTransformDidNotFire("app.DisabledComponent: wrapped in skippableNode")
        }
    }
}
