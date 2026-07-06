# Fix all issues from the K5/K6 implementation review

## Context

The K5/K6 implementation (uncommitted working tree, ~1,600 insertions) was reviewed with 8 finder angles + verification: **10 findings reported** (9 correctness, 1 efficiency; all CONFIRMED) plus verified cleanup items below the cap. This plan fixes all of them. **Second opinion (round 1):** every finding + fix independently verified by Codex: 8× AGREE, 2× PARTIAL, 0 disagreements; Codex ran the JVM compile-test validating F9's IR claim and caught one real plan error (F4 diff-arm simplification).

**Re-verification after the frame-ordinals rework (round 2, Codex per-finding + whole-plan):** the repo has since been reworked (commits 65224c6..0b2b08d: frames migration, compiler plugin mandatory for all modules v0.3.0, slot ordinals compiler-assigned, FIR checks=error). Verdicts against current HEAD: **F1–F4, F6–F10 STILL-VALID** (anchors updated below); **F5 FIXED-BY-REWORK** (EachRowCache/prefix-eviction machinery replaced by keyed frames + seen-set eviction — only an optional row-key canonicalization note remains); cleanup **C5 obsolete** (one slotGeneration now), **CI item done** (bench-jvm/docs-site in ci.yml). One new Codex catch folded in: label BAKING at wire boundaries (old F4/F8 idea) would make hydrated clients span-wrap texts via label-based textNeedsElement — baking is dropped; derived labels are a live-tree projection only.

## Correctness fixes

### F1 — Stale `CHILDREN_SINGLE_TEXT` flag: make the fast path self-defending + strip flags in `asLeaving`
- `kinetica-browser/src@js/BrowserKineticaApp.kt` `patchSingleTextChild` (L684): add guards — bail to the slow path when `mountedText.wrapped` is true OR `textNeedsElement(nextText)` is true. This makes ANY lying/stale flag degrade safely instead of freezing text (covers future predicate drift too).
- `kinetica-runtime/src/Boundary.kt` `asLeaving()` HostNode branch (L326-330): the copy rewrites child semantics (leaving=true) so strip `CHILDREN_SINGLE_TEXT` from `flags` (keep `CHILDREN_KEYED` — keys are preserved by asLeaving).
- Prefer a small shared helper that strips child-shape flags whenever a transform replaces children (asLeaving today; future transforms get it for free).
- Test: exit-group over `host("div") { text("Item") }` → after asLeaving patch, the DOM text is wrapped in `<span data-kinetica-leaving>` (runtime flags assertion + JS regression).

