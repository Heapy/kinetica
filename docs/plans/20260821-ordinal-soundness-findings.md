# Review Findings: Commit 657eef5 ("Fail compilation for unassigned Kinetica ordinals")

Deep multi-agent review, 2026-08-21. Method: 10 finder angles → verification → gap
sweep. Most items were confirmed by actually compiling: forced rebuilds against the
new plugin (kinetica-runtime/test **19 errors**, kinetica-persist/test **5**,
bench-jvm/src **4**) plus runtime probes through `KineticaCompilationHarness`.

**Line numbers are as of commit 657eef5** — HEAD has since advanced and a
comment-cleanup pass shifted `KineticaFirExtension.kt`. Re-locate by symbol name.

Fixed by the plan in `20260821-ordinal-soundness.md`. Task mapping:

| ID  | Site | One-line label | Plan task |
|-----|------|----------------|-----------|
| F1  | KineticaFirExtension.kt:255 | `render { }` treated as ordinal consumer — 28 downstream compile errors | Task 4 |
| F2  | KineticaFirExtension.kt:263 | nullable-handler exemption: compile-clean code crashes or aliases | Task 5 |
| F3  | KineticaFirExtension.kt:440 | region frames never fork on re-entry — content invoked twice aliases | Task 3 |
| F4  | KineticaFirExtension.kt:377 | blanket single-run whitelist covers deferred handler lambdas | Task 6 |
| F5  | KineticaFirExtension.kt:377 | `each`/`lazyEach` key selector treated single-run | Task 6 |
| F6  | KineticaFirExtension.kt:445 | val-stored lambdas invisible to rule F, IR still numbers them | Task 7 |
| F7  | ComponentScope.kt:762 | `each` in a loop: iteration 2 evicts iteration 1's rows | Task 9 |
| F8  | KineticaFirExtension.kt:272 | non-literal `@UiComponent` content bypasses rule F, crashes at render | Task 8 |
| F9  | KineticaIrFrames.kt:385 | `suspendSubtree(key = ...)`: IR declines silently, guaranteed crash | Task 10 |
| F10 | KineticaIrFrames.kt:452 | unstageable component receiver: crash or wrong-frame render | Task 11 |
| F11 | KineticaFirExtension.kt:256 | `callableId` bail precedes `@UiComponent` check | Task 12 |
| F12 | KineticaFirExtension.kt:283 | FIR receiver-based vs IR package-based lambda classification | Tasks 1, 14 |
| F13 | KineticaFirExtension.kt:91 | hand-written name lists where contracts (`callsInPlace`) exist | Task 2 |
| F14 | KineticaFirExtension.kt:462 | nested-report suppression drops diagnostics on early exits | Task 13 |
| F15 | KineticaFirExtension.kt:78 | FIR tables are verbatim copies of IR tables — drift twice already | Task 1 |
| S1  | KineticaCompilerRegistrar.kt:33 | `checks=off` removes soundness rules entirely | Task 15 |
| S2  | KineticaFirExtension.kt:132 | hardcoded "Use each(...)" advice, wrong or impossible in context | Task 16 |
| S3  | KineticaFirExtension.kt:400 | dead source-offset matcher — only branch that can match wrong lambda | Task 13 |
| S4  | KineticaFirExtension.kt:184 | expensive component-lambda probe runs before cheap early-return | Task 17 |
| S5  | KineticaCompilationHarness.kt:93 | `kinetica-compile-*` temp trees never cleaned | Task 18 |

---

## Task 20 verification walk (2026-08-21)

Every finding re-verified against HEAD by walking the mapping table above, item by
item. "Dedicated tests" are the pins that fail if the fix regresses; compiler tests
live in `kinetica-compiler/test/`, runtime tests in `kinetica-runtime/test/`.

