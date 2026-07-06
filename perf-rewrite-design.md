# Kinetica renderer performance — rewrite design

Status: in progress · 2026-07-06 · **P0–P3 benchmark packaging landed and measured — geomean 15.2× → 1.25×, ahead of React in the latest full run; see §8.**
Evidence: `bench/results/results.json`, report `bench/report/index.html`
(full-run report: https://claude.ai/code/artifact/168ad7db-9368-40ca-8c62-66088e99811a,
earlier: https://claude.ai/code/artifact/6d768a3a-100d-476d-a5a9-989627f2d15d), CPU profiles below.

## 1. Summary

The js-framework-benchmark run puts Kinetica at a **15.2× geometric-mean slowdown** versus the
fastest framework per operation (React 1.35×, Preact/Vue 1.12×, Svelte/vanilla 1.06×), with
create-10k at **20.3s** — superlinear, not just slow. Profiling shows this is not "the DOM is
expensive": DOM calls are <5% of CPU. It is four specific framework defects, all fixable without
abandoning any spec decision (Node as serializable value, one UI loop, synchronous atomic commit,
journal/replay).

The plan is three phases:

- **P0 — runtime hygiene** (no architecture change): event registry becomes a map with eviction,
  debug attributes gated, focus restore made conditional. Removes the O(n²) and the leak.
- **P1 — retained renderer**: keep the mounted DOM, render → keyed diff → minimal patch, event
  delegation. This is the actual rewrite; partial operations become O(changed).
- **P2 — memoized subtrees**: unchanged `each` rows return reference-equal Nodes so diff skips
  them; plus allocation hygiene in Node construction.
- **P3 — packaging** (parallel track): post-link bundling/minification for release pages.

Target after P2: **geomean ≤ 2× the fastest** (react-class performance), partial ops at the paint
floor (~8–12ms), create-10k ≤ 500ms, no growth across renders.

## 2. Evidence

Median ms, 10 samples + 3 warmup, trusted click → last paint (Chrome trace), M4 Max, Chromium 149
headless, no throttling:

| Operation | Kinetica | React | Preact | Vue | Svelte | Vanilla |
|---|---|---|---|---|---|---|
| create 1,000 rows | 252 | 31.7 | 39.4 | 34.1 | 34.3 | 33.4 |
| replace 1,000 rows | 431 | 39.9 | 40.9 | 35.9 | 34.0 | 34.2 |
| partial update (every 10th) | 251 | 7.5 | 7.6 | 8.4 | 8.4 | 7.6 |
| select row | 61.6 | 7.9 | 8.2 | 7.9 | 7.7 | 7.7 |
| swap two rows | 61.6 | 33.6 | 6.6 | 8.4 | 7.7 | 9.0 |
| remove one row | 474 | 8.4 | 7.5 | 8.5 | 7.0 | 7.6 |
| create 10,000 rows | 20,269 | 315 | 260 | 232 | 208 | 202 |
| append 1,000 to 1,000 | 795 | 36.4 | 40.1 | 34.7 | 32.5 | 32.6 |
| clear 1,000 rows | 7.2 | 7.7 | 6.8 | 8.1 | 8.0 | 7.2 |
| **geomean slowdown** | **15.2×** | 1.35× | 1.12× | 1.12× | 1.06× | 1.06× |

Startup/weight: 241KB gz across **219 module files** (React 60KB, one file); TTI 50ms vs 15–26ms.
Memory after 1k rows: 9.1MB vs 4.3–4.4MB (React/Preact/Vue), 1.9MB vanilla.

CPU profile (CDP sampling, self time):

```
create 10,000 rows                          select row ×5 (1k table)
 59.9%  KineticaRuntime.registerEvent        66.7%  KineticaRuntime.registerEvent
  9.8%  (native) focus                       10.6%  registerHostEvent (HostDsl)
  7.4%  button() DSL (map allocs)             5.0%  (native) focus
  5.2%  registerHostEvent                     <2%   setAttribute/appendChild/removeChild
  <2%   setAttribute/appendChild/createElement
```

Two structural observations fall out of the numbers themselves:

- `clear` is already competitive (7.2ms) — teardown is fine; everything expensive is on the
  *rebuild* path.
- `replace` (431ms) and `remove` (474ms) cost **more** than `create` (252ms) even though they do
  the same amount of DOM work. Cost depends on how much history the runtime has accumulated —
  that's the growing event registry, not rendering.

## 3. Root causes

Ranked by measured impact.

### RC1 — Event registry: O(n²) scan + unbounded growth
`kinetica-runtime/src/KineticaRuntime.kt:159` — `registerEvent` reuses ids via
`eventIdentities.firstOrNull { it.identity == identity }`: a **linear scan of a plain list on
every registration**. Every render re-registers every handler (2 per bench row via
`HostDsl.kt:95,139` → `nextEventKey()`), so one render of n rows costs O(n·registry). Worse,
`eventIdentities` (`KineticaRuntime.kt:33`) and `events` (`:32`) are **never evicted**: rows
removed from the UI leave their registrations behind forever, so the registry — and every
subsequent render — grows for the lifetime of the app. This is 60–67% of all CPU in both
profiles, the whole 20s of create-10k, the replace>create anomaly, part of the 9.1MB heap, and a
genuine leak in any long-lived app.

### RC2 — Full DOM teardown + rebuild on every event
`kinetica-browser/src@js/BrowserKineticaApp.kt:83-88, 225-229` — `dispatchAndRender` calls
`render()`, which does `clearRoot()` and rebuilds every element from the fresh Node tree. There is
no keyed reconciliation on the client; `diffNodes` (`kinetica-runtime/src/Node.kt:61`) is used only
for server-component patches. Consequence: *select row* costs the same as rebuilding 1,000 rows.
This is why partial ops are 8–60× slower than everyone else, and it's also the root of the whole
focus-restoration machinery and its bug class (CODE_REVIEW C22/C30–C33).

### RC3 — Focus capture/restore forces querySelector + layout per event
`BrowserKineticaApp.kt:306+` — every dispatch captures `document.activeElement`, and after the
rebuild re-finds it via attribute selector and calls `.focus()` on the fresh tree — a forced
style/layout pass. ~5–10% of CPU (~200ms during create-10k). Entirely a symptom of RC2: retained
DOM keeps focus for free in the common case.

### RC4 — Per-node bookkeeping on the hot path
Every element gets `data-kinetica-tag` + `data-kinetica-path` (+ `data-kinetica-key`)
(`BrowserKineticaApp.kt:106-111`), with path strings joined per node and a fresh `path + index`
list allocated per child. Every `button()`/`host()` allocates props maps and stringifies values
per render (`HostDsl.kt:93`); event/slot keys build `"prefix/event-N"` strings per row
(`ComponentScope.kt:334-346`). Individually small; together the `button()` DSL alone is 7.4% of
create-10k. None of it is needed in production mode.

### RC5 — Toolchain packaging can dominate startup without a bundle step
The `js/app` preview product links an unminified multi-file ES-module graph — previously 223
files, about 1.03MB raw / 260KB gz including stdlib, coroutines, serialization — with no DCE or
minifier. The benchmark now mitigates this with a post-link esbuild production bundle: 1 file,
237KB raw / 75KB gz, and startup 20.7ms in the 2026-07-06 full run. Toolchain-level DCE is still
the upstream fix for library consumers; the benchmark no longer measures the unbundled preview
graph.

Secondary (same theme as CODE_REVIEW "threading model"): `registerEvent` takes `runtimeLock` per
call. On JS this is overhead without benefit; the spec says one UI loop — the runtime should
commit to it structurally rather than sprinkling locks.

## 4. Goals and non-goals

**Goals**
1. Partial operations (select/update/swap/remove) cost O(changed nodes), landing at the browser's
   paint floor (~8–12ms on the bench machine).
2. Create operations within ~1.5–2× React; create-10k linear, ≤ 500ms.
3. No cost growth with app lifetime; no event/heap leak; memory near React's.
4. Preserve spec decisions: `Node` stays a serializable value; one UI loop with synchronous
   atomic commit; journal/replay; debuggability and *linear causality* improve, not degrade —
   the patch itself becomes journalable data ("event → cell writes → re-render → these 3 DOM ops").
5. Public DSL unchanged: `samples/browser-bench/src/main.kt` must run unmodified.

**Non-goals**
- Fine-grained signal→DOM binding (Solid/Svelte style). Rejected below.
- Concurrent rendering / time slicing — explicitly cut in spec v2.
- Chasing vanilla-JS numbers; a value-tree framework has an honest floor (React's).
- SSR/hydration performance (separate track; the diff work feeds it but isn't gated on it).

## 5. Architecture decision

Three candidate architectures for the client renderer:

**A. Retained tree + keyed diff/patch (chosen).** Keep the render model exactly as specced —
events run, cells update, one synchronous render produces a fresh `Node` value — but the browser
keeps the previous tree mounted and applies a minimal patch computed by a keyed diff.
*Why it fits:* `Node`-as-value survives untouched (tests, previews, RSC wire, serialization);
the diff output is itself a value — journalable, assertable in tests, printable by devtools —
which strengthens the debuggability/AI-legibility pillars; and it's the smallest rewrite that
fixes RC2 (only the browser layer and `diffNodes` change).

**B. Fine-grained reactive bindings** (cells bound directly to DOM nodes, no tree). Fastest on
paper, but it deletes the framework's center of gravity: `Node` stops being the unit of truth,
journal/replay loses its "one commit = one tree" story, server components need a separate
renderer, and headless tests can no longer diff values. Rejected — conflicts with 4 of 5 spec
priorities.

**C. Keep rebuild, add aggressive memoization only.** Cheapest to build, but the DOM floor
remains O(visible tree) per event — select-row can never beat ~25–30ms on 1k rows, and focus
machinery stays. Rejected as the end state; its memoization half survives as P2.

Granularity stance: invalidation stays **root-per-runtime** for now (one render per dispatch);
the existing per-cell `renderDependencies` machinery already supports component-scoped
re-rendering later, but with A+P2 in place the full re-render of a memoized tree is cheap enough
that finer scheduling is a follow-up, not part of this rewrite.

## 6. Design

### 6.1 Event registry (P0)

Replace list+scan with an indexed registry with mark-and-sweep eviction per committed render:

```kotlin
private val eventsByIdentity = HashMap<Any, EventEntry>()   // identity -> (id, callback, epoch)
private val eventsById = HashMap<String, EventEntry>()
private var renderEpoch = 0
```

- `registerEvent(identity, cb)`: O(1) map hit → update callback, stamp `epoch = renderEpoch`,
  return existing id (ids stay render-stable, as the C3 fix established).
- `commitRender(...)`: `renderEpoch++`, then drop entries whose `epoch < renderEpoch - 1`
  (they were not re-registered by the render that just committed). Eviction is O(evicted)
  amortized using a generation ring rather than a full sweep.
- Identity keys today are `"prefix/event-N"` strings built per render (`ComponentScope.kt:342`);
  P2 interns them per key-scope so steady-state renders allocate zero key strings.
- Dispatch is unchanged: `events[eventId]` lookup, journal entry, invalidate, sync render.
- Threading: registration/dispatch assert the UI loop in debug instead of locking
  (`runtimeLock` leaves the per-event hot path), settling the CODE_REVIEW threading theme for
  the client the way the spec already decided: one loop.

Fixes RC1 (both the quadratic scan and the leak). Fully internal; no API change.

### 6.2 One keyed diff for everyone (P1)

Extend `diffNodes` (`Node.kt:61`) from positional/replace-only to a real reconciler, and make it
the single diff used by the DOM patcher *and* server-component patches:

- **Diff kinds**: `InsertChild`, `RemoveChild`, `MoveChild`, `ReplaceNode`, `UpdateProps`
  (added/removed/changed prop names only), `UpdateText`, `UpdateSemantics`.
- **Fast path**: `left === right` → skip subtree unconditionally. This is the contract that
  makes P2 memoization pay: a cached row Node short-circuits in O(1).
- **Keyed children**: when either child list contains keyed nodes, match by key
  (`HostNode.key` — `each` already sets it), compute moves via longest-increasing-subsequence
  (the inferno/Vue algorithm) so swap-rows is 2 moves, remove-row is 1 removal. Unkeyed lists
  diff positionally with head/tail trimming.
- **Same container, changed props** → `UpdateProps`, not `ReplaceNode` (today prop changes
  replace the whole subtree — correct since the C2 fix, but maximally expensive).
- Deep `left != right` data-class equality at every level is replaced by the recursion itself
  (compare own fields, recurse children) so a diff never re-walks a subtree twice.

The diff result is serializable — like `Node` — so it can go over the RSC wire, into the journal,
and into test assertions unchanged.

### 6.3 Browser patcher: mounted tree, delegation, focus (P1)

`BrowserKineticaApp` keeps a shadow tree alongside the DOM:

```kotlin
private class Mounted(var node: Node, val dom: DomNode, val children: MutableList<Mounted>)
```

`render()` becomes: produce new tree → `diff(mounted.node, newTree)` → apply patch ops to
`Mounted`/DOM in one pass → `mounted.node = newTree`. Apply rules:

- **Props**: `setAttribute`/`removeAttribute` for changed names only, through the existing
  sanitization allowlist. `value`, `checked`, `disabled` are set as **properties** (attribute
  patching breaks controlled inputs mid-typing); `value` writes are skipped when the live DOM
  already matches, preserving cursor position.
- **Events**: replace per-node `addEventListener` (`configureButton` etc.) with **root
  delegation** — one listener per event type on the app root; the listener walks from
  `event.target` to the nearest mounted node carrying `event:<type>` and dispatches its id.
  Kills 2×n listener registrations per build (and re-binding on patch becomes a no-op: ids are
  stable). Non-bubbling cases (focus/blur if we add them) get capture-phase listeners.
- **Focus**: capture/restore is deleted from the normal path — retained DOM keeps focus. It runs
  only when the patch actually replaced or moved the focused element's subtree (detectable during
  apply). Fixes RC3 and retires the C22/C30–C33 bug class instead of patching it.
- **Debug attributes**: `data-kinetica-path`/`-tag` are written only when `runtime.debug`;
  `data-testid` (semantics) always. Path strings — and the per-child `path + index` list
  allocations — leave the production path entirely (RC4). Test/tooling selectors keep working in
  debug mode, which is where tests run.
- **Journal**: in debug mode every applied patch op is recorded
  (`JournalKind.DomPatch`, op + path + key), completing the causal chain devtools can show:
  event id → cell writes → render cause → concrete DOM mutations. Replay of a journal now
  replays *patches*, which is strictly more legible than "the whole tree changed".

`awaitIdle()` keeps its loop but calls the same patch path; `clearRoot` survives only in
`dispose()`.

### 6.4 Memoized rows and allocation hygiene (P2)

After P1, partial ops are O(changed) in DOM work but each dispatch still *builds* the full Node
tree. Two measures bring Node construction down:

- **`each` caches per key scope.** `withKeyScope` already gives each row a persistent scope;
  store `(item, producedNode)` in it and, when the incoming item `==` the cached one and the
  scope's cells are clean, emit the cached Node — reference-equal, so the 6.2 fast path skips it.
  Opt-out flag for rows that read ambient context. The compiler plugin's `skippableNode` does the
  same for `@UiComponent`s (its cache-invalidation correctness was already fixed in review item
  #1); hand-written code gets it via `each` for free.
- **Hot-path allocations**: intern slot/event key strings per scope (no `"$prefix/$local"` per
  render), stop `toString()`-ing unchanged prop values (`enabled` in `HostDsl.kt:93`), pre-size
  children lists. Profile-guided — re-profile after P1 and attack what's left.

With both, a select-row dispatch builds ~1 fresh row Node + header, diffs in O(rows) reference
comparisons (cheap pointer walk), and patches 2 class attributes.

### 6.5 Packaging (P3, parallel)

- Add `scripts/bundle-js.mjs`: esbuild `bundle+minify` over the linkJs output
  (entry `build/tasks/_<module>_linkJs/<module>.mjs` → single file), wired as the "release page"
  path for samples and the benchmark. Expected: 219 files → 1, 943KB → ~250–350KB raw /
  ~70–90KB gz (esbuild treeshakes the ESM graph; real DCE still needs the toolchain).
- Track Kotlin Toolchain release-mode/DCE support as the upstream fix; revisit when `js/app`
  leaves preview.
- Re-measure TTI/weight in the bench report with the bundled variant, labeled separately, so the
  framework's own startup story is visible next to the packaging story.

## 7. Performance model and targets

Estimates from the profile shares (registerEvent 60–67%, focus 5–10%, DOM <5%); gate numbers are
what we verify with the harness, not promises:

| Operation | now | after P0 (est.) | after P1 (est.) | after P2 (gate) | React ref |
|---|---|---|---|---|---|
| create 1,000 | 252 | ~90–120 | ~60–80 | **≤ 60** | 32 |
| replace 1,000 | 431 | ~run1k | ~65–85 | **≤ 65** | 40 |
| partial update | 251 | ~80–100 | ~10–14 | **≤ 10** | 7.5 |
| select row | 62 | ~18–25 | ~9–12 | **≤ 9** | 7.9 |
| swap rows | 62 | ~18–25 | ~9–12 | **≤ 9** | 33.6 |
| remove row | 474 | ~run1k×0.9 | ~9–12 | **≤ 9** | 8.4 |
| create 10,000 | 20,269 | ~1,000–2,000 | ~400–600 | **≤ 500** | 315 |
| append 1,000 | 795 | ~150–200 | ~50–70 | **≤ 55** | 36 |
| clear 1,000 | 7 | 7 | ~5 | ≤ 7 | 7.7 |
| **geomean** | 15.2× | ~5–6× | ~1.6× | **≤ 2×** | 1.35× |
| heap after 1k | 9.1MB | ~6MB | — | **≤ 5MB** | 4.4MB |

P0 numbers stay far from React because RC2 remains; they exist to prove RC1's magnitude and ship
a leak fix independently of the rewrite.

## 8. Phased plan

Each phase ends with: `./kotlin build -m browser-bench && node bench/driver/bench.mjs
--frameworks=kinetica --out=bench/results/part-kinetica.json`, merge + regenerate report, compare
against the gate column, and `node scripts/verify-browser.mjs` (all existing browser samples must
stay green).

- **P0 — hygiene** (small, ships alone): registry map + epoch eviction (6.1); debug-gated
  attributes; skip focus restore when the active element is unaffected. Add a regression test:
  registry size constant across N replace-cycles.

  **Status 2026-07-05 — part 1 (map lookup, no eviction yet) landed.** `eventIdsByIdentity`
  HashMap replaced the list scan (`KineticaRuntime.kt:33,:159`); 104 JVM + browser JS tests green.
  Measured (median ms): create-1k 252→50.7, replace 431→49.7, partial update 251→45.4,
  select 61.6→52.1, swap 61.6→53.0, remove 474→51.6, **create-10k 20,269→406 (50×, linear
  again)**, append 795→95.1, clear unchanged. Geomean slowdown **15.2× → 3.19×** — better than
  the P0 estimate above. Every rebuild op now costs a uniform ~50ms (pure Node-build + DOM-rebuild),
  confirming RC2 as the sole remaining hot path. Still open in P0: identity eviction (the leak),
  debug-attribute gating, focus-restore gating.

  **Status 2026-07-05 — part 2 (eviction) landed.** Implemented as designed, with one refinement:
  liveness is owned by `ComponentScope`, not the runtime — the runtime keeps a plain
  `id → callback` store (`registerEvent`/`updateEvent`/`removeEvent`), while the scope tracks
  `identity → id` (`hostEventIds`) and sweeps entries not re-registered by the committing render
  (`evictUntouchedHostEvents`, mirroring the `touchedSlots` pattern); `dispose()` releases
  everything. This sidesteps the multi-scope epoch hazard flagged in §6.1 entirely. Regression
  test `hostEventRegistryEvictsHandlersThatStopRendering` covers eviction, no-growth on keyed
  replacement, stale-dispatch no-op, and dispose. 113 JVM tests + browser JS tests +
  `scripts/verify-browser.mjs` green. Benchmarks unchanged within noise (rebuild ops ~51–56ms,
  create-10k 405ms) — expected: part 1 already removed the scan cost; part 2 closes the leak.
  Still open in P0: debug-attribute gating, focus-restore gating.

  **Status 2026-07-05 — P0 complete.** `data-kinetica-tag`/`data-kinetica-path` now written only
  when `runtime.debug` (`data-kinetica-key` and `ClientRef` hydration attributes stay always);
  focus capture bails on `body`, restore skips when the element is already active and uses
  `focus({preventScroll: true})`. All samples/tests mount debug=true, so snapshots and the
  Playwright verification are unaffected (all green). Measured: create-1k 55.9→41.9ms,
  create-10k 405→359ms, append 95→85ms, partial ops ~54→~47ms. **Geomean 3.03×** (from 15.2×
  at baseline). Remaining gap is pure RC2 — every partial op still pays the ~45ms full rebuild;
  that is P1's job (target: single digits).
- **P1 — retained renderer** (the rewrite): keyed diff kinds + LIS reconciler in `Node.kt` with
  exhaustive unit tests (keyed move/insert/remove matrices, prop diffs, `===` fast path);
  `Mounted` tree + patch applier + root delegation + focus-on-replace-only in
  `kinetica-browser`; server-components switched to the same differ; journal `DomPatch` entries.
  Existing DOM snapshot tests remain valid (same final DOM); browser-tests extend with
  focus-across-patch and controlled-input-while-patching cases.

  **Status 2026-07-05 — P1 landed**, with three scoping deviations from the sketch above:
  (1) the patcher lives in `kinetica-browser` as a recursive one-pass diff-and-apply
  (`Mounted` shadow tree in `BrowserKineticaApp.kt`, LIS helper in `ListReconcile.kt`) rather
  than a serializable op list in `Node.kt` — server components keep the existing `diffNodes`;
  unification and journal `DomPatch` entries are deferred; (2) focus restore triggers on
  "previously-active element disconnected after patch", implemented, plus every render syncs
  controlled input values (DOM drift vs unchanged props); (3) debug `data-kinetica-path`
  attributes are refreshed by an O(n) debug-only walk after each patch so test snapshots stay
  exact. Root event delegation replaced all per-node listeners (one listener set per app root).
  Verified: browser JS tests incl. new LIS suite, all sample builds, `verify-browser.mjs` with
  two new identity regressions (patched button not recreated; keyed reorder moves the same
  elements) — 9/9 self-tests green.
  Measured (median ms): select 46→**13.1**, partial update 47→**19.1**, swap 48→**19.0** (now
  ahead of React's 33.6), remove 48→**18.8**, append 85→64; creates paid for the shadow tree —
  create-1k 42→51.7, create-10k 359→455, heap after 1k rows 9.2→10.4MB. **Geomean 3.03× →
  1.93×** — under the ≤2× P2 gate before P2 has run. Remaining partial-op cost is Node-tree
  rebuild + data-equality diff over unchanged rows — exactly what P2's memoization (`===` fast
  path) removes; P2 should also claw back the create-op regression via allocation hygiene.
- **P2 — memoization**: `each` per-key Node cache + compiler `skippableNode` on the client path;
  allocation hygiene from a fresh profile. Gate: the table above.

  **Status 2026-07-05 — P2 landed** (`ComponentScope.renderEach`): each row caches
  `(item, produced Nodes, cell deps + versions, context reads, host-event keys, cursor deltas)`
  per callsite; a hit re-emits the SAME Node references (P1's `===` fast path skips the
  subtree), re-touches the row's host-event keys so eviction spares its handlers, reserves the
  slot/event cursor positions so sibling rebuilds keep stable keys, and re-records deps so
  render subscriptions survive. Rows using effects, resources, boundaries, exit groups,
  `provide`, render-time writes, nested list constructs, or `skippableNode` hits are detected
  and rebuilt every render; `memoize = false` opts out for other ambient reads. The bench app
  switched selection to a per-row boolean cell (holder mutated only in handlers), so select
  touches exactly two rows' deps. Verified: 14 new runtime tests (identity, per-row
  invalidation, event survival incl. a mixed-eviction regression, slot-key stability under
  skips, context invalidation, nested each, external-cell subscription survival), full CI
  matrix, `verify-browser.mjs`.
  Measured (median ms): select 12.9→**7.0** (vanilla 7.0 — at the paint floor, ahead of React
  7.4), partial update 16.1→**9.0**, swap 18.1→**9.2**, remove 18.1→**9.1**, append 60.8→**52.0**;
  creates flat within noise (create-1k 50.9→53.5 ± 5.5, create-10k 461→474 ± 14), clear
  6.9→8.1 (pays one cache-eviction sweep). **Geomean 1.93× → 1.42×** — the ≤2× gate holds with
  margin; remaining gap is create-op Node construction and toolchain packaging (P3).

  **Status 2026-07-05 (later) — P2 allocation hygiene landed**, attacking the profile's largest
  JS self-time bucket (Kotlin stdlib map/hash work on the create path): array-backed `PropMap` +
  `propsOf()` for small prop sets (identity-fast key scan, full Map contract vs `mapOf`);
  `host()` stops copying props when there are no frame bindings; `button`/`textInput`/`checkbox`/
  `row` build props without hash maps; the key-scope prefix is maintained incrementally instead
  of list-copy + `joinToString` per read (5–6 reads per row per render); `renderEach` iterates
  the deduped key map directly; the browser mount path drops its intermediate filtered prop map.
  Measured on a FULL fresh 6-framework run (all parts re-benched back to back, median ms):
  create-1k 53.5→**43.5**, replace 53.6→**43.5**, create-10k 474→**301** (React 313 — Kinetica
  now ahead on create-10k), append 52.0→**45.8**; partials unchanged (select 7.8, swap 8.3,
  remove 8.9, update 9.9, clear 7.4). **Geomean 1.42× → 1.29× — statistically tied with React
  (1.29×)**; Preact/Vue 1.11×, Svelte 1.08×, vanilla 1.03×. Measurement caveat learned the hard
  way: on battery below ~10% macOS low-power throttling floors every headless-Chrome op at
  ~2 vsyncs (~30 ms) — React included — so benches are only valid on AC power (verified with a
  React canary before this run).
- **Status 2026-07-06 — P3 benchmark packaging and browser fast-paths landed.** The benchmark
  now runs Kinetica from a post-link esbuild bundle instead of the raw Kotlin Toolchain
  multi-file graph. Fresh full run: create-1k **38.4**, replace **37.5**, create-10k **314**
  (React 317), append **40.8**, partials at ~7-9ms, **geomean 1.25×** vs React 1.29×,
  Preact/Vue 1.09×, Svelte 1.05×, vanilla 1.02×. Startup is **20.7ms, 237KB raw / 75KB gzip /
  1 file**, down from 54.3ms, 1.03MB raw / 260KB gzip / 223 files.

Sequencing rationale: P0 is independent and de-risks the numbers; P1 is where the architecture
changes and wants the fattest test net; P2 only pays once P1's `===` fast path exists.

## 9. Risks and open questions

- **Controlled inputs under patching** — value/cursor preservation is the classic retained-mode
  bug source. Mitigation: property-based writes, skip-if-equal, explicit browser tests (typing
  during invalidation, IME).
- **Exit lifecycle (`onExit`) & motion**: spec keeps exiting subtrees alive; the patcher must
  treat `leaving` semantics as "retain + mark", not remove — needs a decided contract with
  `exitGroup` (currently the rebuild renderer sidesteps it by rebuilding everything).
  Open question for the spec owner.
- **`lazyEach`/virtualization** interacts with keyed moves (windowed keys enter/leave
  constantly). Diff handles it as inserts/removes at window edges; verify with a lazyEach bench
  case before calling P1 done.
- **Hydration/`data-kinetica-path`**: server-component client refs use path attributes; P1 keeps
  paths in debug and for `ClientRef` islands only. Verify `samples/server-components` end-to-end.
- **Duplicate keys** currently soft-dedupe (`keyedLastWins`) in production; keyed reconciliation
  makes duplicates genuinely ambiguous. Decision: keep debug-mode error, document last-wins, and
  make the reconciler tolerate (never crash on) duplicates.
- **Estimate risk**: P1/P2 targets assume nothing else superlinear hides behind RC1/RC2. The
  phase gates + re-profiling are the control; if a new hotspot appears, it gets its own RC entry
  rather than silently missing the gate.

## 10. What this buys beyond the benchmark

- The event-registry leak fix and focus-machinery removal delete two active bug classes
  (CODE_REVIEW #3 follow-ups, C22/C30–C33).
- One diff implementation for client DOM, RSC patches, and test assertions — less code than
  today's two paths.
- The journal gains DOM-patch entries: the devtools story ("what exactly did this event change,
  and why") becomes stronger than the rebuild renderer could ever offer, which is the project's
  stated priority order — debuggability first, speed as its consequence.
