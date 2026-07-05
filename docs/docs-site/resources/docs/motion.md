# Motion

Animation in Kinetica keeps the value-tree model intact: animated numbers are **frame values** —
mutable floats that live *outside* the node tree and bind to nodes via `frameProps`. A running
animation never re-renders components; it only updates frame bindings.

## Frame values

```kotlin
val opacity = frameValue(0f)                     // runtime primitive

host("div", frameProps = mapOf("opacity" to opacity)) {
    text("Fades in")
}

opacity.snapTo(1f)                               // imperative set
opacity.observe { value -> … }                   // subscribe (renderer does this)
opacity.commitTo(cell)                           // fold a settled value back into state
```

A frame prop serializes as `frame:opacity` metadata on the node — snapshot-friendly and
renderer-agnostic like everything else in the tree.

## Animated values

```kotlin
val scale = animate(
    initial = 0f,
    target = 1f,
    spec = AnimationSpec.Spring(stiffness = 400f, dampingRatio = 1f),
)

host("div", frameProps = mapOf("scale" to scale.frameValue)) { … }

scale.animateTo(0.5f)                            // retarget mid-flight
scale.interrupt(0f, AnimationSpec.Tween(120))    // replace spec and target
scale.snapTo(1f)                                 // jump, no animation
scale.advanceBy(16)                              // advance the clock by one frame
```

Specs: `Tween(durationMillis, easing)` with `Linear / EaseIn / EaseOut / EaseInOut`, and
physical `Spring(stiffness, dampingRatio, visibilityThreshold)`. `animate()` survives
re-renders — calling it again with a new `target` retargets the existing animation instead of
restarting it.

The clock is explicit: `advanceBy(millis)` drives the animation and returns whether it is still
running. Drivers (a browser rAF loop, a test, the runtime's virtual time) own time — which is
why animations are deterministic in tests: `advanceBy(100)` twice lands exactly at the tween's
end state.

## Enter & exit

```kotlin
val enter = enterTransition()                    // 0 -> 1, 180ms ease-out by default

exitGroup(key = "sheet", visible = open) {
    exitTransition {                             // delays unmount until the block completes
        fade.animateTo(0f)
    }
    Sheet()
}
```

`exitTransition` registers an [`onExit` callback](/docs/effects) so removal plays an animation
before the subtree unmounts — the runtime retains the leaving tree, marks it with `leaving`
semantics, and force-completes after a debug timeout if an animation stalls.

## Drag gestures

```kotlin
val drag = dragGesture(onEnd = { offset -> settle(offset) })

host("div", frameProps = drag.frameProps()) { … }   // translateX / translateY bindings

drag.dragBy(dx, dy)      // feed pointer deltas
drag.end()               // fires onEnd with the accumulated GestureOffset
drag.reset()
```
