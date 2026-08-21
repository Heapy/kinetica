package io.heapy.kinetica.compiler

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins S5: every harness entry point deletes the `kinetica-compile-*` temp tree it
 * created. Asserted as "no NEW entries" against a pre-run snapshot — the shared temp
 * root may hold trees leaked by pre-fix runs or owned by concurrent builds, which this
 * suite must neither sweep nor be failed by.
 */
class KineticaCompilationHarnessTest {
    private val harness = KineticaCompilationHarness()

    @Test
    fun compileExpectingErrorsLeavesNoTempTreeBehind() {
        val before = kineticaCompileTempEntries()
        harness.compileExpectingErrors(
            mapOf("main.kt" to RULE_A_VIOLATION),
        )
        assertNoNewTempEntries(before)
    }

    @Test
    fun successfulCompileDeletesItsTempTreeOnClose() {
        val before = kineticaCompileTempEntries()
        harness.compile(
            mapOf("main.kt" to CLEAN_COMPONENT),
            checks = "error",
        ).use { compiled ->
            compiled.loadClass("app.MainKt")
            assertEquals(
                1,
                (kineticaCompileTempEntries() - before).size,
                "Expected the temp tree to survive while classes still load lazily",
            )
        }
        assertNoNewTempEntries(before)
    }

    @Test
    fun failedCompileDeletesItsTempTreeBeforeReportingFailure() {
        val before = kineticaCompileTempEntries()
        assertFailsWith<AssertionError> {
            harness.compile(
                mapOf("main.kt" to RULE_A_VIOLATION),
                checks = "error",
            )
        }
        assertNoNewTempEntries(before)
    }

    private fun assertNoNewTempEntries(before: Set<String>) {
        assertEquals(
            emptySet(),
            kineticaCompileTempEntries() - before,
            "Expected the harness to delete every kinetica-compile-* temp tree it created",
        )
    }

    /**
     * Entry names only. The trailing hyphen keeps the JVM-lifetime
     * `kinetica-compiler-plugin*.jar` (built lazily by the first compile) out of the diff.
     */
    private fun kineticaCompileTempEntries(): Set<String> =
        File(System.getProperty("java.io.tmpdir"))
            .list { _, name -> name.startsWith("kinetica-compile-") }
            ?.toSet()
            .orEmpty()

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
