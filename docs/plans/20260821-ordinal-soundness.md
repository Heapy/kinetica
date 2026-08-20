# Ordinal Soundness: Fix All Findings from the 657eef5 Review

## Overview
- Commit 657eef5 ("Fail compilation for unassigned Kinetica ordinals") added FIR rules
  that promise: code that compiles cleanly never throws `MissingKineticaPluginException`
  and never silently aliases state. The review (`20260821-ordinal-soundness-findings.md`)
  proved the commit fails in both directions: it rejects correct framework code
  (28 downstream compile errors on a forced rebuild) and misses unsound code that
  crashes or aliases at runtime.
- This plan fixes all 20 verified findings: 15 primary (F1-F15) + 5 secondary (S1-S5).
  The findings file carries the ID → task mapping table.
- Strategy (Approach A): build the shared FIR/IR policy foundation first, then the
  runtime backstops, then relax/tighten the static rules on top, so the two compiler
  phases can never drift again and the tree is never knowingly unsound between tasks.
- Line numbers cited in the findings file refer to commit 657eef5, not HEAD.
  HEAD has advanced and a comment-cleanup pass shifted `KineticaFirExtension.kt`.
  Re-locate each site by symbol name, not by line.

## Context (from discovery)
- FIR checker: `kinetica-compiler/src/KineticaFirExtension.kt` (rules A-F,
  `consumesCompilerOrdinal`, `isKnownSingleRun`, `findLambdaHost`,
  `nestedUnderOrdinalConsumer`; diagnostic factories + `KineticaFirErrorRenderers.MAP`
  message templates live here too).
- IR numbering pass: `kinetica-compiler/src/KineticaIrFrames.kt` (`transformRegion`,
  `stageComponentCall`, `wrapAnnotatedContentArgumentsOf`, `transformArgumentsSelectively`,
  duplicated `REGION_CONTENT_PARAMS` / `KOTLIN_PKG` / `SINGLE_RUN_SCOPE_FUNCTIONS` tables).
- IR reporting: `kinetica-compiler/src/KineticaIrTransform.kt` — `report(...)` emits at
  `CompilerMessageSeverity.LOGGING` (invisible without `-verbose`) and passes no
  `CompilerMessageSourceLocation`.
- Registrar / options: `kinetica-compiler/src/KineticaCompilerRegistrar.kt` (the only
  `checks` branch is `!= "off"`; `checks=warning` is today a no-op alias for `error`
  because all six diagnostics are fixed-severity `error1`/`error2` factories),
  `kinetica-compiler/src/KineticaSourceModel.kt` (`checks` defaults to `"error"`).
- Runtime: `kinetica-runtime/src/Frames.kt` (`enterFixedChild` forks on re-entry via
  generation + `childEnterStamp` + `childForks`; `enterRegionChild` is a bare
  `map.getOrPut(table)` keyed by `FrameTable` with none of that machinery;
  `commitChecks` already deactivates children/regions whose
  `keptGeneration != generation`), `kinetica-runtime/src/ComponentScope.kt`
  (`beginRegionFrame` — sole caller of `enterRegionChild`; `renderEachRegion`
  eviction; `consumeStagedOrdinal`; `beginComponentFrame` is public plugin↔runtime
  ABI), `kinetica-runtime/src/HostDsl.kt` (`registerHostEvent`),
  `kinetica-runtime/src/Boundary.kt`.
- Important runtime fact: `enterRegionChild` is reachable only from `beginRegionFrame`,
  which IR emits when wrapping a *content lambda* into a region. A raw `for` loop in a
  component body creates no region — both iterations run on the same `currentFrame`
  with the same static ordinal. Region forking therefore does NOT cover finding F7.
- Consumers resolve the plugin as `io.heapy.kinetica:kinetica-compiler:0.4.0` from
  `mavenLocal` (pinned in `common.module-template.yaml`, applied by essentially every
  module). A source-tree rebuild does NOT pick up compiler changes without a
  `publish mavenLocal` step.
- Tests: `kinetica-compiler/test/KineticaFirCheckerTest.kt`,
  `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`,
  `kinetica-compiler/test/KineticaCompilationHarness.kt`;
  runtime: `kinetica-runtime/test/FrameKernelTest.kt` (frames/slots),
  `kinetica-runtime/test/EachIdentitySemanticsTest.kt` (keyed regions).
