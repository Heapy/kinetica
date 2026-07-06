# Effects & lifecycle

<!-- code: kinetica-runtime/src/Effects.kt (launchEffect, watch, layoutEffect), kinetica-runtime/src/ComponentScope.kt (runPostCommitEffects) -->

Effects are **explicit**. Nothing re-runs because you happened to read a cell — you declare what
to watch. All effects start after the render commits, never during it.

## launchEffect

<!-- code: kinetica-runtime/src/Effects.kt (launchEffect, EffectScope.awaitDispose), kinetica-runtime/src/Frames.kt (commitChecks, disposeFrameSlotValue) -->

```kotlin
launchEffect {
    val socket = connect()
    awaitDispose {          // suspends until the component unmounts
        socket.close()
    }
}
```

A coroutine tied to the component's lifetime. `awaitDispose { cleanup }` is the teardown hook.
Returns an `EffectHandle` with `cancel()` if you need to stop it early. The lifetime is enforced
by the frame model: the effect lives in a *transient* slot, so when a committed render no longer
reaches its call site the effect is cancelled and disposed.

## watch — react to data changes

<!-- code: kinetica-runtime/src/Effects.kt (watch, WatchEffectState), kinetica-runtime/src/KineticaRuntime.kt (watchLoopRestartLimit) -->

```kotlin
watch(source = { userId }) { id ->
    profile = api.loadProfile(id)
}
```

`watch` tracks the cells read inside `source` and re-runs `block` when the *value* changes
(structural equality by default — pass `equals =` to override). This is the spec'd alternative to
dependency arrays: the dependency is the expression itself. In debug mode a restart-loop guard
(`watchLoopRestartLimit`, default 32) stops a watch that keeps invalidating itself and records a
`WatchLoop` journal entry naming the culprit — a diagnosable stop instead of a silent hang.

## layoutEffect

<!-- code: kinetica-runtime/src/Effects.kt (layoutEffect), kinetica-router/src/Router.kt (NavHost layoutEffect) -->

```kotlin
layoutEffect { /* runs synchronously at commit, before the frame is shown */ }
```

For work that must observe the committed tree in the same frame — the router uses it to persist
navigation state.

## Event handlers

<!-- code: kinetica-runtime/src/Effects.kt (event), kinetica-browser/src@js/BrowserKineticaApp.kt (dispatchAndRender) -->

`event {}` / `event<T> {}` are covered in [State](/docs/state); they are the only way user input
enters the loop: event → cell writes → one synchronous commit → effects.

## Try it

<!-- code: docs/docs-client/src/main.kt (EffectTimerExample) -->

::: example effect-timer

The stopwatch is one `watch` whose block loops while `running` is true — flipping the cell
cancels the previous block and starts a new one, so Stop is just a state write.

## Exit lifecycle

<!-- code: kinetica-runtime/src/Boundary.kt (exitGroup, onExit, ExitScope, asLeaving), kinetica-motion/src/Motion.kt (exitTransition) -->

Removal is a first-class phase. Wrap removable content in an `exitGroup`; when `visible` flips
false the subtree is retained, exit callbacks run, and only then does it unmount:

```kotlin
exitGroup(key = "toast", visible = showToast) {
    onExit { complete() }              // or animate first — see below
    host("div", props = mapOf("class" to "toast")) { text("Saved!") }
}
```

With the [motion battery](/docs/motion):

```kotlin
exitGroup(key = "panel", visible = open) {
    exitTransition {                    // registers onExit
        fade.animateTo(0f)              // play the exit animation
        // completes when done; the runtime unmounts afterwards
    }
    Panel()
}
```

While leaving, nodes carry `leaving` semantics (`data-kinetica-leaving` in the browser), and
`isLeaving(key)` reports the phase. A debug-mode timeout (`exitTimeoutMillis`, 5s default)
force-completes exits that never call `complete()` — a stuck animation cannot leak a subtree
silently.

## The journal sees everything

<!-- code: kinetica-runtime/src/Journal.kt (JournalKind, JournalReplay), kinetica-runtime/src/KineticaRuntime.kt (journal, replay) -->

In debug mode, every effect start, cancellation, watch restart and cell write lands in the
runtime journal with its cause (production runtimes skip journaling). When "why did this
re-render?" comes up, read `runtime.journal()` — or replay it:

```kotlin
val replay = runtime.replay()
replay.stateAt(sequence)     // reconstructed state at that journal entry
replay.states()              // the series over time
```
