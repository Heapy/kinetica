# Effects & lifecycle

Effects are **explicit**. Nothing re-runs because you happened to read a cell — you declare what
to watch. All effects start after the render commits, never during it.

## launchEffect

```kotlin
launchEffect {
    val socket = connect()
    awaitDispose {          // suspends until the component unmounts
        socket.close()
    }
}
```

A coroutine tied to the component's lifetime. `awaitDispose { cleanup }` is the teardown hook.
Returns an `EffectHandle` with `cancel()` if you need to stop it early.

## watch — react to data changes

```kotlin
watch(source = { userId }) { id ->
    profile = api.loadProfile(id)
}
```

`watch` tracks the cells read inside `source` and re-runs `block` when the *value* changes
(structural equality by default — pass `equals =` to override). This is the spec'd alternative to
dependency arrays: the dependency is the expression itself. A restart-loop guard
(`watchLoopRestartLimit`, default 32) turns a watch that keeps invalidating itself into a
diagnosable error instead of a hang.

## layoutEffect

```kotlin
layoutEffect { /* runs synchronously at commit, before the frame is shown */ }
```

For work that must observe the committed tree in the same frame — the router uses it to persist
navigation state.

## Event handlers

`event {}` / `event<T> {}` are covered in [State](/docs/state); they are the only way user input
enters the loop: event → cell writes → one synchronous commit → effects.

## Exit lifecycle

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

Every effect start, cancellation, watch restart and cell write lands in the runtime journal with
its cause. When "why did this re-render?" comes up, read `runtime.journal()` — or replay it:

```kotlin
val replay = runtime.replay()
replay.stateAt(sequence)     // reconstructed state snapshots over time
```