- Test commands: `./kotlin test -m kinetica-compiler --platform jvm`,
  `./kotlin test -m kinetica-runtime --platform jvm`.
- The commit is unpushed (`main` ahead of `origin/main`); downstream breakage is masked
  by stale compile caches. Do not push until Task 19 verification passes.

## Development Approach
- **testing approach**: TDD — every fix starts by turning the review's verified probe
  into a failing test, then making it pass. The probes in the findings file are
  ready-made reproductions; port them verbatim where possible.
- complete each task fully before moving to the next
- make small, focused changes
- **CRITICAL: every task MUST include new/updated tests** for code changes in that task
  - tests are not optional - they are a required part of the checklist
  - tests cover both success and error scenarios
- **CRITICAL: all tests must pass before starting next task** - no exceptions
- **CRITICAL: update this plan file when scope changes during implementation**
- run tests after each change
- backward compatibility: the `checks` option keeps its three values `error|warning|off`,
  but their meaning changes — today `warning` is a behavioral no-op alias for `error`,
  and `off` removes soundness rules. After Task 15, `checks` governs style diagnostics
  only; soundness rules are always errors. This is a deliberate breaking change,
  documented in release notes (see Post-Completion).

## Testing Strategy
- **unit tests**: required for every task. FIR rules → `KineticaFirCheckerTest.kt`
  (expect-diagnostic / expect-clean pairs). IR + runtime behavior →
  `KineticaIrFrameCompileTest.kt` via `KineticaCompilationHarness` (compile, run,
  assert rendered output / thrown exception). Runtime-only fixes →
  `kinetica-runtime/test/FrameKernelTest.kt` and
  `kinetica-runtime/test/EachIdentitySemanticsTest.kt`.
- **drift tests**: Task 1 adds a test that fails if FIR and IR policy tables diverge;
  Task 14 adds one enumerating all top-level `io.heapy.kinetica` lambda-taking
  functions.
- **downstream verification**: Task 19 publishes the plugin to mavenLocal, cleans, and
  force-rebuilds every consumer module — this is the check stale caches defeated for
  657eef5.
- **e2e tests**: none in this repo (no UI e2e harness); the downstream rebuild plays
  that role.

## Progress Tracking
- mark completed items with `[x]` immediately when done
- add newly discovered tasks with ➕ prefix
- document issues/blockers with ⚠️ prefix
- update plan if implementation deviates from original scope
- keep plan in sync with actual work done

## Solution Overview
- **One source of truth.** A shared `internal object KineticaFramePolicy` in
  `kinetica-compiler/src/` holds every table both phases consult — region content
  parameters, single-run function sets — plus ONE shared Kinetica-lambda
  classification predicate replacing FIR's receiver-based `isKineticaDsl` and IR's
  package-based `inKinetica`. FIR predicts exactly what IR does because both read the
  same object (F12, F15).
- **Contracts over name lists.** Single-run detection derives from Kotlin contracts
  (`callsInPlace(block, EXACTLY_ONCE / AT_MOST_ONCE)`) resolved in FIR. A
  per-compilation oracle carries FIR's contract verdicts to the IR pass so IR descends
  into exactly the lambdas FIR approved. The shared name lists remain as the fallback
  whenever no contract verdict exists for a callee — unresolvable contracts,
  null-`callableId` symbols, or the FIR extension being absent (F13).
- **Runtime backstops first, static relaxation after.** Region frames fork on
  re-entry within one render (like fixed children already do) BEFORE the FIR rule
  that guarded that hazard is relaxed, so the tree is never knowingly unsound between
  tasks (F3, then F1).
- **Mirror IR, don't approximate it.** `consumesCompilerOrdinal` is rebuilt to return
  true only for calls the IR pass actually numbers (slot calls, staged component calls,
  host event registrations) — entry points like `render { }` are not consumers (F1).
- **No silent IR bail-outs.** Every construct IR declines to transform either gets a
  matching FIR error or an IR-level ERROR diagnostic with a source location — never
  bare `LOGGING` (F9, F10).
- **Eviction scoped per render pass, not per call.** `renderEachRegion` stops evicting
  keyed children other invocations of the same static ordinal still own; unkept rows
  are collected once per render generation (F7).
