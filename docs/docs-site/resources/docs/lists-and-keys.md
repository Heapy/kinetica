# Lists & keys

## each

```kotlin
each(todos, key = { it.id }) { todo ->
    host("li", key = todo.id) {
        text(todo.title)
    }
}
```

`each` renders a collection with **per-item identity**. The `key` does two jobs:

1. **State identity.** Every item gets its own key scope: `state`, `event` and effect slots
   inside the item belong to that key. Reordering the list moves state with the item; removing
   the item disposes its slots (and, in the browser, evicts its event handlers).
2. **DOM identity.** Passing the same key to the emitted node (`host("li", key = todo.id)`) lets
   the browser renderer reconcile by key: reorders become element *moves* (computed with a
   longest-increasing-subsequence plan), not teardown-and-rebuild. Swapping two rows in a
   1,000-row table costs two `insertBefore` calls.

Keys must be unique within one `each`. In debug mode a duplicate key fails the render loudly;
in production the last item wins.

## Try it

::: example keyed-list

Reverse moves the existing DOM elements — watch selection and element identity survive.

## keyed

`keyed(key) { … }` opens a key scope manually for a single subtree — useful when a component's
identity should follow data rather than position (e.g. a detail panel keyed by the selected id,
so switching ids resets its state deliberately).

## lazyEach — windowed lists

```kotlin
val items = lazyItems(allRows, estimatedSize = allRows.size)
var scroll by state(key = "scroll") { LazyListState(firstVisibleIndex = 0, visibleCount = 40) }

lazyEach(items, key = { it.id }, state = scroll, retain = RetainPolicy.Keyed) { row ->
    RowView(row)
}
```

`lazyEach` renders only `state.visibleRange` — the built-in lever for huge lists. `RetainPolicy`
controls what happens to off-screen items' state:

| Policy | Off-screen item state |
|--------|-----------------------|
| `Keyed` | kept (scroll back retains everything) |
| `VisibleOnly` | disposed when out of range |
| `PersistentSlots` | only `persistent = true` slots survive |

`LazyListState(firstVisibleIndex, visibleCount)` is serializable; the
[router](/docs/router) can persist it per navigation entry (`rememberNavScrollState`) so back
navigation restores scroll position.

## Where keys come from

Prefer stable domain ids. Never use the item index as a key — an insertion at the head would
re-key every row, which turns a 1-row change into an every-row update (state *and* DOM).
