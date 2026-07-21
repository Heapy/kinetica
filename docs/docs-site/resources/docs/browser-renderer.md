# Browser renderer

<!-- code: kinetica-browser/src@js/BrowserKineticaApp.kt (mountKineticaApp, render) -->

`kinetica-browser` is a **retained-mode DOM renderer**. The first render mounts the tree; every
subsequent render diffs the fresh `Node` value against a mounted shadow tree and applies the
minimal patch. Rendering a select-row change in a 1,000-row table costs two attribute writes,
not a rebuild.

```kotlin
mountKineticaApp("#app", runtime = KineticaRuntime(debug = false)) {
    App()
}
```

## What a patch does

<!-- code: kinetica-browser/src@js/BrowserKineticaApp.kt (patch, applyProp, patchKeyedChildren), kinetica-render-core/src/ListReconcile.kt (longestIncreasingSubsequenceIndices) -->

- **Reference-equal subtrees are skipped in O(1).** If a subtree's `Node` is the *same object*
  as last render's (memoized [`each` rows](/docs/lists-and-keys), `skippableNode`), the patch
  doesn't descend into it at all.
- **Props** diff per name: attributes set/removed through the sanitizing allowlist; `value` and
  `checked` applied as DOM properties (controlled inputs keep cursor and selection —
  a re-render only writes `input.value` when it actually differs); the `enabled` prop maps to
  the DOM `disabled` attribute.
- **Text** updates write the text node in place (`textContent`, or `nodeValue` on the
  single-text and template fast paths) — no element churn.
- **Keyed children** reconcile by key: common prefix/suffix patch in place, the middle uses a
  longest-increasing-subsequence plan so reorders become the minimum set of element *moves*.
  Unkeyed lists patch positionally.
- **Semantics** changes reapply the node's semantics attributes (previous set removed, next set
  written).

## Event delegation

<!-- code: kinetica-browser/src@js/BrowserKineticaApp.kt (handleDelegatedEvent, dispatchAndRender, DelegatedEventTypes) -->

Elements carry **no listeners**. One listener set on the app root handles `click`, `input`,
`change` and `keydown`, resolves the nearest node with a handler, and dispatches into the
runtime. Ten thousand rows register zero listeners, and re-renders never rebind anything.

Every dispatch is atomic: native event → handler runs → cells update → one synchronous patch
commits before the frame paints.

## Focus

<!-- code: kinetica-browser/src@js/BrowserKineticaApp.kt (BrowserFocus) -->

Because the DOM is retained, focus and text selection survive re-renders naturally. The renderer
restores focus only when a patch actually replaced the focused element's subtree — and does so
with `preventScroll`, so background updates never scroll the page.

## Debug mode

<!-- code: kinetica-runtime/src/KineticaRuntime.kt (debug, record), kinetica-browser/src@js/BrowserKineticaApp.kt (mountHost, refreshGeneratedAttributes) -->

With `debug = true` (the default for `mountKineticaApp`), elements carry `data-kinetica-tag` /
`data-kinetica-path` attributes for tooling, duplicate `each` keys throw, and the runtime
journals every event and patch cause. Production mounts skip all of it.

## Testing hooks

<!-- code: kinetica-browser/src@js/BrowserKineticaApp.kt (BrowserKineticaApp, BrowserUiSnapshot) -->

`BrowserKineticaApp` exposes `innerHtml()`, `tree()`, `snapshot()` (DOM + tree HTML + tree
JSON), `elementByTestTag`, `clickTestTag`, `inputTestTag`, and `awaitIdle()` for async
settling — the browser-level complement to the [headless harness](/docs/testing).

## History

<!-- code: bench/results/results.json -->

The renderer became retained-mode in the July 2026 performance rewrite — previously every event
rebuilt the whole DOM. The numbers and the root-cause story are in
[Performance](/docs/performance).
