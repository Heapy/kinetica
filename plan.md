# Kinetica plan — KNT tickets

Renderer-perf, soundness and spec backlog. The K5/K6 review-fix stream (KNT-0001–0022 plus the
review-surfaced KNT-0032) fully landed 2026-07-07 via the codex-TDD pipeline, squashed into
`39692ea` (per-ticket commits preserved on `frame-ordinals-pre-rebase-backup`); the ticket
bodies, statuses, review-round history and verification evidence live in this file's git
history. Ticket numbering continues from KNT-0039.

Retired planning docs (folded into this file 2026-07-07; full text in git history):

- `CODE_REVIEW.md` — reactive-core review, all fifteen findings (R01–R15) fixed & verified.
  Its still-open cleanup triad is now KNT-0038; its single-writer/reentrancy contract note
  moved to the docs (`docs/docs-site/resources/docs/state.md`, Events section).
- `compiler-perf-design.md` — compiler perf phases: K0–K4 landed, K5 rejected on profile
  evidence (evidence folded into KNT-0024), K6 (template cloning) later landed as part of the
  plugin-only/frame-ordinals model (documented in docs/compiler-plugin.md).
- `soundness-findings.md` — KSND suite findings: confirmed bugs → KNT-0033/KNT-0034, feature
  gap → KNT-0035, design review → KNT-0036, deferred coverage → KNT-0037; certified contracts,
  the do-not-revisit list and authoring pitfalls kept below.

## Context

**Perf stream.** Status 2026-07-07: P0–P4 landed (registry eviction, retained renderer with
keyed LIS diff + event delegation, each-row memoization, allocation hygiene, benchmark
packaging, frame ordinals). Re-benched on Chrome 130 at default sampling (10 samples, vanilla
drift vs stored parts ≤2% median, so cross-part comparison holds): 13-op geomean **0.969×
vs React** — ahead of React overall for the first time (was 15.2× before the rewrite, 1.27×
after P3, 1.20× before frame ordinals). swap1k 0.36×, swap10k 0.78×, replace1k and create10k
0.90×; still above React: remove10k 1.48×, update10th10k 1.26× (KNT-0024); select10k 1.09×
is essentially the paint floor. Weight 85KB gz, startup median 24.5ms. The full perf design,
root-cause analysis and phase history live in the git history of `perf-rewrite-design.md`
(up to commit `7cfde69`). Post-review-stream spot-check (3 samples): per-op medians
flat-to-better vs the stored parts (remove10k 53.9ms vs 59.8ms recorded).

**Compiler stream.** K0–K4 landed 2026-07-06: IR-extension architecture (source rewriting was
a dead end — the K2/JS pipeline never invokes `ProcessSourcesBeforeCompilingExtension`),
semantic stability inference + `skippableNode` wrapping, const-`propsOf` interning + static
LEAF-host hoisting, plugin applied to bench-jvm/browser-bench, and runtime-certified
`CHILDREN_KEYED` flags (the "keyed proof" moved from compiler to `each`, which has the
knowledge at O(1)). K5 (static each-safety proof) was REJECTED on CPU-profile evidence: 10k
partial-op cost is browser style/layout/paint, not capture-time detection (details in
KNT-0024). K6 (template cloning) later landed as part of the plugin-only/frame-ordinals
model — see docs/compiler-plugin.md "IR passes". Still leaf-only: whole-subtree hoisting of
static host trees with children was never done (template extraction covers only the
single-dynamic-text shape) — tracked as a KNT-0031 candidate, not worth its own ticket while
create10k sits at 0.90× React. Phase details and measurements: git history of
`compiler-perf-design.md`.

**Soundness suite.** 135 test cases distilled from the test suites of Inferno, React, Svelte 5,
SolidJS, Preact and Vue 3 (~850 raw cases surveyed, deduplicated and mapped to Kinetica's
plugin-only / frame-ordinals / template-cloning model). **134 cases active and passing on
JVM+JS, 1 `@Ignore`d** — the last ignored case is the executable spec of KNT-0035 (`KSND-032`,
multi-root keyed rows); KNT-0033 (7 cases) and KNT-0034 (1) landed 2026-07-07. Every test's
KDoc carries its `KSND-nnn` id, source-case ids
(INF/RCT/SVL/SOL/PRE/VUE) and scenario; the synthesis plan that produced them (batch specs,
per-case scenario tables, traceability back to concrete framework test files and GitHub
issues, surveyed from the checkouts in `projects/`) lives in git history
(`soundness-test-plan.md`).

