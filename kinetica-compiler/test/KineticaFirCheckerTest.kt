package io.heapy.kinetica.compiler

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
    fun multiRunCallWithComponentTypedLambdaArgumentIsReported() {
        val messages = harness.compileExpectingErrors(
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
        )

        messages.assertSingleErrorEquals(multiRunMessage("helper", "forEach"))
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
