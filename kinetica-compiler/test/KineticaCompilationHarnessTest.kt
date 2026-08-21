package io.heapy.kinetica.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins S5: every harness entry point deletes the `kinetica-compile-*` temp tree it
 * created. Asserted against the exact roots the harness records in
 * [KineticaCompilationHarness.createdTempRoots] — never by diffing the SHARED
 * java.io.tmpdir, which concurrent processes can mutate at any time.
 */
class KineticaCompilationHarnessTest {
    private val harness = KineticaCompilationHarness()

    @Test
    fun compileExpectingErrorsLeavesNoTempTreeBehind() {
        harness.compileExpectingErrors(
            mapOf("main.kt" to RULE_A_VIOLATION),
        )
        val root = harness.createdTempRoots.single()
        assertFalse(root.exists(), "expected the harness to delete $root")
    }

    @Test
    fun successfulCompileDeletesItsTempTreeOnClose() {
        harness.compile(
            mapOf("main.kt" to CLEAN_COMPONENT),
            checks = "error",
        ).use { compiled ->
            compiled.loadClass("app.MainKt")
            val root = harness.createdTempRoots.single()
            assertTrue(
                root.exists(),
                "expected the temp tree to survive while classes still load lazily: $root",
            )
        }
        val root = harness.createdTempRoots.single()
        assertFalse(root.exists(), "expected close() to delete $root")
    }

    @Test
    fun failedCompileDeletesItsTempTreeBeforeReportingFailure() {
        assertFailsWith<AssertionError> {
            harness.compile(
                mapOf("main.kt" to RULE_A_VIOLATION),
                checks = "error",
            )
        }
        assertEquals(1, harness.createdTempRoots.size)
        val root = harness.createdTempRoots.single()
        assertFalse(root.exists(), "expected the failure path to delete $root before fail()")
    }

    private companion object {
        private val CLEAN_COMPONENT = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.UiComponent
            import io.heapy.kinetica.text

            @UiComponent
            fun ComponentScope.Hello() {
                text("hello")
            }
        """

        private val RULE_A_VIOLATION = """
            package app

            import io.heapy.kinetica.ComponentScope
            import io.heapy.kinetica.state

            fun ComponentScope.helper() {
                val count = state { 0 }
                count.value
            }
        """
    }
}