| Module | Files | KSND cases | @Test fns | Run |
|---|---|---|---|---|
| kinetica-browser (test@js) | 9 | 85 (1 ignored) | 111 | `./kotlin build -m kinetica-browser && node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs` |
| kinetica-runtime (test) | 2 | 20 | 20 | `./kotlin test -m kinetica-runtime --platform jvm` + JS bundle |
| kinetica-test (test) | 3 | 30 | 30 | `./kotlin test -m kinetica-test --platform jvm` + JS bundle |

Contracts the suite **certified as intended behavior** (tests were rewritten to assert these;
keep them that way):

- `KSND-115/116/117` (`BatchOrderingTest`): the designed semantics is one synchronous,
  glitch-free propagation wave **per source write** (Cell.kt `PropagationWave`), plus one
  derived refresh per render commit — not once-per-event batching (a premise imported from
  batched frameworks). The docs/state.md contract ("exactly one synchronous render commits")
  holds in all three scenarios. A `batch {}` transaction would be a new feature.
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
- `KSND-135`: `null` prop-hole vs `null` key-hole behave differently — divergence annotated in
  the test; design review tracked as KNT-0036.

Surveyed and **dropped as not applicable — do not revisit**: VDOM implementation details
(vnode flags/cloning/normalization, `$stable` slots, mergeProps/splitProps, lazy prop getters,
children-helper flattening); React-hooks/legacy-lifecycle semantics (gDSFP/gSBU/cWRP ordering,
setState-in-constructor, functional-updater merge, forceUpdate, useImperativeHandle,
callback-ref cleanup protocol); Suspense/transitions/SuspenseList internals; outro transitions;
hydration/SSR internals (covered by RuntimeSmokeServerTest + the Playwright flow); portals (no
portal API in Kinetica); Vue emits/props casting/warnings, deep-watch options and
reactive-proxy semantics (cells are value-typed, no proxies); observable interop; compiler
contracts already gated by kinetica-compiler suites (keyed-list detection, template
eligibility, children-attr precedence; event names are ids in Kinetica); already-covered
Kinetica ground (derived/scheduler adversarial JVM suite, each memoization/keyed-flag
certification, resource staleness, template text patch, escaping, one-render-per-dispatch,
dispose-blocks-revival, retry slot collision, LIS plan shape, nested keyed scratch reorders,
data-kinetica-key emission, detach-before-bulk-skip).

## Open backlog

### KNT-0024 (was perf §2) — 10k-table partial operations
- The last remaining bench gap, narrowed by frame ordinals: remove-10k 59.8ms (1.48× React) and update-every-10th-10k 43.3ms (1.26×) are still above React, while select-10k (8.7ms, 1.09×) and swap-10k (42.1ms, 0.78×) have effectively closed. Partial ops on 10k rows pay the O(rows) reference-walk diff and LIS over large child arrays even when 2 rows changed. Candidates: dirty-row short lists from cell subscriptions instead of full-tree walks, LIS skip when moves are adjacent transpositions.
- Prior profile evidence (2026-07-06, PRE-frame-ordinals — numbers stale, method and shape hold): CDP-profiling update10th10k on the unminified bundle showed the app-JS slice is ~4ms of a ~50ms op — the bulk is browser style/layout/paint. Slice split: ~0.7ms `emitCachedEachRow` (row-cache HIT replay over unchanged rows), ~0.7ms `patchText` (legitimate DOM writes), ~1.1ms `hashCode` (`touchedHostEvents` inserts + row-map lookups, ~20k string hashes per render — frame ordinals have since replaced key strings, so this term should have died), `patchKeyedChildren` ~0.26ms (the K4 flags leave the diff nearly free).
- Start: RE-PROFILE first (same CDP method; 4× CPU throttle if deltas sit near noise) and confirm where the remaining remove10k/update10th10k gap actually lives before picking a lever. K5 (static each-safety proof) was rejected precisely because it attacked a cost the profile showed to be ~zero.

### KNT-0025 (was perf §3) — Unify the two diffs; serializable patch ops; journal `DomPatch`
- Deferred from P1: the browser patcher is a recursive diff-and-apply in `kinetica-browser` (`BrowserKineticaApp.kt` + `ListReconcile.kt`), while server components still use the old positional `diffNodes` (`Node.kt`). The design wants one keyed reconciler emitting a serializable op list used by both, with debug-mode `JournalKind.DomPatch` entries completing the causal chain (event → cell writes → render → concrete DOM ops). This is a debuggability feature as much as a perf one — the project's stated priority order.
- Related: KNT-0004 (landed) normalizes TemplateNodes via `materializeDeep` at the `diffNodes` entry and every wire boundary — the unified reconciler must keep that boundary.
- Design together with KNT-0033: per-`each`-region boundaries belong in the unified reconciler's node/op model; building regions into today's browser patcher and then again into the unified one would be double work.