- **Soundness rules cannot be switched off.** `checks=off` keeps suppressing style
  diagnostics but no longer removes rules whose absence converts a compile error into
  a runtime crash (S1).

## Technical Details
- **FIR→IR oracle**: constructed once per compilation inside
  `KineticaCompilerRegistrar.registerExtensions` (the only per-compilation hook) and
  handed to both extensions — never a top-level `object` or registrar field. Backed by
  a concurrent map (FIR checkers may run concurrently). FIR records verdicts from
  `FirResolvedContractDescription.effects` (`KtCallsEffectDeclaration.kind:
  EventOccurrencesRange`): `EXACTLY_ONCE`/`AT_MOST_ONCE` → single-run;
  `AT_LEAST_ONCE`/`UNKNOWN`/no contract → no oracle entry (NOT "multi-run" — absence
  falls through to the name lists, so `let`/`run`/`with`/`apply`/`also` never regress
  if contract resolution fails). Oracle key: `CallableId` when present; callees with a
  null `callableId` get no oracle entry and are covered identically on both sides by
  the name-list fallback.
- **Sound proxy for optional handlers (F2)**: a handler participates in ordinal
  numbering unless the argument is *absent* or a *literal null*. Static nullability of
  the parameter type is no longer consulted anywhere (`isDefinitelyNonNull` is removed).
- **Region re-entry fork (F3)**: `Frame.enterRegionChild` adopts the `enterFixedChild`
  mechanism, which requires machinery regions don't have today: a `generation`
  parameter threaded through `beginRegionFrame` (`ComponentScope.kt`), per-entry
  stamps, a per-render-reset fork counter, and a composite `(table, invocation)` key.
  Disposal of forked siblings needs NO new code: `Frame.commitChecks` already
  deactivates regions whose `keptGeneration != generation`.
- **Eviction scoping (F7)**: region forking does not apply here (`enterRegionChild` is
  unreachable from a raw loop body — see Context). Preferred mechanism: defer keyed
  eviction from `renderEachRegion` to `Frame.commitChecks`, which already collects
  unkept children by generation; alternative: accumulate the `seen` set per
  `(ordinal, render generation)` so eviction drops only keys unseen by EVERY
  invocation in the pass. Either way the per-generation state lives on `Frame`.
- **IR diagnostics (F9, F10)**: `KineticaIrTransform.report` gains a severity
  parameter AND a `CompilerMessageSourceLocation` derived from the `IrCall`'s
  file/offset; every "declined to transform, will throw at runtime" path reports
  `CompilerMessageSeverity.ERROR`. An ERROR without a location is not actionable.
- **Style-severity mechanism (S1)**: `KtDiagnosticFactory` severities are fixed per
  factory, so `checks=warning` needs either parallel warning factories or severity
  remapping at report time — Task 15 picks and implements one.

## What Goes Where
- **Implementation Steps** (`[ ]` checkboxes): compiler + runtime code changes, tests,
  documentation updates in this repo.
- **Post-Completion** (no checkboxes): pushing the branch, CI observation, release
  notes, consumer project notes.

## Implementation Steps

### Task 1: Extract shared KineticaFramePolicy with one classification predicate (F15 + predicate half of F12)

**Files:**
- Create: `kinetica-compiler/src/KineticaFramePolicy.kt`
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [ ] write a drift test first: assert the FIR checker and IR pass consult the same
      region-content-parameter and single-run tables (compile a probe per region kind —
      `each`, `lazyEach` incl. `empty`/`placeholder`, `keyed` — and assert FIR verdict
      matches IR numbering outcome)
