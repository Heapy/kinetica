package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Drives the frame kernel directly through internal APIs — no compiler plugin involved.
 * Covers the storage/lifecycle invariants the ordinal rewrite relies on: slot identity
 * per (frame, ordinal), keyed child forking, transient expiry on committed renders,
 * branch deactivation vs memoized keep, and growable root mode.
 */
class FrameKernelTest {
    private class FakeEffect : ManagedEffectState {
        var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    private fun scope() = ComponentScope(KineticaRuntime())

    private fun ComponentScope.render(block: ComponentScope.() -> Unit) {
        beginRender()
        block()
        commitRender()
    }

    private val table = FrameTable(
        functionFqName = "test.Component",
        slotCount = 2,
        eventCount = 1,
        childCount = 2,
        transientSlotOrdinals = intArrayOf(1),
    )

    @Test
    fun slotIdentitySurvivesRerenderPerFrameAndOrdinal() {
        val scope = scope()
        var first: Any? = null
        var second: Any? = null
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            first = frameSlot(0) { Any() }
            second = frameSlot(1, transient = true) { Any() }
            endComponentFrame()
        }
        assertNotSame(first, second)
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            assertSame(first, frameSlot(0) { Any() })
            assertSame(second, frameSlot(1, transient = true) { Any() })
            endComponentFrame()
        }
    }

    @Test
    fun distinctChildOrdinalsGetDistinctFrames() {
        val scope = scope()
        var a: Any? = null
        var b: Any? = null
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            a = frameSlot(0) { Any() }
            endComponentFrame()
            ordinal(1)
            beginComponentFrame(table)
            b = frameSlot(0) { Any() }
            endComponentFrame()
        }
        assertNotSame(a, b)
    }

    @Test
    fun missingStagedOrdinalThrowsMissingPlugin() {
        val scope = scope()
        scope.beginRender()
        assertFailsWith<MissingKineticaPluginException> {
            scope.beginComponentFrame(table)
        }
    }

    @Test
    fun transientSlotUntouchedByCommittedRenderIsDisposed() {
        val scope = scope()
        val effect = FakeEffect()
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            frameSlot(1, transient = true) { effect }
            endComponentFrame()
        }
        // Re-render the frame without touching the transient ordinal (conditional off).
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            frameSlot(0) { Any() }
            endComponentFrame()
        }
        assertTrue(effect.cancelled)
    }

    @Test
    fun transientSlotSurvivesWhenFrameIsNotReentered() {
        val scope = scope()
        val effect = FakeEffect()
        lateinit var child: Frame
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            child = currentFrame
            frameSlot(1, transient = true) { effect }
            endComponentFrame()
        }
        // A memoized skip keeps the child without entering it: transients must survive.
        scope.render {
            currentFrame.touchFixedChild(0, generation = child.enteredGeneration + 1)
        }
        assertEquals(false, effect.cancelled)
        assertEquals(false, child.deactivated)
    }

    @Test
    fun unkeptChildIsDeactivatedTransientsDisposedStateRetained() {
        val scope = scope()
        val effect = FakeEffect()
        var stateCell: Any? = null
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            stateCell = frameSlot(0) { Any() }
            frameSlot(1, transient = true) { effect }
            endComponentFrame()
        }
        // Branch switches away: the child ordinal is neither entered nor kept.
        scope.render { }
        assertTrue(effect.cancelled)
        // Branch comes back: state cell survives deactivation, transient recreates.
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            assertSame(stateCell, frameSlot(0) { Any() })
            val recreated = frameSlot(1, transient = true) { FakeEffect() }
            assertNotSame(effect, recreated)
            endComponentFrame()
        }
    }

    @Test
    fun keyedChildrenAreSeparateAndRemovalDisposes() {
        val scope = scope()
        val effectA = FakeEffect()
        var slotA: Any? = null
        var slotB: Any? = null
        scope.render {
            val row = currentFrame.enterKeyedChild(0, "a", table, generation = 1)
            enterFrame(row)
            slotA = frameSlot(0) { Any() }
            frameSlot(1, transient = true) { effectA }
            exitFrame()
            val rowB = currentFrame.enterKeyedChild(0, "b", table, generation = 1)
            enterFrame(rowB)
            slotB = frameSlot(0) { Any() }
            exitFrame()
        }
        assertNotSame(slotA, slotB)
        assertEquals(setOf<Any>("a", "b"), scope.rootFrame.keyedChildKeys(0))
        scope.rootFrame.removeKeyedChild(0, "a", scope.runtime)
        assertTrue(effectA.cancelled)
        assertEquals(setOf<Any>("b"), scope.rootFrame.keyedChildKeys(0))
    }

    @Test
    fun doubleEnterOfOneCallsiteForksSiblingFrames() {
        val scope = scope()
        var first: Any? = null
        var second: Any? = null
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            first = frameSlot(0) { Any() }
            endComponentFrame()
            ordinal(0)
            beginComponentFrame(table)
            second = frameSlot(0) { Any() }
            endComponentFrame()
        }
        assertNotSame(first, second)
        // Second render maps invocations back to the same forks.
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            assertSame(first, frameSlot(0) { Any() })
            endComponentFrame()
            ordinal(0)
            beginComponentFrame(table)
            assertSame(second, frameSlot(0) { Any() })
            endComponentFrame()
        }
    }

    @Test
    fun growableRootModeGrowsSlotStorage()  {
        val scope = scope()
        val values = mutableListOf<Any>()
        scope.render {
            repeat(20) { i -> values += frameSlot(i) { Any() } }
        }
        scope.render {
            repeat(20) { i -> assertSame(values[i], frameSlot(i) { Any() }) }
        }
    }

    @Test
    fun frameEventReusesIdAndEvictsUntouched() {
        val scope = scope()
        var firstId: String? = null
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            firstId = frameEvent(0) { }
            endComponentFrame()
        }
        val baseline = scope.runtime.registeredEventCount()
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            assertEquals(firstId, frameEvent(0) { })
            endComponentFrame()
        }
        assertEquals(baseline, scope.runtime.registeredEventCount())
        // Event callsite stops rendering while its frame re-renders: id is evicted.
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            endComponentFrame()
        }
        assertEquals(baseline - 1, scope.runtime.registeredEventCount())
    }

    @Test
    fun disposeTearsDownWholeTree() {
        val scope = scope()
        val effect = FakeEffect()
        scope.render {
            ordinal(0)
            beginComponentFrame(table)
            frameSlot(1, transient = true) { effect }
            frameEvent(0) { }
            endComponentFrame()
        }
        scope.dispose()
        assertTrue(effect.cancelled)
        assertEquals(0, scope.runtime.registeredEventCount())
    }
}
