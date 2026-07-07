# Compiler plugin: perf transforms — implement, test, apply

Status: K0–K3 implemented · 2026-07-06 · owner: kinetica-compiler

**Implementation log (2026-07-06):**
- **K0** — done, with a decisive finding that reversed the phase design: source rewriting
  cannot serve K2/JS (see §K0), so the perf transforms moved to a new `IrGenerationExtension`
  (`KineticaIrTransform.kt`). PSI source pipeline is opt-in per module (`sourcePipeline: psi`,
  used by `samples/annotated`); `samples/annotated-js` proves the IR path on Kotlin/JS.
  Plugin publishes as 0.2.0 to the toolchain-local repo (`.kotlin/home/.m2`); CI and
  `bench/build-kinetica.mjs` publish before plugin-enabled builds.
- **K1** — done: semantic stability inference on resolved IR types (primitives/String/enums/
  val-only data classes/@Stable assertion; unknown or function types → unstable), receiver-style
  `skippableNode` wrapping, `@UiComponent(skippable = false)` opt-out, `transforms=off` kill
  switch, LOGGING diagnostics naming the unstable parameter. Skip semantics verified
  behaviorally on JVM and JS (render-count flat on unchanged inputs, bumps on change).
- **K2** — done at leaf scope (`KineticaIrHoist.kt`): const-`propsOf` interning + fully-static
  leaf `host()` hoisting into file-level singletons, structurally deduplicated; verified by
  cross-render reference-equality checks in both annotated samples. Whole-subtree hoisting
  (static text children etc.) remains open — see §K2 follow-up.
- **K3** — done: plugin applied to `bench-jvm` (`benchTable`) and `samples/browser-bench`
  (`BenchApp` hoist-only — function-type param keeps it unskippable, which also avoids the
  skippable-inside-each memoization conflict; `TreeApp` fully skippable; `treeNode` opted out).
  All 13 browser ops, animation, memory hooks and all four tree ops pass through the
  plugin-built bundle. Measured (M4 Max, quick passes): bench-jvm `render_create_1k`
  2.731 → 2.675 ms (~2%, noise-level — JVM allocation is cheap); browser create-10k A/B via
  the kill switch: 301.5 → 291.1 ms median, GC 118 → 113 ms (~3%, at the noise edge). The
  narrow leaf-only scope explains the modest delta; the structural effect (reference-equal
  leaves riding the `===` diff path) also isn't isolated by create ops. Bundle 76.9 → 77.4 KB
  gz. Pre-plugin part snapshot: `bench/results/part-kinetica-preplugin-before.json`.
  **Discovered constraint (since resolved):** `skippableNode` inside a memoized `each` row
  called `markEachCapturesUnsafe()`, disabling row memoization; and a second, larger issue —
  the cache's global `stateWriteVersion` guard invalidated every skippable cache on ANY state
  write, making skips unreachable in stateful apps (TreeApp never actually skipped in the
  browser suite). Both fixed on 2026-07-06 by unifying the capture machinery: skippable
  factories run inside the same capture-frame stack as each rows, the cache captures events/
  context reads/cursor deltas and replays them on hit (composing with row memoization instead
  of disabling it), and the global guard is replaced by the row contract — equal inputs +
  unchanged cell deps + unchanged contexts + live events. Replay-unsafe factories (effects,
  render-phase writes, nested each) are never cached. Covered by
  `SkippableEachCompositionTest`; the smoke test's old "any state write re-renders" assertion
  was updated to the precise contract.
- **K4** — done (2026-07-06), with a design deviation and an honest null result. Deviation:
  the flag SETTING moved from the compiler to the runtime — a sound static proof of "all
  children uniquely keyed" is impossible in general (key collisions aren't statically
  decidable), while `each` has exactly that knowledge at O(1) amortized cost:
  `keyedLastWins` guarantees unique row keys, each row certifies "exactly one HostNode keyed
  by the row key" (cached in `EachRowCache`), and `host()` stamps
  `NodeFlags.CHILDREN_KEYED` when certified rows are its entire child list (frame-identity
  consumption; any emission before/after poisons the flag). The browser renderer runs keyed
  reconciliation directly when both sides carry the flag, skipping `shouldReconcileKeyed`'s
  O(children) scan + two per-patch hash sets. The compiler side stays on the K2
  IrGenerationExtension unchanged. PROPS_STATIC was dropped: K2's interning already gives
  O(1) props equality via identity inside the existing `!=` guard.
  Measured: **no significant win on M4 Max** — select10k unchanged at noise level, and a 4×
  CPU-throttled A/B gave 47.4 vs 48.7 ms (~3%, within stddev). The reconcile-prep scan was
  NOT the 10k-partials tax the plan assumed; that cost lives in paint at scale and the patch
  loop itself. The change stays (strictly less work + allocation per patch, matters more on
  weaker hardware) with 9 certification tests (`EachKeyedFlagTest`), 151 runtime tests green.
