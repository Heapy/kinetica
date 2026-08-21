package io.heapy.kinetica.compiler

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.FrameTable
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.MissingKineticaPluginException
import io.heapy.kinetica.Node
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
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
    fun nameListFallbackNumbersScopeFunctionLambdas() {
        // Since Task 15 the FIR extension is registered at checks=off too, so the
        // per-compilation SingleRunOracle is no longer empty here (contract verdicts
        // are recorded eagerly). The name-list branch is still what this pins: for
        // kotlin scope functions the IR walker consults KineticaFramePolicy BEFORE the
        // oracle, so numbering `run { state {} }` proves the shared name lists alone
        // classify it single-run.
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
    fun multiRunLambdaSlotCallsFailCompileWhenChecksAreOff() {
        // S1: rule F is a soundness rule, so checks=off no longer removes it — a slot
        // call in a multi-run lambda fails the compile in every mode. (The IR walker
        // still refuses to number such lambdas, as defense-in-depth.)
        val messages = harness.compileExpectingErrors(
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
        )

        messages.assertContainsError(
            "Kinetica call 'derived' cannot use a compiler-assigned ordinal inside the multi-run 'List' lambda",
        )
    }

    @Test
    fun multiRunComponentTypedHelperFailsCompileWhenChecksAreOff() {
        // Closes the known gap recorded at Task 15: this shape used to compile at every
        // checks mode and crash at first render (IR never descends into the multi-run
        // forEach lambda to wrap the content, and post-F1 the wrapper consumes no
        // ordinal). It is a soundness rule now, so `checks=off` must not remove it.
        harness.compileExpectingErrors(
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
        ).assertContainsError(
            "'helper' receives @UiComponent content inside the multi-run 'forEach' lambda",
        )
    }

    @Test
    fun entryContentUserWrapperRendersWithStableIdentity() {
        // The sound counterpart of the closed Task 15 gap: OUTSIDE component bodies the
        // entry-point pass wraps @UiComponent content ungated — a user wrapper nested in
        // entry content, and even a wrapper inside a multi-run forEach within entry
        // content, must render correctly with per-invocation identity (region re-entry
        // forks) that is stable across renders. NOTE: this probe alone cannot detect the
        // entry double-wrap (its leaked stagings stayed in one frame and LIFO hid them);
        // entryContentWrapperInsideComponentCallArgumentRendersOnce is the real pin.
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
                    fun ComponentScope.Chip() {
                        val id = state { nextId++ }
                        text("chip:" + id.value)
                    }

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    fun renderNested(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { helper { Chip(); Chip() } }.tree

                    fun renderLooped(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) {
                            listOf(1, 2).forEach { helper { Chip() } }
                        }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "renderNested", runtime = runtime, scope = scope)
                .toDebugString()
            val second = compiled.invokeRender("app.MainKt", "renderNested", runtime = runtime, scope = scope)
                .toDebugString()
            assertTrue("chip:0" in first && "chip:1" in first, "two chip call sites must get distinct slots: $first")
            assertEquals(first, second, "wrapper content slots must be stable across renders")

            val loopRuntime = KineticaRuntime()
            val loopScope = ComponentScope(loopRuntime)
            val firstLoop = compiled.invokeRender("app.MainKt", "renderLooped", runtime = loopRuntime, scope = loopScope)
                .toDebugString()
            val secondLoop = compiled.invokeRender("app.MainKt", "renderLooped", runtime = loopRuntime, scope = loopScope)
                .toDebugString()
            assertTrue(
                Regex("chip:(\\d+)").findAll(firstLoop).map { it.groupValues[1] }.toSet().size == 2,
                "the two forEach invocations must not alias one region frame: $firstLoop",
            )
            assertEquals(firstLoop, secondLoop, "per-invocation forks must be stable across renders")
        }
    }

    @Test
    fun entryContentWrapperInsideComponentCallArgumentRendersOnce() {
        // Double-wrap regression probe: the entry pass wraps helper's content bottom-up
        // at visitCall(helper); wrapping the enclosing render content then re-walked the
        // same subtree and wrapped/staged it AGAIN — two beginRegionFrame and two
        // ordinal(0) stagings with a single consume. The leaked entry (staged inside the
        // inner region frame) sat above Outer's own staged ordinal (staged in the entry
        // region), so Outer's prologue tripped the F10 frame-pairing check at first
        // render: IllegalStateException "staged in a different frame".
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
                    fun ComponentScope.Badge() {
                        val id = state { nextId++ }
                        text("badge:" + id.value)
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Outer(label: String) {
                        text("outer:" + label)
                    }

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) {
                            Outer(label = run { helper { Badge() }; "x" })
                        }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
                .toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
                .toDebugString()
            assertTrue("badge:0" in first && "outer:x" in first, "wrapper content must render: $first")
            assertEquals(first, second, "wrapper content slots must be stable across renders")
        }
    }

    @Test
    fun contractSingleRunContentWrapperWrapsContentExactlyOnce() {
        // The Walker flavor of the double processing: a wrapper whose @UiComponent
        // content parameter ALSO carries a callsInPlace EXACTLY_ONCE contract.
        // transformArgumentsSelectively used to descend via the oracle verdict
        // (numbering the body into the ENCLOSING region) and wrapAnnotatedContentArguments
        // then wrapped the same literal as a fresh region — every component call inside
        // staged twice, leaking one entry per invocation and tripping the F10
        // frame-pairing check when the wrapper ran in another call's argument position.
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
                    import kotlin.contracts.ExperimentalContracts
                    import kotlin.contracts.InvocationKind
                    import kotlin.contracts.contract

                    @OptIn(ExperimentalContracts::class)
                    fun ComponentScope.section(content: @UiComponent ComponentScope.() -> Unit) {
                        contract { callsInPlace(content, InvocationKind.EXACTLY_ONCE) }
                        content()
                    }

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Badge() {
                        val id = state { nextId++ }
                        text("badge:" + id.value)
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Outer(label: String) {
                        text("outer:" + label)
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan() {
                        Outer(label = run { section { Badge() }; "x" })
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fan() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
                .toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
                .toDebugString()
            assertTrue("badge:0" in first && "outer:x" in first, "wrapped content must render: $first")
            assertEquals(first, second, "wrapper content slots must be stable across renders")
        }
    }

    @Test
    fun constructorContentLambdaInEntryCodeIsFrameWrapped() {
        // A @UiComponent content literal handed to a CONSTRUCTOR is stored and invoked
        // later (the BrowserKineticaApp/AppKitKineticaApp shape). The entry pass must
        // wrap it exactly like a function-call argument — pre-fix it only handled
        // IrCall, so the stored lambda stayed unwrapped and Badge() threw
        // MissingKineticaPluginException at first render.
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
                    fun ComponentScope.Badge() {
                        val id = state { nextId++ }
                        text("badge:" + id.value)
                    }

                    class Holder(val content: @UiComponent ComponentScope.() -> Unit)

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node {
                        val holder = Holder({ Badge() })
                        return runtime.render(scope, holder.content).tree
                    }
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
                .toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
                .toDebugString()
            assertTrue("badge:0" in first, "constructor-stored content must render: $first")
            assertEquals(first, second, "constructor-stored content slots must be stable across renders")
        }
    }

    @Test
    fun constructorContentLambdaInsideComponentBodyWrapsAsFreshRegion() {
        // Inside a component body the Walker's ungated default recursion used to descend
        // THROUGH the IrConstructorCall and stage the content's component calls into the
        // ENCLOSING component's region (cross-frame aliasing, F10 class). The content
        // literal must instead become its own fresh region: invoking the stored lambda
        // twice forks per-invocation region frames with independent cells.
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
                    fun ComponentScope.Chip() {
                        val id = state { nextId++ }
                        text("chip:" + id.value)
                    }

                    class Holder(val content: @UiComponent ComponentScope.() -> Unit)

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel() {
                        val holder = Holder({ Chip() })
                        holder.content(this)
                        holder.content(this)
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Panel() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
                .toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
                .toDebugString()
            assertTrue(
                "chip:0" in first && "chip:1" in first,
                "the two invocations must not alias one region frame: $first",
            )
            assertEquals(first, second, "per-invocation forks must be stable across renders")
        }
    }

    @Test
    fun localComponentBodiesAreNotNumberedIntoTheEnclosingRegion() {
        // Defense-in-depth behind FIR's LOCAL_COMPONENT_FUNCTION (compiled with the
        // checkers off via the registrar's test-only property): the Walker leaves local
        // @UiComponent bodies unnumbered, so their slot calls fail fast at first render
        // instead of silently sharing one cell between the two call sites (the pre-gate
        // behavior: both calls rendered "inner:1" from one aliased state cell).
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
                    fun ComponentScope.Rows() {
                        @UiComponent
                        fun ComponentScope.Inner() {
                            var count by state { 0 }
                            count += 1
                            text("inner:" + count)
                        }
                        Inner()
                        Inner()
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Rows() }.tree
                """,
            ),
            checks = "error",
            disableFirCheckersForTesting = true,
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
                compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope)
            }
            assertTrue(
                failure.cause is MissingKineticaPluginException,
                "local component slots must fail fast, not alias: ${failure.cause}",
            )
        }
    }

    @Test
    fun suspendSubtreeExplicitKeyDeclineIsALocatedIrError() {
        // The FIR rules fire first in every checks mode, so the IR decline paths are
        // normally unreachable; compiled with the checkers off (test-only property) to
        // pin that a regression back to un-located LOGGING cannot go unnoticed — even
        // without FIR the compile still FAILS, via the located IR ERROR.
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.suspendSubtree
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Profile(id: String) {
                        suspendSubtree(key = id, fallback = { text("loading") }) {
                            text("profile " + id)
                        }
                    }
                """,
            ),
            disableFirCheckersForTesting = true,
        ).assertContainsLocatedError("suspendSubtree with an explicit key cannot be")
    }

    @Test
    fun persistentStateExplicitKeyDeclineIsALocatedIrError() {
        harness.compileExpectingErrors(
            mapOf(
                "legacy.kt" to KEY_ADDRESSED_STATE_OVERLOAD,
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Draft() {
                        val note = state(key = "draft", persistent = true) { "" }
                        text(note.value)
                    }
                """,
            ),
            disableFirCheckersForTesting = true,
        ).assertContainsLocatedError("persistent state with an explicit key cannot be")
    }

    @Test
    fun nonLiteralRegionContentDeclineIsALocatedIrError() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Rows(content: ComponentScope.() -> Unit) {
                        keyed("k", content)
                    }
                """,
            ),
            disableFirCheckersForTesting = true,
        ).assertContainsLocatedError("keyed content is not a lambda literal")
    }

    @Test
    fun unstagedComponentCallDeclineIsALocatedIrError() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Badge() {
                        text("badge")
                    }

                    @UiComponent
                    fun ComponentScope.Fan(panes: List<ComponentScope>) {
                        panes.first().Badge()
                    }
                """,
            ),
            disableFirCheckersForTesting = true,
        ).assertContainsLocatedError("left unstaged")
    }

    @Test
    fun nonScopeReceiverContentLambdaDeclineIsALocatedIrError() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.text

                    fun host(scope: ComponentScope, content: @UiComponent (ComponentScope) -> Unit) {
                        content(scope)
                    }

                    fun entry(scope: ComponentScope) {
                        host(scope) { s -> s.text("x") }
                    }
                """,
            ),
            disableFirCheckersForTesting = true,
        ).assertContainsLocatedError("content lambda has no ComponentScope receiver")
    }

    @Test
    fun nonLiteralPersistentStateArgumentWarnsAboutDroppedPersistence() {
        // state(persistent = <non-literal>) cannot be retargeted to a compiler SlotId;
        // the requested persistence is silently dropped, so the skip must at least warn.
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
                    fun ComponentScope.Draft(persist: Boolean) {
                        val note = state(persistent = persist) { "n" }
                        text(note.value)
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Draft(persist = true) }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertTransformFired("state(persistent = <non-literal>) cannot be retargeted")
        }
    }

    @Test
    fun contentLambdaInvokedTwiceForksRegionFramesPerInvocation() {
        // F3 probe: a helper that invokes its @UiComponent content twice gets ONE static
        // FrameTable for the lambda literal; the runtime must fork the region frame on
        // re-entry so the two invocations cannot alias state cells or event ordinals.
        // The cells live in a child component (slot calls directly inside the content
        // lambda are a rule A error in every checks mode since S1): each fork stages
        // child ordinal 0 in its own region frame, so without the fork the invocations
        // would resolve the same child frame and alias.
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
                    fun ComponentScope.Cell() {
                        val id = state { nextId++ }
                        val clicks = state { 0 }
                        val click = hostEvent(onEvent = { clicks.value = clicks.value + 1 })
                        host("button", props = mapOf("event:onClick" to click))
                        text("cell" + id.value + "=" + clicks.value)
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan() {
                        twice {
                            Cell()
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
        // The content lambda calls a child component rather than the slot DSL directly:
        // slot calls inside component-typed lambdas are a rule A error in every checks
        // mode since S1. The wrap is still what this pins — an unwrapped content lambda
        // would leave Inner() unstaged and crash instead of rendering "inner-state".
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
                    fun ComponentScope.Inner() {
                        val inner = state { "inner-state" }
                        text(inner.value)
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Outer() {
                        Shell {
                            Inner()
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
    fun rawComponentCallOutsideComponentsFailsCompileWhenChecksAreOff() {
        // Rule B is a soundness rule — an unstaged raw call crashes at first render —
        // so since S1 the shape fails the compile in every checks mode. The runtime
        // missing-plugin backstop still exists, but only bytecode compiled WITHOUT the
        // plugin can reach it now.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
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
            checks = "off",
        )

        messages.assertContainsError(
            "@UiComponent function 'Badge' can only be called from a @UiComponent function",
        )
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
    fun deferredHandlerSlotCallsFailCompileWhenChecksAreOff() {
        // F4 + S1: deferred handler lambdas are multi-run, and checks=off keeps rule F
        // active — the handler's slot call fails the compile instead of failing fast at
        // dispatch time.
        val messages = harness.compileExpectingErrors(
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
        )

        messages.assertContainsError(
            "Kinetica call 'state' cannot use a compiler-assigned ordinal inside the multi-run 'button' lambda",
        )
    }

    @Test
    fun eachKeySelectorSlotCallsFailCompileWhenChecksAreOff() {
        // F5 + S1: key selectors are multi-run, and checks=off keeps rule F active —
        // the selector's slot call fails the compile instead of collapsing every item
        // onto one key at runtime.
        val messages = harness.compileExpectingErrors(
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
        )

        messages.assertContainsError(
            "multi-run 'each' lambda",
        )
    }

    @Test
    fun valStoredLambdaSlotCallsFailCompileWhenChecksAreOff() {
        // F6 + S1: a val-stored lambda is an unknown-run host, and checks=off keeps
        // rule F active — the stored row lambda's slot call fails the compile instead
        // of failing fast at render.
        val messages = harness.compileExpectingErrors(
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
        )

        messages.assertContainsError(
            "multi-run 'row' lambda",
        )
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

    // F12 remainder: top-level io.heapy.kinetica helpers without a ComponentScope
    // receiver. At 657eef5 the IR pass classified lambdas by PACKAGE (inKinetica) while
    // FIR classified by RECEIVER, so IR descended into derive/invalidate/serverActionStub
    // lambdas and numbered slot calls FIR had just rejected — the review probe compiled
    // AND rendered at checks=off with unsound ordinals. Both phases now read
    // KineticaFramePolicy: these lambdas re-run reactively (recompute / per-key
    // invalidation / per-dispatch), so IR must never number inside them. The FIR halves
    // (checks=error rejections) live in KineticaFirCheckerTest.

    @Test
    fun deriveComputeSlotCallsFailCompileWhenChecksAreOff() {
        // F12 + S1: derive's compute lambda re-runs reactively, and checks=off keeps
        // rule F active — the review probe is a compile error in every mode.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.derive
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan() {
                        // The review's F12 probe: derive's compute lambda re-runs whenever a
                        // dependency changes, so the state call inside it must not receive a
                        // static ordinal from the enclosing component's counters.
                        val c = derive { state { 1 }.value }
                        text("d=" + c.value)
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fan() }.tree
                """,
            ),
            checks = "off",
        )

        messages.assertContainsError(
            "multi-run 'derive' lambda",
        )
    }

    @Test
    fun invalidatePredicateOrdinalConsumersFailCompileWhenChecksAreOff() {
        // F12 + S1: invalidate's predicate runs per cached key at invalidation time,
        // and checks=off keeps rule F active — its slot call fails the compile.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.invalidate
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan(doInvalidate: Boolean) {
                        val count = state { 0 }
                        if (doInvalidate) {
                            // Runs once per cached resource key at invalidation time, long
                            // after this render pass — never a numbering context. Guarded so
                            // the runtime never executes it against the global registry; the
                            // IR verdict below is static.
                            invalidate { state { 1 }.value > 0 }
                        }
                        text("count=" + count.value)
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fan(false) }.tree
                """,
            ),
            checks = "off",
        )

        messages.assertContainsError(
            "multi-run 'invalidate' lambda",
        )
    }

    @Test
    fun serverActionStubHandlerOrdinalConsumersFailCompileWhenChecksAreOff() {
        // F12 + S1: serverActionStub's handler runs per server-action dispatch, and
        // checks=off keeps rule F active — its slot call fails the compile.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.ServerActionRegistration
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.serverActionStub
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text
                    import kotlinx.serialization.builtins.serializer

                    @UiComponent(skippable = false)
                    fun ComponentScope.Fan() {
                        val count = state { 0 }
                        // The handler runs per server-action dispatch, never inline in the
                        // numbering render pass.
                        val stub = serverActionStub(
                            registration = ServerActionRegistration(
                                actionId = "bump",
                                functionFqName = "app.bump",
                            ),
                            inputSerializer = Int.serializer(),
                            outputSerializer = Int.serializer(),
                        ) { input -> state { input }.value }
                        text("count=" + count.value + " action=" + stub.registration.actionId)
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Fan() }.tree
                """,
            ),
            checks = "off",
        )

        messages.assertContainsError(
            "multi-run 'serverActionStub' lambda",
        )
    }

    @Test
    fun entryPointInPropertyGetterIsFrameWrapped() {
        // Review finding: collectSimpleFunctions never reached IrProperty accessors, so a
        // render entry point in a getter kept its content lambda unwrapped and threw
        // MissingKineticaPluginException at first render while compiling clean.
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
                    fun ComponentScope.Badge() {
                        val id = state { nextId++ }
                        text("id:" + id.value)
                    }

                    class Screen(private val runtime: KineticaRuntime, private val scope: ComponentScope) {
                        val tree: Node get() = runtime.render(scope) { Badge() }.tree
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        Screen(runtime, scope).tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("id:0" in first, "getter entry point must be framed: $first")
            assertEquals(first, second, "slots must be reused across renders")
        }
    }

    @Test
    fun entryPointInPropertyInitializerIsFrameWrapped() {
        // Same hole, backing-field initializer flavor: `object Holder { val tree = render {} }`.
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
                    fun ComponentScope.Badge() {
                        val id = state { nextId++ }
                        text("id:" + id.value)
                    }

                    object Holder {
                        val holderRuntime: KineticaRuntime = KineticaRuntime()
                        val tree: Node = holderRuntime.render { Badge() }.tree
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node = Holder.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val tree = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("id:0" in tree, "property-initializer entry point must be framed: $tree")
        }
    }

    @Test
    fun entryPointInAnonymousInitializerIsFrameWrapped() {
        // Same hole, `init { }` flavor: IrAnonymousInitializer was never collected.
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
                    fun ComponentScope.Badge() {
                        val id = state { nextId++ }
                        text("id:" + id.value)
                    }

                    class Screen(runtime: KineticaRuntime, scope: ComponentScope) {
                        var tree: Node? = null

                        init {
                            tree = runtime.render(scope) { Badge() }.tree
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        Screen(runtime, scope).tree!!
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val tree = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("id:0" in tree, "init-block entry point must be framed: $tree")
        }
    }

    @Test
    fun componentInObjectLiteralInsidePropertyInitializerIsFramed() {
        // Review finding: LOCAL_COMPONENT_FUNCTION never fires for an object literal in a
        // property initializer (no function on the FIR containment path) while IR never
        // collected the anonymous class — compile-clean crash at first render.
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

                    abstract class Panel {
                        // Annotated on the declaration the CALL resolves to as well:
                        // Kotlin does not inherit annotations onto overrides, and the
                        // staging site reads the callee it resolved.
                        @UiComponent(skippable = false)
                        abstract fun ComponentScope.Render()
                    }

                    class Screen {
                        val panel: Panel = object : Panel() {
                            @UiComponent(skippable = false)
                            override fun ComponentScope.Render() {
                                val id = state { nextId++ }
                                text("panel:" + id.value)
                            }
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node {
                        val screen = Screen()
                        return runtime.render(scope) {
                            with(screen.panel) { Render() }
                        }.tree
                    }
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("panel:0" in first, "object-literal component must be framed: $first")
        }
    }

    @Test
    fun componentInEnumEntryBodyIsFramed() {
        // Enum-entry bodies are IrEnumEntry, not IrClass, so collection skipped them too.
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

                    enum class Tab {
                        HOME {
                            @UiComponent(skippable = false)
                            override fun ComponentScope.Render() {
                                val id = state { nextId++ }
                                text("home:" + id.value)
                            }
                        };

                        @UiComponent(skippable = false)
                        abstract fun ComponentScope.Render()
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) {
                            with(Tab.HOME) { Render() }
                        }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("home:0" in first, "enum-entry component must be framed: $first")
        }
    }

    @Test
    fun fileLevelContentPropertyIsFrameWrappedAtItsLiteralSite() {
        // Rule C exempts reads of non-local properties on the premise that the content was
        // wrapped at its own literal site. Before the collection fix that premise was
        // false for a property INITIALIZER: field initializers were never visited by the
        // entry-point pass, so the literal handed to `wrapContent` stayed unwrapped and
        // `render(scope, hoisted)` threw at first render while compiling clean.
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
                    fun ComponentScope.Badge() {
                        val id = state { nextId++ }
                        text("id:" + id.value)
                    }

                    fun wrapContent(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ): @UiComponent ComponentScope.() -> Unit = content

                    val hoisted: @UiComponent ComponentScope.() -> Unit = wrapContent { Badge() }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope, hoisted).tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("id:0" in first, "hoisted content must be framed at its literal site: $first")
            assertEquals(first, second, "slots must be reused across renders")
        }
    }

    @Test
    fun contentLambdaOfSlotDslCallIsFrameWrapped() {
        // Review finding: the slot-DSL branch of visitCall returns before
        // wrapAnnotatedContentArguments, so a @UiComponent content literal on a slot-DSL
        // callee was neither descended nor wrapped — compile-clean crash at first render.
        harness.compile(
            mapOf(
                "kinetica/SlotExt.kt" to """
                    package io.heapy.kinetica

                    public fun ComponentScope.frameValue(
                        label: String,
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        text(label)
                        content()
                    }
                """,
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.frameValue
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Badge() {
                        val id = state { nextId++ }
                        text("id:" + id.value)
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel() {
                        frameValue(label = "slot") {
                            Badge()
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Panel() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("id:0" in first, "slot-DSL content must be framed: $first")
            assertEquals(first, second, "slots must be reused across renders")
        }
    }

    @Test
    fun contentLambdaOfEventDslCallIsFrameWrapped() {
        // Same asymmetry on the event-DSL branch.
        harness.compile(
            mapOf(
                "kinetica/EventExt.kt" to """
                    package io.heapy.kinetica

                    public fun ComponentScope.button(
                        label: String,
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        text(label)
                        content()
                    }
                """,
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Badge() {
                        val id = state { nextId++ }
                        text("id:" + id.value)
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel() {
                        button(label = "event") {
                            Badge()
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Panel() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("id:0" in first, "event-DSL content must be framed: $first")
            assertEquals(first, second, "slots must be reused across renders")
        }
    }

    @Test
    fun hostEventFusionKeepsArgumentsItCannotCarry() {
        // The hostEvent -> hostEventBlock fusion retargets to a callee with only
        // (ordinal, block): any other argument of the source call is silently dropped, and
        // a @UiComponent content literal there is neither wrapped nor executed. The fusion
        // must decline for callees it cannot faithfully retarget.
        harness.compile(
            mapOf(
                "kinetica/FusionExt.kt" to """
                    package io.heapy.kinetica

                    public fun ComponentScope.hostEvent(
                        label: String,
                        onEvent: () -> Unit,
                        content: @UiComponent ComponentScope.() -> Unit,
                    ): String {
                        text(label)
                        content()
                        onEvent()
                        return label
                    }
                """,
                "app/Main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.event
                    import io.heapy.kinetica.hostEvent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Badge() {
                        val id = state { nextId++ }
                        text("id:" + id.value)
                    }

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel() {
                        hostEvent(label = "fused", onEvent = event { }) {
                            Badge()
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Panel() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val first = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            val second = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("id:0" in first, "fusion must not drop the content argument: $first")
            assertEquals(first, second, "slots must be reused across renders")
        }
    }

    @Test
    fun componentsInInitBlockAndDelegateInitializerAreFramed() {
        // The two remaining initializer positions the widened collection must reach, and
        // FIR must therefore NOT reject: an `init { }` block and the backing field of a
        // delegated property. Neither puts a function on the FIR containment path.
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
                    import kotlin.reflect.KProperty

                    var nextId: Int = 0

                    abstract class Panel {
                        @UiComponent(skippable = false)
                        abstract fun ComponentScope.Render()
                    }

                    class PanelDelegate(private val panel: Panel) {
                        operator fun getValue(thisRef: Any?, property: KProperty<*>): Panel = panel
                    }

                    val delegated: Panel by PanelDelegate(
                        object : Panel() {
                            @UiComponent(skippable = false)
                            override fun ComponentScope.Render() {
                                val id = state { nextId++ }
                                text("delegated:" + id.value)
                            }
                        },
                    )

                    class Screen(runtime: KineticaRuntime, scope: ComponentScope) {
                        var tree: Node? = null

                        init {
                            val localPanel: Panel = object : Panel() {
                                @UiComponent(skippable = false)
                                override fun ComponentScope.Render() {
                                    val id = state { nextId++ }
                                    text("init:" + id.value)
                                }
                            }
                            tree = runtime.render(scope) {
                                with(localPanel) { Render() }
                                with(delegated) { Render() }
                            }.tree
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        Screen(runtime, scope).tree!!
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val runtime = KineticaRuntime()
            val scope = ComponentScope(runtime)
            val tree = compiled.invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toDebugString()
            assertTrue("init:0" in tree, "init-block object literal must be framed: $tree")
            assertTrue("delegated:1" in tree, "delegate-initializer object literal must be framed: $tree")
        }
    }

    @Test
    fun firCheckerSeamIgnoresAnyValueButItsToken() {
        // S1: the seam must not be trippable by a build that merely sets the property
        // name (e.g. through kotlin.daemon.jvmargs). Only the opaque token removes the
        // rules; "true" — the previous gate value — keeps every checker registered.
        val previous = System.getProperty(KINETICA_DISABLE_FIR_CHECKERS_PROPERTY)
        System.setProperty(KINETICA_DISABLE_FIR_CHECKERS_PROPERTY, "true")
        try {
            harness.compileExpectingErrors(
                mapOf(
                    "main.kt" to """
                        package app

                        import io.heapy.kinetica.ComponentScope
                        import io.heapy.kinetica.UiComponent
                        import io.heapy.kinetica.state
                        import io.heapy.kinetica.text

                        @UiComponent
                        fun ComponentScope.Rows(items: List<String>) {
                            items.forEach { item ->
                                val hits = state { 0 }
                                text(item + hits.value)
                            }
                        }
                    """,
                ),
            ).assertContainsError("multi-run 'forEach' lambda")
        } finally {
            restoreSystemProperty(KINETICA_DISABLE_FIR_CHECKERS_PROPERTY, previous)
        }
    }

    @Test
    fun firCheckerSeamAnnouncesItselfWhenActive() {
        // The seam removes rules that have no IR counterpart, so a compilation running
        // with it active must say so out loud rather than look like a normal build.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.suspendSubtree
                    import io.heapy.kinetica.text

                    @UiComponent
                    fun ComponentScope.Profile(id: String) {
                        suspendSubtree(key = id, fallback = { text("loading") }) {
                            text("profile " + id)
                        }
                    }
                """,
            ),
            disableFirCheckersForTesting = true,
        )
        assertTrue(
            messages.any {
                it.severity == CompilerMessageSeverity.STRONG_WARNING &&
                    "FIR soundness checkers are DISABLED" in it.message
            },
            "Expected a STRONG_WARNING announcing the disabled checkers. Messages:\n" +
                messages.joinToString("\n") { "${it.severity}: ${it.message}" },
        )
    }

    private fun Node.toDebugString(): String = toString()

    private fun Node.collectHostEventIds(): List<String> = when (this) {
        is FragmentNode -> children.flatMap { it.collectHostEventIds() }
        is HostNode -> listOfNotNull(props["event:onClick"]) + children.flatMap { it.collectHostEventIds() }
        else -> emptyList()
    }

    private fun List<RecordedCompilerMessage>.assertContainsError(needle: String) {
        assertTrue(
            any { it.severity.isError && needle in it.message },
            "Expected an error containing '$needle'. Messages:\n" +
                joinToString("\n") { "${it.severity}: ${it.message}" },
        )
    }

    /** A decline-to-transform IR ERROR must carry a source location to be actionable. */
    private fun List<RecordedCompilerMessage>.assertContainsLocatedError(needle: String) {
        val match = firstOrNull { it.severity.isError && needle in it.message }
        assertTrue(
            match != null,
            "Expected an error containing '$needle'. Messages:\n" +
                joinToString("\n") { "${it.severity}: ${it.message}" },
        )
        assertTrue(match.location != null, "Expected a source location on: ${match.message}")
    }

    private companion object {
        /**
         * Simulates a runtime revision whose persistent state is addressed by string
         * key — the pinned runtime has no such overload, so this is the only way to
         * reach the IR decline that defends against exactly this skew (mirrors the
         * FirCheckerTest fixture of the same name).
         */
        private val KEY_ADDRESSED_STATE_OVERLOAD = """
            package io.heapy.kinetica

            public fun <T> ComponentScope.state(
                key: String?,
                persistent: Boolean = false,
                ordinal: Int = -1,
                initial: () -> T,
            ): MutableCell<T> = throw UnsupportedOperationException("simulated legacy overload")
        """
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
