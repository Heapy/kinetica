package io.heapy.kinetica.motion

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.exitGroup
import io.heapy.kinetica.frameBindings
import io.heapy.kinetica.host
import io.heapy.kinetica.text
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MotionSmokeTest {
    @Test
    fun tweenAnimationAdvancesFrameValueWithEasing() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var opacity: AnimatedFloat

        runtime.render(scope) {
            opacity = animate(
                initial = 0f,
                target = 10f,
                spec = AnimationSpec.Tween(durationMillis = 1_000, easing = Easing.Linear),
            )
            host("box", frameProps = mapOf("opacity" to opacity.frameValue))
        }

        assertEquals(0f, opacity.value)
        assertTrue(opacity.isRunning)
        assertTrue(opacity.advanceBy(250))
        assertClose(2.5f, opacity.value)
        assertFalse(opacity.advanceBy(750))
        assertClose(10f, opacity.value)
    }

    @Test
    fun animationProgressSurvivesRecomposition() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var opacity: AnimatedFloat

        fun render() = runtime.render(scope) {
            opacity = animate(
                initial = 0f,
                target = 10f,
                spec = AnimationSpec.Tween(durationMillis = 1_000, easing = Easing.Linear),
            )
            host("box", frameProps = mapOf("opacity" to opacity.frameValue))
        }

        render()
        assertTrue(opacity.advanceBy(250))
        assertClose(2.5f, opacity.value)

        // An unrelated recomposition must not restart the in-flight tween.
        render()

        // 750ms more must complete the original 1000ms tween, not begin a new one.
        assertFalse(opacity.advanceBy(750))
        assertClose(10f, opacity.value)
    }

    @Test
    fun animationRetargetsExistingStateOnRecomposition() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        var target = 10f
        lateinit var opacity: AnimatedFloat

        fun render() = runtime.render(scope) {
            opacity = animate(
                initial = 0f,
                target = target,
                spec = AnimationSpec.Tween(durationMillis = 1_000, easing = Easing.Linear),
            )
            host("box", frameProps = mapOf("opacity" to opacity.frameValue))
        }

        render()
        assertTrue(opacity.advanceBy(250))
        assertClose(2.5f, opacity.value)

        val retained = opacity
        target = 20f
        render()

        assertSame(retained, opacity)
        assertEquals(20f, opacity.target)
        assertTrue(opacity.isRunning)
        assertTrue(opacity.advanceBy(500))
        assertClose(11.25f, opacity.value)
    }

    @Test
    fun animationInterruptsFromCurrentFrameValue() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var x: AnimatedFloat

        runtime.render(scope) {
            x = animate(
                initial = 0f,
                target = 100f,
                spec = AnimationSpec.Tween(durationMillis = 1_000),
            )
        }

        x.advanceBy(500)
        assertClose(50f, x.value)

        x.interrupt(
            target = 0f,
            spec = AnimationSpec.Tween(durationMillis = 500, easing = Easing.EaseOut),
        )

        assertTrue(x.advanceBy(250))
        assertClose(12.5f, x.value)
        assertFalse(x.advanceBy(250))
        assertClose(0f, x.value)
    }

    @Test
    fun tweenAnimationCoversRemainingEasingsAndIdleEdges() {
        val easeIn = animatedTween(Easing.EaseIn)
        assertTrue(easeIn.advanceBy(500))
        assertClose(2.5f, easeIn.value)

        val easeInOutFirstHalf = animatedTween(Easing.EaseInOut)
        assertTrue(easeInOutFirstHalf.advanceBy(250))
        assertClose(1.25f, easeInOutFirstHalf.value)

        val easeInOutSecondHalf = animatedTween(Easing.EaseInOut)
        assertTrue(easeInOutSecondHalf.advanceBy(750))
        assertClose(8.75f, easeInOutSecondHalf.value)

        val idle = animatedTween(Easing.Linear, initial = 4f, target = 4f)
        assertFalse(idle.isRunning)
        assertFalse(idle.advanceBy(0))
        idle.snapTo(2f)
        assertEquals(2f, idle.value)
        assertEquals(2f, idle.target)
        assertFailsWith<IllegalArgumentException> {
            idle.advanceBy(-1)
        }
    }

    @Test
    fun animationSpecsRejectInvalidValuesAndFramePropsCanBeNamed() {
        assertFailsWith<IllegalArgumentException> {
            AnimationSpec.Tween(durationMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AnimationSpec.Spring(stiffness = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            AnimationSpec.Spring(dampingRatio = 0f)
        }
        assertFailsWith<IllegalArgumentException> {
            AnimationSpec.Spring(visibilityThreshold = 0f)
        }

        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var gesture: DragGesture
        runtime.render(scope) {
            gesture = dragGesture(initialX = 1f, initialY = 2f)
        }

        assertEquals(setOf("left", "top"), gesture.frameProps("left", "top").keys)
    }

    @Test
    fun defaultMotionArgumentsCreateUsableAnimationsAndGestures() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var animated: AnimatedFloat
        lateinit var entered: AnimatedFloat
        lateinit var gesture: DragGesture

        runtime.render(scope) {
            animated = animate(initial = 0f, target = 1f)
            entered = enterTransition()
            gesture = dragGesture()
        }

        assertTrue(animated.isRunning)
        animated.animateTo(target = 2f)
        assertTrue(animated.isRunning)
        animated.interrupt(target = 0f)
        repeat(80) {
            animated.advanceBy(16)
        }
        assertFalse(animated.isRunning)
        assertClose(0f, animated.value)

        assertTrue(entered.isRunning)
        assertEquals(GestureOffset(0f, 0f), gesture.offset)
        assertEquals(GestureOffset(0f, 0f), gesture.end())
    }

    @Test
    fun springAnimationConvergesToTarget() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var scale: AnimatedFloat

        runtime.render(scope) {
            scale = animate(
                initial = 0f,
                target = 1f,
                spec = AnimationSpec.Spring(stiffness = 500f, dampingRatio = 1f, visibilityThreshold = 0.01f),
            )
        }

        repeat(80) {
            scale.advanceBy(16)
        }

        assertFalse(scale.isRunning)
        assertClose(1f, scale.value, tolerance = 0.01f)
    }

    @Test
    fun springAnimationSettlesNanTargets() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var spring: AnimatedFloat
        lateinit var idle: AnimatedFloat
        lateinit var externallySettled: AnimatedFloat

        runtime.render(scope) {
            spring = animate(
                initial = 0f,
                target = Float.NaN,
                spec = AnimationSpec.Spring(),
            )
            idle = animate(
                initial = Float.NaN,
                target = Float.NaN,
                spec = AnimationSpec.Spring(),
            )
            externallySettled = animate(
                initial = 0f,
                target = 1f,
                spec = AnimationSpec.Spring(),
            )
        }

        assertFalse(idle.isRunning)
        assertTrue(spring.isRunning)
        assertFalse(spring.advanceBy(16))
        assertTrue(spring.value.isNaN())

        externallySettled.frameValue.snapTo(1f)
        assertFalse(externallySettled.advanceBy(16))
        assertFalse(externallySettled.isRunning)
    }

    @Test
    fun enterTransitionProducesFrameBackedAnimation() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var opacity: AnimatedFloat

        val node = runtime.render(scope) {
            opacity = enterTransition(
                initial = 0f,
                target = 1f,
                spec = AnimationSpec.Tween(durationMillis = 200, easing = Easing.EaseOut),
            )
            host("panel", frameProps = mapOf("opacity" to opacity.frameValue))
        }.tree as HostNode

        val binding = node.frameBindings().single()
        assertEquals("opacity", binding.property)
        assertEquals(0f, binding.initialValue)
        assertTrue(opacity.isRunning)
        assertTrue(opacity.advanceBy(100))
        assertClose(0.75f, opacity.value)
        assertFalse(opacity.advanceBy(100))
        assertClose(1f, opacity.value)
    }

    @Test
    fun dragGestureUpdatesFrameValuesAndReportsFinalOffset() {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var gesture: DragGesture
        var ended: GestureOffset? = null

        val node = runtime.render(scope) {
            gesture = dragGesture(
                initialX = 2f,
                initialY = 4f,
                onEnd = { offset -> ended = offset },
            )
            host("card", frameProps = gesture.frameProps())
        }.tree as HostNode

        val bindings = node.frameBindings().associateBy { it.property }
        assertEquals(2f, bindings.getValue("translateX").initialValue)
        assertEquals(4f, bindings.getValue("translateY").initialValue)

        val dragged = gesture.dragBy(deltaX = 10f, deltaY = -6f)
        assertEquals(GestureOffset(12f, -2f), dragged)
        assertEquals(12f, runtime.frameValue(bindings.getValue("translateX").frameId)?.value)
        assertEquals(-2f, runtime.frameValue(bindings.getValue("translateY").frameId)?.value)

        assertEquals(GestureOffset(12f, -2f), gesture.end())
        assertEquals(GestureOffset(12f, -2f), ended)
        assertEquals(GestureOffset(2f, 4f), gesture.reset())
    }

    @Test
    fun exitTransitionRunsAndCompletesLeavingGroup() = runTest {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        panelReleaseExit = CompletableDeferred()
        panelVisible = true
        panelExitCompleted = false

        fun render() = runtime.render(scope) {
            ExitPanel()
        }.tree

        assertIs<TextNode>(render())

        panelVisible = false
        val leaving = assertIs<TextNode>(render())
        assertTrue(leaving.semantics?.leaving == true)
        assertTrue(scope.isLeaving("panel"))

        panelReleaseExit!!.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(2_000) {
                while (scope.isLeaving("panel")) {
                    delay(10)
                }
            }
        }

        assertTrue(panelExitCompleted)
        assertIs<FragmentNode>(render())
    }

    private fun assertClose(
        expected: Float,
        actual: Float,
        tolerance: Float = 0.001f,
    ) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected, got $actual")
    }

    private fun animatedTween(
        easing: Easing,
        initial: Float = 0f,
        target: Float = 10f,
    ): AnimatedFloat {
        val runtime = KineticaRuntime()
        val scope = ComponentScope(runtime)
        lateinit var animated: AnimatedFloat
        runtime.render(scope) {
            animated = animate(
                initial = initial,
                target = target,
                spec = AnimationSpec.Tween(durationMillis = 1_000, easing = easing),
            )
        }
        return animated
    }
}

// exitGroup is slot DSL, so the leaving panel must be a @UiComponent; the test drives it
// through file-level vars.

private var panelVisible = true
private var panelExitCompleted = false
private var panelReleaseExit: CompletableDeferred<Unit>? = null

@UiComponent(skippable = false)
private fun ComponentScope.ExitPanel() {
    exitGroup(key = "panel", visible = panelVisible) {
        exitTransition {
            panelReleaseExit!!.await()
            panelExitCompleted = true
        }
        text("Panel", semantics = Semantics(testTag = "panel"))
    }
}
