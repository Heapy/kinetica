# Kinetica plan — KNT tickets

Single backlog merging the K5/K6 review-fix plan and the renderer-perf open items
(formerly `perf-rewrite-design.md`, retired into this file). Tickets are **Open** unless a
Status line says otherwise. Mapping to old labels: KNT-0001..0010 = review findings F1..F10,
KNT-0011..0020 = cleanup C1..C10, KNT-0021 = harness dedup, KNT-0022 = CI item,
KNT-0023..0031 = perf-rewrite items §1..§9.

## Context

**Review stream (KNT-0001–0022).** The K5/K6 implementation (~1,600 insertions at review
time) was reviewed with 8 finder angles + verification: 10 findings reported (9 correctness,
1 efficiency; all CONFIRMED) plus verified cleanup items below the cap. Second opinion
(round 1): every finding + fix independently verified by Codex — 8× AGREE, 2× PARTIAL,
0 disagreements; Codex ran the JVM compile-test validating KNT-0009's IR claim and caught one
real plan error (KNT-0004 diff-arm simplification). Round 2 (Codex per-finding + whole-plan)
re-verified everything after the frame-ordinals rework (commits 65224c6..0b2b08d: frames
migration, compiler plugin mandatory for all modules v0.3.0, slot ordinals compiler-assigned,
FIR checks=error): KNT-0001–0004 and 0006–0010 STILL-VALID (anchors updated in the ticket
bodies); KNT-0005 fixed by the rework; KNT-0015 obsolete; KNT-0022 already done. One new
round-2 Codex catch folded in: label BAKING at wire boundaries (an early KNT-0004/0008 idea)
would make hydrated clients span-wrap texts via label-based `textNeedsElement` — baking is
dropped; derived labels are a live-tree projection only. Round 3 (ultrareview 2026-07-07):
substance held on every claim and the bench geomean reproduces exactly; KNT-0006 reframed to
the actual defect (single-slot `__kinetica` overwrite), KNT-0016/0018 closed (targets deleted
by the rework), line anchors refreshed against the current tree (drift was 1-6 lines;
`asLeaving` ~80). Line numbers are hints — grep the symbol when implementing. Round 4
(second opinion 2026-07-07): KNT-0004 gained the missed `encodeSignedChunk` boundary (:389
serializes the raw chunk into the signed wrapper); Order-of-work flipped to publish-then-test
(ci.yml:26-30); KNT-0009/0017 aligned on empty props (both shapes template); KNT-0016 reworded
(the runtime helper survives as a branch-free `frameEvent` delegate); stale README/docs
pointers to the retired perf-rewrite-design.md repointed at plan.md.

**Perf stream (KNT-0023–0031).** Status 2026-07-07: P0–P4 landed (registry eviction,
retained renderer with keyed LIS diff + event delegation, each-row memoization, allocation
hygiene, benchmark packaging, frame ordinals). Re-benched on Chrome 130 at default sampling
(10 samples, vanilla drift vs stored parts ≤2% median, so cross-part comparison holds):
13-op geomean **0.969× vs React** — ahead of React overall for the first time (was 15.2×
before the rewrite, 1.27× after P3, 1.20× before frame ordinals). swap1k 0.36×, swap10k
0.78×, replace1k and create10k 0.90×; still above React: remove10k 1.48×, update10th10k
1.26× (KNT-0024); select10k 1.09× is essentially the paint floor. Weight 85KB gz, startup
median 24.5ms. The full perf design, root-cause analysis and phase history live in the git
history of `perf-rewrite-design.md` (up to commit `7cfde69`).

## Correctness fixes (K5/K6 review)