- [ ] create `internal object KineticaFramePolicy` holding `REGION_CONTENT_PARAMETERS`,
      `KOTLIN_PACKAGE`, `SINGLE_RUN_SCOPE_FUNCTIONS`, and ONE shared Kinetica-lambda
      classification predicate that replaces both FIR's receiver-based `isKineticaDsl`
      and IR's package-based `inKinetica`; fold the `SINGLE_RUN_KINETICA_FUNCTIONS =
      setOf("peek")` patch-list into that predicate instead of porting it as a
      separate table (Task 14 would otherwise delete it again)
- [ ] point `KineticaFirExtension.kt` at the shared object; delete its private copies
- [ ] point `KineticaIrFrames.kt` at the shared object; delete `REGION_CONTENT_PARAMS`,
      `KOTLIN_PKG`, its `SINGLE_RUN_SCOPE_FUNCTIONS`
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 2

### Task 2: Contract-based single-run detection with FIR→IR oracle (F13)

**Files:**
- Create: `kinetica-compiler/src/SingleRunOracle.kt`
- Modify: `kinetica-compiler/src/KineticaCompilerRegistrar.kt`
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [ ] write failing tests from the review probes: `runCatching { state { 2 } }`,
      `flag.takeIf { state { 3 }.value > 0 }`, and a user-defined
      `inline fun <R> mySection(block: () -> R): R` with a
      `callsInPlace(block, EXACTLY_ONCE)` contract — all must compile clean at
      `checks=error` AND render correctly (IR must number them)
- [ ] implement contract resolution in FIR: `EXACTLY_ONCE`/`AT_MOST_ONCE` → single-run
      verdict recorded in the oracle; `AT_LEAST_ONCE`/`UNKNOWN`/no contract → record
      nothing, so the callee falls through to `KineticaFramePolicy` name lists on BOTH
      sides (`let`/`run`/`with`/`apply`/`also` must never regress to errors if
      contract resolution fails)
- [ ] add `SingleRunOracle`: constructed per compilation inside
      `registerExtensions`, passed to both extensions, concurrent map, keyed by
      `CallableId`; callees with a null `callableId` get no entry — assert in a test
      that both sides then agree via the name-list fallback
- [ ] keep behavior for no-contract hosts (`forEach`, `map`, `repeat`) unchanged —
      add regression tests asserting they are still multi-run
- [ ] write the fallback test at the oracle level (not tied to `checks` mode, which
      Task 15 changes): an empty oracle must make IR use the name lists —
      `run { state { } }` still numbers correctly
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 3

### Task 3: Runtime — fork region frames on re-entry within one render (F3)

**Files:**
- Modify: `kinetica-runtime/src/Frames.kt`
- Modify: `kinetica-runtime/src/ComponentScope.kt`
- Modify: `kinetica-runtime/test/FrameKernelTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [ ] write failing test from the review probe: helper
      `fun ComponentScope.twice(content: @UiComponent ComponentScope.() -> Unit) { content(); content() }`
      — the two invocations must get independent state cells and event ordinals
- [ ] port the `enterFixedChild` re-entry fork to `enterRegionChild`: composite
      `(table, invocation)` key, per-entry stamps, per-render-reset fork counter;
      thread the `generation` parameter through `beginRegionFrame` in
      `ComponentScope.kt`
- [ ] verify disposal of forked siblings when a later render invokes the content
      fewer times: `commitChecks` already deactivates regions with
      `keptGeneration != generation` — write the test, do NOT rebuild the mechanism
- [ ] write tests for single-entry regions (no fork, no behavior change) — the
      existing runtime suite must stay green
