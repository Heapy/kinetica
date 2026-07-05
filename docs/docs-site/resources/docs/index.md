# Kinetica

Kinetica is a Kotlin UI framework built around one idea: **the UI is a value**. Every render
produces an immutable, serializable `Node` tree. Because the tree is a value, you get things most
frameworks bolt on afterwards, for free and by construction: headless testing, HTML server
rendering, streaming server components, an execution journal with time-travel replay, and a
retained DOM renderer that patches by diffing values.

It is "React without the historical constraints": one UI loop, synchronous atomic commits, a
single reactive cell primitive, explicit effects, and linear causality you can read back from the
journal — no fibers, no lanes, no concurrent rendering.

```kotlin
fun ComponentScope.Counter() {
    var count by state(key = "count") { 0 }
    val label by derived { "Clicked $count times" }

    column {
        text(label)
        button(onClick = event { count += 1 }) {
            text("Increment")
        }
    }
}

fun main() {
    mountKineticaApp("#app") { Counter() }
}
```

## Design pillars

- **`Node` is a serializable value.** Trees can be asserted in tests, snapshotted, sent over the
  wire as server components, and diffed for minimal DOM patches.
- **One UI loop, atomic commit.** An event runs, cells update, one render commits synchronously.
  No tearing, no partially applied states, no scheduling heuristics to debug.
- **One reactive primitive.** `state`, `derived`, `store`, contexts and resources are all cells
  with dependency tracking. Effects are explicit (`watch`, `launchEffect`) — no auto-tracking
  surprises.
- **Linear causality.** The runtime journals every event, cell write, render cause and effect.
  `replay()` reconstructs state at any point. Debuggability outranks features.
- **Batteries included.** Router, forms, motion, data/offline, persistence, theming, markdown and
  a test harness are first-party modules with the same conventions.

## Where things run

| Target | Module | What you get |
|--------|--------|--------------|
| Browser | `kinetica-browser` | Retained-mode DOM renderer: keyed diffing, event delegation, focus preservation |
| JVM server | `kinetica-runtime` | Safe HTML rendering, hydration plans, streamed server components, typed server actions |
| Headless | `kinetica-test` | Render, click, input and snapshot components without any DOM |

## Reading order

Start with [Getting started](/docs/getting-started), then the Core section in order:
[State & reactivity](/docs/state), [UI DSL](/docs/ui-dsl), [Lists & keys](/docs/lists-and-keys),
[Effects](/docs/effects), [Resources](/docs/resources). The Batteries section covers the
first-party modules. If you want to know how the renderer stays fast, read
[Browser renderer](/docs/browser-renderer) and [Performance](/docs/performance).

> This site is dogfood: every page is Markdown parsed by `kinetica-markdown`, rendered to a
> Kinetica `Node` tree on the JVM, and serialized with the same `toSafeHtml()` that powers server
> components. The interactive widgets are Kinetica browser apps mounted into the page.