### KNT-0001 (was F1) — Stale `CHILDREN_SINGLE_TEXT` flag: make the fast path self-defending + strip flags in `asLeaving`
**Status:** Done (2026-07-07, codex TDD) — guard bails `patchSingleTextChild` on `wrapped`/`textNeedsElement`; `asLeaving` strips `CHILDREN_SINGLE_TEXT` via shared `stripChildShapeFlagsForReplacedChildren()` (Node.kt, next to NodeFlags); runtime flags test + JS DOM regression (leaving span wrap) green; DOM shim extracted to shared `kinetica-browser/test@js/TestDom.kt` for the upcoming renderer tickets.
- `kinetica-browser/src@js/BrowserKineticaApp.kt` `patchSingleTextChild` (L684): add guards — bail to the slow path when `mountedText.wrapped` is true OR `textNeedsElement(nextText)` is true. This makes ANY lying/stale flag degrade safely instead of freezing text (covers future predicate drift too).
- `kinetica-runtime/src/Boundary.kt` `asLeaving()` (L407-420; HostNode branch L412-416): the copy rewrites child semantics (leaving=true) so strip `CHILDREN_SINGLE_TEXT` from `flags` (keep `CHILDREN_KEYED` — keys are preserved by asLeaving).
- Prefer a small shared helper that strips child-shape flags whenever a transform replaces children (asLeaving today; future transforms get it for free).
- Test: exit-group over `host("div") { text("Item") }` → after asLeaving patch, the DOM text is wrapped in `<span data-kinetica-leaving>` (runtime flags assertion + JS regression).

