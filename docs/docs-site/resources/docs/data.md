# Data & offline

`kinetica-data` layers real-world data concerns over the core
[resource loop](/docs/resources): retries, optimistic mutations, pagination, and offline caches.

## Retry

```kotlin
val user = retry(RetryPolicy(attempts = 3, delayMillis = 250, backoffMultiplier = 2.0)) { attempt ->
    api.loadUser()
}

resource(UserKey) { api.loadUser() }.awaitWithRetry(RetryPolicy(attempts = 3))
```

`RetryPolicy` computes exponential backoff capped at `maxDelayMillis`; `shouldRetry` filters
which failures are worth retrying. Coroutine cancellation always rethrows immediately — a
cancelled retry loop never spins. `awaitWithRetry` invalidates the resource between attempts so
each retry re-runs the loader.

## Optimistic actions

```kotlin
val addTodo = optimisticAction(
    invalidates = { listOf(TodosKey) },
    optimistic = { title -> todos = todos + title },          // apply immediately
    rollback = { title, error -> todos = todos - title },     // undo on failure
) { title ->
    api.createTodo(title)
}

addTodo("Ship docs")    // UI updates instantly; rolls back + rethrows if the API fails
```

On success the declared resource keys invalidate, so canonical data replaces the optimistic
version through the normal resource loop.

## Pagination

```kotlin
val pager = paginator<Article>(key = "feed")

launchEffect {
    pager.loadNext { request -> api.articles(offset = request.offset, limit = request.limit) }
}

lazyEach(pager.asLazyItems(), key = { it.id }) { article -> ArticleRow(article) }
text(if (pager.canLoadMore) "Scroll for more" else "That's all")
```

`Paginator` tracks `items`, `isLoading`, `error`, `totalCount` and `canLoadMore`; a loader
returns `Page(items, next, totalCount)` and `isEnd` falls out of `next == null`.
`asLazyItems()` plugs straight into [`lazyEach`](/docs/lists-and-keys).

## Offline cache

```kotlin
val cache = InMemoryOfflineCache<String, Orders>()

// serve cache when present, otherwise fetch:
val fast = cache.load("orders", strategy = OfflineStrategy.CacheFirst) { api.orders() }

// prefer fresh, degrade to stale cache when offline:
val load = cache.load("orders", strategy = OfflineStrategy.NetworkFirst) { api.orders() }
if (load.stale) showOfflineBanner(load.failure)
```

`OfflineLoad` reports where the value came from (`source: Cache | Network`), whether it is
`stale` (network failed, cache served), and the underlying `failure`. Implement `OfflineCache`
over any storage; the in-memory one is the reference. Cancellation is never swallowed into a
stale-cache fallback.
