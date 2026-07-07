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
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
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
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            assertTrue(
                "value:A" in compiled.invokeRender(
                    "app.MainKt",
                    "render",
                    Boolean::class.java to true,
                    runtime = runtime,
                    scope = scope,
                ).toDebugString(),
            )
            assertTrue(
                "value:B" in compiled.invokeRender(
                    "app.MainKt",
                    "render",
                    Boolean::class.java to false,
                    runtime = runtime,
                    scope = scope,
                ).toDebugString(),
            )
            assertTrue(
                "value:A" in compiled.invokeRender(
                    "app.MainKt",
                    "render",
                    Boolean::class.java to true,
                    runtime = runtime,
                    scope = scope,
                ).toDebugString(),
            )
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
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender(
                "app.MainKt",
                "render",
                List::class.java to listOf("a", "b"),
                runtime = runtime,
                scope = scope,
            ).toDebugString()
            assertTrue("a=0" in first && "b=1" in first, "rows must get distinct frames: $first")
            val reordered = compiled.invokeRender(
                "app.MainKt",
                "render",
                List::class.java to listOf("b", "a"),
                runtime = runtime,
                scope = scope,
            ).toDebugString()
            assertTrue("a=0" in reordered && "b=1" in reordered, "row state must follow keys: $reordered")
            // Row removal disposes its frame; a returning key re-initializes.
            compiled.invokeRender(
                "app.MainKt",
                "render",
                List::class.java to listOf("b"),
                runtime = runtime,
                scope = scope,
            )
            val returned = compiled.invokeRender(
                "app.MainKt",
                "render",
                List::class.java to listOf("b", "a"),
                runtime = runtime,
                scope = scope,
            ).toDebugString()
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
    fun multiRunLambdaSlotCallsFailFast() {
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
                        // all iterations into one slot, so slot calls in multi-run lambdas
                        // are not numbered and must fail fast instead of aliasing silently.
                        val cells = List(3) { index -> derived { index } }
                        text("sum:" + cells.sumOf { it.value })
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fan() }.tree
                """,
            ),
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
            }
            assertTrue(
                failure.cause is MissingKineticaPluginException,
                "slot calls in multi-run lambdas must fail fast, got: ${failure.cause}",
            )
        }
    }

    @Test
    fun componentContentParametersWrapInsideComponentBodies() {
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
                    fun ComponentScope.Shell(content: @UiComponent ComponentScope.() -> Unit) {
                        text("shell:")
                        content()
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Outer() {
                        Shell {
                            val inner = state { "inner-state" }
                            text(inner.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Outer() }.tree
                """,
            ),
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val tree = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue(
                "inner-state" in tree,
                "content lambdas of component calls inside component bodies must region-wrap: $tree",
            )
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