### KNT-0002 (was F2) — Template transform: props callee unchecked (wrong DOM)
- `kinetica-compiler/src/KineticaIrTemplate.kt` L179-183: the props branch accepts any `IrCall`. Require `propsArgument.symbol.owner.kotlinFqName == propsOf` (add `propsOfFqName` to `KineticaTemplateSymbols`; the hoister's check at `KineticaIrHoist.kt:124-125` is the model — implement via the shared helper from KNT-0017).
- Test (harness): a component with `props = myProps("class", "x")` must NOT template (assert no "emitted N template definitions" message + rendered tree keeps computed props).

### KNT-0003 (was F3) — Template transform: unguarded expression lift (compiler ICE risk)
- `KineticaIrTemplate.kt` `extractSingleTextTemplate` (L175-203): before lifting `textValue`, walk it for `IrGetValue`/receiver references to symbols declared inside the content lambda (the lambda's own parameters incl. its ComponentScope receiver, and any local declarations). Any hit → return null (callsite stays on the current path). Implementation: collect the lambda's `IrValueSymbol`s (its receiver/params + declarations directly inside it, EXCLUDING symbols declared within the value expression itself or nested lambdas' own params — over-disqualifying is safe, but outer component params/receiver must NOT disqualify or valid `text(label, semantics = null)` stops templating).
- Test (harness): `host("p", props = propsOf("class","x")) { text(scopeExtension(), semantics = null) }` where `scopeExtension()` is a ComponentScope extension — must compile, not template, and render correctly.

### KNT-0004 (was F4) — TemplateNode leaks to serialization/diff: one normalization boundary
**Status:** Done (2026-07-07, codex TDD) — identity-preserving `Node.materializeDeep()` (Node.kt, copy-on-write recursion) applied at all boundaries incl. `encodeSignedChunk` (payload+integrity from one materialized instance) and hydration-plan NodeDiff nodes; Template arms of `hasSameDiffContainerAs`/`childrenForDiff` deleted outright; `decodeSignedChunk` returns the normalized chunk (defense in depth). Suites: runtime 174/174, server-components(+shared), browser JS, browser-tests build — all green.
- `kinetica-runtime/src/Node.kt`: add `public fun Node.materializeDeep(): Node` (TemplateNode → materialize() then recurse; Host/Fragment map children; Text/ClientRef identity — reuse existing `materializeTemplateNode` machinery L157-179).
- Apply at ALL boundaries where trees leave the render/patch world (Codex-verified list): `hydrationPlan` (`initialTree`, ServerComponents.kt:432), both `toServerRenderStream` paths incl. deferred patch nodes (L443, L493-497), `toInitialServerChunk`, `KineticaServerTransport.encodeNode/encodeChunk/encodeSignedChunk/encodeHydrationPlan` (L374/380/389/407 — direct transport callers must not leak `"type":"template"`; round-4 catch: `encodeSignedChunk` serializes the RAW chunk into the signed wrapper (:390-396) while hashing `encodeChunk`'s output (:387) — normalize once at its top so payload and integrity agree, else a fixed `encodeChunk` alone leaves the signed wire leaking templates with a still-passing integrity check), `BrowserUiSnapshot.treeJson` (BrowserKineticaApp.kt:77), and `diffNodes` entry (normalize both sides).
- After diff-entry normalization, DELETE all Template arms of `hasSameDiffContainerAs`/`childrenForDiff` outright. **Do NOT replace the Template/Template arm with an id/values/key compare** (original plan error, caught by Codex): values differing would fail container equality and degrade to whole-subtree Replace, losing exactly the child-level diff being restored.
- Do NOT bake derived text labels here (round-2 Codex catch: labels on wire TextNodes would make hydrating clients span-wrap every text via label-based `textNeedsElement`). Deserialized trees simply don't carry derived labels — documented limitation; an explicit `deriveTextLabel` marker is the follow-up if it ever matters.
- Keep the browser live renderer's TemplateNode fast path intact — samples/browser-tests asserts template patching without DOM recreation; normalization applies only at wire/snapshot/diff boundaries. Raw-HTML SSR (Html.kt:37,43) already materializes.
- Current anchors: transport `encodeNode`/`encodeChunk`/`encodeSignedChunk`/`encodeHydrationPlan` (ServerComponents.kt:374/380/389/407), `hydrationPlan.initialTree` (:432), stream chunks (:443, :493), `BrowserUiSnapshot.treeJson` (BrowserKineticaApp.kt:77), `diffNodes` (Node.kt:199).
- Outcome: wire format stays plain HostNode; diff granularity restored. Update NodeDiff tests expecting TemplateNode to materialized HostNode.
- Tests: RuntimeSmokeTest — serialize a template-bearing tree via `encodeChunk` AND `encodeSignedChunk`, assert JSON contains no `"template"` discriminator and one skeleton copy total; diff TemplateNode vs its materialized form yields child-level diff, not whole-subtree Replace.
- Related: KNT-0025 wants to replace `diffNodes` with the unified keyed reconciler — the normalization boundary added here must survive that move.

### KNT-0005 (was F5) — Each-row cache prefix eviction destroys sibling rows
**Status:** Closed — fixed by the frames rework, no action.
- The destructive prefix-eviction bug is gone: each rows now render into keyed frames (`enterKeyedChild`, ComponentScope.kt:678; `HashMap<Any, Frame>` Frames.kt:217; memo in `rowFrame.skipCache`), and eviction removes only frames absent from the live `seen` set — no string-prefix path exists.
- Optional (not scheduled): numeric representation flips (Int 1 vs Long 1L) still memo-miss because frame identity is raw-key; if that ever matters, canonicalize row keys before `seen`/`enterKeyedChild`.

### KNT-0006 (was F6) — Template events: one binding per element
**Status:** Done (2026-07-07, codex TDD, together with KNT-0014) — `MountedTemplateEvents(element, byPropName)` container replaces the single-slot `__kinetica`; dispatch = table lookup + `byPropName[propName]`; `removeTemplateEventBinding` identity-checks entries and clears `__kinetica` only when the container empties; template `enabled` Prop holes map to the `disabled` attribute and template click gates on it. Shim gained real listener registry + bubbling dispatch. Browser JS, runtime 181/181, browser-tests build green.
- Current state (re-verified 2026-07-07): the per-element `__kinetica` slot already exists and IS the dispatch view — `patchTemplateValues`' `TemplateHoleKinds.EventProp` branch (BrowserKineticaApp.kt:626-638) sets `element.asDynamic().__kinetica = created` per hole (:635), but the slot holds a SINGLE `MountedTemplateEvent`, so two event holes on one element overwrite each other — last hole wins at dispatch (`handleDelegatedEvent` reads one binding per element, :141-148). The per-hole `mounted.eventBindings[index]` array is the bookkeeping view (eventId updates), NOT the dispatch view.
- Fix: widen the slot to a per-element container `MountedTemplateEvents(element, byPropName: MutableMap<String, MountedTemplateEvent>)`; `handleDelegatedEvent`'s `as? MountedTemplateEvent` cast (:146) switches to the container, and dispatch stays one lookup — event-type→propName via the KNT-0014 shared table, then `byPropName[propName]` — so duplicate holes can't accumulate. Keep the per-hole `eventBindings` array for eventId updates. Disposal: dispose the container once — one binding's cleanup must not clear siblings'. Preserve keydown Enter filtering + preventDefault. Click parity: gate on the element's `disabled` DOM attribute AND special-case template Prop holes named `enabled` → map to the `disabled` attribute (the template Prop branch :615-625 only handles public attrs; `enabled` maps to `disabled` on the host path at :502/:528 — BrowserMapping.kt no longer exists, it was folded into BrowserKineticaApp.kt).
- Test (JS/browser test or harness-driven): TemplateDefinition with `event:onInput` + `event:onSubmit` holes on one element — both dispatch.

### KNT-0007 (was F7) — `data-kinetica-key` debug-gated on templates
**Status:** Done (2026-07-07, codex TDD) — mount/patch sync `DATA_KINETICA_KEY` unconditionally from `TemplateNode.reconcileKey` (KNT-0010's property; no duplicate helper); explicit→null restores the skeleton fallback; path/tag attrs stay debug-only. Production `innerHtml()` == `toSafeHtml()` snapshot test + all key transitions + reorder element-identity test (focus-restore fallback variant — shim querySelector is stubbed). Browser JS + runtime 181/181 green.
- `BrowserKineticaApp.kt` `mountTemplate` (:313) and `patchTemplate` (:574): add `TemplateNode.effectiveRootKey() = key ?: definition.skeleton.key`; set/remove `DATA_KINETICA_KEY` unconditionally from it; in patchTemplate compare previous vs next EFFECTIVE keys (explicit-key→null must restore the skeleton fallback, not remove the attribute). Note: production clones may already inherit the skeleton's key attr from the prototype — sync explicitly. Keep path/tag attrs debug-only.
- Test: production-mode (debug=false) snapshot equality `innerHtml()` vs `tree().toSafeHtml()` for a keyed template row; focus-restore test across a reorder.

### KNT-0008 (was F8) — Semantics: identity-comparable default (fixes opt-out inversion + matcher split + per-call allocation)
**Status:** Done (2026-07-07, codex TDD) — `DefaultTextSemantics` + identity-derived `effectiveSemantics()` in SemanticsTree.kt; `hasLabel` matches on it; legacy structural-derivation assertions flipped (RuntimeSmokeServerTest:512/:1087 — the old RuntimeSmokeTest monolith was split into Core/Slots/Server/Resource, hence the stale :3624 anchor); ui-dsl.md documents the opt-out + serialization limits. Suites: runtime 168/168, kinetica-test 22/22.
- Hoist `public val DefaultTextSemantics: Semantics = Semantics(role = Role.Text)` and use it as the default in BOTH `text()` (HostDsl.kt:93) AND `TextNode`'s own constructor default (Node.kt:87-90 — Codex catch: otherwise direct `TextNode("x")` loses derivation). One shared instance kills the per-call allocation (R4).
- `public fun Node.effectiveSemantics(): Semantics?` (public — kinetica-test consumes it): derive `label = value` ONLY when `this is TextNode && semantics === DefaultTextSemantics` (identity). Explicit `Semantics(role = Role.Text)` becomes a real opt-out (R2) — this is a DOCUMENTED contract change: update the semantics docs and RuntimeSmokeTest:3624-3633, which currently asserts structural derivation for an explicit instance.
- Identity does not survive serialization — accepted, documented limitation (baking was rejected, see KNT-0004: wire labels would trigger client span-wrapping); deserialized trees don't carry derived labels; explicit `deriveTextLabel` marker is the escape hatch if needed later. Test to update: RuntimeSmokeServerTest.kt:1077 (asserts structural derivation for an explicit instance — flips to opt-out).
- Replace `SemanticsTree.derivedSemantics` (L66-73) with `effectiveSemantics()`; update `hasLabel` (KineticaTest.kt:96-97) to match on it (R3 — matcher and tree agree).
- Tests: `hasLabel("Save")` finds plain `text("Save")`; explicit `Semantics(role = Role.Text)` yields NO derived label in tree or matcher; direct `TextNode("x")` derives.

### KNT-0009 (was F9) — Template qualification fragility (source regex / props-less / transform order)
- `KineticaIrTemplate.kt`: DELETE `hasExplicitNullArgument` + `sourceText` (L223-230, L89-93) — verified redundant: at IrGenerationExtension time a defaulted arg is an absent slot (already rejected at L193), an explicit null is a present IrConst null (L194). This also removes the silent templating kill on unreadable source paths.
- Props-less hosts: change `null -> return null` (L180) to produce an empty-props template (`buildPropsOfCall` arity-0 `propsOf()` exists). Same end-state for an explicit zero-arg `propsOf()`: relax `constPropsPairs` (L241 — currently returns null on empty) to return the empty list, keeping the odd-arity rejection — absent and explicit-empty props must template identically, or the KNT-0017 shared helper ends up with two contracts.
- Order-independence with a proof boundary: one per-file ConstProps index OWNED BY `KineticaIrGenerationExtension` and passed to both transformers (round-2: the hoister's `internedProps` is private to `KineticaHoistTransformer` — the index cannot live there); `IrGetField` props recognized only via that index; comment states the shared-analysis contract. Current order (templater at KineticaIrTransform.kt:100, before hoister at :101) keeps working either way.
- IR claim independently validated: defaulted args are absent slots at IrGenerationExtension time (JVM verified by running KineticaIrTemplateCompileTest — 3/3; JS uses the same common extension with default-parameter injection as a later lowering). Add a JS compile canary via samples/annotated-js.
- Tests (harness): props-less single-text host templates; propsOf host templates regardless of transform order.

### KNT-0010 (was F10) — Template rows lose K4 certification (gate-metric regression)
**Status:** Done (2026-07-07, codex TDD) — public allocation-free `Node.reconcileKey` (skeleton fallback); `renderEachRegion` certification generalized to it (cached bit inherits); `areKeyedTemplateRows` HashSet deleted with NO replacement needed — the frame path now covers template rows for all eaches; browser duplicates delegate to the property (both mounted and next sides skeleton-aware). Finding nuance vs the original ticket: explicit-key template rows already certified via the HashSet each render (the perf tax); the real RED was the skeleton-key fallback (3 failures). Runtime 181/181, browser JS green. Bench A/B deferred to the final sweep.
- Add `public val Node.reconcileKey: String?` to Node.kt (`HostNode -> key; TemplateNode -> key ?: definition.skeleton.key; else -> null`) — PUBLIC (internal isn't visible to kinetica-browser). Replace BOTH browser-private duplicates (`Node.reconcileKey()` AND `Mounted.reconcileKey()`, BrowserKineticaApp.kt:1093-1111 — they also ignore the skeleton fallback today).
- Site moved by the rework: certification now lives in `ComponentScope.renderEachRegion` (:659, host-only check at :696, cached bit stored at :704) — generalize it (and any non-memoized sibling) to `nodes[0].reconcileKey == rowKey`; cached hits inherit the fixed bit.
- Then DELETE `areKeyedTemplateRows` + its per-render HashSet fallback in `HostDsl.childShapeFlags` (:98-118).
- Test: EachKeyedFlagTest — keyed templateNode rows certify CHILDREN_KEYED in memoized AND non-memoized each, first and cached renders (current tests cover host rows only).

## Cleanup batch (verified items below the report cap)

### KNT-0011 (was C1) — Unify `unmount`/`disposeMountedSubtree`
- BrowserKineticaApp L400/L893: one recursive `detach(mounted)` doing `__kinetica` + `clientRefCount` bookkeeping, with DOM removal as the variation point — fixes the nested-ClientRef `clientRefCount` leak on keyed removal (permanent `refreshGeneratedAttributes` per render).

### KNT-0012 (was C2) — Bulk-clear walk skip
- Skip `disposeMountedSubtree` recursion entirely when `clientRefCount == 0` (verified: expando-nulling on discarded DOM is collectable garbage either way).

### KNT-0013 (was C3) — `hasNoKeyOverlap` allocation
- L864-885: reuse the scratch frame's `keyToNewIndex` (build new-side map first, probe old keys) instead of allocating a fresh HashSet per reorder patch.

### KNT-0014 (was C4) — `dispatchTo` duplication
**Status:** Done (2026-07-07, with KNT-0006) — `DelegatedEventPropNames` map is the single eventType→propName source; `DelegatedEventTypes` derives from its keys; both `dispatchTo` overloads resolve through it with the per-type behaviors (click gate, input payload, keydown preventDefault) preserved.

### KNT-0015 (was C5) — Slot/event generation unification
**Status:** Closed — obsolete; the rework already collapsed to a single `slotGeneration` driving slots and events (ComponentScope.kt:39,152-156; event roles are ordinal groups in Frames.kt).

### KNT-0016 (was C6) — `registerHostEvent` tail dedup
**Status:** Closed — obsolete; the duplicated touch+capture-recording tail is gone: browser-side per-element registration was replaced by event delegation (one listener per type, BrowserKineticaApp.kt:49 over `DelegatedEventTypes`), and the surviving runtime `registerHostEvent` (HostDsl.kt:201-208) is a single branch-free `frameEvent` delegate. Nothing left to dedup.

### KNT-0017 (was C7) — Compiler duplication: shared helpers for hoist + template transformers
- Extract `constPropsOf(call)` (propsOf fqName check; empty-pairs semantics: hoist skips empty — nothing to intern; template ACCEPTS empty per KNT-0009 — absent and zero-arg `propsOf()` both yield the empty-props template), the shared per-file ConstProps interned-field index (proof boundary for KNT-0009), `addStaticFileField(...)`, `isNullConst`.

### KNT-0018 (was C8) — `emitCachedEachRow` double lookup
**Status:** Closed — obsolete; `emitCachedEachRow`/`EachRowCache` were deleted by the frames rework (each-row caching moved to row frames — see KNT-0005/KNT-0023; L807 now sits inside `patchKeyedChildren`). Nothing left to dedup.

### KNT-0019 (was C9) — Scratch pool free-list
- L848-862 (`acquireKeyedPatchScratch`/`releaseKeyedPatchScratch`), L1029-1063 (`KeyedPatchScratchFrame`): replace depth-counter + cap with a free-list (`pool.removeLastOrNull() ?: Frame()` / `pool.add(frame)`), removing the desync failure class; keep a size cap.

### KNT-0020 (was C10) — Debug-mode template clones carry stale path/tag attrs
- (A#4): after cloning under `runtime.debug`, strip or rewrite descendant `data-kinetica-path`/`tag` attrs (they're stale copies of the prototype's paths and corrupt path-based focus fallback); simplest: remove those attrs from the prototype after `templatePrototype` builds it, since per-instance paths were never correct for clones.

### KNT-0021 — Harness/test dedup (opportunistic)
- Shared `withKotlinCoreEnvironment` + `RecordingMessageCollector` between `KineticaCompilationHarness` and `CompilerPluginWiringTest`; `invokeRender`/`assertFileField` helpers on `CompiledKineticaModule`; shared `run()` in `scripts/lib/run.mjs` for the four spawnSync copies.

### KNT-0022 — CI coverage for bench-jvm/docs-site
**Status:** Closed — done by the rework (bench-jvm/docs-site test steps present in ci.yml; plugin published first as mandatory).

**Cleanup re-verification note:** KNT-0011–0014, 0019, 0020 were verified against the PRE-rework tree; re-check each site briefly before applying (the frames migration moved runtime internals). Round-3 check (2026-07-07): KNT-0014's two `dispatchTo` overloads survive event delegation (BrowserKineticaApp.kt:157/:202) — still valid; KNT-0016's and KNT-0018's target code is gone — both closed above.

## Renderer perf & spec backlog (formerly perf-rewrite-design.md)

### KNT-0023 (was perf §1) — Intern slot/event key strings per scope
**Status:** Closed — superseded by frame ordinals (P4); instead of interning the key strings, the strings are gone.
- The mandatory compiler plugin's IR pass assigns every slot/event call site a dense static ordinal in a per-component *frame* (tree of instances: component calls and boundary branches are fixed children, `keyed`/`each` rows keyed children, wrapped content lambdas region frames). A state read is an array index; commit eviction is O(re-rendered frames) instead of scans over all slot metadata and host events; the cursor machinery (CursorMark boundary neutrality, slot/event cursor deltas in row/skippable caches) is deleted, and the slot-collision bug class died at compile time (FIR checker enforces the authoring rules). Each-row memoization and component skipping now cache on the row/component frame (inputs + cell versions + context reads). Bench outcome: see Context (13-op geomean 0.969× vs React).

### KNT-0024 (was perf §2) — 10k-table partial operations
- The last remaining bench gap, narrowed by KNT-0023: remove-10k 59.8ms (1.48× React) and update-every-10th-10k 43.3ms (1.26×) are still above React, while select-10k (8.7ms, 1.09×) and swap-10k (42.1ms, 0.78×) have effectively closed. Partial ops on 10k rows pay the O(rows) reference-walk diff and LIS over large child arrays even when 2 rows changed. Candidates: dirty-row short lists from cell subscriptions instead of full-tree walks, LIS skip when moves are adjacent transpositions.

### KNT-0025 (was perf §3) — Unify the two diffs; serializable patch ops; journal `DomPatch`
- Deferred from P1: the browser patcher is a recursive diff-and-apply in `kinetica-browser` (`BrowserKineticaApp.kt` + `ListReconcile.kt`), while server components still use the old positional `diffNodes` (`Node.kt`). The design wants one keyed reconciler emitting a serializable op list used by both, with debug-mode `JournalKind.DomPatch` entries completing the causal chain (event → cell writes → render → concrete DOM ops). This is a debuggability feature as much as a perf one — the project's stated priority order.
- Related: KNT-0004 adds materializeDeep normalization at the `diffNodes` entry — carry that boundary over to the unified reconciler.

### KNT-0026 (was perf §4) — Component-scoped re-rendering
**Status:** Deferred — not urgent while memoized full renders stay ~single-digit ms on 1k-row apps.
- Invalidation is still root-per-runtime: one dispatch → one full render (cheap now thanks to memoization, but O(app) not O(component)). The per-cell `renderDependencies` machinery already exists to scope re-renders to the components that read a written cell. Revisit if real apps show render-cost growth with app size.

### KNT-0027 (was perf §5) — Toolchain DCE / release mode
**Status:** Blocked upstream.
- The benchmark and docs pages run from post-link esbuild bundles, but library consumers of the `js/app` preview product still get the unminified multi-file ES-module graph (~1.6MB raw) with no DCE. Track Kotlin Toolchain release-mode/DCE support; revisit packaging when `js/app` leaves preview.

### KNT-0028 (was perf §6) — exitGroup/motion contract with the retained patcher
**Status:** Open — spec decision needed.
- Spec keeps exiting subtrees alive (`leaving` semantics); the patcher must treat them as "retain + mark", never remove. The rebuild renderer sidestepped this; the retained one has no decided contract yet. Needs a decision plus browser tests for exit transitions under patching (motion battery interplay). Related: KNT-0001 touches `asLeaving` flag semantics.

### KNT-0029 (was perf §7) — lazyEach/virtualization vs keyed moves
- Windowed rendering makes keys enter/leave constantly at window edges; the diff should handle it as edge inserts/removes, but this was never verified with a lazyEach bench case. Add one before relying on virtualization.

### KNT-0030 (was perf §8) — Controlled inputs: IME
- Property-based writes, skip-if-equal value sync, and typing-during-invalidation tests landed with P1. IME composition (the classic retained-mode input bug) remains untested — add a browser-tests case with composition events.

### KNT-0031 (was perf §9) — Memory gate not met
- Heap after 1k rows: **5.7MB** against the ≤5MB gate (React 4.4MB, vanilla 1.9MB). Close but above; the shadow tree and row caches are the suspects. First step: re-measure now that KNT-0023 landed (the key strings were also heap); attack only if a real gap remains.

## Order of work

1. Review fixes: KNT-0008 (semantics singleton first — KNT-0001's predicate and tests read it) → KNT-0001 → KNT-0004 → KNT-0010 (frame-era certification; one runtime pass with surviving cleanup items) → KNT-0006 + KNT-0007 (+ KNT-0011–0014, 0019, 0020 where still applicable — one renderer pass) → KNT-0002 + KNT-0003 + KNT-0009 + KNT-0017 (one compiler pass) → KNT-0021 (harness dedup).
2. The compiler plugin is MANDATORY for every module: each pass starts with `./kotlin publish mavenLocal -m kinetica-compiler && ./kotlin test -m kinetica-compiler --platform jvm` before building dependents — publish FIRST: the plugin resolves from the toolchain-local repo and even the compiler's own test fragment (via kinetica-runtime) needs the published artifact (mirrors ci.yml:26-30).
3. Each pass: build + module tests before moving on.
4. Perf/spec backlog (KNT-0024–0031): unscheduled, after the review fixes; KNT-0031 starts with a re-measure.

## Verification

- Full sweeps: `./kotlin test -m kinetica-runtime --platform jvm` (151+ tests incl. new KNT-0001/0008/0010 cases), `./kotlin test -m kinetica-compiler --platform jvm`, kinetica-browser build + `node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs`, both annotated samples (JVM + JS).
- Renderer behavior: browser-bench 13-op + tree smoke through the plugin-built bundle (`node bench/build-kinetica.mjs`, then `node bench/driver/bench.mjs --frameworks=kinetica --samples=3` and `driver/tree.mjs`) — all assertions green.
- Perf-backlog changes (KNT-0024+): `./kotlin build -m browser-bench && cd bench && node run-all.mjs --frameworks=kinetica`, then `node scripts/verify-browser.mjs` from the repo root — the script lives in root `scripts/`, not `bench/` (15 self-tests must stay green).
- Gate integrity: KNT-0010 must restore certification without the HashSet fallback — re-run the select10k/update10k A/B numbers and the K5 gate ops (pre-review plan.md, in git history) afterward (these fixes should only help); dom-count for the bulk-clear path (KNT-0012) — clear10k removeChild ~1.
- Wire format: serialize a template-bearing tree — no `"template"` discriminator, including via `encodeSignedChunk` (KNT-0004).
- Size check: `node scripts/size-report.mjs` within baseline tolerance.