### KNT-0026 (was perf §4) — Component-scoped re-rendering
**Status:** Deferred — not urgent while memoized full renders stay ~single-digit ms on 1k-row apps.
- Invalidation is still root-per-runtime: one dispatch → one full render (cheap now thanks to memoization, but O(app) not O(component)). The per-cell `renderDependencies` machinery already exists to scope re-renders to the components that read a written cell. Revisit if real apps show render-cost growth with app size.

### KNT-0027 (was perf §5) — Toolchain DCE / release mode
**Status:** Blocked upstream.
- The benchmark and docs pages run from post-link esbuild bundles, but library consumers of the `js/app` preview product still get the unminified multi-file ES-module graph (~1.6MB raw) with no DCE. Track Kotlin Toolchain release-mode/DCE support; revisit packaging when `js/app` leaves preview.

### KNT-0028 (was perf §6) — exitGroup/motion contract with the retained patcher
**Status:** Open — spec decision needed.
- Spec keeps exiting subtrees alive (`leaving` semantics); the patcher must treat them as "retain + mark", never remove. The rebuild renderer sidestepped this; the retained one has no decided contract yet. Needs a decision plus browser tests for exit transitions under patching (motion battery interplay). Related: KNT-0001 (landed) made `asLeaving` strip `CHILDREN_SINGLE_TEXT` and the single-text fast path self-defending — the leaving-subtree wrap now patches correctly, but the retain-vs-remove contract is still undecided.

### KNT-0029 (was perf §7) — lazyEach/virtualization vs keyed moves
- Windowed rendering makes keys enter/leave constantly at window edges; the diff should handle it as edge inserts/removes, but this was never verified with a lazyEach bench case. Add one before relying on virtualization.

### KNT-0030 (was perf §8) — Controlled inputs: IME
- Property-based writes, skip-if-equal value sync, and typing-during-invalidation tests landed with P1. IME composition (the classic retained-mode input bug) remains untested — add a browser-tests case with composition events.
- Soundness confirmation (was KSND deferred item 4): TestDom has no composition-event pipeline, so this must be a real-browser Playwright case in `samples/browser-tests` (compositionstart/update/end with IME-style intermediate values), not a TestDom case.

### KNT-0039 (soundness review-fix stream) — region-diff + controlled-input bugs found reviewing KNT-0033/0034
**Status: Landed 2026-07-07 on `mem-opt-experiments` (`284c290`, `795d799`, `ffede32`, `5baf1f9`).** A max-effort review of the branch surfaced 7 correctness bugs in the "landed" region-diff (KNT-0033) and controlled-input (KNT-0034) work; all reproduced test-first via codex, fixed, and verified (runtime JVM 208/0, kinetica-test 52/0, kinetica-compiler 70/0, full browser JS suite green). An 8th finding (regions dropped by `@EncodeDefault(NEVER)`) was **refuted** — that mode omits only the empty default, so non-empty regions serialize fine.
- **C1 — region ordinal collision (`ffede32`):** `ChildRegion.ordinal` used the compiler numbering-region-LOCAL each ordinal, so two flat `each` regions from different scopes (sibling non-skippable components, or `keyed{}` blocks) flattening into one parent host both recorded ordinal 0 → `patchRegionedChildren` `oldRegionByOrdinal` collision → orphaned rows / DOM corruption. Fix: `ChildRegion.ordinal` is now a parent-host-scoped id from a monotonic per-`ComponentScope` counter, cached per `(enclosing Frame, compiler ordinal)` in `Frame.childRegionOrdinals` — unique across sibling scopes AND stable across renders (the frame ordinal still keys the keyed row frame). Also fixes a shift: omitting a conditional MIDDLE sibling region no longer renumbers trailing regions. Tests: `RegionOrdinalCollisionTest`, `RegionConditionalMiddleRegionIdentityTest`, `BrowserRegionCompositionTest`.
- **C2 — non-keyed region full teardown (`795d799`):** `patchRegionRange` sent a non-all-keyed each region to `replaceRange` (full unmount+remount every render, losing focus/state on unchanged rows). Now delegates the non-keyed fallback to positional-in-place `patchStaticRange`. Test: `BrowserNonKeyedRegionTest`.
- **C3 — static remount on region toggle (`ffede32`):** static gaps keyed by `(before,after)` region ordinals → a persistent static remounted when an adjacent `each` was structurally omitted. Gaps now matched by gap index; `StaticGapKey` removed. Test: `BrowserRegionToggleTest`.
- **C4 — interior block-replace (`795d799`):** `patchStaticRange` block-replaced its trimmed mismatched middle, remounting a positionally-stable same-type interior child between type-changing neighbours. New `patchPositionalRange` patches the overlapping middle index-aligned. Test: `BrowserPositionalInteriorTest`.
- **C5 — nested controlled-input not resynced (`5baf1f9`):** the KNT-0034 fix only resynced a controlled input that IS the row's root node; one nested in a memoized wrapper was skipped by the `patch()` identity short-circuit. `Mounted` now carries a `containsControlledInputHost` flag (computed at mount, refreshed per child-diff); the short-circuit descends when set. Test: `BrowserNestedControlledInputTest`.
- **C6a/C6b — region threading gaps (`284c290`):** `lazyEachRegion` never recorded a `ChildRegion`, and `button` dropped `lastCollectedRegions` — both lost region-aware reconciliation for mixed static+keyed content. Now record/thread regions like `each`/`host`. Tests: `LazyEachRegionTest`, `ButtonRegionThreadingTest`.
- **Residual (open):** `fragment` and multi-root `FragmentNode` roots still drop regions (no `regions` field on `FragmentNode` — overlaps KNT-0035); the server-side `diffNodes` still does not consume regions (KNT-0033 follow-up, folds into KNT-0025). No red test written for these.

