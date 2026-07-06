package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The slot store is one map with an unchecked cast at the accessor; `checkedSlot` guards it.
 * A class mismatch (two render paths landing different constructs on one key) must fail loudly
 * in debug builds and self-heal with a `slot-class-mismatch` warning in production — never the
 * silent corruption Kotlin/JS produced before (mangled property reads of a wrong-class holder).
 */
class SlotClassMismatchTest {
    private class FirstHolder : Disposable {
        var disposed = false
        override fun dispose() {
            disposed = true
        }
    }

    private class SecondHolder

    @Test
    fun debugRenderFailsLoudlyOnMismatch() {
        val runtime = KineticaRuntime(debug = true)
        val scope = ComponentScope(runtime)

        runtime.render(scope) {
            checkedSlot("clash", FirstHolder::class) { FirstHolder() }
            text("first")
        }

        val error = assertFailsWith<IllegalStateException> {
            runtime.render(scope) {
                checkedSlot("clash", SecondHolder::class) { SecondHolder() }
                text("second")
            }
        }
        assertTrue("Slot class mismatch at 'clash'" in (error.message ?: ""), error.message)
        assertTrue("FirstHolder" in (error.message ?: ""), error.message)
        assertTrue("SecondHolder" in (error.message ?: ""), error.message)
    }

    @Test
    fun productionRenderWarnsDisposesStaleHolderAndRecreates() {
        val runtime = KineticaRuntime(debug = false)
        val scope = ComponentScope(runtime)

        lateinit var first: FirstHolder
        runtime.render(scope) {
            first = checkedSlot("clash", FirstHolder::class) { FirstHolder() }
            text("first")
        }

        lateinit var second: Any
        val result = runtime.render(scope) {
            second = checkedSlot("clash", SecondHolder::class) { SecondHolder() }
            text("second")
        }

        assertTrue(second is SecondHolder, "expected the slot to be recreated with the right class")
        assertTrue(first.disposed, "expected the stale holder to be disposed")

        val warning = result.warnings.single { it.code == "slot-class-mismatch" }
        assertEquals("clash", warning.attributes["slot"])
        assertEquals("SecondHolder", warning.attributes["expected"])
        assertEquals("FirstHolder", warning.attributes["actual"])
    }

    @Test
    fun matchingSlotIsReusedWithoutWarnings() {
        val runtime = KineticaRuntime(debug = true)
        val scope = ComponentScope(runtime)

        lateinit var first: FirstHolder
        runtime.render(scope) {
            first = checkedSlot("stable", FirstHolder::class) { FirstHolder() }
            text("first")
        }

        lateinit var second: FirstHolder
        val result = runtime.render(scope) {
            second = checkedSlot("stable", FirstHolder::class) { FirstHolder() }
            text("second")
        }

        assertTrue(first === second, "expected the same holder instance across renders")
        assertTrue(result.warnings.none { it.code == "slot-class-mismatch" })
    }
}
