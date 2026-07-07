# Soundness suite findings (2026-07-07)

135 test cases distilled from the test suites of Inferno, React, Svelte 5, SolidJS, Preact and
Vue 3 (~850 raw cases surveyed, deduplicated and mapped to Kinetica's plugin-only /
frame-ordinals / template-cloning model). **126 cases active and passing on JVM+JS, 9
`@Ignore`d** documenting the defects below. Every test's KDoc carries its `KSND-nnn` id, source
ids and scenario; the full synthesis plan that produced them (batch specs, per-case scenario
tables) served its purpose and lives in git history (`soundness-test-plan.md`).

## Confirmed framework bugs

### 1. Mixed static+keyed child lists degrade to positional diffing (7 cases, one root cause)

`KSND-025 026 027 029 030` (`BrowserKeyedNeighborsTest`), `KSND-109 114` (`BrowserChildShapeTest`)

`shouldReconcileKeyed` (kinetica-browser/src@js/BrowserKineticaApp.kt:714-727) takes the keyed
path only when **every** child of the parent carries a unique key. One unkeyed static sibling —
or a conditional branch that renders nothing (no placeholder node) — forces strictly
index-aligned `patchPositionalChildren`. Any length change in the keyed/conditional region then
shifts subsequent siblings into tag mismatches, and `replace()` recreates them. HTML output
stays correct; node identity, focus, and any DOM-held state are lost, and per-frame work is
wasted. `renderEachRegion` (kinetica-runtime/src/ComponentScope.kt:676-695) flattens rows into
the parent with no region boundary, so the browser side has nothing to anchor a region diff on.

Fix direction: per-`each`-region boundary markers plus placeholder nodes for empty conditional
branches (frame ordinals can supply stable slot identity for both). Not yet tracked anywhere;
existing backlogs only defer a "unified keyed reconciler" for fully-keyed lists.

### 2. Identity short-circuit skips controlled-input resync on memoized rows

`KSND-065` (`BrowserControlledInputTest`)

`if (mounted.currentNode === next) return mounted` (BrowserKineticaApp.kt:351-353) bypasses
`patchHost`, whose drift resync (BrowserKineticaApp.kt:422-438) is the only place `.checked` /
`.value` are corrected — and memoized `each` rows return identical node instances on a cache
hit. A user-toggled checkbox in a memoized surviving row silently keeps its drifted DOM state,
violating the renderer's own "every committed render syncs it back" comment.

Fix direction: exclude controlled-input hosts from the identity skip, or run a dedicated
resync pass over mounted controlled inputs per commit.

## Feature gap (by design today)

- `KSND-032`: multi-root keyed rows. `FragmentNode` carries no reconcile key
  (kinetica-runtime/src/Node.kt:104-112), so a row with two roots flattens to unkeyed siblings
  and patches positionally instead of moving atomically. Keyed multi-root containers would be a
  new feature; test kept `@Ignore`d as an executable spec.

## Expected-behavior clarifications (tests rewritten to certify the real contract)

- `KSND-115/116/117` (`BatchOrderingTest`): Kinetica's designed semantics is one synchronous,
  glitch-free propagation wave **per source write** (Cell.kt PropagationWave), plus one derived
  refresh per render commit — not once-per-event batching (a premise imported from batched
  frameworks). The docs/state.md contract ("exactly one synchronous render commits") holds in
  all three scenarios. A `batch {}` transaction would be a new feature.
- `KSND-093/094` (`EffectCleanupOrderingTest`): the two "missed cleanup / nondeterministic
  order" failures were test-harness races (unsynchronized shared log appended from concurrent
  `Dispatchers.Default` finalizers). Framework disposal is sound: key-addressed dispose walk +
  `effectScope.cancel()` guarantee every cleanup runs; cancel *initiation* is deterministic
  depth-first, *completion* order is scheduler-dependent by design.
- `KSND-042`: raw host keys `Int 1` vs `String "1"` do NOT collide — no stringified-key
  identity bug.
- `KSND-043/074/091/095`: keyed and boundary frames **deactivate** (retaining non-transient
  state) rather than dispose when their key/content leaves; return re-activates with retained
  state. Differs from React/Inferno remount semantics — certified as intended behavior.
- `KSND-135`: a `null` prop-hole removes the attribute while a `null` key-hole restores the
  skeleton default — divergence is annotated in the test; flagged as a design-review candidate.

## Deferred backlog (needs missing infrastructure)

What + why + the infra that would unlock it:

1. **Real focus & selection preservation across patches** — TestDom has no
   focus()/selectionStart. Unlock: Playwright self-test in samples/browser-tests
   (focus + `setSelectionRange`, assert activeElement identity + offsets across keyed
   insert/remove/reorder), or teach TestDom focus/selection emulation.
2. **SVG / MathML namespaces** — zero `createElementNS`/namespace code in kinetica-browser.
   Unlock: renderer namespace propagation (svg context flag, foreignObject switch-back) + DSL.
