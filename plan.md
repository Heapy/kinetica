# K5 + K6 + compiler test harness — beat React consistently

## Context

Fresh 13-op baseline (2026-07-06): Kinetica geomean **1.354×** vs React **1.27×**. Kinetica loses 9/13 ops — all per-row mutation paths (updates +36–41%, removes +31–36%, append +35%, create1k +22%, clear +18%, select10k +22%) — while winning swaps, select1k and create10k wall-clock. Matching React on just the losing ops → geomean **1.144**, decisively ahead. Structural liabilities: create10k pays **118.6ms GC where React pays 0**, worst-in-class heap (7.53MB after 1k rows), largest bundle.

CPU profiles + an Inferno mechanism study (verified against `projects/inferno` + `projects/babel-plugin-inferno` checkouts) locate the costs: event-registration churn per handler per render, string-hash liveness sets paid even by memoized rows, no child-shape fast paths (N×removeChild clears, per-child dispatch for single/text children), per-diff scratch allocation, and — for create ops — 80k `createElement`+90k `setAttribute` vs vanilla's 10k `cloneNode` (the K6 target). Kinetica is already at-or-better than Inferno on props identity tiers, positional keys, delegation, and static hoisting.

Everything stays on the K2 compiler stack (`IrGenerationExtension`, `supportsK2`; no K1 APIs). Doc invariants hold: unproven/unflagged code keeps current behavior; every phase has exit gates on named ops; re-baseline events snapshot `part-kinetica-*-before.json` first.

## K5 — hot-path de-hashing + shape fast paths (runtime-only)

### K5.1 Event liveness via entry references + generation ints  ← profiled #1 lever
- **Files:** `kinetica-runtime/src/ComponentScope.kt` (+ EachMemoizationTest, SkippableEachCompositionTest additions).
- `hostEventIds` → `Map<String, HostEventEntry(id, var touchedGeneration, var dead)>`; delete the `touchedHostEvents` string set; bump an `eventGeneration` int in `beginRender()`. Capture frames + `EachRowCache`/`SkippableNodeCache` hold entry **references**: the memoized-row replay (today ~20k string-hash inserts/render at ComponentScope.kt:805-810) and skippable replay become plain int field writes. Liveness check becomes `entries.none { it.dead }` (strictly safer than `containsKey` — catches evict-then-reregister). Same generation treatment for `touchedSlots`/`slotMetadata`.
- **Contract preserved:** `"event-N"` ids stay in `props["event:onClick"]`; `registerEvent/updateEvent/dispatchTo` untouched — representation-only change inside ComponentScope.
- **Gate:** 13/10/03/12 improve ≥2σ; re-profile `hashCode` self-time <0.3ms (from ~1.1); select10k ≤8.5ms; no op regresses.

### K5.2 Each-row hit-path diet
- **Files:** same.
- (a) `EachCallsiteCache.rows` keyed by raw `itemKey: Any` (kills 10k `toString` + string-hash lookups/render; `toString` moves to miss/evict branches). (b) Defer `pushKeyScope(rowKey)` into the miss branch (verified: the hit path never reads the prefix). (c) `keyedLastWins` allocation trim, gated on an allocation profile.
- **Gate:** 06_remove1k +31%→≤+12%; 12/10 beyond noise; 13 ≤+20% cumulative with K5.1; heap-after-1k <7.53MB; the win ops (04/05/11) unregressed.

### K5.3 Child-shape fast paths (Inferno childFlags, K4-style runtime certification)
- **Files:** `Node.kt` (flag bits), `HostDsl.kt` (O(1) construction-time checks in `host()`/`column`/`row`), `kinetica-browser/src@js/BrowserKineticaApp.kt`, ListReconcile/BrowserMapping tests.
- (a) Bulk clear: `patchChildren` gains `ownsParent` (true from patchHost); `next.isEmpty() && ownsParent && appendAnchor == null` → one `textContent = ""` (walk decrements `clientRefCount`). (b) Replace-all: zero key-overlap after trims + ownsParent → clear + append (exactly 02_replace1k). (c) `CHILDREN_SINGLE_TEXT` flag: single TextNode child, `semantics == null && !strikethrough` (conservative under-approximation of `textNeedsElement`) → patchHost writes `firstChild.nodeValue` directly.
- **Gate:** 09_clear1k +18%→≤+5%; 02_replace1k → parity; dom-count clear10k removeChild ~N→~1; lying-flag goldens.

### K5.4 Keyed-diff scratch reuse
- Hoist `patchKeyedChildren`'s per-diff allocations (result array, keyToNewIndex, sourceOldIndex, inStableRun) + LIS arrays (`ListReconcile.kt:11-39`) into a renderer-owned depth-indexed scratch pool (patch recurses → per-depth frames; depth>4 falls back to fresh allocation). **Gate:** behavior-neutral; GC medians on 05/11/02 don't regress.

### K5.5 Text-semantics default (APPROVED behavior change)
- Drop `label = value` from `text()`'s default `Semantics` (`HostDsl.kt:90`); plain text renders as a bare DOM text node (today every default text is span-wrapped with `aria-label` via `textNeedsElement`); SemanticsTree derives labels from `TextNode.value`. Snapshot + docs-verify updates included. Not a bench gate (bench passes `semantics = null`) — real-app win and prerequisite for K6 text holes.

## K6 — template cloning (compiler + renderer; re-baseline event)

