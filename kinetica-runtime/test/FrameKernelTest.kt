package io.heapy.kinetica

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        val failure = assertFailsWith<MissingKineticaPluginException> {
            scope.beginComponentFrame(table)
        }
        val message = failure.message.orEmpty()
        assertTrue(
            message.startsWith("A Kinetica component call ran without a compiler-assigned ordinal."),
            message,
        )
        assertTrue("io.heapy.kinetica.compiler plugin" in message, message)
        assertTrue("supported compiler-transformed context" in message, message)
    }

    @Test
    fun stagedOrdinalConsumedInDifferentFrameThrowsImmediately() {
        // F10 backstop: staging always immediately precedes its call in the same frame,
        // so a pop from another frame proves an unstaged component call (stale plugin
        // output) is stealing someone else's ordinal. It must throw BEFORE entering any
        // frame — not render into the wrong fixed child and surface later.
        val scope = scope()
        scope.beginRender()
        scope.ordinal(0)
        scope.beginRegionFrame(table)
        val failure = assertFailsWith<IllegalStateException> {
            scope.beginComponentFrame(table)
        }
        val message = failure.message.orEmpty()
        assertTrue("staged in a different frame" in message, message)
        assertTrue("recompile the module" in message, message)
    }

    @Test
    fun beginRenderDropsLeakedStagedOrdinalsInsteadOfConsumingThem() {
        // A staged entry can legitimately linger when a throw unwinds between staging
        // and the staged call (an error boundary catching mid-argument-evaluation).
        // beginRender must DROP such leaks: the next render's first component prologue
        // finds an empty stack (MissingKineticaPluginException), never last render's
        // stale entry — which would render into an arbitrary wrong child frame.
        val scope = scope()
        scope.render {
            beginRegionFrame(table)
            ordinal(1)
            endRegionFrame()
        }
        scope.render {
            assertFailsWith<MissingKineticaPluginException>(
                "the leaked entry from the previous render must have been dropped",
            ) {
                beginComponentFrame(table)
            }
        }
    }

    @Test
    fun argumentPositionStagingKeepsLifoDiscipline() {
        // A component call in argument position of another component call pops its own
        // ordinal before the outer one resolves — both staged in the same frame, in LIFO
        // order. The discipline check must not disturb this blessed shape.
        val scope = scope()
        var inner: Any? = null
        var outer: Any? = null
        scope.render {
            ordinal(1)
            ordinal(0)
            beginComponentFrame(table)
            inner = frameSlot(0) { Any() }
            endComponentFrame()
            beginComponentFrame(table)
            outer = frameSlot(0) { Any() }
            endComponentFrame()
        }
        assertNotSame(inner, outer)
        scope.render {
            ordinal(1)
            ordinal(0)
            beginComponentFrame(table)
            assertSame(inner, frameSlot(0) { Any() })
            endComponentFrame()
            beginComponentFrame(table)
            assertSame(outer, frameSlot(0) { Any() })
            endComponentFrame()
        }
    }

    @Test
    fun stagingInsideRegionFrameIsConsumedInThatFrame() {
        // Component calls inside compiler-wrapped content lambdas stage and pop within
        // the region frame; the discipline check keys on that frame, not the root.
        val scope = scope()
        var cell: Any? = null
        scope.render {
            beginRegionFrame(table)
            ordinal(0)
            beginComponentFrame(table)
            cell = frameSlot(0) { Any() }
            endComponentFrame()
            endRegionFrame()
        }
        scope.render {
            beginRegionFrame(table)
            ordinal(0)
            beginComponentFrame(table)
            assertSame(cell, frameSlot(0) { Any() })
            endComponentFrame()
            endRegionFrame()
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
        scope.render { }
        assertTrue(effect.cancelled)
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
    fun regionReenteredWithinOneRenderForksSiblingFrames() {
        val scope = scope()
        var first: Any? = null
        var second: Any? = null
        var firstEvent: String? = null
        var secondEvent: String? = null
        scope.render {
            beginRegionFrame(table)
            first = frameSlot(0) { Any() }
            firstEvent = frameEvent(0) { }
            endRegionFrame()
            beginRegionFrame(table)
            second = frameSlot(0) { Any() }
            secondEvent = frameEvent(0) { }
            endRegionFrame()
        }
        assertNotSame(first, second, "repeated content invocations must not alias state")
        assertNotEquals(firstEvent, secondEvent, "repeated content invocations must not alias events")
        scope.render {
            beginRegionFrame(table)
            assertSame(first, frameSlot(0) { Any() })
            assertEquals(firstEvent, frameEvent(0) { })
            endRegionFrame()
            beginRegionFrame(table)
            assertSame(second, frameSlot(0) { Any() })
            assertEquals(secondEvent, frameEvent(0) { })
            endRegionFrame()
        }
    }

    @Test
    fun forkedRegionSiblingIsDeactivatedWhenLaterRenderEntersOnce() {
        val scope = scope()
        val effect = FakeEffect()
        var forkState: Any? = null
        scope.render {
            beginRegionFrame(table)
            endRegionFrame()
            beginRegionFrame(table)
            forkState = frameSlot(0) { Any() }
            frameSlot(1, transient = true) { effect }
            endRegionFrame()
        }
        scope.render {
            beginRegionFrame(table)
            endRegionFrame()
        }
        // commitChecks already deactivates regions whose keptGeneration lags — the fork
        // needs no extra disposal mechanism.
        assertTrue(effect.cancelled)
        scope.render {
            beginRegionFrame(table)
            endRegionFrame()
            beginRegionFrame(table)
            assertSame(forkState, frameSlot(0) { Any() }, "fork state must survive deactivation")
            val recreated = frameSlot(1, transient = true) { FakeEffect() }
            assertNotSame(effect, recreated, "fork transients must be recreated after deactivation")
            endRegionFrame()
        }
    }

    @Test
    fun singleEntryRegionKeepsIdentityAcrossRenders() {
        val scope = scope()
        var cell: Any? = null
        var eventId: String? = null
        scope.render {
            beginRegionFrame(table)
            cell = frameSlot(0) { Any() }
            eventId = frameEvent(0) { }
            endRegionFrame()
        }
        scope.render {
            beginRegionFrame(table)
            assertSame(cell, frameSlot(0) { Any() })
            assertEquals(eventId, frameEvent(0) { })
            endRegionFrame()
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
