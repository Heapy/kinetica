package io.heapy.kinetica.compiler

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.FrameTable
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.MissingKineticaPluginException
import io.heapy.kinetica.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun eachContentTableIsTheKeyedRowFrame() {
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

                    @UiComponent(skippable = false)
                    fun ComponentScope.Rows(items: List<String>) {
                        each(items, key = { it }) { item ->
                            val row = state { item }
                            text(row.value)
                            keyed("nested") {
                                val nested = state { "nested:" + item }
                                text(nested.value)
                            }
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope, items: List<String>): Node =
                        runtime.render(scope) { Rows(items) }.tree
                """,
            ),
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            compiled.invokeRender(
                "app.MainKt",
                "render",
                List::class.java to listOf("a"),
                runtime = runtime,
                scope = scope,
            )

            val rootFrame = privateField(scope, "rootFrame")!!
            val renderFrame = frameRegions(rootFrame).values.single()!!
            val rowsFrame = frameChildren(renderFrame)[0]!!
            val rowFrame = childMap(frameChildren(rowsFrame)[0])["a"]!!
            val rowTable = frameTable(rowFrame)
            assertTrue(rowTable != null, "each row keyed frame must be the numbered row frame")
            assertEquals(1, rowTable.slotCount, "row state ordinal belongs to the keyed row frame")
            assertEquals(1, rowTable.childCount, "nested region ordinal belongs to the keyed row frame")
            assertEquals(null, privateField(rowFrame, "regions"), "row content must not open a second region frame")

            val nestedKeyedFrame = childMap(frameChildren(rowFrame)[0])["nested"]!!
            assertEquals(null, frameTable(nestedKeyedFrame), "ordinary keyed frames remain growable")
            assertTrue(
                frameRegions(nestedKeyedFrame).isNotEmpty(),
                "nested region inside the row must still open a child region frame",
            )
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
    fun inlineUnitEventPassedToHostEventFusesIntoFrameEvent() {
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.event
                    import io.heapy.kinetica.host
                    import io.heapy.kinetica.hostEvent

                    var fusedClicks: Int = 0
                    var storedClicks: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fused() {
                        val click = hostEvent(onEvent = event { fusedClicks += 1 })
                        host("button", props = mapOf("event:onClick" to click))
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Stored() {
                        val callback = event { storedClicks += 1 }
                        val click = hostEvent(onEvent = callback)
                        host("button", props = mapOf("event:onClick" to click))
                    }

                    fun renderFused(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fused() }.tree

                    fun renderStored(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Stored() }.tree

                    fun fusedClickCount(): Int = fusedClicks
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

            val fused = tables.single { it.functionFqName == "app.Fused" }
            assertEquals(0, fused.slotCount, "inline unit event passed to hostEvent must not consume a slot")
            assertEquals(1, fused.eventCount, "fused hostEvent still consumes one frame event ordinal")

            val stored = tables.single { it.functionFqName == "app.Stored" }
            assertEquals(1, stored.slotCount, "stored event callback keeps the StableUnitEvent slot")
            assertEquals(1, stored.eventCount, "stored callback hostEvent still consumes one frame event ordinal")

            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender(fileClass, "renderFused", runtime = runtime, scope = scope) as HostNode
            val firstEventId = first.props.getValue("event:onClick")
            val second = compiled.invokeRender(fileClass, "renderFused", runtime = runtime, scope = scope) as HostNode
            assertEquals(firstEventId, second.props.getValue("event:onClick"), "frame event id must be reused")
            assertEquals(
                1,
                (privateField(runtime, "events") as Map<*, *>).size,
                "rerender must update, not duplicate, the event",
            )

            runtime.dispatch(firstEventId)
            assertEquals(1, fileClass.getDeclaredMethod("fusedClickCount").invoke(null))
        }
    }

    @Test
    fun emptyOracleFallsBackToNameListsForScopeFunctionLambdas() {
        // checks=off keeps the FIR extension unregistered, so the per-compilation
        // SingleRunOracle stays empty and IR must number scope-function lambdas from the
        // shared KineticaFramePolicy name lists alone. NOTE for Task 15: once checks=off
        // starts registering the FIR soundness rules, the oracle will no longer be empty
        // here — the assertion stays valid but weakens; keep the name lists covered.
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
                    fun ComponentScope.Wrapped() {
                        run {
                            val id = state { nextId++ }
                            text("run=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Wrapped() }.tree
                """,
            ),
            checks = "off",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("run=0" in first, "name-list fallback must number the run {} lambda: $first")
            assertEquals(first, second, "slots must be reused, not re-initialized, across renders")
        }
    }

    @Test
    fun multiRunLambdaSlotCallsFailFastWhenChecksAreOff() {
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
            checks = "off",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
            }
            val cause = failure.cause
            assertTrue(
                cause is MissingKineticaPluginException,
                "slot calls in multi-run lambdas must fail fast, got: $cause",
            )
            assertTrue(
                cause.message.orEmpty().startsWith(
                    "A Kinetica derived ran without a compiler-assigned ordinal.",
                ),
                "the fail-fast message must identify the untransformed construct: ${cause.message}",
            )
        }
    }

    @Test
    fun multiRunComponentTypedHelperFailsFastWhenChecksAreOff() {
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan() {
                        listOf(1).forEach { helper { Badge() } }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fan() }.tree
                """,
            ),
            checks = "off",
        ).use { compiled ->
            val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                compiled.invokeRender("app.MainKt", "render")
            }
            val cause = failure.cause
            assertTrue(
                cause is MissingKineticaPluginException,
                "component calls hidden behind multi-run component content must fail fast, got: $cause",
            )
            assertTrue(
                cause.message.orEmpty().startsWith(
                    "A Kinetica component call ran without a compiler-assigned ordinal.",
                ),
                "the fail-fast message must identify the unstaged component call: ${cause.message}",
            )
        }
    }

    @Test
    fun contentLambdaInvokedTwiceForksRegionFramesPerInvocation() {
        // F3 probe: a helper that invokes its @UiComponent content twice gets ONE static
        // FrameTable for the lambda literal; the runtime must fork the region frame on
        // re-entry so the two invocations cannot alias state cells or event ordinals.
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.host
                    import io.heapy.kinetica.hostEvent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    fun ComponentScope.twice(content: @UiComponent ComponentScope.() -> Unit) {
                        content()
                        content()
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan() {
                        twice {
                            val id = state { nextId++ }
                            val clicks = state { 0 }
                            val click = hostEvent(onEvent = { clicks.value = clicks.value + 1 })
                            host("button", props = mapOf("event:onClick" to click))
                            text("cell" + id.value + "=" + clicks.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fan() }.tree
                """,
            ),
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
            val firstText = first.toDebugString()
            assertTrue(
                "cell0=0" in firstText && "cell1=0" in firstText,
                "the two content invocations must get independent state cells: $firstText",
            )
            val eventIds = first.collectHostEventIds()
            assertEquals(2, eventIds.size, "each invocation must render its own button: $firstText")
            assertTrue(
                eventIds[0] != eventIds[1],
                "each invocation must register its own event, not update the other's: $eventIds",
            )

            runtime.dispatch(eventIds[1])
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
                .toDebugString()
            assertTrue(
                "cell0=0" in second && "cell1=1" in second,
                "clicking the second invocation's button must mutate only its own cell: $second",
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

    @Test
    fun nullLiteralHandlersInLoopCompileAndRenderWithoutEvents() {
        // F2 sound proxy end to end: an absent or literal-null handler never reaches
        // registerHostEvent, so the loop-shared static event ordinal IR fills stays
        // unused — the pattern must compile at checks=error AND render without crashing.
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button
                    import io.heapy.kinetica.checkbox
                    import io.heapy.kinetica.text

                    @UiComponent(skippable = false)
                    fun ComponentScope.Rows() {
                        for (label in listOf("a", "b", "c")) {
                            button(onClick = null) { text("row-" + label) }
                            checkbox(checked = false)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Rows() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue(
                "row-a" in first && "row-b" in first && "row-c" in first,
                "all rows must render: $first",
            )
            assertTrue("event:" !in first, "null handlers must not register host events: $first")
            assertEquals(first, second, "re-render must be stable")
        }
    }

    @Test
    fun deferredHandlersAndRegionContentCompileAndDispatchAtChecksError() {
        // F4/F5 positive side: handlers without ordinal consumers stay legal and still
        // register their events, and slot calls directly in region content lambdas keep
        // compiling and numbering correctly next to a (multi-run) plain key selector.
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button
                    import io.heapy.kinetica.each
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel(items: List<String>) {
                        val count = state { 0 }
                        button(onClick = { count.value = count.value + 1 }) {
                            text("clicks=" + count.value)
                        }
                        each(items, key = { it }) { item ->
                            val id = state { nextId++ }
                            text(item + "=" + id.value)
                        }
                        keyed("footer") {
                            val id = state { nextId++ }
                            text("footer=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope, items: List<String>): Node =
                        runtime.render(scope) { Panel(items) }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val items = listOf("a", "b")
            val first = compiled.invokeRender(
                "app.MainKt",
                "render",
                List::class.java to items,
                runtime = runtime,
                scope = scope,
            )
            val firstText = first.toDebugString()
            assertTrue("clicks=0" in firstText, "initial click count must render: $firstText")
            assertTrue(
                "a=0" in firstText && "b=1" in firstText && "footer=2" in firstText,
                "region content slots must number correctly: $firstText",
            )

            runtime.dispatch(first.collectHostEventIds().single())
            val second = compiled.invokeRender(
                "app.MainKt",
                "render",
                List::class.java to items,
                runtime = runtime,
                scope = scope,
            ).toDebugString()
            assertTrue("clicks=1" in second, "dispatched handler must update the cell: $second")
            assertTrue(
                "a=0" in second && "b=1" in second && "footer=2" in second,
                "region content slots must be reused across renders: $second",
            )
        }
    }

    @Test
    fun deferredHandlerSlotCallsFailFastWhenChecksAreOff() {
        // F4 IR alignment: the walker no longer numbers slot calls inside deferred
        // handler lambdas, so at checks=off the handler fails fast at dispatch time
        // instead of silently sharing one root-frame ordinal across components.
        harness.compile(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel() {
                        button(onClick = { state { "handler" }.value }) { text("b") }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Panel() }.tree
                """,
            ),
            checks = "off",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val tree = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
            val eventId = tree.collectHostEventIds().single()
            val failure = assertFailsWith<MissingKineticaPluginException> {
                runtime.dispatch(eventId)
            }
            assertTrue(
                failure.message.orEmpty().startsWith("A Kinetica state ran without a compiler-assigned ordinal."),
                "the fail-fast message must identify the untransformed construct: ${failure.message}",
            )
        }
    }

    @Test
    fun eachKeySelectorSlotCallsFailFastWhenChecksAreOff() {
        // F5 IR alignment: key selectors are not numbered, so at checks=off the selector
        // fails fast instead of borrowing the enclosing region's counters and collapsing
        // every item onto the first item's key (duplicate-key crash).
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

                    @UiComponent(skippable = false)
                    fun ComponentScope.Rows() {
                        each(listOf("a", "b", "c"), key = { item -> state { item }.value }) { item ->
                            text(item)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Rows() }.tree
                """,
            ),
            checks = "off",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
            }
            val cause = failure.cause
            assertTrue(
                cause is MissingKineticaPluginException,
                "slot calls in key selectors must fail fast, got: $cause",
            )
        }
    }

    @Test
    fun valStoredLambdaSlotCallsFailFastWhenChecksAreOff() {
        // F6 IR alignment: the walker no longer descends into lambda literals stored in
        // variables (they are not call arguments, so no single-run verdict can exist),
        // so at checks=off the stored row lambda fails fast on its state call instead of
        // aliasing slot 0 of the enclosing region across every invocation.
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
                    fun ComponentScope.ValLambdaFan(items: List<Int>) {
                        val row: (Int) -> Unit = { i ->
                            val s = state { i }
                            text("row=" + i + " state=" + s.value)
                        }
                        items.forEach { row(it) }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope, items: List<Int>): Node =
                        runtime.render(scope) { ValLambdaFan(items) }.tree
                """,
            ),
            checks = "off",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                compiled.invokeRender(
                    "app.MainKt",
                    "render",
                    List::class.java to listOf(10, 20, 30),
                    runtime = runtime,
                    scope = scope,
                )
            }
            val cause = failure.cause
            assertTrue(
                cause is MissingKineticaPluginException,
                "slot calls in stored lambdas must fail fast, got: $cause",
            )
            assertTrue(
                cause.message.orEmpty().startsWith("A Kinetica state ran without a compiler-assigned ordinal."),
                "the fail-fast message must identify the untransformed construct: ${cause.message}",
            )
        }
    }

    @Test
    fun componentCallsOnSimpleReceiversStageAndRenderStably() {
        // Positive side of F10 (rule H): explicit `this` and a plain local val are the
        // IrGetValue shapes IR stages — the two calls get distinct child frames whose
        // state survives re-render, and no "left unstaged" decline fires at checks=error.
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
                    fun ComponentScope.Badge(label: String) {
                        val id = state { nextId++ }
                        text("badge:" + label + ":" + id.value)
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan() {
                        this.Badge("explicit")
                        val scope = this
                        scope.Badge("local")
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fan() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertTransformDidNotFire("left unstaged")
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("badge:explicit:0" in first, "explicit-this call must get its own staged frame: $first")
            assertTrue("badge:local:1" in first, "local-val call must get its own staged frame: $first")
            assertEquals(first, second, "staged component frames must be reused, not re-initialized, across renders")
        }
    }

    private fun Node.toDebugString(): String = toString()

    private fun Node.collectHostEventIds(): List<String> = when (this) {
        is FragmentNode -> children.flatMap { it.collectHostEventIds() }
        is HostNode -> listOfNotNull(props["event:onClick"]) + children.flatMap { it.collectHostEventIds() }
        else -> emptyList()
    }

    private fun privateField(instance: Any, name: String): Any? =
        instance.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(instance)

    private fun frameTable(frame: Any): FrameTable? =
        privateField(frame, "table") as? FrameTable

    private fun frameRegions(frame: Any): Map<*, *> =
        privateField(frame, "regions") as? Map<*, *> ?: emptyMap<Any, Any>()

    @Suppress("UNCHECKED_CAST")
    private fun frameChildren(frame: Any): Array<Any?> =
        privateField(frame, "children") as Array<Any?>

    @Suppress("UNCHECKED_CAST")
    private fun childMap(entry: Any?): Map<Any, Any> =
        entry as Map<Any, Any>
}
