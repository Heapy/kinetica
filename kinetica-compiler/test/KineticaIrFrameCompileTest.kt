package io.heapy.kinetica.compiler

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.FrameTable
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.MissingKineticaPluginException
import io.heapy.kinetica.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Compiles sources with the frame/ordinal emission pass and runs them against the real
 * runtime: slot identity per (frame, ordinal), sibling call sites and branches not
 * aliasing, each rows in keyed frames, emitted FrameTable statics, and the
 * missing-plugin backstop for unstaged component calls.
 */
class KineticaIrFrameCompileTest {
    private val harness = KineticaCompilationHarness()

    @Test
    fun stateKeepsIdentityPerCallsiteAcrossRenders() {
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Child() {
                        val id = state { nextId++ }
                        text("id:" + id.value)
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel() {
                        Child()
                        Child()
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Panel() }.tree
                """,
            ),
        ).use { compiled ->
            val render = compiled.loadClass("app.MainKt").getDeclaredMethod(
                "render",
                KineticaRuntime::class.java,
                ComponentScope::class.java,
            )
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = (render.invoke(null, runtime, scope) as Node).toDebugString()
            val second = (render.invoke(null, runtime, scope) as Node).toDebugString()
            assertTrue("id:0" in first && "id:1" in first, "two call sites must get distinct slots: $first")
            assertEquals(first, second, "slots must be reused, not re-initialized, across renders")
        }
    }

    @Test
    fun divergentBranchesDoNotAliasState() {
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent(skippable = false)
                    fun ComponentScope.Branchy(flag: Boolean) {
                        if (flag) {
                            val a = state { "A" }
                            text("value:" + a.value)
                        } else {
                            val b = state { "B" }
                            text("value:" + b.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope, flag: Boolean): Node =
                        runtime.render(scope) { Branchy(flag) }.tree
                """,
            ),
        ).use { compiled ->
            val render = compiled.loadClass("app.MainKt").getDeclaredMethod(
                "render",
                KineticaRuntime::class.java,
                ComponentScope::class.java,
                Boolean::class.java,
            )
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            assertTrue("value:A" in (render.invoke(null, runtime, scope, true) as Node).toDebugString())
            assertTrue("value:B" in (render.invoke(null, runtime, scope, false) as Node).toDebugString())
            assertTrue("value:A" in (render.invoke(null, runtime, scope, true) as Node).toDebugString())
        }
    }

    @Test
    fun eachRowsRenderInOwnKeyedFramesAndSurviveReorder() {
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.each
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Rows(items: List<String>) {
                        each(items, key = { it }) { item ->
                            val id = state { nextId++ }
                            text(item + "=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope, items: List<String>): Node =
                        runtime.render(scope) { Rows(items) }.tree
                """,
            ),
        ).use { compiled ->
            val render = compiled.loadClass("app.MainKt").getDeclaredMethod(
                "render",
                KineticaRuntime::class.java,
                ComponentScope::class.java,
                List::class.java,
            )
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = (render.invoke(null, runtime, scope, listOf("a", "b")) as Node).toDebugString()
            assertTrue("a=0" in first && "b=1" in first, "rows must get distinct frames: $first")
            val reordered = (render.invoke(null, runtime, scope, listOf("b", "a")) as Node).toDebugString()
            assertTrue("a=0" in reordered && "b=1" in reordered, "row state must follow keys: $reordered")
            // Row removal disposes its frame; a returning key re-initializes.
            render.invoke(null, runtime, scope, listOf("b"))
            val returned = (render.invoke(null, runtime, scope, listOf("b", "a")) as Node).toDebugString()
            assertTrue("a=2" in returned, "removed row's state must not resurrect: $returned")
        }
    }

    @Test
    fun frameTableStaticsCarryRegionCounts() {
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button
                    import io.heapy.kinetica.launchEffect
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent(skippable = false)
                    fun ComponentScope.Counter() {
                        val count = state { 0 }
                        val label = state { "x" }
                        launchEffect { }
                        button(onClick = { count.value = count.value + 1 }) {
                            text(label.value + count.value)
                        }
                    }
                """,
            ),
        ).use { compiled ->
            val fileClass = compiled.loadClass("app.MainKt")
            val tables = fileClass.declaredFields
                .filter { it.type == FrameTable::class.java }
                .map { field ->
                    field.isAccessible = true
                    field.get(null) as FrameTable
                }
            val component = tables.single { it.functionFqName == "app.Counter" && it.slotCount > 0 }
            assertEquals(3, component.slotCount, "count, label, launchEffect")
            assertEquals(1, component.eventCount, "button onClick")
            assertEquals(intArrayOf(2).toList(), component.transientSlotOrdinals.toList(), "launchEffect is transient")
        }
    }

    @Test
    fun multiRunLambdasKeepPerInvocationSlots() {
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.derived
                    import io.heapy.kinetica.text

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan() {
                        // List's init lambda runs three times: a static ordinal would alias
                        // all iterations into one slot. Multi-run lambdas must stay on the
                        // per-invocation legacy cursors.
                        val cells = List(3) { index -> derived { index } }
                        text("sum:" + cells.sumOf { it.value })
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fan() }.tree
                """,
            ),
        ).use { compiled ->
            val render = compiled.loadClass("app.MainKt").getDeclaredMethod(
                "render",
                KineticaRuntime::class.java,
                ComponentScope::class.java,
            )
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val tree = (render.invoke(null, runtime, scope) as Node).toDebugString()
            assertTrue("sum:3" in tree, "each iteration must get its own slot (0+1+2): $tree")
        }
    }

    @Test
    fun unstagedComponentCallThrowsMissingPlugin() {
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent(skippable = false)
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    fun callRaw(scope: ComponentScope) {
                        scope.Badge()
                    }
                """,
            ),
        ).use { compiled ->
            val callRaw = compiled.loadClass("app.MainKt").getDeclaredMethod("callRaw", ComponentScope::class.java)
            val scope = ComponentScope(KineticaRuntime())
            val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                callRaw.invoke(null, scope)
            }
            assertTrue(
                failure.cause is MissingKineticaPluginException,
                "raw component calls must hit the missing-plugin backstop, got: ${failure.cause}",
            )
        }
    }

    private fun Node.toDebugString(): String = toString()
}
