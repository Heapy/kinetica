# Kinetica plan — KNT tickets

Current native macOS capabilities, measured limitations and next milestones are recorded in
[the macOS status](docs/native-macos-status.md). Older native ticket descriptions below retain
their historical implementation context.

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
| kinetica-browser (test@js) | 9 | 85 (1 ignored) | 111 | `./kotlin build -v release -m kinetica-browser && node build/artifacts/CompiledWebArtifact/kinetica-browserjsTestrelease/kotlin-output/kinetica-browser_test.mjs` |
| kinetica-runtime (test) | 2 | 20 | 30 (10 non-KSND, ordinal-soundness F7/keyed-overlap probes incl. memoized/key-migration/empty-batch review additions) | `./kotlin test -m kinetica-runtime --platform jvm` + JS bundle |
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

### KNT-0040 — Browser render scheduler: async invalidations never flush themselves
**Status:** Open.
- Outside the synchronous DOM-event path, a cell write only sets `hasPendingInvalidation`; nothing in `kinetica-browser` ever flushes it. Every browser app hand-rolls a pump today: Game of Life threads a `requestRender` callback into its watch loop (`samples/browser-game-of-life/src/main.kt`), docs-client pumps `awaitIdle` after events plus a 100ms interval for `effect-timer` and a rAF loop for `motion-toggle` (`docs/docs-client/src/main.kt`). This is the spec's "one UI loop" decision left unimplemented on the browser side.
- Shape: `BrowserKineticaApp` owns the loop — an invalidation while quiescent arms a rAF (or microtask) flush that batches every write since the last frame into one render; the synchronous event path stays as-is for input latency. Then delete the per-app pumps and the docs-client special cases.
- Companion to KNT-0026 (component-scoped re-rendering): the scheduler decides WHEN to flush, KNT-0026 narrows WHAT.

### KNT-0042 — kinetica-runtime tests do not compile for macosArm64 (toolchain friend-paths gap)
**Status:** Open (upstream toolchain limitation).
- `./kotlin test --platform macosArm64 -m kinetica-runtime` fails: the Native test compilation cannot access the module's own internals (`frameSlot`, `Frame`, `platformLock`, …) — friend-paths are not passed to Native test fragments by Kotlin Toolchain 0.11, while JVM/JS/wasmJs test fragments see internals normally.
- Consequence: the CI `macos` job runs every macosArm64 module except kinetica-runtime; its code is exercised natively only through dependents. Revisit when the toolchain fixes Native friend visibility (check each toolchain upgrade).

### KNT-0043 — Native flake: exitGroupAbandonmentCleansRetainedEffectExactlyOnce
**Status:** Open.
- Failed once on macosArm64 (53 ms, right after a clean rebuild), passed on 4 consecutive reruns; never seen on JVM. Suspect timing of exit-group abandonment vs. `Dispatchers.Default` scheduling on Native. If it recurs in the CI `macos` job, capture the assertion output (the test currently reports only pass/fail) and harden like KSND-099's `waitForLog` diagnostics.

### KNT-0041 — Frame-binding driver in kinetica-browser (motion without recomposition)
**Status:** Open.
- The renderer deliberately drops `frame:` props (no-ops in `BrowserKineticaApp.kt`, private-name list in `BrowserMapping.kt`), so `frameProps`/`FrameValue` — the model motion.md documents ("a running animation never re-renders components") — has no browser consumer. The docs `motion-toggle` example works around it: a watch loop advances the clock and commits every frame into ordinary state, re-rendering per frame.
- Shape: on mount, resolve `frame:<property>` bindings to their `FrameValue` by id, subscribe via `observe`, and patch the mapped style property on the host element directly — no recomposition; one renderer-owned rAF clock calls `advanceBy` on running `AnimatedFloat`s and writes styles. Afterwards the motion example drops its manual driver and the motion.md caveat ("the browser renderer does not consume frame bindings yet") goes away.
- Related: KNT-0028 (retain-and-mark contract is where exit animations meet the patcher), KNT-0037 §10 (FLIP geometry needs real layout metrics).

