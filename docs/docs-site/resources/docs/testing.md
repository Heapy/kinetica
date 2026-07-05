# Testing

Because rendering produces a value, components test **headlessly** — no DOM, no browser, no
emulator. `kinetica-test` drives components the way a user would and asserts on trees.

## The harness

```kotlin
val root = KineticaTest.render {
    var count by state(key = "count") { 0 }
    column {
        text("Count: $count")
        button(
            onClick = event { count += 1 },
            semantics = Semantics(role = Role.Button, testTag = "increment"),
        ) { text("Increment") }
    }
}

root.click(hasTestTag("increment"))
root.assertHtmlSnapshot("""<column>Count: 1<button enabled="true">Increment</button></column>""")
```

Interactions dispatch through the real event pipeline: `click(matcher)`, `input(matcher, value)`,
`node(matcher).submit()`. Matchers compose: `hasTestTag("save") and hasRole(Role.Button)`,
`hasText("Count: 1")`, `hasLabel("Save document")`.

## Async: the suspend harness

```kotlin
val root = KineticaTest.renderSuspend {
    loadingBoundary(fallback = { text("Loading") }) {
        val value = resource(ProfileKey) { api.load() }.read()
        text(value)
    }
}

withTimeout(2_000) { root.awaitIdle() }     // drains effects and resource loads
root.node(hasText("Loaded"))                // now ready
root.advanceTimeBy(125)                     // virtual time for timers/animations
```

`awaitIdle()` settles launched effects, watches and resources; `advanceTimeBy` drives the
runtime's virtual clock — deterministic tests for [motion](/docs/motion) and timeouts, no real
sleeping.

## Snapshots

Two canonical serializations, both stable and diff-friendly:

```kotlin
root.treeSnapshot()      // pretty JSON of the Node tree
root.htmlSnapshot()      // the toSafeHtml() form
root.assertTreeSnapshot(expected)
root.assertHtmlSnapshot(expected)
```

## The journal in tests

Every test root records the full execution journal — assert on causality itself:

```kotlin
val commit = root.journal().last { it.kind == JournalKind.RenderCommitted }
assertEquals("cell write", commit.attributes["cause"])
```

## Browser-level verification

For renderer behavior that only exists in a real DOM (focus, patched-element identity, input
cursors), the repo runs Playwright against browser-executed self-tests
(`samples/browser-tests`, driven by `scripts/verify-browser.mjs`). Component logic belongs in
headless tests; browser tests cover the renderer contract.

```
./kotlin test -m my-module --platform jvm     # headless component tests
node scripts/verify-browser.mjs               # browser renderer verification
```