- [ ] run `./kotlin test -m kinetica-runtime --platform jvm` and
      `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 4

### Task 4: Blocker — stop treating render entry points as ordinal consumers (F1)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [ ] write failing tests from the review: `repeat(2) { runtime.render(scope) { text("x") } }`
      and `assertFailsWith<...> { KineticaTest.render { ... } }` must compile clean at
      `checks=error`
- [ ] rebuild `consumesCompilerOrdinal`: a call consumes an ordinal only if IR numbers
      it — slot call, `@UiComponent` component call, or host event registration;
      merely receiving a `@UiComponent`-typed lambda argument (an entry point such as
      `render`, or a user content-wrapper helper) does NOT qualify
- [ ] flip `multiRunCallWithComponentTypedLambdaArgumentIsReported` from expect-error
      to expect-clean — this is authorized and expected: the pattern is sound now
      because IR wraps the content lambda into one static FrameTable
      (`wrapAnnotatedContentArgumentsOf`) and Task 3's region re-entry fork makes
      repeated entries independent (this is why Task 3 lands first)
- [ ] verify rules D and F still fire for true consumers inside loops; write negative
      tests: component call inside `forEach` still errors
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 5

### Task 5: Blocker — remove the nullable-handler exemption (F2)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [ ] write failing tests from the review probes: nullable-typed non-null handler in
      `forEach` (runtime `MissingKineticaPluginException`) and in a `for` loop
      (event aliasing: clicking row "a" runs row "c") — both must become FIR errors
      at `checks=error`
- [ ] replace the static-nullability exemption with the sound proxy: exempt only when
      the handler argument is absent or a literal `null`; delete `isDefinitelyNonNull`
- [ ] rewrite `nullableOptionalHandlersRemainAllowedInRepeatedContexts` to cover the
      sound cases only (absent argument / literal null in a loop stays allowed)
- [ ] add tests for platform types and unbounded type parameters (previously
      misclassified by `isDefinitelyNonNull`)
- [ ] note the migration path for newly-rejected code in the release notes draft
      (Post-Completion): hoist the handler out of the loop, or wrap rows in
      `each`/`keyed`
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 6

### Task 6: Parameter-aware single-run classification (F4, F5)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFramePolicy.kt`
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [ ] write failing tests from the review probes: `state { }` inside `button(onClick = { ... })`
      / `launchEffect` / `watch` / `action` must be a FIR error (deferred handlers are
      multi-run); `state { }` inside the `each(items, key = { ... })` key selector must
      be a FIR error (numbered with enclosing counters → duplicate-key crash)
- [ ] remove the blanket `if (callee.isKineticaDsl()) return true` from
      `isKnownSingleRun`; classify per (callee, parameter) via `KineticaFramePolicy`:
      region content params single-run, deferred handler params multi-run, key
      selectors multi-run
- [ ] align IR: stop numbering slot calls inside key selectors and deferred handler
      lambdas (they must fail FIR, not get unsound ordinals)
- [ ] write positive tests: slot calls directly in region content lambdas
      (`each` item content, `keyed` content) still compile and number correctly
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 7

### Task 7: Close the val-stored-lambda hole (F6)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [ ] write failing test from the review probe: `val row: (Int) -> Unit = { i -> state { i } ... }`
      invoked from `forEach` — currently compiles clean and aliases slot 0 across
      invocations; must become a FIR error at `checks=error`
- [ ] FIR: treat a lambda that is not a resolved call argument (local `val`, property,
      vararg element) as an unknown-run host — ordinal consumers inside it are errors;
      make `findLambdaHost` returning null flag instead of `continue`, and unwrap
      `FirVarargArgumentsExpression`
- [ ] IR: gate `visitVariable` descent the same way `transformArgumentsSelectively`
      gates call arguments — never assign ordinals inside non-argument lambdas
- [ ] write positive test: a `val` lambda with no ordinal consumers still compiles
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 8

### Task 8: Reject non-literal @UiComponent content arguments (F8, extends rule C)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [ ] write failing test from the review probe: hoisted content
      `val content: @UiComponent ComponentScope.() -> Unit = { Badge() }` passed to
      `render` — IR cannot wrap it, runtime throws; must become a FIR error telling
      the author to pass a lambda literal
