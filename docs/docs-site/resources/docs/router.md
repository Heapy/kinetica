# Router & navigation

Navigation is core-adjacent by design: `BackStack` lives in the runtime, and the router battery
(`kinetica-router`) adds the host component, transitions, history and deep links.

## Routes and the back stack

```kotlin
@Serializable
sealed interface AppRoute : Route {
    @Serializable data object Home : AppRoute
    @Serializable data class Details(val id: String) : AppRoute
}

val stack = BackStack<AppRoute>(AppRoute.Home)

stack.push(AppRoute.Details("42"))
stack.pop()                        // false when only the root remains
stack.replaceAll(AppRoute.Home)    // reset
```

`BackStack` is itself a reactive cell over `List<Route>` — reading it in render subscribes, and
it enforces the invariant that it is never empty.

## NavHost

```kotlin
NavHost(stack, options = NavOptions(retainPreviousEntries = 1, restoreScroll = true)) { route ->
    when (route) {
        AppRoute.Home -> HomeScreen()
        is AppRoute.Details -> DetailsScreen(route.id)
    }
}
```

`NavHost` renders the current route and up to `retainPreviousEntries` previous entries (kept
mounted, marked retained — the entry you'd return to keeps its state). Dropped entries have
their non-persistent state disposed. Each entry knows its `NavEntry` — direction
(`Forward`/`Back`/`Replace`), transition, stack index — via `currentNavEntry()`.

Transitions annotate entries for renderers: `NavOptions(transition = NavTransition.slide(200))`
(`None`, `fade(ms)`, `slide(ms)`).

## Scroll restoration

```kotlin
val scroll = rememberNavScrollState(key = "feed")
navLazyEach(items, key = { it.id }, scrollKey = "feed") { item -> Row(item) }
```

Scroll state persists per navigation entry: navigate away and back, the window position is
restored (`restoreScroll = true`).

## System back

```kotlin
val dispatcher = InMemoryHostBackDispatcher()      // adapt to browser/Android back
val binding = stack.bindHostBack(dispatcher)       // back -> stack.pop()
dispatcher.dispatchBack()                          // true when handled
```

Handlers are dispatched newest-first; the first to return `true` wins — register your own
(`dispatcher.registerBackHandler { … }`) to intercept back before the stack pops (e.g. to close
a dialog).

## Serialization, history, deep links

```kotlin
val codec = jsonRouteCodec(AppRoute.serializer())   // any RouteCodec<R>

val history = InMemoryRouteHistory()                // adapt to browser History API
stack.writeToHistory(history, codec)                // push or replace
stack.restoreFromHistory(history, codec)

val link = codec.deepLink(AppRoute.Details("42"))   // encoded deep link
val route = codec.parseDeepLink(link.encoded)
```

Because routes are serializable values, the whole stack round-trips through history storage —
state restoration after process death is the same code path as browser history.