### KNT-0044 — Native renderers: AppKit (Phase 1 landed), GTK (Phase 3)
**Status:** Phase 1 landed (macOS AppKit POC), reviewed 2026-07-21 (2 fixes in-tree: dispatcher
binding leak, contentView pinning). Close-out → KNT-0045; Phase 2 (scoped native-only) →
KNT-0046; Phase 3 (preconditions verified) → KNT-0047.
- `kinetica-appkit` is a `kmp/lib` (`platforms: [macosArm64]`) that mounts the immutable `Node` tree into AppKit views (`AppKitKineticaApp.kt`). `samples/native-counter` (`product: macos/app`, restricted to `[macosArm64]`) boots `NSApplication` + a window and renders the same `CounterApp()` component as `samples/browser-counter`, proving value-tree portability across the DOM and AppKit backends.
- **Phase 1 simplification:** `patch()` does a full teardown/rebuild on each invalidation. State lives in cells, not views, so this is correct — just cosmetically blunt. No incremental diffing yet.
- **Event loop:** `AppKitEventDispatcher` (a single `NSObject` target shared by every actionable `NSControl`) maps the sender back to its `event:<name>` id, calls `runtime.dispatch(eventId)`, then `renderUntilSettled()` drains the synchronous invalidation loop. This mirrors `BrowserKineticaApp`'s event-delegation + immediate-flush path. Background-thread cell writes (effects on `Dispatchers.Default`) are NOT marshalled to the main thread yet — only main-thread (click) invalidations re-render. Add a main-thread marshal (`dispatch_async(main)`) keyed off `hasPendingInvalidation` polling before relying on async effects.
- **Compiler-plugin gotcha (important):** the K2 plugin's `transformEntryPoints` only stages call-site ordinals when the `@UiComponent`-typed content is passed to a **top-level function** whose parameter type carries the `@UiComponent` annotation in its IR metadata. Calling `AppKitKineticaApp(...)` as a constructor directly throws `MissingKineticaPluginException` at runtime because the constructor-call path isn't transformed the same way. The workaround is `renderAppKitApp(...)` (top-level, mirrors `mountKineticaApp`) — always go through it. Worth investigating whether the plugin's `wrapAnnotatedContentArgumentsOf` can be extended to constructor calls.
- **Obj-C interop idioms discovered (not documented elsewhere in the codebase):** AppKit `BOOL`-backed properties (`isEditable`, `isBordered`, `isEnabled`, `drawsBackground`) are exposed by Kotlin/Native as explicit `setX()` setters, not Kotlin `var`s — except `translatesAutoresizingMaskIntoConstraints`, which is a top-level extension property requiring `import platform.AppKit.translatesAutoresizingMaskIntoConstraints`. The `NS_ENUM` constants (`NSBezelStyleRounded`, `NSSwitchButton`) are top-level `val`s, NOT nested enum cases. `@ObjCAction` lives in `kotlinx.cinterop`. `companion object` fields are forbidden on `NSObject` subclasses.
- **No cinterop needed for macOS:** the Kotlin/Native toolchain ships pre-built klibs for all Apple system frameworks (`~/.konan/.../klib/platform/macos_arm64/org.jetbrains.kotlin.native.platform.{AppKit,Foundation,Cocoa,…}` — 177 frameworks). `import platform.AppKit.NSWindow` works with zero `.def` files.
- **`macos/app` product gotcha:** the product type defaults to compiling for BOTH `macosArm64` and `macosX64`; if your deps only declare `macosArm64`, override with `product: { type: macos/app, platforms: [macosArm64] }`.
- **Toolchain note (resolves the earlier "Amper lacks cinterop" worry):** Kotlin Toolchain 0.11.0 — the version pinned by the `./kotlin` wrapper — supports `cinterop` natively. Drop a `.def` file into a module's `cinterop/` dir (platform-scoped via `cinterop@linux/`) and the toolchain runs cinterop automatically. No Gradle, no plugin, no `module.yaml` changes. Phase 3 (GTK) uses this directly.

**Phase 2 — reusable reconciliation:** extract the keyed-LIS / regioned / positional / template-cloning logic from `BrowserKineticaApp.kt` into a backend-agnostic `HostAdapter` interface (the reconciliation already operates on `Node` trees, not the DOM). `BrowserKineticaApp`, `AppKitKineticaApp`, and the future GTK renderer plug their adapter in — paying back the Phase-1 teardown/rebuild simplification and giving all backends focus management + keyed reconciliation for free.
*Scoping decision 2026-07-21 (→ KNT-0046): the shared core targets NATIVE backends only; the tuned browser path (monomorphic `when`-helpers, pooled scratch, flag gates — 0.969× geomean) stays as-is. Browser unification remains KNT-0025's call, revisited bench-gated after GTK proves the adapter. Template cloning and event delegation are DOM-only idioms and are not part of the shared core.*

