package io.heapy.kinetica.motion

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.ExitScope
import io.heapy.kinetica.FrameValue
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.frameValue
import io.heapy.kinetica.onExit
import io.heapy.kinetica.state
import kotlin.math.abs
import kotlin.math.exp

public sealed interface AnimationSpec {
    public data class Tween(
        val durationMillis: Long,
        val easing: Easing = Easing.Linear,
    ) : AnimationSpec {
        init {
            require(durationMillis > 0) { "durationMillis must be positive." }
        }
    }

    public data class Spring(
        val stiffness: Float = 400f,
        val dampingRatio: Float = 1f,
        val visibilityThreshold: Float = 0.001f,
    ) : AnimationSpec {
        init {
            require(stiffness > 0f) { "stiffness must be positive." }
            require(dampingRatio > 0f) { "dampingRatio must be positive." }
            require(visibilityThreshold > 0f) { "visibilityThreshold must be positive." }
        }
    }
}

public enum class Easing {
    Linear,
    EaseIn,
    EaseOut,
    EaseInOut,
}

public data class GestureOffset(
    val x: Float,
    val y: Float,
)

public class AnimatedFloat internal constructor(
    public val frameValue: FrameValue,
    initialTarget: Float,
    initialSpec: AnimationSpec,
) {
    private var startValue: Float = frameValue.value
    private var elapsedMillis: Long = 0
    private var running: Boolean = !frameValue.value.motionEquals(initialTarget)
    private var spec: AnimationSpec = initialSpec

    public var target: Float = initialTarget
        private set

    public val value: Float
        get() = frameValue.value

    public val isRunning: Boolean
        get() = running

    public fun animateTo(
        target: Float,
        spec: AnimationSpec = this.spec,
    ) {
        this.startValue = frameValue.value
        this.elapsedMillis = 0
        this.target = target
        this.spec = spec
        this.running = !frameValue.value.motionEquals(target)
    }

    public fun interrupt(
        target: Float,
        spec: AnimationSpec = this.spec,
    ) {
        animateTo(target, spec)
    }

    internal fun retargetIfNeeded(
        target: Float,
        spec: AnimationSpec,
    ) {
        if (!this.target.motionEquals(target) || this.spec != spec) {
            animateTo(target, spec)
        }
    }

    public fun snapTo(value: Float) {
        startValue = value
        elapsedMillis = 0
        target = value
        running = false
        frameValue.snapTo(value)
    }

    public fun advanceBy(millis: Long): Boolean {
        require(millis >= 0) { "millis must be non-negative." }
        if (!running || millis == 0L) {
            return running
        }
        return when (val activeSpec = spec) {
            is AnimationSpec.Tween -> advanceTween(millis, activeSpec)
            is AnimationSpec.Spring -> advanceSpring(millis, activeSpec)
        }
    }

    private fun advanceTween(
        millis: Long,
        activeSpec: AnimationSpec.Tween,
    ): Boolean {
        elapsedMillis = (elapsedMillis + millis).coerceAtMost(activeSpec.durationMillis)
        val fraction = elapsedMillis.toFloat() / activeSpec.durationMillis.toFloat()
        val eased = activeSpec.easing.transform(fraction.coerceIn(0f, 1f))
        frameValue.snapTo(lerp(startValue, target, eased))
        if (elapsedMillis >= activeSpec.durationMillis || frameValue.value.motionEquals(target)) {
            frameValue.snapTo(target)
            running = false
        }
        return running
    }

    private fun advanceSpring(
        millis: Long,
        activeSpec: AnimationSpec.Spring,
    ): Boolean {
        if (frameValue.value.motionEquals(target)) {
            running = false
            return false
        }
        val seconds = millis.toFloat() / 1000f
        val decay = exp((-activeSpec.stiffness / 100f * activeSpec.dampingRatio * seconds).toDouble()).toFloat()
        val next = target + (frameValue.value - target) * decay
        if (abs(next - target) <= activeSpec.visibilityThreshold || next.motionEquals(target)) {
            frameValue.snapTo(target)
            running = false
        } else {
            frameValue.snapTo(next)
            running = !frameValue.value.motionEquals(target)
        }
        return running
    }
}

@UiComponent
public fun ComponentScope.animate(
    initial: Float,
    target: Float,
    spec: AnimationSpec = AnimationSpec.Spring(),
): AnimatedFloat {
    // The frameValue slot and the AnimatedFloat slot live in the same per-call-site frame,
    // so their lifetimes are coupled by construction — no dynamic key needed anymore.
    val value = frameValue(initial)
    val animated = state {
        AnimatedFloat(value, target, spec)
    }.value
    animated.retargetIfNeeded(target, spec)
    return animated
}

@UiComponent
public fun ComponentScope.enterTransition(
    initial: Float = 0f,
    target: Float = 1f,
    spec: AnimationSpec = AnimationSpec.Tween(durationMillis = 180, easing = Easing.EaseOut),
): AnimatedFloat {
    return animate(initial = initial, target = target, spec = spec)
}

public fun ComponentScope.exitTransition(block: suspend ExitScope.() -> Unit) {
    onExit {
        block()
        complete()
    }
}

public class DragGesture internal constructor(
    public val x: FrameValue,
    public val y: FrameValue,
    private val initialOffset: GestureOffset,
    private val onEnd: (GestureOffset) -> Unit,
) {
    public val offset: GestureOffset
        get() = GestureOffset(x.value, y.value)

    public fun dragBy(
        deltaX: Float,
        deltaY: Float,
    ): GestureOffset {
        x.snapTo(x.value + deltaX)
        y.snapTo(y.value + deltaY)
        return offset
    }

    public fun dragTo(
        x: Float,
        y: Float,
    ): GestureOffset {
        this.x.snapTo(x)
        this.y.snapTo(y)
        return offset
    }

    public fun reset(): GestureOffset =
        dragTo(initialOffset.x, initialOffset.y)

    public fun end(): GestureOffset =
        offset.also(onEnd)
}

@UiComponent
public fun ComponentScope.dragGesture(
    initialX: Float = 0f,
    initialY: Float = 0f,
    onEnd: (GestureOffset) -> Unit = {},
): DragGesture {
    return DragGesture(
        x = frameValue(initialX),
        y = frameValue(initialY),
        initialOffset = GestureOffset(initialX, initialY),
        onEnd = onEnd,
    )
}

public fun DragGesture.frameProps(
    xProperty: String = "translateX",
    yProperty: String = "translateY",
): Map<String, FrameValue> =
    mapOf(
        xProperty to x,
        yProperty to y,
    )

private fun Easing.transform(fraction: Float): Float =
    when (this) {
        Easing.Linear -> fraction
        Easing.EaseIn -> fraction * fraction
        Easing.EaseOut -> 1f - (1f - fraction) * (1f - fraction)
        Easing.EaseInOut -> {
            if (fraction < 0.5f) {
                2f * fraction * fraction
            } else {
                1f - 2f * (1f - fraction) * (1f - fraction)
            }
        }
    }

private fun lerp(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

private fun Float.motionEquals(other: Float): Boolean =
    this == other || (isNaN() && other.isNaN())
