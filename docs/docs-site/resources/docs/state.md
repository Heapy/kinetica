# State & reactivity

Kinetica has **one reactive primitive: the cell**. `state`, `derived`, `store`, contexts and
resources are all cells; reads inside render are tracked, writes invalidate exactly the
components that read them.

## state

```kotlin
var count by state { 0 }
count += 1                       // write -> invalidate -> re-render
```

`state` allocates a *slot* in the component's frame. Slot identity is assigned by the
[compiler plugin](/docs/compiler-plugin): every call site gets a static ordinal, and the value
lives in a per-instance array indexed by it. There are no keys to pass and no positional
collisions — two branches of an `if/else` are two different call sites, so they can never
share a slot. Writes go through an `EqualityPolicy` — `structural()` by default — so writing
an equal value does not re-render.

Dynamic identity (the same call site rendering many logical instances) comes from the keyed
constructs: `each(items, key = { … })` gives every row its own frame, and `keyed(key) { … }`
does the same for a single subtree. When a key disappears from the list its frame is disposed
— state does not resurrect if the key later returns.

```kotlin
fun <T> ComponentScope.state(
    policy: EqualityPolicy<T> = EqualityPolicy.structural(),
    persistent: Boolean = false,   // slot survives keyed-frame disposal, see /docs/persist
    transient: Boolean = false,    // slot is dropped when not touched by a render
    initial: () -> T,
): MutableCell<T>
```

## derived

```kotlin
val remaining by derived { todos.count { !it.done } }
```

`derived` memoizes a computation and re-runs it only when a tracked dependency's version
changes. Equality-deduped: if the recomputed value is equal, downstream readers do not
re-render — `derived { count > 0 }` re-renders its readers only when the boolean flips.

## store — cells outside the render tree

```kotlin
val theme = store(Theme.Light)          // plain reactive cell, no component scope needed
val current = peek { theme.value }      // read without dependency tracking
```

`store` builds reactive objects that live outside components (the forms battery uses it for
field state). Renders that read a store re-render when it changes.

## Events

```kotlin
val add = event { todos = todos + draft }         // () -> Unit
val onInput = event<String> { draft = it }        // (String) -> Unit
```

`event {}` returns a handler with a **stable identity across renders** (slot-backed), so a
re-render does not re-register anything. Handlers run on the single UI loop: the event executes,
all cell writes apply, then exactly one synchronous render commits. There is no batching to
configure and no torn intermediate state to observe.

## Try it

::: example counter

The counter above is `state` + `derived` + two `event` handlers, mounted with the browser
renderer. So is this input mirror:

::: example input-mirror

## Equality policies

| Policy | Behavior |
|--------|----------|
| `structural()` | `==` comparison (default) |
| `referential()` | `===` comparison |
| `neverEqual()` | every write invalidates |

## Rules of thumb

- Model state as immutable values (`data class` copies, new lists). The renderer diffs values —
  mutation in place defeats change detection.
- Read cells where you render them; derive aggregates with `derived` instead of recomputing in
  every component.
- Effects never run during render — reach for [`watch` and `launchEffect`](/docs/effects) when
  something must happen *because* state changed.
