# Soundness suite findings (2026-07-07)

135 test cases distilled from the test suites of Inferno, React, Svelte 5, SolidJS, Preact and
Vue 3 (~850 raw cases surveyed, deduplicated and mapped to Kinetica's plugin-only /
frame-ordinals / template-cloning model — see `soundness-test-plan.md` for the full case list
and batch layout). All 14 batch files are implemented and green in CI-equivalent runs:
**126 tests active and passing, 9 `@Ignore`d** documenting the defects below.

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

## Deferred (needs missing infrastructure)

See `soundness-test-plan.md` § "Deferred / not implementable now": SVG/namespaces (no
`createElementNS` support at all), real focus/selection preservation, IME/composition,
infinite-loop guards (would hang the Node runner), real-browser-only behaviors (Playwright).

## Suite layout

| Module | Files | Tests | Run |
|---|---|---|---|
| kinetica-browser (test@js) | 9 | 104 (9 ignored) | `./kotlin build -m kinetica-browser && node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs` |
| kinetica-runtime (test) | 2 | 20 | `./kotlin test -m kinetica-runtime --platform jvm` + JS bundle |
| kinetica-test (test) | 3 | 30 | `./kotlin test -m kinetica-test --platform jvm` + JS bundle |

Provenance: every test's KDoc cites its KSND id and source cases (INF/RCT/SVL/SOL/PRE/VUE ids
map to `soundness-test-plan.md`, which traces back to concrete framework test files and GitHub
issues).