| ID | Verdict | Dedicated tests (primary pins) |
|----|---------|--------------------------------|
| F1 | ✅ fixed (Task 4) | `renderEntryPointInsideRepeatCompilesAndRuns`, `renderEntryPointInsideAssertFailsWithCompiles` |
| F2 | ✅ fixed (Task 5) | `nullableTypedHandlerInForEachLambdaIsReported`, `nullableTypedHandlerInForLoopIsReported`, `nullLiteralHandlersInLoopCompileAndRenderWithoutEvents` |
| F3 | ✅ fixed (Task 3) | `contentLambdaInvokedTwiceForksRegionFramesPerInvocation`, `regionReenteredWithinOneRenderForksSiblingFrames`, `forkedRegionSiblingIsDeactivatedWhenLaterRenderEntersOnce` |
| F4 | ✅ fixed (Task 6) | `deferredHandlerLambdasAreMultiRunHosts`, `deferredHandlerSlotCallsFailCompileWhenChecksAreOff`, `deferredHandlersAndRegionContentCompileAndDispatchAtChecksError` |
| F5 | ✅ fixed (Task 6) | `eachAndLazyEachKeySelectorsAreMultiRunHosts`, `eachKeySelectorSlotCallsFailCompileWhenChecksAreOff` |
| F6 | ✅ fixed (Task 7) | `valStoredLambdaWithOrdinalConsumersIsReported`, `varargLambdaElementWithOrdinalConsumersIsReported`, `valStoredLambdaSlotCallsFailCompileWhenChecksAreOff`, `valStoredLambdaWithoutOrdinalConsumersCompiles` |
| F7 | ✅ fixed (Task 9; `lazyEach` sibling hazard closed in Task 20) | `eachInsideLoopPreservesRowStateAcrossRenders`, `eachInvocationsSharingUserKeysKeepIndependentRowState`, `eachInsideLoopStillDisposesRowsWhoseKeysLeave`, `vanishedEachInvocationDisposesItsRows`; lazyEach: `lazyEachInvocationsSharingUserKeysKeepIndependentRowState`, `lazyEachVisibleOnlyInLoopKeepsSiblingInvocationRows`, `lazyEachPersistentSlotsInLoopKeepsSiblingInvocationRows` |
| F8 | ✅ fixed (Task 8) | `ruleC_hoistedComponentContentArgumentIsReported`, `ruleC_forwardedContentValuesRemainAllowed`, `ruleC_literalComponentContentArgumentsCompile` |
| F9 | ✅ fixed (Task 10) | `suspendSubtreeExplicitKeyIsReported`, `suspendSubtreeExplicitKeyFailsCompileWhenChecksAreOff`, `suspendSubtreeNullOrImplicitKeyCompilesAndTransforms`, `persistentStateExplicitKeyIsReported` |
| F10 | ✅ fixed (Task 11) | `ruleH_componentCallResultReceiverIsReported`, `ruleH_nestedArgumentReceiverStealIsReported`, `ruleH_safeCallAndSmartCastReceiversAreReported`, `stagedOrdinalConsumedInDifferentFrameThrowsImmediately` |
| F11 | ✅ fixed (Task 12; latent in Kotlin 2.4.10 — see the plan note) | `localComponentFunctionCallsInRepeatedContextsAreReported` |
| F12 | ✅ fixed (Tasks 1, 14) | `firAndIrAgreeOnKeyedRegionContent` / `…EachRegionContent` / `…LazyEachContentAndPlaceholder` / `…SingleRunLambdaHosts`, `everyTopLevelKineticaLambdaFunctionHasAnExplicitFramePolicyClassification`, `deriveComputeLambdaOrdinalConsumerIsReported` (+ the invalidate / serverActionStub FIR and checks=off IR pins) |
| F13 | ✅ fixed (Task 2) | `runCatchingLambdaHostCompilesAndNumbersOrdinals`, `takeIfContractLambdaHostCompilesAndNumbersOrdinals`, `userExactlyOnceContractHostCompilesAndNumbersOrdinals`, `userAtMostOnceContractHostCompilesAndNumbersOrdinals`, `localFunctionHostStaysMultiRunViaNameListFallback` |
| F14 | ✅ fixed (Task 13) | `multiRunReportSurvivesOuterConsumerRuleAEarlyExit`, `multiRunNestedOrdinalCallsReportOnlyTheOutermostConsumer` |
| F15 | ✅ fixed (Task 1) | the four `firAndIrAgree…` drift tests (both phases now read `KineticaFramePolicy`) |
| S1 | ✅ fixed (Task 15) | `checksOffKeepsMultiRunOrdinalRuleAnError`, `checksOffKeepsValStoredLambdaRuleAnError`, `checksOffKeepsSlotCallOutsideComponentAnError`, `checksWarningKeepsSoundnessRulesAsErrors`, `checksWarningDowngradesStyleDiagnosticsToWarnings`, `checksOffSuppressesStyleDiagnostics` |
| S2 | ✅ fixed (Task 16) | `multiRunKeyedConstructGetsHoistAdviceInsteadOfEachKeyedAdvice` (+ the flagged-`each` expectation in `multiRunRegionAndComponentCallsAreReported`) |
| S3 | ✅ fixed (Task 13) | dead matcher deleted; symbol-identity-only matching pinned by `multiRunNestedOrdinalCallsReportOnlyTheOutermostConsumer` and `multiRunReportSurvivesOuterConsumerRuleAEarlyExit` |
| S4 | ✅ fixed (Task 17) | none by design — a pure evaluation-order reorder of a pure predicate; the full checker suite is the regression check (recorded in the plan's Task 17) |
| S5 | ✅ fixed (Task 18) | `KineticaCompilationHarnessTest` (expect-errors cleanup, success-path cleanup on close, failure-path cleanup) |

Two gaps surfaced during implementation were weighed against the Overview promise
("compile-clean code never throws `MissingKineticaPluginException` and never silently
aliases") and CLOSED in Task 20 — both were compile-clean shapes that crashed or
aliased at runtime:

- **Content-wrapper call inside a multi-run lambda in a component body**
  (`listOf(1).forEach { helper { Badge() } }`, the ⚠️ recorded at Task 15, including
  its stored-lambda door `val row = { helper { Badge() } }`): now a FIR soundness
  error (`COMPONENT_CONTENT_IN_MULTI_RUN_LAMBDA`, active in every checks mode). The
  rule keys on COMPONENT_BODY containment — exactly where IR's gated walker never
  wraps the content — so entry content stays compiling and rendering (the ungated
  entry-point pass wraps there): `componentContentWrapperInMultiRunLambdaIsReported`,
  `componentContentWrapperInStoredLambdaIsReported`,
  `multiRunComponentTypedHelperFailsCompileWhenChecksAreOff` vs.
  `componentContentWrapperInEntryContentCompiles`,
  `entryContentUserWrapperRendersWithStableIdentity`,
  `multiRunCallWithComponentTypedLambdaArgumentCompiles`.
- **Correction (post-completion review):** the Task 20 sweep's "entry-content
  double-wrap suspicion retired — green" verdict was WRONG. The double wrap was real:
  `transformEntryPoints` wrapped a nested wrapper's content at its own call
  (bottom-up) and the enclosing content's fresh-region walk wrapped/staged the same
  literal again (2× `beginRegionFrame`, 2× `ordinal(0)`, one consume). The retiring
  probe stayed green only because its leaked entries never crossed a frame; the
  review's probe `runtime.render { Outer(label = run { helper { Badge() }; "x" }) }`
  crashed at first render on the Task 11 frame-pairing check. Fixed after review
  (idempotent `wrapFreshRegionOf` + `transformArgumentsSelectively` skipping
  `@UiComponent`-typed literals), pinned by
  `entryContentWrapperInsideComponentCallArgumentRendersOnce` and
  `contractSingleRunContentWrapperWrapsContentExactlyOnce`.
- **`lazyEach` in a loop** shared the F7 hazard shape (flagged at Task 9): rows now
  carry the render-pass index like `each` rows (`Frame.beginKeyedPass`), and the
  VisibleOnly/PersistentSlots retention sweeps are scoped to their own invocation's
  rows; single-invocation behavior is byte-identical (pass 0 keeps bare keys):
  the four `lazyEach…` tests listed under F7 plus
  `lazyEachVisibleOnlySingleInvocationStillDisposesHiddenRows`.

## Primary findings (verified)

### F1 — KineticaFirExtension.kt:255

`consumesCompilerOrdinal` returns true for any call that merely passes a lambda
literal to a `@UiComponent`-typed parameter, so `KineticaRuntime.render { }` /
`KineticaTest.render { }` become "ordinal consumers" and rules D and F reject the
framework's own re-render idiom — although the IR pass numbers zero ordinals for such
a call.

Verified by forcing rebuilds against the new plugin: kinetica-runtime/test 19 errors
(hosts runTest x14, assertFailsWith x4, repeat x1 — e.g. RuntimeSmokeSlotsTest.kt:877
`repeat(2) { sampled.render(scope) { text("Sampled") } }`), kinetica-persist/test 5
errors, bench-jvm/src 4 errors (main.kt:293/304/311/329, host `Benchmark`), plus ~57
KineticaTest.render sites in kinetica-test/kinetica-data/kinetica-forms. IR's
visitCall falls through to `wrapAnnotatedContentArguments(expression); return
expression` (KineticaIrFrames.kt:287) for `render` — no fillOrdinal, no
stageComponentCall — and `transformEntryPoints` (:513) descends into loops and lambdas
ungated, so all this code is correct at runtime (the 213 kinetica-runtime jvm tests
pass on the stale cached compile). `checks` defaults to "error"
(KineticaSourceModel.kt:15), so every downstream consumer breaks on upgrade.

### F2 — KineticaFirExtension.kt:263 (REGRESSION)

`consumesCompilerOrdinal` exempts button/textInput/checkbox from rules D and F
whenever the handler's STATIC type is nullable, but IR numbers these call sites
unconditionally (KineticaIrFrames.kt:277-279) and the runtime branches on the VALUE —
so a nullable-typed, non-null-at-runtime handler either crashes or silently aliases.

Both modes probe-verified at checks=error. (a) In a `forEach`, IR never descends,
`ordinal` keeps its -1 default, and `registerHostEvent` (HostDsl.kt:216) throws
`MissingKineticaPluginException: A Kinetica host event ran without a
compiler-assigned ordinal` on first render — compile-clean code that crashes, the
exact opposite of the commit title. (b) In a `for` loop, IR does descend and fills one
static event ordinal for all iterations; `Frame.event` (Frames.kt:186-190) finds the
existing HostEventGroup and calls `runtime.updateEvent(existing, callback)`, so all
rows emit `event:onClick=event-0` and clicking row "a" ran row "c"'s closure. Before
this commit rule D rejected both. The new test
`nullableOptionalHandlersRemainAllowedInRepeatedContexts` (KineticaFirCheckerTest.kt:650)
enshrines the hole. `isDefinitelyNonNull` (:291) is also true-for-nullable on Java
platform types and unbounded type parameters. The sound proxy is "argument absent or
literal null", not "declared type is nullable".

### F3 — KineticaFirExtension.kt:440

Rule F treats every `@UiComponent`-typed lambda literal as a single-run numbering
boundary (`isComponentTypedLambda(...) -> break@search`), but nothing checks that the
callee invokes that content parameter at most once — and unlike fixed child frames,
region frames have no re-entry fork, so a helper that calls its content twice silently
aliases every slot inside it.

`fun ComponentScope.twice(content: @UiComponent ComponentScope.() -> Unit) {
content(); content() }` used as `@UiComponent fun ComponentScope.Fan() { twice { val
n = state { 0 }; button(onClick = { n.value++ }) { text(n.value.toString()) } } }`
compiles clean at checks=error: rule F breaks the search at line 440 and the
`twice(...)` call itself sits in no multi-run lambda. IR's
`wrapAnnotatedContentArgumentsOf` (KineticaIrFrames.kt:527-539) builds exactly ONE
FrameTable static per lambda literal, and `Frame.enterRegionChild` (Frames.kt:240-242)
is a bare `map.getOrPut(table) { Frame(table, this) }`, so both invocations enter the
same Frame: the second `state { 0 }` returns the first invocation's cell and both
buttons share one counter and one event ordinal. The codebase already knows this
hazard — `enterFixedChild` (Frames.kt:211-213) forks to an invocation-indexed sibling
with the comment "Re-entered within one render (a content wrapper invoked twice):
fork ... so repeated invocations cannot alias state" — `enterRegionChild` simply does
not. This is the commit's own new IR test helper (KineticaIrFrameCompileTest.kt:430-435)
with `content()` called twice: the promised fail-fast becomes silent aliasing.

### F4 — KineticaFirExtension.kt:377

`LambdaHost.isKnownSingleRun()` opens with `if (callee.isKineticaDsl()) return true`,
whitelisting EVERY lambda parameter of EVERY ComponentScope DSL function — including
the deferred handler blocks `event { }`, `hostEvent`, `hostEventBlock`,
`launchEffect`, `watch`, `action`, which run at dispatch/post-commit time and
arbitrarily often, contradicting the rule's own doc "lambdas known to run at most once
per frame".

Probe-verified compile-clean at checks=error: two sibling components, `First()` with
`button(onClick = { log += "first=" + state { "first" }.value })` and `Second()` with
the analogous "second" handler. Dispatching both buttons yields `log =
"first=first,second=first"` — Second's handler read First's state cell. The inner
`state` gets a real ordinal in its component's region, but the lambda executes after
commit when `currentFrame` is the growable `rootFrame` (table == null, so
`ensureSlotCapacity` grows silently instead of range-checking), so different
components collide on one root-frame ordinal. No diagnostic, no exception.

### F5 — KineticaFirExtension.kt:377

The same blanket whitelist ignores WHICH parameter the lambda is bound to (unlike
`isRegionContent()` directly above, which consults REGION_CONTENT_PARAMETERS), so the
per-item `key` selector of `each`/`lazyEach` is treated as single-run while IR numbers
it with the ENCLOSING region's counters (KineticaIrFrames.kt:394-398).

Probe-verified: `@UiComponent fun ComponentScope.Rows(items: List<String>) {
each(items, key = { item -> state { item }.value }) { item -> text(item) } }` with
items=[a,b,c] compiles clean at checks=error and the compiler reports `Rows: framed
(slots=4, ...)` — the key-selector `state` got a static slot. At render the first
invocation creates the cell holding "a" and every later invocation returns the same
cell, so all three items produce key "a" and `eachRegion` throws
`java.lang.IllegalStateException: Duplicate key: a`.

### F6 — KineticaFirExtension.kt:445

`val host = findLambdaHost(elements, index, element) ?: continue` treats "this lambda
is not a resolved call argument" as safe, so a lambda stored in a local `val`/property
is invisible to rule F — while the IR walker reaches it through `visitVariable` ->
super -> children and numbers its body with the enclosing region's ordinals.

Probe-verified at checks=error: `@UiComponent fun ComponentScope.ValLambdaFan(items:
List<Int>) { val row: (Int) -> Unit = { i -> val s = state { i }; text("row=" + i + "
state=" + s.value) }; items.forEach { row(it) } }` compiles with no diagnostics and
renders `row=10 state=10`, `row=20 state=10`, `row=30 state=10` — all invocations
share slot ordinal 0. IR's `transformArgumentsSelectively` (KineticaIrFrames.kt:486)
only gates lambdas in call-argument position, so the ordinal is assigned but unsound:
neither a compile error nor a MissingKineticaPluginException. `unwrapArgument()` also
never unwraps `FirVarargArgumentsExpression`, so vararg lambda elements escape the
same way.

### F7 — kinetica-runtime/src/ComponentScope.kt:762

The new test `keyedAndEachRemainAllowedDirectlyInsideLoops` blesses `each(...)` inside
a `for` loop, but `renderEachRegion` ends every call by evicting each keyed child of
that static ordinal whose key is not in the current call's `seen` set — so iteration 2
destroys iteration 1's row frames on every render.

Probe-verified. `for (batch in listOf(listOf(1,2), listOf(3,4))) { each(batch, key =
{ it }, memoize = false) { item -> val seen = state { initCount++; ... };
text(seen.value) } }` rendered 3 times gives `initCount = 12` (all 4 rows
re-initialised every render); the single-batch control with the same 4 rows and 3
renders gives `initCount = 4` (state preserved). Both loop iterations share one static
child ordinal, so `currentFrame.keyedChildKeys(ordinal).forEach { if (it !in seen)
removeKeyedChild(...) }` disposes rows 1-2 — dropping their state and unregistering
their host events — while their nodes are already in the emitted tree. `keyed` is
unaffected (`keyedRegion` -> `getOrPut`). The added test uses a single batch and
cannot catch this.

### F8 — KineticaFirExtension.kt:272

`hasComponentTypedLambdaArgument` only matches a lambda LITERAL
(`argument.unwrapArgument() is FirAnonymousFunctionExpression`), so hoisting the
content into a variable bypasses rule F — and IR's `wrapAnnotatedContentArgumentsOf`
has the same literal-only restriction (`as? IrFunctionExpression ?: continue`,
KineticaIrFrames.kt:535), leaving the content unwrapped and crashing at runtime with
nothing reported.

`val content: @UiComponent ComponentScope.() -> Unit = { Badge() };
listOf(1,2).forEach { runtime.render(content) }` — FIR's guard at line 184 returns
early (no literal) so no diagnostic; IR never wraps `content` in a region and never
stages `Badge()`, so `beginComponentFrame` -> `consumeStagedOrdinal` throws
MissingKineticaPluginException on first render. The mirror image is that F1's false
positive disappears under an irrelevant refactor: wrapping the render in a nested
local `fun render() = ...` makes `multiRunLambdaHost` break at FirNamedFunction and
the error vanish — which is the only reason kinetica-test/src/KineticaTest.kt:179,237
and kinetica-forms/test/FormsSmokeTest.kt:31 still compile.

### F9 — KineticaIrFrames.kt:385

`suspendSubtree` with an explicit non-null `key` is an ordinal the plugin deliberately
declines to assign — `transformRegion` bails to the legacy path and only logs at
`CompilerMessageSeverity.LOGGING` — yet this commit adds no FIR rule for it, so the
headline claim does not cover a construct that is GUARANTEED, not merely likely, to
throw.

`@UiComponent fun ComponentScope.Profile(id: String) { suspendSubtree(key = id,
fallback = { text("loading") }) { loadProfile(id) } }` compiles clean at checks=error
— rule C sees two lambda literals, rules D/F see no loop and no multi-run host.
`transformRegion` hits `if (keyArgument != null && !keyArgument.isNullConst())`, calls
`report(...)` which is `messageCollector.report(CompilerMessageSeverity.LOGGING, ...)`
(KineticaIrTransform.kt:293-294, invisible without -verbose), and returns the call
un-retargeted. The untransformed `ComponentScope.suspendSubtree` (Boundary.kt:58-64)
is a bare `throw MissingKineticaPluginException("suspendSubtree")`, so the first
render dies with this commit's newly reworded message telling the author to apply a
plugin that IS applied. The same LOGGING-only bail exists for `argument is not a
lambda literal` (:424) and `persistent state with explicit key` (:353).

### F10 — KineticaIrFrames.kt:452

`stageComponentCall` refuses to stage a `@UiComponent` call whose extension-receiver
argument is not an `IrGetValue`, logging "left unstaged" at LOGGING severity and
emitting no `ordinal(n)` prologue — while FIR's `consumesCompilerOrdinal` returns true
for any `@UiComponent` callee (KineticaFirExtension.kt:258) and never inspects the
receiver, so no rule catches this second silent IR bail-out.

`@UiComponent fun ComponentScope.Fan(panes: List<ComponentScope>) {
panes.first().Badge() }` compiles clean at checks=error. The receiver is an IrCall,
not IrGetValue, so line 452 bails; Badge's prologue calls `beginComponentFrame` ->
`consumeStagedOrdinal("component call")` (ComponentScope.kt:70-79) and throws
MissingKineticaPluginException. The nastier variant: when the unstaged call is
evaluated inside another staged component call's argument list the ordinal stack is
NOT empty, so `consumeStagedOrdinal` pops the ENCLOSING component's ordinal instead of
throwing — Badge renders into the wrong fixed child frame and the failure surfaces
later somewhere unrelated.

### F11 — KineticaFirExtension.kt:256

`consumesCompilerOrdinal` bails out with `val callableId = callee.callableId ?: return
false` BEFORE the `@UiComponent` annotation check on the very next line, so a
component call whose symbol has no callableId escapes rules D and F entirely.

This same commit added the `?: callee.name.asString()` fallback at line 176 precisely
because `callableId` can be null, and line 183 still classifies such a call as
`isComponentCall` so it passes rules A/B. A callableId-less `@UiComponent` symbol
inside `listOf(1).forEach { Badge() }` therefore reports nothing and the author gets
MissingKineticaPluginException at runtime instead of a compile error. Moving `if
(callee.hasAnnotation(UI_COMPONENT_CLASS_ID, session)) return true` above the
callableId elvis fixes it; the divergence exists only because `check()` and
`consumesCompilerOrdinal` derive the same five facts twice.

### F12 — KineticaFirExtension.kt:283

FIR's `isKineticaDsl()` is RECEIVER-based (ComponentScope classId or ComponentScope
receiver) while IR's `inKinetica` (KineticaIrFrames.kt:261) is PACKAGE-based, so the
two sides disagree about which lambdas are entered; `SINGLE_RUN_KINETICA_FUNCTIONS =
setOf("peek")` (line 92) is a hand-written patch for one instance of that gap and
misses the rest.

Enumerated every top-level `io.heapy.kinetica` function taking a lambda with no
ComponentScope receiver: `peek` (Cell.kt:702) matches; `derive` (Cell.kt:697),
`invalidate` (Resources.kt:494) and `serverActionStub` (ServerComponents.kt:193) all
MISMATCH — IR descends into their lambdas and numbers slot calls, FIR calls them
multi-run. Probe-verified: `@UiComponent fun ComponentScope.Fan() { val c = derive {
state { 1 }.value }; ... }` reports `Kinetica call 'state' cannot use a
compiler-assigned ordinal inside the multi-run 'derive' lambda` at checks=error while
the identical source compiles and is numbered at checks=off. Every future top-level
helper inherits the asymmetry.

### F13 — KineticaFirExtension.kt:91 (ALTITUDE)

`SINGLE_RUN_SCOPE_FUNCTIONS`/`SINGLE_RUN_KINETICA_FUNCTIONS` are five-name string
allow-lists where Kotlin already has the general mechanism — `contract {
callsInPlace(block, EXACTLY_ONCE) }` — and the API is present in the pinned compiler
(FirResolvedContractDescription.effects -> KtCallsEffectDeclaration.kind:
EventOccurrencesRange).

Verified with the released plugin at checks=error: `runCatching { state { 2 } }`,
`flag.takeIf { state { 3 }.value > 0 }`, and a user's own `inline fun <R> mySection(
block: () -> R): R { contract { callsInPlace(block, EXACTLY_ONCE) }; return block() }`
are all rejected with "cannot use a compiler-assigned ordinal inside the multi-run
'runCatching' lambda. Use each(items, key = ...) or keyed(...) for repeated rendering"
— unfixable errors with nonsense advice, purely because the name is not one of five. A
contract-derived predicate reproduces the current list exactly (let/run/with/apply/also
are EXACTLY_ONCE, repeat is AT_LEAST_ONCE, forEach/map declare nothing) and
generalises. Note the fix must be shared with IR: deriving it in FIR alone would trade
a compile error for a runtime crash, since KineticaIrFrames.kt:487 would still refuse
to descend.

### F14 — KineticaFirExtension.kt:462

`nestedUnderOrdinalConsumer` suppresses the rule-F report on the assumption that an
enclosing `consumesCompilerOrdinal` call will report it instead, but `check()` can
exit at rule A (line 197) or rule B (line 203) long before reaching rule F, and
`consumesCompilerOrdinal` is not the same predicate as "will be reported".

An ordinal-consuming call nested in the argument of another consumer that exits early
as SLOT_CALL_OUTSIDE_COMPONENT or COMPONENT_CALL_OUTSIDE_COMPONENT suppresses the
inner CALL_IN_MULTI_RUN_LAMBDA, so once the author fixes the outer error the multi-run
violation is reported nowhere and ships as the silent-aliasing failure of F4-F6. The
suite has no case exercising an outer consumer's early-exit paths. Measured supporting
fact: the `!enclosingCall.hasSameSourceAs(expression)` half of the guard fired 0 times
across the whole 83-test suite while plain `!== expression` fired 8, so only the
early-exit half of the assumption is live.

### F15 — KineticaFirExtension.kt:78 (REUSE)

`REGION_CONTENT_PARAMETERS` (line 78) and `KOTLIN_PACKAGE`/`SINGLE_RUN_SCOPE_FUNCTIONS`
(lines 90-91) are verbatim hand-maintained copies of `REGION_CONTENT_PARAMS` and
`KOTLIN_PKG`/`SINGLE_RUN_SCOPE_FUNCTIONS` in KineticaIrFrames.kt:166,183-184 — the
same package, same directory, and the very pass whose behaviour the FIR rule exists to
predict.

Update only the IR map when adding a region (say `lazyEach(empty = { })`) and FIR
stops treating that lambda as a numbering boundary, so rule F rejects valid code.
Update only the FIR map and rule F treats it as a boundary IR never wraps, leaving
ordinal -1 and a runtime MissingKineticaPluginException at checks=error — precisely
the guarantee this commit adds. Nothing in the test suite compares the two tables, and
they have already drifted once (F3 and F12). One `internal object KineticaFramePolicy`
beside the existing `internal` FqName/ClassId constants at lines 62-64 is a
same-directory refactor; CompilerContract.kt is the wrong home since it is published
API.

## Secondary findings (verified, below the 15-cap)

### S1 — KineticaCompilerRegistrar.kt:33

`checks=off` removes the whole FIR extension, but rule F is a *soundness* rule with no
IR counterpart, while the CLI help still calls `checks` an authoring-style "escape
hatch for migration branches". Verified: `checks=off` turns a compile error into a
first-render crash blaming a correct build config (or, in the val-lambda case, into no
error at all).

### S2 — KineticaFirExtension.kt:132

The `CALL_IN_MULTI_RUN_LAMBDA` text hardcodes "Use each(items, key = ...) or
keyed(...)", which is the construct the author already wrote when the flagged call
*is* `each`/`keyed`, and is impossible advice for a `render` entry point.

### S3 — KineticaFirExtension.kt:400

The source-offset matcher in `findLambdaHost` and the whole of `hasSameSourceAs` are
dead (measured: 137/137 matches decided by symbol identity; 0 hits for the offset
guard) yet are the only branches that can match the *wrong* lambda.

### S4 — KineticaFirExtension.kt:184

`hasComponentTypedLambdaArgument()` sits above the early-return guard, so every call
in every file pays an argument-mapping walk plus per-parameter cone-type and
annotation resolution for a predicate only 5 declarations in the whole runtime can
satisfy.

### S5 — KineticaCompilationHarness.kt:93

`compileExpectingErrors` never cleans its `kinetica-compile-*` temp tree; this commit
adds ~10 more leaked source+class trees per test run.

## Audits that came back clean

The per-construct `REGION_CONTENT_PARAMETERS` refactor drops nothing the old flat set
covered and correctly adds `lazyEach`→`placeholder`; the reworded
`MissingKineticaPluginException` has no doc/sample/test dependents on the old text;
the `checks = "off"` added to the IR tests was already the harness default, so no
coverage was lost; and conventions found nothing to flag.