### F2 — Template transform: props callee unchecked (wrong DOM)
- `kinetica-compiler/src/KineticaIrTemplate.kt` L175-179: the props branch accepts any `IrCall`. Require `propsArgument.symbol.owner.kotlinFqName == propsOf` (add `propsOfFqName` to `KineticaTemplateSymbols`; the hoister's check at `KineticaIrHoist.kt:121` is the model — implement via the shared helper from C7).
- Test (harness): a component with `props = myProps("class", "x")` must NOT template (assert no "emitted N template definitions" message + rendered tree keeps computed props).

### F3 — Template transform: unguarded expression lift (compiler ICE risk)
- `KineticaIrTemplate.kt` `extractSingleTextTemplate` (L171-199): before lifting `textValue`, walk it for `IrGetValue`/receiver references to symbols declared inside the content lambda (the lambda's own parameters incl. its ComponentScope receiver, and any local declarations). Any hit → return null (callsite stays on the current path). Implementation: collect the lambda's `IrValueSymbol`s (its receiver/params + declarations directly inside it, EXCLUDING symbols declared within the value expression itself or nested lambdas' own params — over-disqualifying is safe, but outer component params/receiver must NOT disqualify or valid `text(label, semantics = null)` stops templating).
- Test (harness): `host("p", props = propsOf("class","x")) { text(scopeExtension(), semantics = null) }` where `scopeExtension()` is a ComponentScope extension — must compile, not template, and render correctly.

### F4 — TemplateNode leaks to serialization/diff: one normalization boundary
- `kinetica-runtime/src/Node.kt`: add `public fun Node.materializeDeep(): Node` (TemplateNode → materialize() then recurse; Host/Fragment map children; Text/ClientRef identity — reuse existing `materializeTemplateNode` machinery L157-179).
- Apply at ALL boundaries where trees leave the render/patch world (Codex-verified list): `hydrationPlan` (`initialTree`, ServerComponents.kt:432), both `toServerRenderStream` paths incl. deferred patch nodes (L443, L493-497), `toInitialServerChunk`, `KineticaServerTransport.encodeNode/encodeChunk/encodeHydrationPlan` (L374-375 — direct transport callers must not leak `"type":"template"`), `BrowserUiSnapshot.treeJson` (BrowserKineticaApp.kt:76), and `diffNodes` entry (normalize both sides).
- After diff-entry normalization, DELETE all Template arms of `hasSameDiffContainerAs`/`childrenForDiff` outright. **Do NOT replace the Template/Template arm with an id/values/key compare** (original plan error, caught by Codex): values differing would fail container equality and degrade to whole-subtree Replace, losing exactly the child-level diff being restored.
- Do NOT bake derived text labels here (round-2 Codex catch: labels on wire TextNodes would make hydrating clients span-wrap every text via label-based `textNeedsElement`). Deserialized trees simply don't carry derived labels — documented limitation; an explicit `deriveTextLabel` marker is the follow-up if it ever matters.
- Keep the browser live renderer's TemplateNode fast path intact — samples/browser-tests asserts template patching without DOM recreation; normalization applies only at wire/snapshot/diff boundaries. Raw-HTML SSR (Html.kt:37,43) already materializes.
- Current anchors: transport `encodeNode`/`encodeChunk`/`encodeHydrationPlan` (ServerComponents.kt:374/380/407), `hydrationPlan.initialTree` (:432), stream chunks (:443, :493), `BrowserUiSnapshot.treeJson` (BrowserKineticaApp.kt:73), `diffNodes` (Node.kt:205).
- Outcome: wire format stays plain HostNode; diff granularity restored. Update NodeDiff tests expecting TemplateNode to materialized HostNode.
- Tests: RuntimeSmokeTest — serialize a template-bearing tree, assert JSON contains no `"template"` discriminator and one skeleton copy total; diff TemplateNode vs its materialized form yields child-level diff, not whole-subtree Replace.

### F5 — FIXED BY THE FRAMES REWORK (no action)
- The destructive prefix-eviction bug is gone: each rows now render into keyed frames (`enterKeyedChild`, ComponentScope.kt:678; `HashMap<Any, Frame>` Frames.kt:217; memo in `rowFrame.skipCache`), and eviction removes only frames absent from the live `seen` set — no string-prefix path exists.
- Optional (not scheduled): numeric representation flips (Int 1 vs Long 1L) still memo-miss because frame identity is raw-key; if that ever matters, canonicalize row keys before `seen`/`enterKeyedChild`.

### F6 — Template events: one binding per element
- `BrowserKineticaApp.kt` `patchTemplateValues` EVENT_PROP branch (L625-637): per-element container `MountedTemplateEvents(element, byPropName: MutableMap<String, MountedTemplateEvent>)` in `__kinetica` (map keyed by propName — dispatch becomes one lookup after the event-type→propName mapping from C4, and duplicate holes can't accumulate). Keep the per-hole `eventBindings` array for eventId updates; the container is the dispatch view. Disposal: dispose the container once — one binding's cleanup must not clear siblings'. Preserve keydown Enter filtering + preventDefault. Click parity: gate on the element's `disabled` DOM attribute AND special-case template Prop holes named `enabled` → map to the `disabled` attribute (generic template prop patching only handles public attrs; `enabled` is private in BrowserMapping.kt:136).
- Test (JS/browser test or harness-driven): TemplateDefinition with `event:onInput` + `event:onSubmit` holes on one element — both dispatch.

### F7 — `data-kinetica-key` debug-gated on templates
- `BrowserKineticaApp.kt` `mountTemplate` (:313) and `patchTemplate` (:574): add `TemplateNode.effectiveRootKey() = key ?: definition.skeleton.key`; set/remove `DATA_KINETICA_KEY` unconditionally from it; in patchTemplate compare previous vs next EFFECTIVE keys (explicit-key→null must restore the skeleton fallback, not remove the attribute). Note: production clones may already inherit the skeleton's key attr from the prototype — sync explicitly. Keep path/tag attrs debug-only.
- Test: production-mode (debug=false) snapshot equality `innerHtml()` vs `tree().toSafeHtml()` for a keyed template row; focus-restore test across a reorder.

### F8 — Semantics: identity-comparable default (fixes opt-out inversion + matcher split + per-call allocation)
- Hoist `public val DefaultTextSemantics: Semantics = Semantics(role = Role.Text)` and use it as the default in BOTH `text()` (HostDsl.kt:93) AND `TextNode`'s own constructor default (Node.kt:87-90 — Codex catch: otherwise direct `TextNode("x")` loses derivation). One shared instance kills the per-call allocation (R4).
- `public fun Node.effectiveSemantics(): Semantics?` (public — kinetica-test consumes it): derive `label = value` ONLY when `this is TextNode && semantics === DefaultTextSemantics` (identity). Explicit `Semantics(role = Role.Text)` becomes a real opt-out (R2) — this is a DOCUMENTED contract change: update the semantics docs and RuntimeSmokeTest:3624-3633, which currently asserts structural derivation for an explicit instance.
- Identity does not survive serialization — accepted, documented limitation (baking was rejected, see F4: wire labels would trigger client span-wrapping); deserialized trees don't carry derived labels; explicit `deriveTextLabel` marker is the escape hatch if needed later. Test to update: RuntimeSmokeServerTest.kt:1077 (asserts structural derivation for an explicit instance — flips to opt-out).
- Replace `SemanticsTree.derivedSemantics` (L66-73) with `effectiveSemantics()`; update `hasLabel` (KineticaTest.kt:96-97) to match on it (R3 — matcher and tree agree).
- Tests: `hasLabel("Save")` finds plain `text("Save")`; explicit `Semantics(role = Role.Text)` yields NO derived label in tree or matcher; direct `TextNode("x")` derives.

### F9 — Template qualification fragility (source regex / props-less / transform order)
- `KineticaIrTemplate.kt`: DELETE `hasExplicitNullArgument` + `sourceText` (L85-89, 219-226) — verified redundant: at IrGenerationExtension time a defaulted arg is an absent slot (already rejected at L189), an explicit null is a present IrConst null (L190). This also removes the silent templating kill on unreadable source paths.
- Props-less hosts: change `null -> return null` (L175) to produce an empty-props template (`buildPropsOfCall` arity-0 `propsOf()` exists).
- Order-independence with a proof boundary: one per-file ConstProps index OWNED BY `KineticaIrGenerationExtension` and passed to both transformers (round-2: the hoister's `internedProps` is private to `KineticaHoistTransformer` — the index cannot live there); `IrGetField` props recognized only via that index; comment states the shared-analysis contract. Current order (templater at KineticaIrTransform.kt:94, before hoister) keeps working either way.
- IR claim independently validated: defaulted args are absent slots at IrGenerationExtension time (JVM verified by running KineticaIrTemplateCompileTest — 3/3; JS uses the same common extension with default-parameter injection as a later lowering). Add a JS compile canary via samples/annotated-js.
- Tests (harness): props-less single-text host templates; propsOf host templates regardless of transform order.

### F10 — Template rows lose K4 certification (gate-metric regression)
- Add `public val Node.reconcileKey: String?` to Node.kt (`HostNode -> key; TemplateNode -> key ?: definition.skeleton.key; else -> null`) — PUBLIC (internal isn't visible to kinetica-browser). Replace BOTH browser-private duplicates (`Node.reconcileKey()` AND `Mounted.reconcileKey()`, BrowserKineticaApp.kt:1093-1107 — they also ignore the skeleton fallback today).
- Site moved by the rework: certification now lives in `ComponentScope.renderEachRegion` (:659, host-only check at :696, cached bit stored at :704) — generalize it (and any non-memoized sibling) to `nodes[0].reconcileKey == rowKey`; cached hits inherit the fixed bit.
- Then DELETE `areKeyedTemplateRows` + its per-render HashSet fallback in `HostDsl.childShapeFlags` (:98-118).
- Test: EachKeyedFlagTest — keyed templateNode rows certify CHILDREN_KEYED in memoized AND non-memoized each, first and cached renders (current tests cover host rows only).

## Cleanup batch (verified items below the report cap)

- **C1** `unmount`/`disposeMountedSubtree` unification (BrowserKineticaApp L399/L892): one recursive `detach(mounted)` doing `__kinetica` + `clientRefCount` bookkeeping, with DOM removal as the variation point — fixes the nested-ClientRef `clientRefCount` leak on keyed removal (permanent `refreshGeneratedAttributes` per render).
- **C2** Bulk-clear walk: skip `disposeMountedSubtree` recursion entirely when `clientRefCount == 0` (verified: expando-nulling on discarded DOM is collectable garbage either way).
- **C3** `hasNoKeyOverlap` (L863-884): reuse the scratch frame's `keyToNewIndex` (build new-side map first, probe old keys) instead of allocating a fresh HashSet per reorder patch.
- **C4** `dispatchTo` duplication: one shared eventType→propName table used by the HostNode and template overloads (feeds F6).
- **C5** OBSOLETE — the rework already collapsed to a single `slotGeneration` driving slots and events (ComponentScope.kt:39,152-156; event roles are ordinal groups in Frames.kt).
- **C6** `registerHostEvent` (L451-470): dedup the touch+capture-recording tail shared by both branches.
- **C7** Compiler duplication: extract shared helpers used by hoist + template transformers — `constPropsOf(call)` (propsOf fqName check; empty-pairs semantics decided per call site — template rejects empty, hoist skips), the shared per-file ConstProps interned-field index (proof boundary for F9), `addStaticFileField(...)`, `isNullConst`.
- **C8** `emitCachedEachRow` (L807): return the `EachRowCache?` so the caller reads `certifiedKeyedHost` off it — removes the double map lookup per hit.
- **C9** Scratch pool (L847-861, 1028-1062): replace depth-counter + cap with a free-list (`pool.removeLastOrNull() ?: Frame()` / `pool.add(frame)`), removing the desync failure class; keep a size cap.
- **C10** Debug-mode template clones (A#4): after cloning under `runtime.debug`, strip or rewrite descendant `data-kinetica-path`/`tag` attrs (they're stale copies of the prototype's paths and corrupt path-based focus fallback); simplest: remove those attrs from the prototype after `templatePrototype` builds it, since per-instance paths were never correct for clones.
- **CI**: DONE by the rework (bench-jvm/docs-site test steps present in ci.yml; plugin published first as mandatory).
- **Harness/test dedup** (from reuse findings, do opportunistically): shared `withKotlinCoreEnvironment` + `RecordingMessageCollector` between `KineticaCompilationHarness` and `CompilerPluginWiringTest`; `invokeRender`/`assertFileField` helpers on `CompiledKineticaModule`; shared `run()` in `scripts/lib/run.mjs` for the four spawnSync copies.

**Cleanup re-verification note:** C1–C4, C6, C8–C10 were verified against the PRE-rework tree; re-check each site briefly before applying (the frames migration moved runtime internals; C6's registerHostEvent and C8's emitCachedEachRow shapes have changed or vanished — apply only where the pattern still exists).

## Order of work

1. F8 (semantics singleton first — F1's predicate and tests read it) → F1 → F4 → F10 (frame-era certification; one runtime pass with surviving cleanup items) → F6 + F7 (+ C1–C4, C9, C10 where still applicable — one renderer pass) → F2 + F3 + F9 + C7 (one compiler pass) → harness dedup.
2. The compiler plugin is now MANDATORY for every module: each pass starts with `./kotlin test -m kinetica-compiler --platform jvm && ./kotlin publish mavenLocal -m kinetica-compiler` before building dependents (mirrors ci.yml).
3. Each pass: build + module tests before moving on.

## Verification

- Full sweeps: `./kotlin test -m kinetica-runtime --platform jvm` (151+ tests incl. new F1/F5/F8/F10 cases), `./kotlin test -m kinetica-compiler --platform jvm`, kinetica-browser build + `node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs`, both annotated samples (JVM + JS).
- Renderer behavior: browser-bench 13-op + tree smoke through the plugin-built bundle (`node bench/build-kinetica.mjs`, then `node bench/driver/bench.mjs --frameworks=kinetica --samples=3` and `driver/tree.mjs`) — all assertions green.
- Gate integrity: F10 must restore certification without the HashSet fallback — re-run the select10k/update10k A/B numbers and the plan.md K5 gate ops afterward (these fixes should only help); dom-count for the bulk-clear path (C2) — clear10k removeChild ~1.
- Wire format: serialize a template-bearing tree — no `"template"` discriminator (F4).
- Size check: `node scripts/size-report.mjs` within baseline tolerance.