- **K5** — **rejected on profile evidence (2026-07-06).** CPU-profiling the 10k partials
  (CDP Profiler, unminified bundle) shows the op cost is browser style/layout/paint;
  Kinetica's app-JS slice of update10th10k is ~4 ms of a ~50 ms op, split into
  `emitCachedEachRow` (~0.7 ms — the row-cache HIT replay over unchanged rows),
  `patchText` (~0.7 ms — legitimate DOM writes), and ~1.1 ms of `hashCode`
  (`touchedHostEvents` set inserts + row-map lookups, ~20k string hashes per render);
  `patchKeyedChildren` is ~0.26 ms (K4's flags leave the diff nearly free). K5 would only
  remove capture-time detection, which runs for CHANGED rows and is trivial branches — it
  cannot move these numbers. **The identified next lever instead:** the each-row hit path's
  hash work — for select10k (~10 ms vs vanilla's ~4.6 ms floor) the hit-path cost is roughly
  half of Kinetica's whole gap on that op. Attack that (pre-hashed row keys, batched event
  touch, or clock-stamped row caches) before anything else in this plan.
Companion to `perf-rewrite-design.md` (since retired into `plan.md`; when this was written
the renderer stood at P0–P3 done, 13-op geomean 1.35× — now P0–P4, 0.97×):
every number in that story was measured **without** the plugin. This plan makes the compiler
contribute, safely.

## 1. Where we are

The plugin (`kinetica-compiler`, published `io.heapy.kinetica:kinetica-compiler:0.1.0` to
mavenLocal) is a `ProcessSourcesBeforeCompilingExtension`: it parses Kotlin **source text with
regexes** (`KineticaSourceModel.kt`), rewrites `@UiComponent`/`@ServerComponent`/
`@ClientComponent` functions before compilation, and emits generated registration sources
(`KineticaGeneratedSourceEmitter.kt`). What already works, at prototype level:

- **UiComponent desugaring** — annotated body wrapped in `renderNode { }` /
  `skippableNode(componentId, inputs) { renderNode { } }`.
- **Skipping** — `skippableNode` wrapping when all parameters pass the stability check;
  the check is `"->" !in type` (`KineticaSourceModel.kt:isStableComponentInputForTransform`).
- **Slot ids** — `state(...)` calls rewritten to `state(slotId = ...)` with compiler-stable ids.
- **Static hoisting** — static **text** nodes only (`hoistStaticTextNodes`).
- Manifests, server-action stubs, route codecs, previews, diagnostics.

Consumers already exist in the runtime and are stable API: `skippableNode` /
`skippableSuspendNode` (input + cell-dependency + state-write-version guarded cache,
`ComponentScope.kt:125`), `staticNode(hoistId)` (`ComponentScope.kt:122`), `state(slotId =)`,
`ComponentTransformRegistration` (`CompilerMetadata.kt`).

Applied today: **only `samples/annotated`** (jvm/app smoke). No JS module has ever compiled
through the plugin; browser-bench, docs-client and every benchmark number are plugin-free.

Measured targets the plugin can attack (from the extended bench suite, 2026-07-06):

| bottleneck | evidence | transform |
|---|---|---|
| create-op Node allocation + GC | 118.6ms GC of 291.4ms create-10k (GC report section) | static host-subtree hoisting, const-props interning |
| re-render overhead on unchanged data | tree `t4_noopRender`; every render re-walks composition | auto-skipping |
| runtime key derivation per render | keyScopePrefix work (P2 hygiene halved it, rest remains) | compile-time slot/event ids |
| keyed-diff prep | `shouldReconcileKeyed` builds 2 HashSets per patch | shape flags |
| each-row bookkeeping | runtime capture-tracking + conservative bail-outs | static safety proof |
| DOM call volume on create | dom-count: 80k createElement vs vanilla's 10k cloneNode | template cloning (long-term, §K6) |

## 2. Invariants (hold in every phase)

1. **Annotations are inert without the plugin.** `@UiComponent` code must compile and behave
   identically with the plugin removed from `module.yaml`. Rollback = delete three yaml lines.
2. **Transforms preserve semantics.** Proven per-phase by the parity harness (§4, layer 3) —
   same component compiled plain and transformed renders byte-identical trees.
3. **Conservative by construction.** Anything the compiler cannot prove (stability, staticness,
   each-safety) falls back to today's runtime behavior. A wrong "skippable" verdict shows stale
   UI — the worst bug class this plan can create — so skipping is gated hardest.
4. **Unflagged code keeps current paths.** New Node metadata (shape flags) defaults to
   "unknown" and the renderer behaves exactly as today for it.

## 3. Phases

Naming: K0–K6, to keep clear of the renderer's P0–P3.

### K0 — Foundations and safety net (prerequisite for everything)

**Outcome of the JS experiment (2026-07-06), which reverses this phase's original design:**
`ProcessSourcesBeforeCompilingExtension` is consumed only by `KotlinCoreEnvironment` — the JVM
CLI environment (verified by scanning kotlin-compiler-embeddable 2.4.0: the only referencing
classes are `KotlinCoreEnvironment{,$Companion}`). The K2/JS pipeline never invokes it, and our
`CommandLineProcessor`'s `useLightTree = false` side effect makes a plugin-enabled JS compile
silently produce an **empty klib** (build "succeeds", 54-byte link output). Source rewriting
therefore cannot serve Kotlin/JS at all — and Kinetica's perf targets are JS.

**Revised architecture (K2-native):**
1. **New `IrGenerationExtension`** — the standard K2 backend hook (what Compose and
   kotlinx.serialization use), runs identically for JVM and JS, and sees fully resolved IR
   types (which upgrades K1's stability inference from syntactic to semantic). It performs the
   perf transforms for **receiver-style** components (`fun ComponentScope.X(...)`, the style
   real apps use): `skippableNode` wrapping, and later K2's hoisting/interning.
2. The existing source-processing extension **stays, documented as JVM-only**: it serves the
   scope-free authoring model (annotated sample) and generated registrations/manifests. The
   two never overlap: source-desugared components return `Node`, the IR transform only touches
   `Unit`-returning receiver-style functions.
3. `useLightTree = false` moves out of `processOption` and is applied only when the
   configuration is a JVM compile, so the plugin no longer breaks the JS pipeline.
4. **Parity/behavior harness**: toolchain-level samples (`annotated` JVM, `annotated-js` JS)
   assert render-count semantics (skip engages on unchanged inputs, re-renders on change) and
   identical trees; plus wiring tests for the IR extension's registration.
5. **CI/publish plumbing.** The toolchain resolves the plugin from its own local repo
   (`.kotlin/home/.m2`), so CI and `build-kinetica.mjs` run
   `./kotlin publish mavenLocal -m kinetica-compiler` before any plugin-enabled module builds.

Exit gate: annotated (JVM) and annotated-js (JS) both build and pass behavior checks; the IR
transform proven on both backends.

### K1 — Slot ids and skipping, hardened

1. Slot-id injection extended beyond simple `state(...)` matches: slots created inside `each`
   rows, nested lambdas, `derived`/effects — every positional-slot callsite the runtime has.
2. **Real stability inference** replacing the string heuristic: a type is stable iff it is a
   primitive/String/enum, a `val`-only data class of stable types, an immutable collection of
   stable elements, or explicitly annotated. Add `@Stable` (trust me) and
   `@UiComponent(skippable = false)` (opt out) to `Annotations.kt`. Emit a diagnostic
   explaining *why* a component is not skippable (mirrors Compose's reports; the
   `ComponentParameterRegistration.stable` field already exists to carry it).
3. Skip-inputs correctness: parameters only (top-level component functions have no other
   inputs); lambda parameters make the component non-skippable unless provably capture-free
   (hoistable) — which K2's lambda hoisting will relax.
4. Plugin kill-switch option (`-P plugin:...:transforms=off`) for A/B debugging in the field.

Tests: parity suite growth (stable/unstable matrices, state writes inside skipped components —
the `stateWriteVersion` guard, cell-dependency invalidation); adversarial cases where skipping
must NOT happen. Perf visibility: new `bench-jvm` benchmark `render_skip_tree` (annotated
nested components, re-render with unchanged inputs) — expected to drop from O(tree) to ~O(1).

Exit gate: zero parity failures across the matrix; diagnostics list is human-readable;
`render_skip_tree` shows skipping actually engages (journal-verified, not inferred from time).

### K2 — Static hoisting of host subtrees + const-props interning

Today only static text hoists. Extend to the cases the bench table is full of
(`td.col-rest`, `span.remove-icon`, toolbar buttons):

1. **Whole-subtree hoisting**: a `host(...)` call with constant tag/props/semantics and all
   children static hoists as one unit. Because `Node` is an immutable value, emit a top-level
   `private val` constructing the `Node` directly (no DSL, no scope needed — the
   `StaticHoistRegistration` type already carries serialized `Node`s), and rewrite the callsite
   to `emit(hoist$N)`. One instance shared across all rows, renders, and scopes; reference
   equality rides the renderer's `===` fast path.
2. **Const-props interning**: `host()` with constant props but dynamic children hoists just the
   `PropMap` (flat-array `propsOf` result) to a top-level val.
3. Correctness guards: hoisting is disabled for nodes with keys, event props, semantics with
   testTags that must stay per-instance... enumerate in the golden tests; when in doubt, don't
   hoist (invariant 3).

Tests: parity snapshots (structure identical); allocation evidence via `bench-jvm`
`render_create_1k` on an annotated table clone (target: measurable drop; set the numeric gate
after the first measurement, expectation ≥20% on hoist-heavy trees) and later the browser GC
section (create-op gcMedianMs down).

Exit gate: parity green; `render_create_1k` (annotated variant) improvement demonstrated and
recorded in this doc.

### K3 — Apply to real apps (the "apply it" milestone)

Order: `bench-jvm` table content → `samples/browser-bench` → docs-client/docs-site.

1. Annotate `bench-jvm`'s `benchTable` composition — gives per-PR JVM numbers for the plugin
   path with zero browser noise.
2. **browser-bench**: annotate `BenchApp`/`TreeApp` (`@UiComponent`), add the
   `settings.kotlin.compilerPlugins` block to its `module.yaml`,teach `build-kinetica.mjs` to
   publish the plugin first. Fairness note for `bench/README.md`: compiler transforms are
   framework tooling (same category as Svelte's compiler), so the plugin build IS the honest
   "production build" — but the switch is a re-baselining event:
   - snapshot current parts as `part-kinetica-preplugin-before.json` (the existing
     before/after report mechanism picks it up);
   - full suite re-run incl. `--tree --scaling`; compare op-by-op — regression on ANY op
     beyond noise blocks the switch;
   - re-run `scripts/size-report.mjs --update-baseline` (generated registrations + rewritten
     bodies change the bundle; watch `kinetica/browser-bench-bundle`).
3. Docs apps last (they exercise SSR + hydration paths and `markdown` composition).

Exit gate: browser suite fully green through the plugin build; before/after published in the
report; README/performance.md updated; docs site verification (`verify-docs.mjs`) green.

### K4 — Shape flags (compiler ↔ renderer co-design)

1. `Node` (runtime) gains a small flags field: child shape (`KEYED | POSITIONAL | TEXT_ONLY |
   EMPTY | UNKNOWN`) and a `PROPS_STATIC` bit. Default `UNKNOWN` everywhere (invariant 4).
2. Compiler sets flags at `host()`/`each` callsites where the shape is statically known.
3. `BrowserKineticaApp.patchChildren` trusts `KEYED`/`POSITIONAL` and skips
   `shouldReconcileKeyed`'s two per-patch HashSet builds; `patchHost` skips `patchProps` on
   `PROPS_STATIC` when the previous node carried the same bit.

Tests: `ListReconcileTest`/`BrowserMappingTest` extensions with flag matrices + a
"lying flag" test (flags must never be produced for unproven shapes — golden tests enforce);
parity harness re-run. Perf: browser 10k partials (ops 10–13) and scaling exponents are the
signal; JVM `render_update_1k` should also tick down.

Exit gate: 10k partial ops improve or hold; scaling exponents don't regress; parity green.

### K5 — `each`-safety proved statically

Compiler proves a row lambda memoization-safe (no effects/resources/boundaries/nested `each`/
context writes — the exact conditions `markEachCapturesUnsafe` guards) and rewrites to
`each(..., provenSafe = true)`; the runtime then skips capture-detection bookkeeping for that
callsite. Unproven lambdas keep today's runtime detection — the two mechanisms must agree, and
a debug assertion mode runs both and reports divergence.

Tests: `EachMemoizationTest` matrix compiled through the plugin; divergence-assertion mode in
the parity harness. Perf: browser partial ops + `render_update_1k`.

### K6 — Template cloning (separate design, after K2–K4 experience)

The endgame the dom-count data points at (Kinetica create-10k: 80k `createElement` + 90k
`setAttribute` + 100k `insertBefore`; vanilla: 10k `cloneNode` + 10k `appendChild`): compiler
emits per-component static HTML templates plus a hole-binding plan; the browser renderer
clones and binds instead of building element-by-element. This changes the renderer's mount
path fundamentally — write `template-cloning-design.md` once K2/K4 land and the flag/hoist
infrastructure exists to express it. Not scheduled here.

## 4. Testing strategy (cross-cutting)

| layer | what | where | when |
|---|---|---|---|
| 1 | model + transform golden tests (source → transformed source) | `kinetica-compiler/test` | every PR |
| 2 | compile-and-run wiring tests (embeddable compiler, JVM + JS) | `CompilerPluginWiringTest` | every PR |
| 3 | **parity harness**: plain vs plugin build → identical `kinetica-test` snapshots + expected journal events | `kinetica-compiler/test` fixture | every PR |
| 4 | perf gates: `bench-jvm` (fast, quantitative), browser suite + GC/scaling/tree sections, dom-count | bench harness | per phase exit |
| 5 | end-to-end: annotated sample (JVM+JS), plugin-built browser-bench through the full driver, docs verification | ci.yml / bench.yml | per phase exit |

Perf-gate discipline: numbers only from the M4 Max reference machine on AC (README canary
rule); CI perf runs are smoke-only. Each phase records its before/after in this doc the way
the retired `perf-rewrite-design.md` §8 did (git history).

## 5. Risks

- **JS-target support of `ProcessSourcesBeforeCompilingExtension`** — unknown #1, retired in
  K0 by the smoke module. Fallback: run the same source model as a standalone pre-compile step
  wired through the toolchain (the model/emitter are compiler-independent).
- **Skipping correctness** (stale UI) — gated by parity matrix, journal assertions, per-component
  opt-out, kill-switch, conservative inference. Ship K1 behind the annotated sample + bench-jvm
  before any real app.
- **Regex fragility on real code** — addressed head-on in K0 (PSI); no real-app application
  before it.
- **Toolchain maturity** — `settings.kotlin.compilerPlugins` is the documented escape hatch
  ("IDE support best effort"); acceptable for our own repo, watch hot-reload/preview interplay.
- **Debug-info drift from source rewriting** — PSI-based rewriting preserves surrounding
  offsets; diagnostics map through `KineticaDiagnosticReporter`. Revisit if line numbers get
  bad enough to hurt; that would accelerate the IR migration decision.
- **Version skew** plugin ↔ runtime (serialized `Node` in registrations) — existing generated
  version checks + bump-on-contract-change rule.

## 6. Sequencing and effort (rough)

K0 is the schedule driver (PSI rewrite + harness + JS proof). K1 and K2 are independent after
K0 and can interleave; K3 needs K1+K2; K4/K5 follow K3's re-baseline so their wins are visible
against a plugin-built baseline. Suggested order of merges:
K0 → K1 → K2 → K3 (re-baseline event) → K4 → K5 → (K6 design doc).
