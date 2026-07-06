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

## Row memoization

`each` also **memoizes each row's output**. When an item is `==` to the previous render's, every
cell the row read reports an unchanged version, and every context value it read is unchanged,
the row re-emits the *same* `Node` references — and the renderer skips the whole subtree with one
identity comparison. This is why a partial update over 1,000 rows builds only the changed rows'
nodes and runs at the paint floor (see [performance](/docs/performance)).

The contract: row content must be a pure function of the item, the cells it reads, and the
contexts it reads. Rows that use effects, resources, boundaries, exit groups, `provide`, or
nested list constructs are detected automatically and rebuilt every render. Rows that read
other ambient state during render (time, randomness, mutable singletons) must opt out:

```kotlin
each(rows, key = { it.id }, memoize = false) { row -> … }
```

Two practical consequences:

- **Prefer immutable items.** Memoization compares items with `==`; mutating an item in place
  makes it compare equal to its old self, so the row won't rebuild.
- **Keep per-row reactive reads per-row.** A row that reads a list-wide cell (`selected == row.id`
  against one shared cell) invalidates *every* row on each write; a per-row `state` cell keeps a
  selection change down to the two affected rows.

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
var scroll by state { LazyListState(firstVisibleIndex = 0, visibleCount = 40) }

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
