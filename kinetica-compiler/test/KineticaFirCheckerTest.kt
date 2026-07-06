package io.heapy.kinetica.compiler

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Golden coverage for the FIR authoring rules (checks=error): one violating and one
 * conforming case per rule. Conforming cases must compile with the checker active.
 */
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
                    import io.heapy.kinetica.keyed

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
                    import io.heapy.kinetica.keyed
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

    private fun List<RecordedCompilerMessage>.assertContainsError(needle: String) {
        assertTrue(
            any { it.severity.isError && needle in it.message },
            "Expected an error containing '$needle'. Messages:\n" +
                joinToString("\n") { "${it.severity}: ${it.message}" },
        )
    }
}
