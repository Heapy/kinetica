package io.heapy.kinetica.compiler

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.KineticaRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KineticaFirCheckerTest {
    private val harness = KineticaCompilationHarness()

    @Test
    fun ruleA_slotCallOutsideComponentIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.state

                    fun ComponentScope.helper() {
                        val count = state { 0 }
                        count.value
                    }
                """,
            ),
        ).assertContainsError("'state' can only be called inside a @UiComponent function")
    }

    @Test
    fun ruleA_slotCallInsideComponentCompiles() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Counter() {
                        val count = state { 0 }
                        emit(TextNode(value = "count: " + count.value))
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleA_slotCallInRenderContentLambdaIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    fun runContent(scope: ComponentScope, content: @UiComponent ComponentScope.() -> Unit) {
                        scope.content()
                    }

                    fun main(scope: ComponentScope) {
                        runContent(scope) {
                            val count = state { 0 }
                            count.value
                        }
                    }
                """,
            ),
        ).assertContainsError("'state' can only be called inside a @UiComponent function")
    }

    @Test
    fun ruleB_componentCallFromPlainFunctionIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    fun ComponentScope.plainHelper() {
                        Badge()
                    }
                """,
            ),
        ).assertContainsError("can only be called from a @UiComponent function")
    }

    @Test
    fun ruleB_componentCallFromComponentAndFromAnnotatedLambdaCompiles() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Panel() {
                        Badge()
                    }

                    fun runContent(scope: ComponentScope, content: @UiComponent ComponentScope.() -> Unit) {
                        scope.content()
                    }

                    fun entry(scope: ComponentScope) {
                        runContent(scope) {
                            Badge()
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleC_regionContentReferenceIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Panel() {
                        val body: ComponentScope.() -> Unit = {}
                        keyed("tab", content = body)
                    }
                """,
            ),
        ).assertContainsError("must be a lambda literal")
    }

    @Test
    fun ruleD_slotCallInLoopIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Rows() {
                        for (index in 0..2) {
                            val row = state { index }
                            row.value
                        }
                    }
                """,
            ),
        ).assertContainsError("must not be called directly inside a loop")
    }

    @Test
    fun ruleD_keyedWrappedLoopBodyCompiles() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Rows() {
                        for (index in 0..2) {
                            keyed(index) {
                                val row = state { index }
                                emit(TextNode(value = "row " + row.value))
                            }
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun ruleD_componentCallInLoopIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Rows() {
                        for (index in 0..1) {
                            Badge()
                        }
                    }
                """,
            ),
        ).assertContainsError("'Badge' must not be called directly inside a loop")
    }

    @Test
    fun ruleE_componentWithoutScopeReceiverIsReported() {
        harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun Standalone() {
                    }
                """,
            ),
        ).assertContainsError("must be an extension of io.heapy.kinetica.ComponentScope")
    }

    @Test
    fun checksOffLeavesViolationsUnreported() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.state

                    fun ComponentScope.helper() {
                        val count = state { 0 }
                        count.value
                    }
                """,
            ),
            checks = "off",
        ).close()
    }

    @Test
    fun multiRunSlotCallNamesTheActualUnsafeHost() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Fan() {
                        repeat(2) { outer ->
                            listOf(outer).map { index ->
                                val value = state { index }
                                value.value
                            }
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "repeat"))
    }

    @Test
    fun multiRunNestedOrdinalCallsReportOnlyTheOutermostConsumer() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button
                    import io.heapy.kinetica.event
                    import io.heapy.kinetica.row

                    @UiComponent
                    fun ComponentScope.Fan() {
                        listOf(1, 2).forEach { item ->
                            row { button(onClick = event { println(item) }) {} }
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("button", "forEach"))
    }

    @Test
    fun multiRunRegionAndComponentCallsAreReported() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.each
                    import io.heapy.kinetica.errorBoundary

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        listOf(1).forEach {
                            errorBoundary(fallback = { _, _, _ -> }) {}
                        }
                        listOf(1).map {
                            Badge()
                        }
                        listOf(listOf(1)).forEach { items ->
                            each(items, key = { it }) {}
                        }
                    }
                """,
            ),
        )

        messages.assertErrorMessages(
            multiRunMessage("errorBoundary", "forEach"),
            multiRunMessage("Badge", "map"),
            multiRunMessage("each", "forEach"),
        )
    }

    @Test
    fun multiRunComponentCallInsideRuntimeRenderIsReported() {
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    fun render(runtime: KineticaRuntime) {
                        runtime.render {
                            listOf(1).forEach { Badge() }
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("Badge", "forEach"))
    }

    @Test
    fun multiRunCallWithComponentTypedLambdaArgumentCompiles() {
        // F1: a content-wrapper helper is not an ordinal consumer. IR wraps its
        // @UiComponent-typed lambda literal into one static FrameTable and numbers
        // nothing on the call itself; region re-entry forking keeps repeated
        // invocations independent at runtime.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.TextNode
                    import io.heapy.kinetica.UiComponent

                    fun ComponentScope.helper(
                        content: @UiComponent ComponentScope.() -> Unit,
                    ) {
                        content()
                    }

                    @UiComponent
                    fun ComponentScope.Badge() {
                        emit(TextNode(value = "badge"))
                    }

                    @UiComponent
                    fun ComponentScope.Fan() {
                        helper { Badge() }
                        listOf(1).forEach { helper { Badge() } }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun renderEntryPointInsideRepeatCompilesAndRuns() {
        // F1 probe (review: RuntimeSmokeSlotsTest repeat host): an entry point such as
        // KineticaRuntime.render merely receives @UiComponent content — IR numbers zero
        // ordinals for the call, so a multi-run host around it is sound.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.text

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node {
                        var tree: Node? = null
                        repeat(2) {
                            tree = runtime.render(scope) { text("x") }.tree
                        }
                        return tree!!
                    }
                """,
            ),
            checks = "error",
        ).use { compiled ->
            val tree = compiled.invokeRender("app.MainKt", "render").toString()
            assertTrue("x" in tree, "Expected rendered text in: $tree")
        }
    }

    @Test
    fun renderEntryPointInsideAssertFailsWithCompiles() {
        // F1 probe. Emulates the downstream assertFailsWith { KineticaTest.render { } }
        // idiom with KineticaRuntime.render (kinetica-test is not on this harness
        // classpath); the F1 shape is identical: an entry point receiving @UiComponent
        // content inside a lambda with no single-run verdict.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.text
                    import kotlin.test.assertFailsWith

                    fun probe(runtime: KineticaRuntime, scope: ComponentScope) {
                        assertFailsWith<IllegalStateException> {
                            runtime.render(scope) {
                                text("boom")
                                error("boom")
                            }
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun knownSingleRunAndKineticaDslLambdasRemainAllowed() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.peek
                    import io.heapy.kinetica.row
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Safe() {
                        listOf(1).let { values ->
                            val count = state { values.size }
                            count.value
                        }
                        run {
                            row {
                                val nested = state { 1 }
                                nested.value
                            }
                        }
                        peek {
                            val untracked = state { 2 }
                            untracked.value
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun keyedAndEachRemainAllowedDirectlyInsideLoops() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.each
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Rows() {
                        for (batch in listOf(listOf(1, 2))) {
                            keyed(batch.size) {
                                val group = state { batch.size }
                                group.value
                            }
                            each(batch, key = { it }) { item ->
                                val row = state { item }
                                row.value
                            }
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    @Test
    fun checksOffLeavesMultiRunViolationsUnreported() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Fan() {
                        listOf(1, 2).forEach { item ->
                            val value = state { item }
                            value.value
                        }
                    }
                """,
            ),
            checks = "off",
        ).close()
    }

    @Test
    fun nullableOptionalHandlersRemainAllowedInRepeatedContexts() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.button
                    import io.heapy.kinetica.checkbox
                    import io.heapy.kinetica.textInput

                    @UiComponent
                    fun ComponentScope.Form(
                        click: (() -> Unit)?,
                        input: ((String) -> Unit)?,
                        submit: (() -> Unit)?,
                        toggle: (() -> Unit)?,
                    ) {
                        listOf("one").forEach { value ->
                            button(onClick = click) {}
                            textInput(value = value, onInput = input, onSubmit = submit)
                            checkbox(checked = false, onToggle = toggle)
                        }
                        for (value in listOf("two")) {
                            button(onClick = click) {}
                            textInput(value = value, onInput = input, onSubmit = submit)
                            checkbox(checked = false, onToggle = toggle)
                        }
                    }
                """,
            ),
            checks = "error",
        ).close()
    }

    // Drift tests: the FIR checker exists to predict what the IR frame pass numbers.
    // Each probe compiles with checks=error (FIR verdict: allowed), then asserts the IR
    // pass actually numbered the same construct (no bail-out, renders with stable slots).
    // Both phases must consult KineticaFramePolicy; these fail if either side grows a
    // private copy of the region-content or single-run tables and drifts.

    @Test
    fun firAndIrAgreeOnKeyedRegionContent() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Panel() {
                        keyed("tab") {
                            val id = state { nextId++ }
                            text("k=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Panel() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("k=0")
        }
    }

    @Test
    fun firAndIrAgreeOnEachRegionContent() {
        harness.compile(
            mapOf(
                "main.kt" to """
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
                    fun ComponentScope.Rows() {
                        each(listOf("a", "b"), key = { it }) { item ->
                            val id = state { nextId++ }
                            text(item + "=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Rows() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("a=0", "b=1")
        }
    }

    @Test
    fun firAndIrAgreeOnLazyEachContentAndPlaceholder() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.lazyEach
                    import io.heapy.kinetica.lazyItems
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Rows() {
                        lazyEach(
                            lazyItems(listOf("a", "b")),
                            key = { it },
                            placeholder = {
                                val pending = state { -1 }
                                text("pending=" + pending.value)
                            },
                        ) { item ->
                            val id = state { nextId++ }
                            text(item + "=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Rows() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            // The placeholder lambda only executes for pending resources; its IR verdict
            // is the absence of a bail-out message, asserted for the whole probe below.
            compiled.assertIrNumberedAndRendersStably("a=0", "b=1")
        }
    }

    @Test
    fun firAndIrAgreeOnSingleRunLambdaHosts() {
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.peek
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Hosts() {
                        listOf(1).let {
                            val a = state { nextId++ }
                            text("let=" + a.value)
                        }
                        run {
                            val b = state { nextId++ }
                            text("run=" + b.value)
                        }
                        with(listOf(1)) {
                            val c = state { nextId++ }
                            text("with=" + c.value)
                        }
                        listOf(1).apply {
                            val d = state { nextId++ }
                            text("apply=" + d.value)
                        }
                        listOf(1).also {
                            val e = state { nextId++ }
                            text("also=" + e.value)
                        }
                        peek {
                            val f = state { nextId++ }
                            text("peek=" + f.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Hosts() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably(
                "let=0", "run=1", "with=2", "apply=3", "also=4", "peek=5",
            )
        }
    }

    // Contract-based single-run hosts (F13): single-run detection derives from Kotlin
    // contracts (callsInPlace EXACTLY_ONCE / AT_MOST_ONCE) resolved in FIR and carried
    // to the IR pass through the per-compilation SingleRunOracle. Callees without a
    // usable contract fall through to the shared KineticaFramePolicy name lists.

    @Test
    fun runCatchingLambdaHostCompilesAndNumbersOrdinals() {
        // stdlib 2.4.10 declares NO contract on runCatching; it is single-run through the
        // shared name-list fallback (semantically a try block) — on BOTH compiler phases.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Catcher() {
                        runCatching {
                            val id = state { nextId++ }
                            text("caught=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Catcher() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("caught=0")
        }
    }

    @Test
    fun takeIfContractLambdaHostCompilesAndNumbersOrdinals() {
        // takeIf declares callsInPlace(predicate, EXACTLY_ONCE): FIR resolves the contract
        // and the oracle makes IR descend into the predicate lambda.
        harness.compile(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.KineticaRuntime
                    import io.heapy.kinetica.Node
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state
                    import io.heapy.kinetica.text

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Gate(flag: Boolean) {
                        flag.takeIf {
                            val id = state { nextId++ }
                            text("gate=" + id.value)
                            it
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Gate(true) }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("gate=0")
        }
    }

    @Test
    fun userExactlyOnceContractHostCompilesAndNumbersOrdinals() {
        harness.compile(
            mapOf(
                "main.kt" to """
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
                    inline fun <R> mySection(block: () -> R): R {
                        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
                        return block()
                    }

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Sectioned() {
                        mySection {
                            val id = state { nextId++ }
                            text("section=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Sectioned() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("section=0")
        }
    }

    @Test
    fun userAtMostOnceContractHostCompilesAndNumbersOrdinals() {
        harness.compile(
            mapOf(
                "main.kt" to """
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
                    inline fun <R> guarded(enabled: Boolean, block: () -> R): R? {
                        contract { callsInPlace(block, InvocationKind.AT_MOST_ONCE) }
                        return if (enabled) block() else null
                    }

                    var nextId: Int = 0

                    @UiComponent(skippable = false)
                    fun ComponentScope.Guarded() {
                        guarded(true) {
                            val id = state { nextId++ }
                            text("guarded=" + id.value)
                        }
                    }

                    fun render(runtime: KineticaRuntime, scope: ComponentScope): Node =
                        runtime.render(scope) { Guarded() }.tree
                """,
            ),
            checks = "error",
        ).use { compiled ->
            compiled.assertIrNumberedAndRendersStably("guarded=0")
        }
    }

    @Test
    fun noContractHostsRemainMultiRun() {
        // forEach and map declare nothing; repeat declares callsInPlace(action) with an
        // UNKNOWN occurrence range. None yields an oracle entry, so all three fall through
        // to the name lists and stay multi-run errors.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Fan() {
                        listOf(1).forEach { item ->
                            val a = state { item }
                            a.value
                        }
                        listOf(1).map { item ->
                            val b = state { item }
                            b.value
                        }
                        repeat(1) { index ->
                            val c = state { index }
                            c.value
                        }
                    }
                """,
            ),
        )

        messages.assertErrorMessages(
            multiRunMessage("state", "forEach"),
            multiRunMessage("state", "map"),
            multiRunMessage("state", "repeat"),
        )
    }

    @Test
    fun localFunctionHostStaysMultiRunViaNameListFallback() {
        // A local function cannot get a SingleRunOracle entry (no stable CallableId on the
        // IR side), so both phases must agree via the name-list fallback: FIR rejects the
        // host, and IR never numbers inside it. The compile error is that agreement.
        val messages = harness.compileExpectingErrors(
            mapOf(
                "main.kt" to """
                    package app

                    import io.heapy.kinetica.ComponentScope
                    import io.heapy.kinetica.UiComponent
                    import io.heapy.kinetica.state

                    @UiComponent
                    fun ComponentScope.Fan() {
                        fun localSection(block: () -> Unit) = block()
                        localSection {
                            val id = state { 0 }
                            id.value
                        }
                    }
                """,
            ),
        )

        messages.assertSingleErrorEquals(multiRunMessage("state", "localSection"))
    }

    /**
     * IR verdict of a drift probe: the frame pass numbered the construct (framed message,
     * no decline-to-transform bail-out) and the slots are real (rendering does not throw
     * MissingKineticaPluginException, re-rendering reuses the same cells).
     */
    private fun CompiledKineticaModule.assertIrNumberedAndRendersStably(vararg markers: String) {
        assertTransformFired("framed (slots=")
        assertTransformDidNotFire("left on the legacy path")
        assertTransformDidNotFire("left unwrapped")
        assertTransformDidNotFire("no ordinal parameter")
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        val first = invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toString()
        val second = invokeRender("app.MainKt", "render", runtime = runtime, scope = scope).toString()
        markers.forEach { marker ->
            assertTrue(marker in first, "Expected '$marker' in rendered output: $first")
        }
        assertEquals(first, second, "slots must be reused, not re-initialized, across renders")
    }

    private fun multiRunMessage(call: String, host: String): String =
        "Kinetica call '$call' cannot use a compiler-assigned ordinal inside the multi-run '$host' lambda. " +
            "Use each(items, key = ...) or keyed(...) for repeated rendering."

    private fun List<RecordedCompilerMessage>.assertSingleErrorEquals(expected: String) {
        assertEquals(listOf(expected), filter { it.severity.isError }.map { it.message })
    }

    private fun List<RecordedCompilerMessage>.assertErrorMessages(vararg expected: String) {
        val errors = filter { it.severity.isError }.map { it.message }
        assertEquals(expected.size, errors.size, "Unexpected errors:\n${errors.joinToString("\n")}")
        assertEquals(expected.toSet(), errors.toSet())
    }

    private fun List<RecordedCompilerMessage>.assertContainsError(needle: String) {
        assertTrue(
            any { it.severity.isError && needle in it.message },
            "Expected an error containing '$needle'. Messages:\n" +
                joinToString("\n") { "${it.severity}: ${it.message}" },
        )
    }
}