**Phase 3 — Linux/GTK:** new `kinetica-gtk` module (`platforms: [linuxX64]`) with `cinterop/gtk4.def`:
```
headers = gtk/gtk.h
package = gtk4
# DISPROVEN 2026-07-21: $(pkg-config ...) substitution does NOT work in .def files —
# def-file "substitution" is target-suffix resolution only, no shell exec (verified against
# the K/N 2.4.10 DefFile parser + all Amper 0.11 jars). Flag strategies → KNT-0047.
```
Tag mapping: `column`/`row` → `GtkBox`, `button` → `GtkButton` (signal `clicked`), `text` → `GtkLabel`, `textInput` → `GtkEntry`, `checkbox` → `GtkCheckButton`. Main loop `g_application_run`. New Ubuntu CI job: `apt-get install -y libgtk-4-dev && ./kotlin build -m <gtk-sample>`. Alternatively depend on [gtk-kn](https://gtk-kn.org/) (maintained GTK4 `.klib`s) instead of a hand-written `.def` — now the preferred first option (KNT-0047).

**Qt/KDE — ruled out:** cinterop binds C headers only; Qt is C++ (moc/templates/signals-slots). No maintained Kotlin/Native Qt binding exists. The only path is a C shim (e.g. [libqt6c](https://github.com/rcalixte/libqt6c)) — high-effort, bespoke.

### KNT-0045 (review of KNT-0044 Phase 1) — Runtime invalidation listener + AppKit close-out
**Status:** Landed 2026-07-21 on `main` (`6e56612` runtime hook + 5 tests, `ddeb493` AppKit marshal/close-out). Manual GUI acceptance still pending (CI has no display). The textInput warn-once shipped and was then removed the same day by KNT-0046's full wiring.
- **Runtime hook (additive):** `KineticaRuntime` gains an invalidation-listener API, fired from `invalidate()` (`KineticaRuntime.kt:191-196`) **outside** `runtimeLock` — cell `observe` listeners run synchronously on the writing thread (Cell.kt NOTIFY phase), so no lock-held callbacks. This is the shared "one UI loop" primitive: the AppKit marshal below AND the KNT-0040 browser scheduler both consume it — today NOTHING self-schedules (browser drains only on events or explicit `awaitIdle` pumps; every browser app hand-rolls one).
- **AppKit main-thread marshal:** subscribe in `AppKitKineticaApp`; coalesce N invalidations into one hop (atomic "hop scheduled" guard) → `dispatch_async(main)` → `renderUntilSettled()`. Needed because native effects run on `Dispatchers.Default.limitedParallelism(1)` (`src@macosArm64/EffectDispatcher.kt`) — cell writes land off-main; today only the click path re-renders (KNT-0044 known limitation).
- **textInput honesty until KNT-0046:** `makeTextField` mounts an editable field but wires no `event:onInput`/`onSubmit`; warn-once at mount + doc note. (Full wiring needs incremental diffing — a rebuild-per-keystroke would destroy focus and in-progress text.)
- Cosmetics: dup "mounted shadow tree" banner + stacked KDoc on `renderAppKitApp` (review findings #4/#5).
- Manual GUI acceptance (window fill, resize without constraint spew, a11y identifiers) — CI smoke-builds only, no display.
- **Related:** KNT-0040 (browser twin of the marshal, same hook), KNT-0044.

### KNT-0046 (KNT-0044 Phase 2, scoped) — `kinetica-render-core`: shared native reconciler
**Status:** Landed 2026-07-21 on `main` (`8ea1516` module + ListReconcile move + docs code-links, `6d59220` Reconciler + 11 mock-adapter cases, `e585b4c` AppKit port + textInput wiring + sample row). Guardrails at landing: runtime JVM 213/0, render-core JVM 15/0 + JS bundle exit 0, browser JS KSND bundle exit 0, macosArm64 lane 7 modules green, size-report within baseline. Bench spot-check post-move (main suite, 10 samples, same-session): per-op medians flat-to-better vs the recorded parts — remove10k 45.5ms (53.9–59.8 recorded), update10th10k 42.6 (43.3), select10k 8.1, swap10k 40.4; startup 20.6ms, 86KB gz — no regression from the byte-identical ListReconcile move. Deferred inside the ticket: regioned pass (KNT-0025), scratch pooling / identity short-circuit (perf posture note in Reconciler.kt).
- New module `kinetica-render-core`: `kmp/lib`, `platforms: [jvm, android, js, wasmJs, macosArm64]` (js required — kinetica-browser will depend on it; linuxX64 arrives with KNT-0047), `apply` the common template, `- ../kinetica-runtime: exported`; register in `project.yaml`.
- **Move `ListReconcile.kt`** (pure IntArray LIS, already common) from `kinetica-browser/src/` into the core; browser gains the dep — the only sharing direction, browser is `platforms: [js]`. Update docs code-links `docs/docs-site/resources/docs/lists-and-keys.md` + `browser-renderer.md` (verify-docs gates), run bench + size gates (same 65 lines — expected neutral).
- Core content: `HostAdapter<V>` (createHost/createText/setText/setProp/removeProp/insert/move/remove/clearChildren + capabilities: leaf-widget text-folding set, controlled-state resync, focus capture/restore), generic `Mounted<V>` shadow tree, `patchChildren` = head/tail converge + keyed-LIS middle + positional fallback. Input = `materializeDeep()`-normalized trees (templates are a DOM-only clone idiom). Regioned/segmented pass deferred: regions belong in the KNT-0025 unified op-model — don't build them twice (KNT-0025/KNT-0033 note).
- Tests: common `test/` with a mock adapter, run on JVM+JS lanes (KNT-0042: native test fragments can't see internals; native coverage = sample smoke-builds). Cases: reorder/insert/remove/key-collision/text-update/converge-scans.
- Port AppKit onto the core: `AppKitHostAdapter` from the existing factories; per-node event rebinding replaces the whole-dispatcher `reset()`; controlled resync = `stringValue` re-push; focus = firstResponder preservation via testTag/path (mirror `BrowserFocus`). Then wire textInput fully: `controlTextDidChange` → `dispatch(eventId, payload)` (dispatcher gains a payload path beside argless `clicked:`), onSubmit via field action; `native-counter` grows a textInput row.
- **Related:** KNT-0025 (browser+server unification is the end-state; this core is a step, not a replacement — browser stays on its tuned path per the 2026-07-21 scoping decision), KNT-0035 (multi-root rows patch positionally — same certified behavior in the core), KNT-0044, KNT-0045.

### KNT-0047 (KNT-0044 Phase 3, preconditions verified) — `kinetica-gtk` (linuxX64)
**Status:** Landed 2026-07-21 on `main` — **`linux` CI job fully green** (run 29823733287 @ `1e6e560`: cinterop, both compiles, `.kexe` link against system GTK4, render-core tests EXECUTED on a native linuxX64 host; whole run green incl. required). Base commits `ba34c2d`/`06d0101` + 5 CI-iteration fixes (`a278cd0`, `07768d1`, `eeac6c2`, `f693532`, `48714fe`→`1e6e560`). Spike verdict: gtk-kn REJECTED (0.0.3-SNAPSHOT only, Kotlin 2.1.0, requires its Gradle plugin — incompatible with the Amper toolchain); flags come from `generate-def.sh` (shell-expanded pkg-config, gitignored def).
- **CI-iteration pitfalls (reusable for any system-lib cinterop on modern distros):** (1) glibc 2.38+ headers need `-D__glibc_clang_prereq(maj,min)=0` for the indexer; (2) the indexer mixes cross-sysroot glibc 2.19 headers with system 2.39 → pin with `-I/usr/include`; (3) cinterop generated `GtkOrientation` as a Kotlin enum class, not top-level constants; (4) a trailing safe-call types a `staticCFunction` body `Unit?` → K/N backend ICE ("doesn't correspond to any C type") — use if-style bodies; (5) ld.lld doesn't search `/usr/lib/x86_64-linux-gnu` → add `-L`; (6) system .so's reference GLIBC_2.34+ versioned symbols vs sysroot 2.19 → `--allow-shlib-undefined` (resolves at runtime; fine for a same-host POC binary, NOT for distributables).
- Local note: kinetica-gtk is registered in project.yaml but compiles only where GTK headers exist — on macOS `./kotlin build` without `-m` fails on it; use module-scoped builds (CI jobs already are).
- GUI acceptance on a real Linux desktop still pending (CI has no display); manual run mirrors the AppKit checklist.
- **Verified offline:** `linux/app` product type exists (Amper `ProductType` enum, `LINUX_X64`/`LINUX_ARM64` platforms); `cinterop/` auto-discovery + `cinterop@linux/` scoping confirmed; `$(pkg-config ...)` in `.def` **disproven** (see KNT-0044 Phase 3 note). Flag strategies, preference order: (1) evaluate **gtk-kn** prebuilt klibs — skips the .def problem entirely; (2) Amper `GeneratedCInteropDefinition` (a build task shells pkg-config and emits the `.def` as `@Output`); (3) inline flags hardcoded for the CI image. Spike first, then module work.
- **Platform additions:** `linuxX64` into `kinetica-runtime` + `kinetica-render-core` (no repo module declares it today). Wrapper nuance: `./kotlin` exports repo-local `KONAN_DATA_DIR=.kotlin/konan` — the first linuxX64 build downloads the Linux cross-toolchain (global `~/.konan` has it; the wrapper won't see it). GTK itself is NOT cross-compilable from macOS (C headers only exist on Linux) → CI-only build lane.
- Module `kinetica-gtk` (`kmp/lib`, `[linuxX64]`, deps: `../kinetica-runtime: exported` + `../kinetica-render-core`): `GtkHostAdapter` — column/row→`GtkBox` (orientation), button→`GtkButton` (`clicked`), text→`GtkLabel`, textInput→`GtkEntry` (`changed`/`activate`), checkbox→`GtkCheckButton` (`toggled`); signal wiring via `staticCFunction` + `StableRef` user_data; **top-level `renderGtkApp(...)` entry** (compiler-plugin gotcha, KNT-0044); async marshal via `g_idle_add` consuming the KNT-0045 hook.
- Sample `samples/native-counter-gtk` (`product: linux/app`, `[linuxX64]`) — the `macos/app` sample can't host a second platform; `CounterApp()` copied verbatim (portability proof continues); `GtkApplication` + `g_application_run`.
- CI: new ubuntu `linux` job in `ci.yml` mirroring the `macos` job (konan cache keyed on `common.module-template.yaml`, publish compiler plugin, `apt-get install -y libgtk-4-dev`, `./kotlin build -m native-counter-gtk`); add to `required.needs`. Smoke-build only (no display), GUI acceptance manual on a Linux box/VM.
- **Related:** KNT-0044, KNT-0046.

### KNT-0048 — `io.heapy.kinetica` Gradle plugin
**Status:** Released as 0.4.0 on 2026-08-18 (Central deployment `5e7b25bc`, 314 signed artifacts incl. the plugin marker). `examples/gradle-ssr` now consumes it as `id("io.heapy.kinetica")` from Central with no manual wiring — clean `jvmTest` + `jsBrowserDistribution` green, which is the end-to-end proof of the release. Landed 2026-08-17 on `gradle-plugin`. Verified locally: `./kotlin test -m kinetica-gradle-plugin` (6/0 contract tests) and `node scripts/verify-gradle-plugin.mjs` (10 assertions over two fixtures — multiplatform and single-target JVM — each building with zero Kinetica wiring in the build script, plus `sourcePipeline=psi` withheld from JS, an authoring-rule violation rejected by the FIR checkers on **both** the JVM and the JS compilation, and a configuration-cache entry stored *and* reused).
- **Why:** Kinetica is compiler-plugin-only, so a Gradle consumer had to resolve the plugin jar through a private configuration and push `-Xplugin=` into every `KotlinCompilationTask` by hand (`examples/gradle-ssr/build.gradle.kts`) — 15 lines nobody invents, and the failure mode when they rot is silent (checkers stop reporting).
- **Module `kinetica-gradle-plugin`** (`jvm/lib`, publish template only — applying the common template would compile the build tooling with the Kinetica compiler plugin): `KotlinCompilerPluginSupportPlugin`, compile-only `dev.gradleplugins:gradle-api:8.11.1` + `org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.10`, `settings.jvm.release: 17` (toolchain default is 21 bytecode — unloadable on a JDK 17 daemon), `languageVersion/apiVersion 2.4` (Gradle 9.7 embeds Kotlin 2.4.0). Everything needed is in the KGP **api** artifact: `KotlinSourceSetContainer`, `KotlinTargetsContainer`, `KotlinCompilation`, `SubpluginOption` — no dependency on the KGP implementation.
- **`kinetica { }`**: the six compiler options verbatim, plus `enabled` and `addRuntimeDependencies` (adds `kinetica-runtime` to commonMain and `kinetica-browser` to a JS target's main source set, both gated on `enabled`). `sourcePipeline=psi` reaches JVM compilations only — the rule the raw `-Xplugin` wiring cannot express.
- **Publication:** the plugin marker (`io.heapy.kinetica:io.heapy.kinetica.gradle.plugin`) is pom-only and generated by `scripts/gradle-plugin-marker.sh` into mavenLocal; `release.sh` calls it after publishing and its staging glob signs/bundles it like any other artifact.
- **Publication fix that made consumption possible at all:** the toolchain publishes every dependency as runtime-scoped, so consumers had *nothing* on the compile classpath — `Cannot access 'kotlinx.serialization.internal.SerializerFactory' which is a supertype of 'Role.Companion'`. `common.module-template.yaml` now marks coroutines + serialization-json `exported`; both genuinely leak into the public API (`EffectScope : CoroutineScope`, `@Serializable` companions).
- **Gotcha worth keeping:** an unrestricted `mavenLocal()` also serves the partial third-party copies other tools leave in `~/.m2` (jar + pom, no `.module`), and Gradle then resolves a **JVM** artifact into a JS compilation. Scope it: `mavenLocal { content { includeGroup("io.heapy.kinetica") } }`.
- **CI:** the fixture check runs in the **macOS** job — it publishes `kinetica-runtime` & co, whose macosArm64 targets cannot be built on a Linux runner; the module's own tests run in the JVM job.
- **Release sequencing:** version bumped repo-wide to 0.4.0 (0.3.0 is immutable on Central). `examples/gradle-ssr` deliberately stays on 0.3.0 with its manual wiring — switch it to `id("io.heapy.kinetica")` only *after* 0.4.0 is released, or the example stops building against Central.
- **Bytecode floor, found by the single-target fixture:** `kinetica-compiler` was Java 21 bytecode, and it is loaded *into the consumer's Kotlin compile daemon* — a JDK 17 daemon (Android, Spring, anything on `jvmToolchain(17)`) died with `UnsupportedClassVersionError` before compiling a line. `kinetica-compiler/module.yaml` now sets `jvm.release: 17`, same as the Gradle plugin. The runtime modules stay at 21 on purpose: they are read by the compiler, not loaded by it, and 21 is the library's target.
- **Adversarial review (codex, 2026-08-17) also produced:** `compilerVersion` silently drove the runtime coordinates too → split into `kineticaVersion` + `compilerVersion`; `enabled = false` still demanded a Kotlin plugin; option typos (`checks = "warn"`) were silently inert → validated against the accepted sets; the psi log lived inside the provider that the configuration cache serializes → only `Property` instances are captured now; the verifier could pass with an up-to-date `compileKotlinJs` → both backends now run a negative pass, which is what proves the checkers are live on JS. `kotlin("js")` single-target needs no support: the plugin is a hard error in Kotlin 2.4.10.
- **Open:** Kotlin/Native. KGP 2.4.10's `KotlinCompilerPluginSupportPlugin` has no `getPluginArtifactForNative()` at all — Native goes through the same `getPluginArtifact()` path — but no Native compilation was exercised from Gradle; the fixtures are jvm+js. Verify before promising Native support to Gradle consumers.

### KNT-0049 — Compose Multiplatform canvas (wasmJs) in the browser benchmark
**CORRECTION 2026-08-21 — FIXED IN THE WORKING TREE; the canvas numbers below are INVALID and
must be replaced by a re-run.**
A calibration run (click handler burns a known K ms, then makes one minimal visible change;
K = 0/50/200/500/1000, 5 samples each) shows the DOM path tracks real elapsed time — slope
**0.9935**, fixed overhead 7.1 ms, and 0.9981/3.8 ms under the cluster anchor, so the anchor rule
itself is sound — while the canvas path does not: slope **0.016**. A click whose work provably
occupies 1001-1003 ms (longtask observer) and whose frame lands at +1011 ms (draw-phase publish
timestamp) was reported as 45 ms, reproducibly, three rounds out of three.

Two independent errors, both at the ends of the measured window:
1. **Start.** `parseTrace` anchors on `EventDispatch` of type `click`. Compose does its work in
   the **pointerup** handler, so the browser dispatches `click` only *after* the operation
   finishes: pointerdown +0.0, pointerup +1.4 (work runs here), mouseup +1001.8, click +1001.8,
   draw +1005.0. The window opened after the thing it was supposed to measure.
2. **End.** A canvas frame emits no Blink `Paint` and no `Commit` at all — between +900 ms and
   +1200 ms, where the frame provably lands, the trace contains **zero** events, with `gpu`,
   `viz` and `cc` categories added. So both anchors latched onto unrelated DOM-layer repaints,
   which is also what the earlier "cluster" fix was really doing. That fix removed a visible
   symptom and produced plausible numbers; it did not make the measurement correct.

The published canvas figures therefore measure "click dispatch to the next unrelated repaint",
not the operation. Big operations correlate with real work only because a stray repaint cannot
precede the blocking task; small ones are pure artifact.

**Fix, implemented and validated.** The canvas harness now times its own operations — capture-phase
`pointerdown` to the frame's `drawnAt`, both taken in the page on one clock — instead of going
through `parseTrace`, which is restored to exactly its pre-KNT-0049 form (the "cluster" anchor is
gone: it was treating a symptom). The app publishes `drawnAt` from the draw phase it already
publishes the frame snapshot from, and carries a `?spin=<ms>` calibration hook.

`bench/driver/calibrate.mjs` is the new guard, and it runs as the **`calibration` suite in the default
set** rather than as a manual step — a path that stops measuring elapsed time now fails the run
instead of waiting to be noticed. It times a click of known cost through each path and fails if one
misses it by more than 3%; the default is a single 500 ms point (~40 s), `--spins=` fits a slope
when a subtle bias is in question. Current reading: a 500 ms click measured as 502.2 ms (DOM) and
503.1 ms (canvas), overheads a few ms apart. Over the denser five-point set the slopes are 0.9941
and 0.9960. The report carries a Calibration section stating the same. This is the only absolute
check here; everything else is relative, which is exactly what the bug slipped through.

Cost of the fix: canvas entries report no GC, because per-operation GC comes from trace events
inside the measured window and that window is no longer expressed in trace time.

**Still to do: re-run the suite.** Every canvas figure in the table below predates the fix.

**Status:** DOM work LANDED 2026-08-19, verified by a full `node bench/run.mjs`
(`bench/results/runs/20260819T120227268Z`, M4 Max / Chromium 149, 3 warmup + 10 samples). Not
promoted to the accepted numbers — that is a separate call.

*Measurement neutrality of the harness refactor* (the review's main object): median per-op delta
against the accepted parts is −3.0%…+1.0% across all seven DOM frameworks, no systematic sign, and
the DOM geomean ranking is unchanged — vanilla 1.056×, Svelte 1.069×, Preact 1.170×, **Kinetica
1.228×**, Vue 1.276×, React 1.278× (README's reference run had Kinetica 1.24×). Compose HTML came
out at +1.0% after the 1.11.1 → 1.12.0-rc01 bump, i.e. the version move is performance-neutral.

*Canvas results* (medians, ms; factor = × the fastest DOM framework on that operation):

| op | Compose canvas (Column) | Compose canvas (Lazy) |
|---|---:|---:|
| create 1k | 237.6 (11.3×) | 25.1 (1.2×) |
| replace 1k | 280.0 (12.3×) | 21.7 (1.0×) |
| update every 10th | 22.0 (3.0×) | 5.1 (0.7×) |
| select row | 12.7 (1.8×) | 3.0 (0.4×) |
| swap two rows | 335.5 (42.4×) | 7.5 (1.0×) |
| remove one row | 321.6 (45.3×) | 5.9 (0.8×) |
| create 10k | 6665.6 (33.0×) | 22.5 (0.1×) |
| append 1k | 243.8 (10.9×) | 3.6 (0.2×) |
| clear 1k | 15.1 (2.3×) | 6.2 (1.0×) |
| select row (10k) | 125.8 (16.4×) | 3.3 (0.4×) |
| swap (10k) | 6943.4 (252.9×) | 10.2 (0.4×) |
| remove (10k) | **36976.1 (877.3×)** | 6.0 (0.1×) |
| update every 10th (10k) | 234.8 (8.1×) | 5.6 (0.2×) |

Geomean in the same units: Column **18.8×**, Lazy **0.4×** — the Lazy variant beats the fastest DOM
framework on average, which is precisely why canvas entries must stay out of the `fastest` baseline;
inside it they would have re-based every DOM framework's factor. Payload 17.2 MB raw / 5.4 MB gzip
(vs Compose HTML's 172 KB gz), mount ~151 ms, heap after 1k rows 48.6 MB (Column) / 10.0 MB (Lazy),
animate 83 fps (Column) vs 120 fps (Lazy). Sample spread is tight (remove-10k 36504–37252 ms, ±1%)
and no operation shows `min < 0.5 × median`, i.e. the cluster anchor never truncated a window.

Two structural findings in the numbers: `select row (10k)` costs 125.8 ms against `select row`'s
12.7 ms on the Column variant — selecting one row recomposes the whole list — and the Column/Lazy
gap on 10k operations is three to four orders of magnitude, since LazyColumn never touches the
off-screen rows at all.
- **Why:** the suite already has **Compose HTML** (`compose-web`, DOM renderer). The question it cannot answer is what the *canvas* renderer — Compose UI on Skia, the way Compose is actually written — costs in a browser. Adding it makes the pair "same framework, two renderers" measurable in one environment.
- **Placement decision:** a canvas app cannot satisfy the app contract (§"The app contract" rule 3 — same DOM, same nodes); it has no DOM at all. So the entries live in a **separate report section**, excluded from the main table's `fwOrder`, from the geometric mean, and — critically — from the `fastest(id)` baseline (`report/generate.mjs:91`), which is a `Math.min` over `fwOrder` and would silently re-base every DOM framework's factor if a virtualized list won an operation. Their factors are still printed *against* that same baseline, so the numbers stay readable in the table's units.
- **Two entries, one codebase:** `compose-canvas` (`Column` inside `verticalScroll` — materializes every row, the DOM-comparable variant) and `compose-canvas-lazy` (`LazyColumn` — what people actually write). The delta between them is the point of the section. Variant selected by `?list=column|lazy`.
- **Compose version:** `1.12.0-rc01` for **both** Compose entries — `browser-bench-compose` is bumped from 1.11.1 and re-benched, otherwise the two Compose rows sit on different runtimes. (Newest stable is 1.11.1; `<release>` on Central points at the rc.) Compose 1.12.0-rc01 is built with Kotlin 2.3.20, klibs read fine by 2.4.10. `samples/browser-game-of-life-compose` stays on 1.11.1 **deliberately** — it belongs to the Game of Life suite with its own published numbers and docs snapshot; bumping it would force a full GoL re-run for no gain here.
- **Suites:** `main` only. `treeUrl` omitted (tree skips itself); `stress`/`extra` are already restricted to `kinetica,react,vanilla` (`run.mjs:312`); `scaling` defaults to *all* frameworks, so the entries declare `suites: ["main"]` and `scaling.mjs` learns to respect it.
- **Driver shape:** `makeHarness` (`driver/common.mjs:69`) already abstracts most DOM access; only the assertion tails are inlined as raw `document.querySelector` inside the benchmarks' `wait()`. Those get lifted into the harness contract (`clickRowSelect`, `clickRowRemove`, `hasButton`, `waitReplaced`, `waitLabelSuffix`, `waitSelected`, `waitSwapped`, `waitLabelTick`), predicates byte-identical, so **nothing measured changes for the DOM frameworks** and existing part files stay comparable. A second implementation, `driver/canvas-harness.mjs`, serves the same contract over `window.__bench`: assertions via `page.evaluate`, clicks via `page.mouse.click` on published rects (trusted events — same methodology). One switch point, `harnessFor(page, fw)`, on the entry's `driver: "canvas"`. `measureTracedClick`, `parseTrace`, trace anchors, warmup/samples, viewport and the 700 ms settle are untouched.
- **No scrolling needed:** every row-level operation in the suite targets rows 2–11 (`04`, `10`) or row 5 (`06`, `12`) — all on screen. Row 999 appears only in swap *assertions*, never as a click target.

**Spike evidence (2026-08-19, toolchain 0.12.0-dev-4248, Kotlin 2.4.10, Compose 1.12.0-rc01, vendored Chromium):**
- **Builds.** `product: wasm-js/app` + `settings.compose.enabled` resolves and compiles; `@OptIn(ExperimentalComposeUiApi::class)` on `ComposeViewport` is mandatory. The Gradle fallback is therefore *not* needed.
- **Skiko runtime is NOT laid out by the toolchain** — the main build gap. `canvas.import-object.mjs:2` emits `import * as … from './skiko.mjs'`, but the web output holds only `<module>.{wasm,mjs,import-object.mjs,js-builtins.mjs}`; in Gradle the compose plugin unpacks it. The artifact is already in the toolchain's own m2 cache (`org/jetbrains/skiko/skiko-js-wasm-runtime/0.150.1/…jar`, version pinned by compose ui's `requires`), containing `skiko.mjs` + `skiko.wasm`. The build script unpacks those two next to `kotlin-output`.
- **No continuous rAF.** 5 s idle after mount: **zero** Paint/Commit/CompositeLayers events, Compose frame counter unmoved. The "last Paint in window" anchor is valid — this was the risk that could have killed the methodology.
- **Trace anchors work unmodified.** Coordinate click → `parseTrace` returns `durationMs 100.4`, `clickDispatchMs 0.006`; the window does not saturate against the 700 ms settle.
- **Bridge works, and draw-phase granularity holds.** `js("window.__bench = …")` from Kotlin/Wasm publishes the frame snapshot and the rect map (`onGloballyPositioned` + `boundsInWindow()`); `@JsExport` gives JS→Kotlin calls; `window.__mountMs` is set from Kotlin (195.6 ms). A click changing **one** row's background *does* re-run the root `drawWithContent` — provided the asserted state is read **inside** the draw scope. Reads lifted outside it would leave the root un-invalidated and every `waitSelected` would hang.
- **Canvas lives in a SHADOW ROOT.** `document.querySelector("canvas")` returns null. Consequences: mount detection and any canvas lookup must pierce the shadow root (or the bridge publishes readiness itself — chosen); the `__mountMs` snippet in `samples/browser-bench-compose/web/index.html` (which polls for `#run`) is useless here. `page.mouse.click` is unaffected.
- **Static server needs a `.wasm` MIME** — `driver/server.mjs:5` has no entry, serves `application/octet-stream`, and `WebAssembly.instantiateStreaming` rejects that.
- **Memory probe stays honest as-is.** `memory.buffer.byteLength` is 0 (Kotlin/Wasm objects live in the WasmGC heap, not linear memory), but V8 places WasmGC objects in its own managed heap and `JSHeapUsedSize` tracks them: 8.18 MB @100 rows → 19.90 MB @1k → 129.14 MB @10k. No wasm-specific accounting needed. Open observation: shrinking back to 100 rows left 120.54 MB after two forced GCs — re-check on the real app before reading it as a leak.
- **Payload (hello-world, no material3): ~16.1 MB raw / ~5.1 MB gzip** — `canvas.wasm` 7297.6 KB (gzip 1825.2), `skiko.wasm` 8437.8 KB (gzip 3246.7), `skiko.mjs` 492.1 KB (gzip 59.4), glue ~32 KB. Mount 264 ms.
- **Non-lazy `Column` is expensive**, on a primitive row (`Box` + `BasicText`, not the contract's four cells): 1k rows 151 ms, **10k rows 2705 ms**, 100k did not finish in 60 s. React's create-10k is ≈316 ms. This is what the separate section exists for.

**Found during implementation (2026-08-19) — a real measurement bug, not a build detail:**
`parseTrace` gates on having seen a Blink `Paint` and then takes the *last* Paint/Commit after the
click. A canvas layer updates through a compositor `Commit` and emits **no Blink Paint at all**, so
for the canvas app the anchor attached to an unrelated DOM-layer repaint arriving 100–350 ms later.
Traced evidence — `09_clear1k`: `FireAnimationFrame` at 0.05 ms (18 ms of work) → real `Commit` at
18.46 ms → stray `Paint`/`Commit` at 123.7 ms; `07_create10k`: 6620 ms rAF → real `Paint`+`Commit`
at 6738–6778 ms → stray pair at 7131–7158 ms. Every cheap operation therefore measured a flat
~104 ms regardless of workload (identical at 1280×900 and 640×450, so not fill cost), and
`create10k` was inflated ~390 ms. The same app sustains 120 fps in the animate loop, which such a
latency would make impossible. Fix: `parseTrace(buffer, { anchor })` — canvas entries use
`"cluster"` (end of the first run of paint/commit activity, `Commit` alone counts, a >50 ms gap
ends it; measured margins: clusters hold within ~5 ms, the stray repaint is ≥100 ms away). DOM
frameworks keep the original branch byte-for-byte. After the fix, `compose-canvas` select-1k reads
15 ms instead of 129 ms and `compose-canvas-lazy` 3 ms instead of 104 ms.

**Work items:**
1. [x] `samples/browser-bench-compose-canvas/` — `wasm-js/app`, compose 1.12.0-rc01, foundation + ui (no material3: no visual parity with the shared CSS is possible anyway, and the bytes would pollute startup). Full app contract semantics: seven toolbar actions, four-cell rows keyed by id, the standard data generator replicated in Kotlin (as `browser-bench-compose` already does — copy, not a shared module), `animate` via `requestAnimationFrame`, `clickable(indication = null)` (contract rule 11 bans press/hover repaints).
2. [x] Bridge `window.__bench`: `frame` snapshot published from the root `drawWithContent` with all asserted reads **inside** the draw scope; `rects` from `onGloballyPositioned`; `__mountMs` on first frame; `__mount`/`__unmount` if `ComposeViewport` exposes teardown — if it does not, the memory leak columns print "—" rather than being faked.
3. [x] `bench/build-compose-canvas.mjs` — `./kotlin build -v release -m browser-bench-compose-canvas`, then unpack `skiko.mjs` + `skiko.wasm` from the toolchain m2 cache (fall back to a Central download if absent). No esbuild step: the wasm glue fetches `.wasm` by URL, there is nothing to bundle.
4. [x] `driver/server.mjs` — add `".wasm": "application/wasm"`.
5. [x] `driver/common.mjs` — extend the harness contract with the lifted predicates; add `harnessFor(page, fw)`.
6. [x] `driver/bench.mjs` — rewrite the 13 benchmark `wait()`/`action()` bodies plus the memory (`:449`) and animation (`:534`) blocks onto harness methods. Predicates must stay byte-identical.
7. [x] `driver/canvas-harness.mjs` — the bridge-backed implementation.
8. [x] `driver/scaling.mjs` — respect `suites` on a config entry.
9. [x] `frameworks.config.mjs` — append the two entries (never reorder: position is the colour slot) and add one palette pair (7 of 8 slots are in use, two entries are being added).
10. [x] `report/generate.mjs` — filter `fwOrder` by `renderer !== "canvas"`; new section after the main table with the 13 ops, startup, animation, loaded bytes (JS **+ `.wasm`**, or the sizes read as a fraction of reality) and a "× vs fastest DOM framework" column; explain why they are not in the main ranking.
11. [x] Bump `samples/browser-bench-compose` to 1.12.0-rc01.
12. [x] Verify `scripts/size-report.mjs` picks the new modules up (it keeps its own list) — it tracks only Kinetica's own bundles, so nothing to add; the startup byte collector *did* filter to `.mjs`/`.js` and now counts `.wasm` too (payload reads 17.2 MB raw / 5.4 MB gzip, matching the build script exactly).
13. [x] README: the canvas methodology deviation (bridge assertions, coordinate clicks, shadow root) in "What is measured", and the out-of-`bench/` app note in "Adding a new framework".
14. [x] Smoke: `node bench/run.mjs --suites=main --frameworks=compose-canvas,compose-canvas-lazy --iterations=2 --warmup=0`, then a full run for real numbers.

**Verification:** a full `node bench/run.mjs` (every framework in one environment — Compose HTML's re-bench after the version bump requires it anyway), the printed report showing the new section, and the DOM frameworks' geomean unchanged versus the accepted numbers (the harness refactor must be measurement-neutral; that invariant is the review's main object).

## Order of work

1. The backlog is unscheduled. Per-ticket starting points: KNT-0024 → re-profile, KNT-0028/KNT-0035 → spec decision, KNT-0036 → design decision, KNT-0045 → runtime listener API first (it unblocks both marshals), KNT-0046 → ListReconcile move + docs code-links, KNT-0047 → gtk-kn vs generated-def spike, KNT-0049 → implemented, only the full re-bench remains. Native-renderer sequence: commit Phase 1 (with `.zcode/` gitignored) → KNT-0045 → KNT-0046 → KNT-0047. (KNT-0031/0033/0033b/0034/0038/0039 landed on `mem-opt-experiments`, pending merge review — see the "Landed" one-liners above; full detail in git history.)
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

- Full sweeps: `./kotlin test -m kinetica-runtime --platform jvm`, `./kotlin test -m kinetica-test --platform jvm`, `./kotlin test -m kinetica-compiler --platform jvm`, kinetica-browser build + `node build/artifacts/CompiledWebArtifact/kinetica-browserjsTestrelease/kotlin-output/kinetica-browser_test.mjs`, the kinetica-runtime/kinetica-test JS bundles the same way, both annotated samples (JVM + JS). The KSND soundness cases ride these same runs (suite layout table in Context).
- Perf-backlog changes (KNT-0024+): `node bench/run.mjs --suites=main --frameworks=kinetica`, then `node scripts/verify-browser.mjs` from the repo root — the script lives in root `scripts/`, not `bench/` (15 self-tests must stay green). Locally the verification script needs what ci.yml:99-103 provides: a static server first (`node -e 'import("./bench/driver/server.mjs").then(m => m.startServer(process.cwd(), 4173))' &`) and `PLAYWRIGHT_IMPORT=.tools/playwright/node_modules/playwright/index.mjs`, plus built browser-tests/counter/todo samples.
- Size check: `node scripts/size-report.mjs` within baseline tolerance.
