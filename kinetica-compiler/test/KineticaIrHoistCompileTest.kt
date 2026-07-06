package io.heapy.kinetica.compiler

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KineticaIrHoistCompileTest {
    private val harness = KineticaCompilationHarness()

    @Test
    fun hoistTransformFiresAndReusesStaticHostNodeAtRuntime() {
        harness.compile(mapOf("app/HoistSample.kt" to HOIST_SAMPLE)).use { compiled ->
            compiled.assertTransformFired("interned 1 const props, hoisted 1 static leaf hosts")
            val facade = compiled.loadClass("app.HoistSampleKt")
            val fieldNames = facade.declaredFields.map { field -> field.name }

            assertTrue(fieldNames.any { name -> name.startsWith("kineticaProps\$") })
            assertTrue(fieldNames.any { name -> name.startsWith("kineticaHost\$") })

            val render = facade.getDeclaredMethod(
                "renderLeaf",
                KineticaRuntime::class.java,
                ComponentScope::class.java,
            )
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)

            val first = render.invoke(null, runtime, scope) as Node
            val second = render.invoke(null, runtime, scope) as Node

            assertSame(first, second)
            assertEquals("td", (second as HostNode).tag)
            assertEquals(mapOf("class" to "col-id"), second.props)
        }
    }

    @Test
    fun transformsKillSwitchLeavesHoistSampleUnchangedAtRuntime() {
        harness.compile(
            sources = mapOf("app/HoistSample.kt" to HOIST_SAMPLE),
            transforms = "off",
        ).use { compiled ->
            compiled.assertTransformDidNotFire("wrapped in skippableNode")
            compiled.assertTransformDidNotFire("interned")
            val facade = compiled.loadClass("app.HoistSampleKt")
            val fieldNames = facade.declaredFields.map { field -> field.name }

            assertFalse(fieldNames.any { name -> name.startsWith("kineticaProps\$") })
            assertFalse(fieldNames.any { name -> name.startsWith("kineticaHost\$") })

            val render = facade.getDeclaredMethod(
                "renderLeaf",
                KineticaRuntime::class.java,
                ComponentScope::class.java,
            )
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)

            val first = render.invoke(null, runtime, scope) as Node
            val second = render.invoke(null, runtime, scope) as Node

            assertEquals(first, second)
            assertNotSame(first, second)
            assertEquals("td", (second as HostNode).tag)
            assertEquals(mapOf("class" to "col-id"), second.props)
        }
    }

    @Test
    fun emptyPropsOfIsNotInternedByHoistTransform() {
        harness.compile(mapOf("app/EmptyPropsHoist.kt" to EMPTY_PROPS_HOIST_SAMPLE)).use { compiled ->
            compiled.assertTransformDidNotFire("interned")
            val facade = compiled.loadClass("app.EmptyPropsHoistKt")
            val fieldNames = facade.declaredFields.map { field -> field.name }

            assertFalse(fieldNames.any { name -> name.startsWith("kineticaProps\$") })
            val render = facade.getDeclaredMethod(
                "renderEmptyPropsLeaf",
                KineticaRuntime::class.java,
                ComponentScope::class.java,
            )
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)

            val rendered = render.invoke(null, runtime, scope) as Node

            assertEquals("td", (rendered as HostNode).tag)
            assertEquals(emptyMap(), rendered.props)
        }
    }

    private companion object {
        private const val HOIST_SAMPLE = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.host
            import io.heapy.kinetica.propsOf

            @UiComponent(skippable = false)
            fun ComponentScope.Leaf() {
                host("td", props = propsOf("class", "col-id"))
            }

            fun renderLeaf(runtime: KineticaRuntime, scope: ComponentScope): Node =
                runtime.render(scope) {
                    Leaf()
                }.tree
        """

        private const val EMPTY_PROPS_HOIST_SAMPLE = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.KineticaRuntime
            import io.heapy.kinetica.Node
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.host
            import io.heapy.kinetica.propsOf

            @UiComponent(skippable = false)
            fun ComponentScope.EmptyPropsLeaf() {
                host("td", props = propsOf())
            }

            fun renderEmptyPropsLeaf(runtime: KineticaRuntime, scope: ComponentScope): Node =
                runtime.render(scope) {
                    EmptyPropsLeaf()
                }.tree
        """
    }
}