- **Compiler (K2 IR):** per qualifying component/callsite emit file-level `TemplateDefinition(id, skeleton: HostNode /*sentinel-holed*/, holes: List<TemplateHole(path: IntArray, kind TEXT|PROP|EVENT_PROP, propName)>)` — skeleton-as-Node (reuses K2 Node-singleton precedent; no HTML strings → no escaping/XSS surface, no duplicated tag/flex mapping). v1 templatability is whole-subtree-or-nothing: const tag + const prop names (dynamic values → holes), null semantics, children = templatable hosts or text holes, key only at root; component calls/each/fragments/conditionals/ClientRef/frameProps disqualify → current path. The bench row qualifies fully.
- **Runtime:** `TemplateNode(definition, values: Array<String?>, key)` joins the sealed `Node`; `materialize(): HostNode` + a serializer writing the materialized tree keeps server-components/snapshots/`toSafeHtml`/`diffNodes`/SemanticsTree on plain HostNode. Event contract: `registerHostEvent` still runs; ids bind to EVENT_PROP holes; `MountedTemplate` answers `props["event:onClick"]` for the delegated dispatcher.
- **Renderer:** prototype built once by mounting the skeleton through existing `mountHost`; rows = `cloneNode(true)` + one hole-path walk → `MountedTemplate(root, holeDoms, values)`; same-definition patch = values compare + per-changed-hole write (an update-path win too). Keyed reconciliation generalizes key access via `Node.reconcileKey`; row memoization composes unchanged.
- **Fallback/kill switch:** no plugin or `templates=off` → TemplateNode never exists; paths identical to K5.
- **Sub-phases:** K6.1 emission + materialize bridge + parity (13 ops within noise; template goldens) → K6.2 clone-mount/hole-patch → K6.3 partial templates only if evidence demands.
- **Gate (K6.2):** dom-count create10k createElement ≤15k, setAttribute ≤20k (from 80k/90k); create10k GC <40ms (from 118.6); 01_run1k ≤+5%, 08_append1k ≤+10%, 02_replace1k parity; heap <6MB; zero regressions; full re-baseline (`--tree --scaling`) + size-report update.

## Compiler test harness (required before K6.1 merges)

- **`kinetica-compiler/module.yaml`:** add `- ../kinetica-runtime` to `test-dependencies` (jvm variant resolves; precedent: samples/annotated, kinetica-test).
- **`kinetica-compiler/test/KineticaCompilationHarness.kt`:** in-process `KotlinCoreEnvironment.createForProduction` + `KotlinToJVMBytecodeCompiler.compileBunchOfSources`; plugin via `configuration.add(CompilerPluginRegistrar.COMPILER_PLUGIN_REGISTRARS, KineticaCompilerRegistrar())` (programmatic — no jar/services); classpath from `java.class.path`; `KineticaConfigurationKeys.*` set directly; `URLClassLoader` over outDir (shared parent → real runtime classes drive compiled components); idea.*/Disposer teardown copied from `CompilerPluginWiringTest.kt:185-251`. **Mandatory `assertTransformFired`** matching the reported messages ("wrapped in skippableNode" / "interned N const props") — `KineticaIrSymbols.resolve` null silently no-ops, so unguarded tests pass vacuously.
- **Test files/cases:** `KineticaIrSkippableCompileTest` (wrap fires + behavioral skip via journal `Skipped`; stability matrix: data class ✓ / List ✗ / lambda ✗ / `@Stable` override / `skippable=false` opt-out — asserting message presence AND absence), `KineticaIrHoistCompileTest` (hoisted `kineticaProps$N`/`kineticaHost$N` fields via reflection + cross-render `===`), kill-switch test (`transforms=off` → no messages, no fields, equal trees), symbol-resolution canary, later `KineticaIrTemplateCompileTest` (K6 golden + materialize parity).

## Work order

K5.1 → K5.2 → K5.3+K5.4 (one measurement pass) → K5.5 → K5 exit review (full kinetica run) → harness → K6.1 → K6.2 (re-baseline event) → K6.3 only if evidence demands. Each item its own commit, verified by re-running kinetica benches (established A/B discipline: stash-or-killswitch comparisons, medians, ≥2σ rule).

## Honest parity assessment

- React parity plausible in K5: 03/13 updates, 06/12 removes (hit-path + GC), 09_clear1k, 02_replace1k (K5.3).
- Borderline in K5, likely with K6 hole-patch: 10_select10k (~half its gap is hit-path).
- Structurally K6's: 01_run1k, 08_append1k (allocation + 8× DOM-call volume).
- Cannot be won in JS: the shared layout/paint floor on 10k ops (~45 of ~50ms) and React's literal 0ms create GC (K6 targets <40ms, not 0).
- Expected: geomean ~1.20–1.26 after K5 (at/near React), **~1.15–1.20 after K6.2 (decisively ahead)**.

## Verification

Per item: `./kotlin test -m kinetica-runtime --platform jvm` (151+ tests), browser JS tests, `node bench/build-kinetica.mjs`, targeted `node bench/driver/bench.mjs --frameworks=kinetica --bench=<op>` A/Bs against the named gate ops, plus dom-count/CPU-profile re-runs where the gate names them. K5 exit + K6.2: full `node bench/run-all.mjs --frameworks=kinetica` (K6.2: all frameworks + `--tree --scaling` re-baseline). Compiler harness runs in `./kotlin test -m kinetica-compiler --platform jvm` and CI as-is.