### KNT-0031 — Memory gate met (LANDED, detail in git history)
After-1k heap 5.7MB → **2.96MB** (≈1.07× React) via allocation-count reduction (~73 → ~33 retained JS objects/row across mem-opt A–I); whole-subtree hoisting not needed.

### KNT-0033 — Mixed static+keyed child lists (LANDED, detail in git history)
Runtime-declared region spans (`HostNode.regions`); browser `patchRegionedChildren` reconciles by them. Correctness bugs found reviewing this work → KNT-0039. Residual: server-side `diffNodes` still does not consume regions (KNT-0025 follow-up).

### KNT-0033b — Startup/heap regression (LANDED, detail in git history)
The "startup regressed" claim was a cross-session measurement artifact; real cost was per-row allocation count, fixed via mem-opt A–I. Reusable finding: atomicfu's plugin cannot elide on JS in this toolchain (its `isJs()` guard rejects the combined js+wasmJs `TargetPlatform`) → purpose-built `KineticaAtomicUnwrapLowering` gated on `!isJvm() && !isNative()`. `performance.md`'s Kinetica column left untouched pending this branch's merge review.

### KNT-0034 — Controlled-input resync on memoized rows (LANDED, detail in git history)
`patch()` identity fast-path guarded with a controlled-input check so memoized `textInput`/`checkbox` rows resync. The nested-under-a-memoized-wrapper gap found later → KNT-0039 C5.

### KNT-0035 (soundness feature gap) — Keyed multi-root rows (`FragmentNode` reconcile key)
**Status:** Open — spec decision needed first.
- `FragmentNode` carries no reconcile key (kinetica-runtime/src/Node.kt:104-112), so an `each` row with two root hosts flattens to unkeyed siblings and patches positionally instead of moving atomically. Keyed multi-root containers would be a NEW FEATURE (runtime node model + browser reconciler + compiler shape flags), not a bug fix.
- Decide: does the spec promise atomic moves for multi-root rows (→ keyed fragment containers), or is "one root per `each` row" the authoring contract (→ compiler diagnostic on multi-root row bodies)?
- Executable spec: `KSND-032` stays `@Ignore`d until the decision; on the "one root" outcome, replace it with a diagnostic test and close.

### KNT-0036 (soundness design review) — `null` prop-hole vs key-hole asymmetry
- `KSND-135` (active, passing — certifies CURRENT behavior): a `null` prop-hole removes the attribute, while a `null` key-hole restores the template-skeleton default. The divergence is annotated in the test.
- Decide: unify (null always removes, or always restores) or certify the asymmetry as intended in docs/ui-dsl.md; then update the KSND-135 annotation to point at the decision. Template-hole semantics live in the K6 template path (KineticaIrTemplate.kt + the browser clone-and-fill fast path).