3. **select / option / radio / textarea controlled semantics** — DSL has no such elements.
   Unlock: DSL + browser mapping (+ TestDom option modeling).
4. **IME / composition sessions** — needs a real browser input pipeline (Playwright).
5. **Style-object semantics** — no style API beyond flex mapping; props are plain strings.
   Unlock: style DSL + patcher.
6. **Capture phase, mouseenter/leave synthesis, non-bubbling emulation, once/passive** —
   delegation supports click/input/change/keydown-Enter only. Unlock: event-type expansion in
   DelegatedEventTypes + a capture-semantics decision.
7. **Infinite-update-loop guard** — no depth guard; a divergent effect hangs awaitIdle/the Node
   runner. Unlock: scheduler depth limit + diagnostic (then port Svelte's throw-once-stay-usable
   case).
8. **Client-side hydration path** — BrowserKineticaApp's hydrate path has no test@js harness.
   Unlock: server-HTML fixture + hydrate entrypoint callable under TestDom.
9. **contenteditable / external DOM mutation tolerance** — needs an opt-out contract for
   externally-mutated managed text (Inferno-style child-diff opt-out) in compiler/renderer.
10. **FLIP/animate geometry on keyed moves** — needs layout metrics (real browser) +
    kinetica-motion integration.
11. **Form reset / defaultValue semantics** — no defaultValue concept in the DSL; TestDom has
    no form.reset(). Unlock: DSL decision first.
12. **wasmJs / android / macosArm64 execution of the common batches** — targets build but never
    run in CI. Unlock: CI runners (wasmJs test task + Node wasm runtime).
13. **10k-iteration shuffle stress + perf guards** — the Node single-process runner has no
    timeout isolation (the suite carries a bounded 100-round fuzz, KSND-012). Unlock: a
    dedicated stress lane (separate script, not kotlin-test).

## Dropped as not applicable (do not revisit)

- VDOM implementation details (vnode flags/cloning/normalization, `$stable` slots,
  mergeProps/splitProps, lazy prop getters, children-helper flattening).
- React-hooks/legacy-lifecycle-specific semantics (gDSFP/gSBU/cWRP ordering,
  setState-in-constructor, functional-updater merge, forceUpdate, useImperativeHandle,
  callback-ref cleanup protocol).
- Suspense/transitions/SuspenseList internals; outro transitions.
- Hydration/SSR internals (covered by RuntimeSmokeServerTest + the Playwright flow).
- Portals (no portal API in Kinetica).
- Vue emits/props casting/warnings, deep-watch options and reactive-proxy semantics (cells are
  value-typed, no proxies); observable interop.
- Compiler contracts already gated by kinetica-compiler suites (keyed-list detection, template
  eligibility, children-attr precedence; event names are ids in Kinetica).
- Already-covered Kinetica ground (derived/scheduler adversarial JVM suite, each
  memoization/keyed-flag certification, resource staleness, template text patch, escaping,
  one-render-per-dispatch, dispose-blocks-revival, retry slot collision, LIS plan shape,
  nested keyed scratch reorders, data-kinetica-key emission, detach-before-bulk-skip).

## Authoring pitfalls (for future additions to the suite)

- `./kotlin publish mavenLocal -m kinetica-compiler` first; republishing the SAME version does
  not invalidate consumers — `touch` a source in the module under test afterwards.
- Slot-consuming bodies go in top-level `private @UiComponent fun ComponentScope.X(...)`
  (`skippable = false` when asserting render counts); slots in multi-run lambdas
  (`List(n){}`, `repeat`, `map`) fail fast — use `each`/`keyed{}`.
- Probes are objects passed as component parameters, never top-level mutable vars; shared logs
  appended from effect finalizers must be thread-safe (Dispatchers.Default races).
- test@js: `installTestDocument()` is the first statement; `try { } finally { app.dispose() }`;
  monkeypatch `Element.prototype` only after install; store writes need an explicit
  `app.render()` (no store→render subscription in the browser app).
- The bare Node runner does NOT await `runTest` promises — async common tests interleave: salt
  ResourceRegistry keys per test, keep collaborators per-test; run `node bundle.mjs` without
  piping when checking exit codes.

## Suite layout

| Module | Files | KSND cases | @Test fns | Run |
|---|---|---|---|---|
| kinetica-browser (test@js) | 9 | 85 (9 ignored) | 104 | `./kotlin build -m kinetica-browser && node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs` |
| kinetica-runtime (test) | 2 | 20 | 20 | `./kotlin test -m kinetica-runtime --platform jvm` + JS bundle |
| kinetica-test (test) | 3 | 30 | 30 | `./kotlin test -m kinetica-test --platform jvm` + JS bundle |

Provenance: every test's KDoc cites its KSND id and source-case ids (INF/RCT/SVL/SOL/PRE/VUE);
the synthesis plan in git history traces those ids back to concrete framework test files and
GitHub issue numbers, surveyed from the checkouts in `projects/`.