- [ ] extend the existing rule C (`REGION_CONTENT_NOT_LITERAL` already rejects
      non-literal region content) to cover every parameter IR declines to wrap:
      any argument whose parameter type carries `@UiComponent` must be a lambda
      literal (matching IR's `as? IrFunctionExpression` restriction) — one rule, not
      a parallel new one; this also covers the "region argument is not a lambda
      literal" IR bail-out so Task 10 does not duplicate it
- [ ] add a test for the nested-local-fun path (`fun render() = ...` wrapper) — after
      Task 4 it compiles because entry points are no longer consumers; pin that with
      an explicit test so the old accidental-compile reason is replaced by a
      deliberate one
- [ ] write positive tests: literal content lambdas unaffected
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 9

### Task 9: Runtime — scope each-region eviction per render pass (F7)

**Files:**
- Modify: `kinetica-runtime/src/ComponentScope.kt`
- Modify: `kinetica-runtime/src/Frames.kt`
- Modify: `kinetica-runtime/test/EachIdentitySemanticsTest.kt`

- [ ] write failing test from the review probe: `for (batch in listOf(a, b)) { each(batch, ...) }`
      rendered 3 times must give `initCount == 4` (state preserved), not 12
- [ ] implement the mechanism (region forking does NOT apply — `enterRegionChild` is
      unreachable from a raw loop body, see Context): preferred — defer keyed eviction
      from `renderEachRegion` to `Frame.commitChecks`, which already collects unkept
      children by generation; alternative — accumulate the `seen` set per
      `(ordinal, render generation)` on `Frame` so eviction only drops keys unseen by
      EVERY invocation in the pass
- [ ] decide overlapping-keys semantics for two loop iterations sharing a user key
      (`for (batch in listOf(listOf(1,2), listOf(1,2)))` — rows currently alias via
      `enterKeyedChild(ordinal, key)`): include the render-pass invocation index in
      the keyed-child identity (recommended — keeps the blessed pattern sound), or
      reject `each`/`keyed` in un-keyed loops in FIR; update
      `keyedAndEachRemainAllowedDirectlyInsideLoops` accordingly and add the
      overlapping-keys test
- [ ] verify keyed-child disposal still happens when items genuinely disappear
      between renders (existing eviction tests stay green)
- [ ] write test for the single-batch case (unchanged behavior, matches the test
      added in 657eef5)
- [ ] run `./kotlin test -m kinetica-runtime --platform jvm` - must pass before task 10

### Task 10: No silent IR bail-outs — suspendSubtree and persistent keys (F9)

**Files:**
- Modify: `kinetica-compiler/src/KineticaIrTransform.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [ ] write failing test from the review probe: `suspendSubtree(key = id, ...)` inside
      a component compiles clean today and throws `MissingKineticaPluginException` on
      first render — must become a compile error
- [ ] audit the repo for `state(persistent = ..., key = ...)`-style explicit-key
      usages (kinetica-persist is the likely user) BEFORE flipping that bail-out to an
      error; if legitimate usages exist, the fix is IR support or a scoped exemption,
      not a blanket error — record the decision here
- [ ] add FIR rules for the two remaining documented IR bail-outs: `suspendSubtree`
      with explicit non-null key, persistent state with explicit key (the non-literal
      region argument bail-out is covered by Task 8's rule C extension)
- [ ] upgrade `KineticaIrTransform.report` decline-to-transform paths from
      `CompilerMessageSeverity.LOGGING` to `ERROR`, threading a
      `CompilerMessageSourceLocation` from the `IrCall`'s file/offset — an ERROR
      without a location is not actionable
- [ ] write tests: `suspendSubtree(key = null, ...)` and implicit-key forms still
      compile and transform
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 11

### Task 11: Component-call receivers IR cannot stage (F10)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-runtime/src/ComponentScope.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [ ] write failing tests from the review probes: `panes.first().Badge()` compiles
      clean and throws at render; the nested-argument variant steals the enclosing
      component's staged ordinal and renders into the wrong frame
- [ ] add FIR rule: a `@UiComponent` call's extension receiver must be a simple
      variable or `this` (mirror IR's `IrGetValue` requirement)
- [ ] upgrade the IR "left unstaged" report to `ERROR` with source location (same
      mechanism as Task 10)
- [ ] runtime hardening: add a stack-discipline check so `consumeStagedOrdinal` throws
      immediately on a mismatched pop instead of corrupting frames — do NOT tag staged
      entries with the callee: `beginComponentFrame` is public plugin↔runtime ABI, and
      changing its contract forces plugin/runtime version lockstep for every mavenLocal
      consumer
- [ ] write positive tests: `this.Badge()`, `scope.Badge()` via local val — unaffected
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` and
      `./kotlin test -m kinetica-runtime --platform jvm` - must pass before task 12

### Task 12: Unify call classification in the FIR checker (F11)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [ ] write failing test: a `@UiComponent` symbol without a `callableId` inside
      `forEach { Badge() }` must be a rule D/F error, not silence (probe via the same
      synthetic-symbol path that motivated the `?: callee.name.asString()` fallback)
- [ ] in `consumesCompilerOrdinal`, check `hasAnnotation(UI_COMPONENT_CLASS_ID)` before
      the `callableId ?: return false` bail
- [ ] extract one shared classification helper (slot call / component call / host event /
      entry point) used by both `check()` and `consumesCompilerOrdinal`, so the five
      facts are derived once
- [ ] verify the full existing checker suite stays green
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 13

### Task 13: Fix nested-report suppression and drop the dead source matcher (F14 + S3)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [ ] write failing test: an ordinal consumer nested in the argument of an outer
      consumer that exits early via rule A or B — the inner
      `CALL_IN_MULTI_RUN_LAMBDA` must still be reported
- [ ] change `nestedUnderOrdinalConsumer` to suppress only when the enclosing call
      actually produced a report (track reported calls per file/function instead of
      re-deriving "would report")
- [ ] delete the dead source-offset branch in `findLambdaHost` and the whole
      `hasSameSourceAs` (measured 0 hits across the suite; only branches able to match
      the wrong lambda)
- [ ] verify suppression still works for the legitimate symbol-identity cases in the
      existing suite
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 14

### Task 14: Per-function FIR/IR alignment for top-level Kinetica helpers (F12 remainder)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFramePolicy.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [ ] write failing tests for the four mismatched top-level functions: `peek`,
      `derive`, `invalidate`, `serverActionStub` — for each, FIR verdict and IR
      numbering must agree (the review probe: `derive { state { 1 }.value }` errors
      at `checks=error` but numbers fine at `checks=off`)
- [ ] decide per function in `KineticaFramePolicy` (Task 1's shared predicate is the
      single place to encode it) whether its lambda is a numbering context (IR
      descends + FIR allows) or not (IR skips + FIR rejects); `derive`, `invalidate`,
      `serverActionStub` lambdas re-run reactively — classify multi-run on both sides
      unless the runtime slot semantics prove otherwise
- [ ] add a drift test enumerating all top-level `io.heapy.kinetica` lambda-taking
      functions and asserting FIR/IR agreement for each
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 15

### Task 15: checks=off must not remove soundness rules (S1)

**Files:**
- Modify: `kinetica-compiler/src/KineticaCompilerRegistrar.kt`
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaCommandLineProcessor.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [ ] write failing test: with `checks=off`, the val-lambda aliasing probe (Task 7)
      and the multi-run rule F probes must still be compile errors
- [ ] split diagnostics into soundness (rules that prevent runtime crash/aliasing —
      always error, not configurable) and style (governed by `checks`)
- [ ] implement the severity mechanism `checks=warning` needs (it is currently a
      behavioral no-op alias for `error` — the registrar's only branch is `!= "off"`
      and all factories are fixed-severity `error1`/`error2`): either parallel
      warning factories or severity remapping at report time — pick one and record
      the choice here
- [ ] `checks=off` keeps style rules off but registers the FIR extension with
      soundness rules active; `checks=warning` downgrades style rules only
- [ ] update the CLI option help text: `checks` is no longer an escape hatch for
      soundness
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 16

### Task 16: Context-aware diagnostic advice (S2)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [ ] write failing tests asserting message content: when the flagged call IS
      `each`/`keyed`, the message must not advise "Use each(...) or keyed(...)";
      when the consumer is an entry point idiom, advice must fit
- [ ] implement the mechanism: the template lives in `KineticaFirErrorRenderers.MAP`
      in `KineticaFirExtension.kt` and `CALL_IN_MULTI_RUN_LAMBDA` is a
      `KtDiagnosticFactory2<String, String>` with a fixed per-factory template —
      either add a third rendered parameter carrying the advice string, or split into
      separate factories per advice shape; pick one and record the choice here
- [ ] make the advice depend on the flagged callee: slot call → suggest `each`/`keyed`;
      `each`/`keyed` themselves → suggest hoisting out of the loop or keying the outer
      construct
- [ ] verify all existing message-matching tests updated consistently
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 17

### Task 17: Move the component-lambda probe behind the cheap guard (S4)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`

- [ ] reorder `check()` so `hasComponentTypedLambdaArgument()` (argument-mapping walk +
      cone-type + annotation resolution) runs only after the cheap early-return guard
      rejects the common case
- [ ] confirm no diagnostic changes: full checker suite green (this is the test — the
      change is pure reordering; no new test cases apply to a no-behavior-change
      reorder, the existing suite is the coverage)
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 18

### Task 18: Clean up harness temp directories (S5)

**Files:**
- Modify: `kinetica-compiler/test/KineticaCompilationHarness.kt`

- [ ] make `compileExpectingErrors` (and any sibling entry point) delete its
      `kinetica-compile-*` temp tree in a `finally` block / `deleteRecursively`
- [ ] write a test asserting the temp root contains no `kinetica-compile-*` entries
      after a compile-expecting-errors run
- [ ] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 19

### Task 19: Publish, clean, and force-rebuild every consumer — the check that caches defeated

**Files:**
- Modify: none expected (fix fallout only; if fixes are needed, add ➕ tasks)

- [ ] publish the rebuilt plugin: `./kotlin publish mavenLocal -m kinetica-compiler`
      — consumers pin `io.heapy.kinetica:kinetica-compiler:0.4.0` from mavenLocal
      (`common.module-template.yaml`), so this OVERWRITES the 0.4.0 coordinate; verify
      the artifact timestamp in `~/.m2/repository/io/heapy/kinetica/kinetica-compiler/0.4.0/`
      changed before rebuilding anything
- [ ] clean consumer outputs so no stale compile task survives (remove the toolchain
      `build/` outputs for the consumer modules, or use the toolchain clean command)
- [ ] rebuild ALL consumers of `common.module-template.yaml`, not just the six that
      broke at 657eef5: `kinetica-runtime`, `kinetica-test`, `kinetica-persist`,
      `kinetica-data`, `kinetica-forms`, `kinetica-browser`, `kinetica-router`,
      `kinetica-appkit`, `kinetica-gtk`, `kinetica-markdown`, `kinetica-motion`,
      `kinetica-theme`, `bench-jvm`, `docs/docs-client`, `samples/*`
- [ ] confirm zero compile errors (the 657eef5 baseline was 28: runtime/test 19,
      persist/test 5, bench-jvm 4)
- [ ] run the module test suites:
      `./kotlin test -m kinetica-runtime --platform jvm`,
      `./kotlin test -m kinetica-test --platform jvm`,
      `./kotlin test -m kinetica-persist --platform jvm`
- [ ] run the review's silent-aliasing probes end-to-end via
      `KineticaCompilationHarness` one final time — every probe must now be either a
      compile error or correct runtime behavior; none may crash or alias
- [ ] run tests - must pass before task 20

### Task 20: Verify acceptance criteria
- [ ] verify all 20 findings from `20260821-ordinal-soundness-findings.md` are
      addressed (walk the findings file's mapping table item by item; mark each with
      the task that fixed it)
- [ ] verify edge cases are handled (platform types, vararg lambdas, nested local
      functions, overlapping keys across loop iterations, `checks=off`/`warning` modes)
- [ ] run full test suite: `./kotlin test -m kinetica-compiler --platform jvm` and
      `./kotlin test -m kinetica-runtime --platform jvm`
- [ ] no e2e suite in this repo — Task 19's downstream rebuild stands in; confirm it
      was completed
- [ ] verify test coverage: every finding has at least one dedicated test case

### Task 21: [Final] Update documentation
- [ ] update `plan.md` module status table if test counts changed
- [ ] record the FIR/IR-policy-must-stay-shared invariant in `AGENTS.md` (the 0-byte
      guidance file; `CLAUDE.md` just references it via `@AGENTS.md`)
- [ ] move this plan to `docs/plans/completed/`

## Post-Completion
*Items requiring manual intervention or external systems - no checkboxes, informational only*

**Manual verification:**
- push `main` (currently 4+ commits ahead of `origin/main`) only after Task 19 passes;
  watch the first CI run — CI has never seen 657eef5 or these fixes
- benchmark impact check: the FIR checker now resolves contracts; verify compiler
  wall-time on `bench` module is not measurably worse

**Release notes (breaking changes to document):**
- `checks` semantics: soundness rules are no longer disableable; `checks=warning`
  gains real meaning (style-only downgrade) instead of being an alias for `error`
- nullable-handler exemption removed: `button(onClick = someNullableHandler)` inside
  a loop becomes a compile error; migration — hoist the handler, or wrap rows in
  `each`/`keyed`
- new compile errors for previously crash-at-runtime constructs: `suspendSubtree`
  with explicit key, non-literal `@UiComponent` content, complex component-call
  receivers, val-stored lambdas with ordinal consumers

**External system updates:**
- the Gradle consumer example (`examples/`) and any published-plugin consumers pick up
  the fixes with the next plugin release