### KNT-0037 — Soundness coverage expansion (each item blocked on missing infrastructure or a design decision)
Deferred KSND areas: what + why it is blocked + the unlock that would open it. Unblock in any order.
1. **Real focus & selection preservation across patches** — TestDom has no focus()/selectionStart. Unlock: Playwright self-test in samples/browser-tests (focus + `setSelectionRange`, assert activeElement identity + offsets across keyed insert/remove/reorder), or teach TestDom focus/selection emulation. Natural follow-up to KNT-0033.
2. **SVG / MathML namespaces** — zero `createElementNS`/namespace code in kinetica-browser. Unlock: renderer namespace propagation (svg context flag, foreignObject switch-back) + DSL.
3. **select / option / radio / textarea controlled semantics** — DSL has no such elements. Unlock: DSL + browser mapping (+ TestDom option modeling).
4. **IME / composition sessions** — tracked as KNT-0030.
5. **Style-object semantics** — no style API beyond flex mapping; props are plain strings. Unlock: style DSL + patcher.
6. **Capture phase, mouseenter/leave synthesis, non-bubbling emulation, once/passive** — delegation supports click/input/change/keydown-Enter only. Unlock: event-type expansion in DelegatedEventTypes + a capture-semantics decision.
7. **Infinite-update-loop guard** — no depth guard; a divergent effect hangs awaitIdle/the Node runner. Unlock: scheduler depth limit + diagnostic (then port Svelte's throw-once-stay-usable case). Same unguarded-reentrancy gap as noted in docs/state.md (Events).
8. **Client-side hydration path** — BrowserKineticaApp's hydrate path has no test@js harness. Unlock: server-HTML fixture + hydrate entrypoint callable under TestDom.
9. **contenteditable / external DOM mutation tolerance** — needs an opt-out contract for externally-mutated managed text (Inferno-style child-diff opt-out) in compiler/renderer.
10. **FLIP/animate geometry on keyed moves** — needs layout metrics (real browser) + kinetica-motion integration.
11. **Form reset / defaultValue semantics** — no defaultValue concept in the DSL; TestDom has no form.reset(). Unlock: DSL decision first.
12. **wasmJs / android / macosArm64 execution of the common batches** — targets build but never run in CI. Unlock: CI runners (wasmJs test task + Node wasm runtime).
13. **10k-iteration shuffle stress + perf guards** — the Node single-process runner has no timeout isolation (the suite carries a bounded 100-round fuzz, KSND-012). Unlock: a dedicated stress lane (separate script, not kotlin-test).

### KNT-0038 — Cell.kt API hygiene triad (LANDED, detail in git history)
Shared `ListenerRegistration` holder (fixes double-dispose of two identical lambdas); `update()`/`setAtomic()` deduped into `commitAtomic`. Side-finding: kinetica-runtime's macosArm64 **test** compile is pre-existingly broken (native test source set is not a friend module) — refines KNT-0037 §12.

## Order of work

1. The backlog is unscheduled. Per-ticket starting points: KNT-0024 → re-profile, KNT-0028/KNT-0035 → spec decision, KNT-0036 → design decision. (KNT-0031/0033/0033b/0034/0038/0039 landed on `mem-opt-experiments`, pending merge review — see the "Landed" one-liners above; full detail in git history.)
2. The compiler plugin is MANDATORY for every module: any pass touching it starts with `./kotlin publish mavenLocal -m kinetica-compiler && ./kotlin test -m kinetica-compiler --platform jvm` before building dependents — publish FIRST: the plugin resolves from the toolchain-local repo and even the compiler's own test fragment (via kinetica-runtime) needs the published artifact (mirrors ci.yml:26-30).
3. Each pass: build + module tests before moving on.

## Soundness authoring pitfalls (for future additions to the KSND suite)

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

## Verification

- Full sweeps: `./kotlin test -m kinetica-runtime --platform jvm`, `./kotlin test -m kinetica-test --platform jvm`, `./kotlin test -m kinetica-compiler --platform jvm`, kinetica-browser build + `node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs`, the kinetica-runtime/kinetica-test JS bundles the same way, both annotated samples (JVM + JS). The KSND soundness cases ride these same runs (suite layout table in Context).
- Perf-backlog changes (KNT-0024+): `node bench/run.mjs --suites=main --frameworks=kinetica`, then `node scripts/verify-browser.mjs` from the repo root — the script lives in root `scripts/`, not `bench/` (15 self-tests must stay green). Locally the verification script needs what ci.yml:99-103 provides: a static server first (`node -e 'import("./bench/driver/server.mjs").then(m => m.startServer(process.cwd(), 4173))' &`) and `PLAYWRIGHT_IMPORT=.tools/playwright/node_modules/playwright/index.mjs`, plus built browser-tests/counter/todo samples.
- Size check: `node scripts/size-report.mjs` within baseline tolerance.
