package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Every slot-consuming construct is kind-discriminated in its key, so a branch that swaps one
 * construct for another at the same cursor position must never reuse the other's slot holder
 * (pre-fix: ClassCastException on JVM, silent mangled-property corruption on JS). Runs with
 * debug = true, where any residual mismatch throws via the checkedSlot detector.
 *
 * Arms consume exactly one cursor of the same space, so the keyless sibling state AFTER the
 * branch keeps its position — and therefore its value — across toggles.
 */
class SlotCollisionMatrixTest {
    private fun assertToggleSafe(
        firstArm: ComponentScope.() -> Unit,
        secondArm: ComponentScope.() -> Unit,
    ) {
        val runtime = KineticaRuntime(debug = true)
        val scope = ComponentScope(runtime)
        var useSecond = false
        lateinit var sibling: MutableCell<Int>

        fun render() = runtime.render(scope) {
            host("div") {
                if (useSecond) secondArm() else firstArm()
            }
            sibling = state { 0 }
            text("sibling:${sibling.value}")
        }

        render()
        val original = sibling
        sibling.value = 42
        render()
        assertSame(original, sibling)
        assertEquals(42, sibling.value)

        useSecond = true
        render()
        assertSame(original, sibling, "sibling slot shifted or was reused across the toggle")
        assertEquals(42, sibling.value)

        useSecond = false
        val result = render()
        assertSame(original, sibling)
        assertEquals(42, sibling.value)
        assertTrue(result.warnings.none { it.code == "slot-class-mismatch" })
    }

    @Test
    fun stateVersusDerived() = assertToggleSafe(
        firstArm = { text("a:${state { 1 }.value}") },
        secondArm = { text("b:${derived { 2 }.value}") },
    )

    @Test
    fun watchVersusLaunchEffect() = assertToggleSafe(
        firstArm = { watch(source = { 1 }) { } },
        secondArm = { launchEffect { } },
    )

    @Test
    fun errorBoundaryVersusLoadingBoundary() = assertToggleSafe(
        firstArm = { errorBoundary(fallback = { _, _, _ -> text("err") }) { text("a") } },
        secondArm = { loadingBoundary(fallback = { text("load") }) { text("b") } },
    )

    @Test
    fun frameValueVersusHostRef() = assertToggleSafe(
        firstArm = { frameValue(0f) },
        secondArm = { hostRef<Ref<Any>>() },
    )

    @Test
    fun stateVersusErrorBoundary() = assertToggleSafe(
        firstArm = { text("a:${state { 1 }.value}") },
        secondArm = { errorBoundary(fallback = { _, _, _ -> text("err") }) { text("b") } },
    )

    @Test
    fun hostRefVersusImperativeHandle() = assertToggleSafe(
        firstArm = { hostRef<Ref<Any>>() },
        secondArm = { imperativeHandle { "handle" } },
    )

    @Test
    fun unitEventVersusTypedEvent() = assertToggleSafe(
        firstArm = { event { } },
        secondArm = { event<String> { } },
    )
}
