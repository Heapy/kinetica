# Resources & boundaries

The data-loading loop is **resource → read → invalidate**: declare an async source, read it like
a value, invalidate it to refetch. Loading and failure are handled structurally by boundaries,
not by hand-rolled `isLoading` flags.

## Declaring and reading

```kotlin
data object ProfileKey : ResourceKey

val profile = resource(ProfileKey, scope = CacheScope.App) { key ->
    api.loadProfile()
}

loadingBoundary(fallback = { text("Loading profile…") }) {
    val value = profile.read()      // value of type Profile, or suspends this subtree
    text(value.name)
}
```

`read()` returns the cached value when ready. While loading it *suspends the subtree*: the
nearest `loadingBoundary` shows its fallback (or retains the previous content —
`retainPrevious = true` is the default, so refreshes don't flash). A failed load re-throws into
the nearest `errorBoundary`. `await()` is the suspend variant for use inside effects.

## Cache scopes

| `CacheScope` | Lifetime |
|--------------|----------|
| `App` | shared per runtime, optional TTL (`appResourceTtlMillis`) |
| `Component` | dropped when the last reading component unmounts |
| `Request` | per server render request |

## Invalidation

```kotlin
profile.invalidate()              // this resource
invalidate(ProfileKey)            // by key, from anywhere
invalidate { it is TodosKey }     // by predicate
```

Invalidation drops the cache and re-renders readers; the loader re-runs. Cancellation is safe: a
cancelled load never poisons the cache as a permanent failure.

## Actions

Mutations pair with invalidation declaratively:

```kotlin
val rename = action(invalidates = { listOf(ProfileKey) }) { newName: String ->
    api.rename(newName)
}
// later: rename("Ada")  — on success, ProfileKey readers refetch
```

The [data battery](/docs/data) adds `optimisticAction` (apply → rollback on failure) and retry
policies on top of this loop.

## Boundaries

```kotlin
errorBoundary(
    fallback = { error, info, retry ->
        text("Something failed: ${error.message}")
        button(onClick = event { retry.retry() }) { text("Try again") }
    },
) {
    loadingBoundary(fallback = { text("Loading…") }) {
        Dashboard()
    }
}
```

- `errorBoundary` catches errors thrown while rendering its content, discards the partial
  subtree, and renders the fallback. `retry.retry()` clears the captured error and re-renders.
  Control-flow signals (a pending resource, coroutine cancellation) pass through untouched.
- `loadingBoundary` catches pending resources. Boundaries nest arbitrarily; the nearest one wins.
- `suspendSubtree(fallback) { … }` renders a subtree from `suspend` code — the streaming
  building block behind [server components](/docs/server-components).
