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

- [x] write a drift test first: assert the FIR checker and IR pass consult the same
      region-content-parameter and single-run tables (compile a probe per region kind —
      `each`, `lazyEach` incl. `empty`/`placeholder`, `keyed` — and assert FIR verdict
      matches IR numbering outcome)
      (note: `lazyEach` has no `empty` parameter in the current runtime signature —
      probes cover `content` + `placeholder`; the placeholder lambda never executes
      without a pending resource, so its IR verdict is bail-out-message absence)
- [x] create `internal object KineticaFramePolicy` holding `REGION_CONTENT_PARAMETERS`,
      `KOTLIN_PACKAGE`, `SINGLE_RUN_SCOPE_FUNCTIONS`, and ONE shared Kinetica-lambda
      classification predicate that replaces both FIR's receiver-based `isKineticaDsl`
      and IR's package-based `inKinetica`; fold the `SINGLE_RUN_KINETICA_FUNCTIONS =
      setOf("peek")` patch-list into that predicate instead of porting it as a
      separate table (Task 14 would otherwise delete it again)
- [x] point `KineticaFirExtension.kt` at the shared object; delete its private copies
- [x] point `KineticaIrFrames.kt` at the shared object; delete `REGION_CONTENT_PARAMS`,
      `KOTLIN_PKG`, its `SINGLE_RUN_SCOPE_FUNCTIONS`
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 2
      (84/84 green, including the 4 new drift tests)

### Task 2: Contract-based single-run detection with FIR→IR oracle (F13)

**Files:**
- Create: `kinetica-compiler/src/SingleRunOracle.kt`
- Modify: `kinetica-compiler/src/KineticaCompilerRegistrar.kt`
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-compiler/src/KineticaIrTransform.kt` (oracle threaded through
  `KineticaIrGenerationExtension` into the frame transformer)
- Modify: `kinetica-compiler/src/KineticaFramePolicy.kt` (`runCatching` joined the
  shared single-run name list — see note below)
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [x] write failing tests from the review probes: `runCatching { state { 2 } }`,
      `flag.takeIf { state { 3 }.value > 0 }`, and a user-defined
      `inline fun <R> mySection(block: () -> R): R` with a
      `callsInPlace(block, EXACTLY_ONCE)` contract — all must compile clean at
      `checks=error` AND render correctly (IR must number them)
      (note: stdlib 2.4.10 declares NO contract on `runCatching`, so it compiles clean
      through the shared name-list fallback in `KineticaFramePolicy` — semantically a
      `try` block — not through contract resolution; `takeIf` and the user contracts
      go through the oracle; an `AT_MOST_ONCE` user-contract probe was added too)
- [x] implement contract resolution in FIR: `EXACTLY_ONCE`/`AT_MOST_ONCE` → single-run
      verdict recorded in the oracle; `AT_LEAST_ONCE`/`UNKNOWN`/no contract → record
      nothing, so the callee falls through to `KineticaFramePolicy` name lists on BOTH
      sides (`let`/`run`/`with`/`apply`/`also` must never regress to errors if
      contract resolution fails)
      (note: verdicts are recorded eagerly in `check()` for every call carrying a
      lambda literal — NOT inside the rule-F walk — so oracle coverage never depends
      on `consumesCompilerOrdinal`, which Task 4 narrows; `isKnownSingleRun` re-derives
      the contract verdict purely and never reads the oracle, so checker traversal
      order cannot matter. stdlib fact: `repeat` declares `callsInPlace(action)` with
      UNKNOWN kind, not AT_LEAST_ONCE — same no-entry bucket, no behavior change)
- [x] add `SingleRunOracle`: constructed per compilation inside
      `registerExtensions`, passed to both extensions, concurrent map, keyed by
      `CallableId`; callees with a null `callableId` get no entry — assert in a test
      that both sides then agree via the name-list fallback
      (note: the key also includes the regular-value-parameter count so overloads
      sharing a `CallableId` cannot collide on a common parameter name like `block`;
      the null-`callableId` agreement is pinned by the local-function-host test —
      local functions are unaddressable on the IR side and stay FIR errors)
- [x] keep behavior for no-contract hosts (`forEach`, `map`, `repeat`) unchanged —
      add regression tests asserting they are still multi-run
- [x] write the fallback test at the oracle level (not tied to `checks` mode, which
      Task 15 changes): an empty oracle must make IR use the name lists —
      `run { state { } }` still numbers correctly
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 3
      (91/91 green: 84 prior + 7 new)

### Task 3: Runtime — fork region frames on re-entry within one render (F3)

**Files:**
- Modify: `kinetica-runtime/src/Frames.kt`
- Modify: `kinetica-runtime/src/ComponentScope.kt`
- Modify: `kinetica-runtime/test/FrameKernelTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [x] write failing test from the review probe: helper
      `fun ComponentScope.twice(content: @UiComponent ComponentScope.() -> Unit) { content(); content() }`
      — the two invocations must get independent state cells and event ordinals
      (compiled probe `contentLambdaInvokedTwiceForksRegionFramesPerInvocation` was red
      before the fix: both invocations rendered "cell0=0" and shared one event id; plus
      kernel test `regionReenteredWithinOneRenderForksSiblingFrames`)
- [x] port the `enterFixedChild` re-entry fork to `enterRegionChild`: composite
      `(table, invocation)` key, per-entry stamps, per-render-reset fork counter;
      thread the `generation` parameter through `beginRegionFrame` in
      `ComponentScope.kt`
      (note: `beginRegionFrame`'s public plugin↔runtime ABI signature is unchanged —
      it passes `slotGeneration` internally, like `beginComponentFrame`; the per-entry
      stamp reuses the primary region frame's existing `enteredGeneration` and the fork
      counter is an Int on that frame, instead of parent-side stamp maps — same
      semantics, fewer allocations; invariant documented in `enterRegionChild`)
- [x] verify disposal of forked siblings when a later render invokes the content
      fewer times: `commitChecks` already deactivates regions with
      `keptGeneration != generation` — write the test, do NOT rebuild the mechanism
      (`forkedRegionSiblingIsDeactivatedWhenLaterRenderEntersOnce`: transient disposed,
      state retained, transient recreated on re-fork)
- [x] write tests for single-entry regions (no fork, no behavior change) — the
      existing runtime suite must stay green
      (`singleEntryRegionKeepsIdentityAcrossRenders`; full suite 216/216)
- [x] run `./kotlin test -m kinetica-runtime --platform jvm` and
      `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 4
      (compiler 92/92 at default checks; runtime 216/216 — but its TEST module only
      compiles under a temporary, uncommitted `checks: "off"` module.yaml override,
      because the mavenLocal 0.4.0 plugin hits the pre-existing F1 baseline: 19
      entry-point-render errors in untouched runtime test files, exactly the plan
      Overview's "runtime/test 19"; stale-cache masking has ended. Override reverted
      before commit; see the ➕ item under Task 4)

### Task 4: Blocker — stop treating render entry points as ordinal consumers (F1)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [x] write failing tests from the review: `repeat(2) { runtime.render(scope) { text("x") } }`
      and `assertFailsWith<...> { KineticaTest.render { ... } }` must compile clean at
      `checks=error`
      (note: the `KineticaTest.render` probe is emulated with `runtime.render` —
      kinetica-test is not on the compiler harness classpath; same F1 shape. Both
      probes plus the flipped wrapper test were red before the fix — `assertFailsWith`
      carries no `callsInPlace` contract in kotlin-test, so it was red too; the repeat
      probe also renders end-to-end through the harness to pin runtime correctness)
- [x] rebuild `consumesCompilerOrdinal`: a call consumes an ordinal only if IR numbers
      it — slot call, `@UiComponent` component call, or host event registration;
      merely receiving a `@UiComponent`-typed lambda argument (an entry point such as
      `render`, or a user content-wrapper helper) does NOT qualify
      (note: the fix deletes the `hasComponentTypedLambdaArgument()` fast-path; region
      constructs stay consumers — IR's `transformRegion` numbers their children and
      boundary slots. The null-`callableId` bail now precedes the `@UiComponent`
      annotation check — that pre-existing hole is Task 12's explicit checkbox)
- [x] flip `multiRunCallWithComponentTypedLambdaArgumentIsReported` from expect-error
      to expect-clean — this is authorized and expected: the pattern is sound now
      because IR wraps the content lambda into one static FrameTable
      (`wrapAnnotatedContentArgumentsOf`) and Task 3's region re-entry fork makes
      repeated entries independent (this is why Task 3 lands first)
      (renamed to `multiRunCallWithComponentTypedLambdaArgumentCompiles`)
- [x] verify rules D and F still fire for true consumers inside loops; write negative
      tests: component call inside `forEach` still errors
      (new `ruleD_componentCallInLoopIsReported` — component call directly in a `for`
      loop; rule F pinned by the existing `multiRunComponentCallInsideRuntimeRenderIsReported`
      — component call inside `forEach` within render content — still green)
- [x] ➕ publish the fixed compiler to mavenLocal (`./kotlin publish mavenLocal -m
      kinetica-compiler`) and run `./kotlin test -m kinetica-runtime --platform jvm` at
      default checks — clears Task 3's deferred gate: the 0.4.0 plugin in mavenLocal
      rejects the runtime test module's 19 entry-point renders (F1 baseline) until this
      republish, and Task 9's runtime gate needs a compilable test module long before
      Task 19
      (jar timestamp and size changed in ~/.m2; runtime 216/216 at default checks with
      NO module.yaml override, re-verified from scratch after `./kotlin clean` so no
      stale compile cache can be masking it)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 5
      (95/95 green: 92 prior + 3 new probes, re-run after the clean)

### Task 5: Blocker — remove the nullable-handler exemption (F2)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [x] write failing tests from the review probes: nullable-typed non-null handler in
      `forEach` (runtime `MissingKineticaPluginException`) and in a `for` loop
      (event aliasing: clicking row "a" runs row "c") — both must become FIR errors
      at `checks=error`
      (`nullableTypedHandlerInForEachLambdaIsReported` — rule F, multi-run 'forEach' —
      and `nullableTypedHandlerInForLoopIsReported` — rule D; both red before the fix.
      A third probe pins the sound side end to end in `KineticaIrFrameCompileTest`:
      `nullLiteralHandlersInLoopCompileAndRenderWithoutEvents` compiles at
      `checks=error` and renders a null-handler loop without registering events)
- [x] replace the static-nullability exemption with the sound proxy: exempt only when
      the handler argument is absent or a literal `null`; delete `isDefinitelyNonNull`
      (new `isLiteralNull()` checks the unwrapped argument for
      `FirLiteralExpression` of kind `ConstantValueKind.Null`; absence falls out of the
      resolved argument mapping for free — defaults are not materialized in FIR;
      `isDefinitelyNonNull` deleted along with the `canBeNull`/`resolvedType` imports)
- [x] rewrite `nullableOptionalHandlersRemainAllowedInRepeatedContexts` to cover the
      sound cases only (absent argument / literal null in a loop stays allowed)
      (renamed to `absentOrNullLiteralHandlersRemainAllowedInRepeatedContexts`; covers
      absent + literal-null handlers for button/textInput/checkbox in both `forEach`
      and a `for` loop)
- [x] add tests for platform types and unbounded type parameters (previously
      misclassified by `isDefinitelyNonNull`)
      (`platformTypedHandlerInLoopIsReported` — `ThreadLocal<() -> Unit>.get()`
      platform type — and `typeParameterTypedHandlerInLoopIsReported` — handler typed
      by a nullable-bounded type parameter, the only shape that can flow into
      `onClick`; both were red before the fix, so old `canBeNull` exempted both)
- [x] note the migration path for newly-rejected code in the release notes draft
      (Post-Completion): hoist the handler out of the loop, or wrap rows in
      `each`/`keyed`
      (already present in Post-Completion: "nullable-handler exemption removed …
      migration — hoist the handler, or wrap rows in `each`/`keyed`" — no duplicate
      entry added)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 6
      (100/100 green: 95 prior + 5 new — 4 error probes + 1 IR render probe; consumer
      scan found only `onClick = event { … }` literal handlers in runtime/test/persist,
      which were consumers before this change too, so no new Task 19 fallout expected)

### Task 6: Parameter-aware single-run classification (F4, F5)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFramePolicy.kt`
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [x] write failing tests from the review probes: `state { }` inside `button(onClick = { ... })`
      / `launchEffect` / `watch` / `action` must be a FIR error (deferred handlers are
      multi-run); `state { }` inside the `each(items, key = { ... })` key selector must
      be a FIR error (numbered with enclosing counters → duplicate-key crash)
      (note: probes cover the full F4 handler set — button/textInput/checkbox handlers,
      `event`, `hostEvent`, `hostEventBlock`, `launchEffect`, `layoutEffect`, `watch`
      source AND block, `action`, `resource` loader — plus `each`/`lazyEach` key
      selectors; all four negative probes were red before the fix, the checks=off
      key-selector IR probe reproducing the review's `Duplicate key: a` crash verbatim)
- [x] remove the blanket `if (callee.isKineticaDsl()) return true` from
      `isKnownSingleRun`; classify per (callee, parameter) via `KineticaFramePolicy`:
      region content params single-run, deferred handler params multi-run, key
      selectors multi-run
      (note: implemented as `KineticaFramePolicy.MULTI_RUN_DSL_PARAMETERS` +
      `isMultiRunDslParameter(callee, parameter)`; unlisted Kinetica DSL params stay
      single-run, so `row`/`peek`/`provide`/button `content` behave exactly as before;
      `state` initializers and `derived` compute lambdas stay single-run — reactive
      reclassification is Task 14 territory)
- [x] align IR: stop numbering slot calls inside key selectors and deferred handler
      lambdas (they must fail FIR, not get unsound ordinals)
      (note: `transformArgumentsSelectively` skips lambda-LITERAL args of multi-run
      params and `transformRegion`'s non-content loop skips the key selector; `IrCall`
      arguments in handler position — `onClick = event { … }` — still transform, so
      the hostEventBlock fusion keeps its event ordinal; at checks=off both shapes now
      fail fast with MissingKineticaPluginException instead of aliasing/duplicate-key,
      pinned by two new checks=off IR tests)
- [x] write positive tests: slot calls directly in region content lambdas
      (`each` item content, `keyed` content) still compile and number correctly
      (new `deferredHandlersAndRegionContentCompileAndDispatchAtChecksError` pins a
      consumer-free `onClick` dispatching + `each` content + `keyed` content numbering
      stably across renders at checks=error; Task 1's `firAndIrAgreeOn*` drift tests
      keep covering region content on their own)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 7
      (105/105 green: 100 prior + 5 new; consumer scan found no slot calls inside
      handlers or key selectors in runtime/persist/test/bench/samples, so no new
      Task 19 fallout expected)

### Task 7: Close the val-stored-lambda hole (F6)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [x] write failing test from the review probe: `val row: (Int) -> Unit = { i -> state { i } ... }`
      invoked from `forEach` — currently compiles clean and aliases slot 0 across
      invocations; must become a FIR error at `checks=error`
      (probe ported verbatim as `valStoredLambdaWithOrdinalConsumersIsReported`, red
      before the fix; the checks=off IR probe
      `valStoredLambdaSlotCallsFailFastWhenChecksAreOff` reproduced the review's
      aliased "state=10 on every row" rendering verbatim pre-fix and now fails fast
      with MissingKineticaPluginException — the only possible coverage for the IR
      gate, since at checks=error FIR blocks compilation)
- [x] FIR: treat a lambda that is not a resolved call argument (local `val`, property,
      vararg element) as an unknown-run host — ordinal consumers inside it are errors;
      make `findLambdaHost` returning null flag instead of `continue`, and unwrap
      `FirVarargArgumentsExpression`
      (note: reports through the existing CALL_IN_MULTI_RUN_LAMBDA factory — no new
      factory, advice wording is Task 16 territory; the host label is the storing
      property's name via nearest-outward `FirProperty`, generic "stored" fallback.
      The vararg unwrap lives inside `findLambdaHost`, so all four callers — rule-F
      walk, loop boundary, region-content and component-typed classification — see
      vararg elements identically; pinned by the vararg probe asserting host
      'fanOut', which the stored-lambda fallback alone would not produce)
- [x] IR: gate `visitVariable` descent the same way `transformArgumentsSelectively`
      gates call arguments — never assign ordinals inside non-argument lambdas
      (note: gates on `IrFunctionExpression` initializers, the narrow mechanism the
      plan names; rarer non-argument positions — var reassignment, return-position
      or branch lambdas — stay FIR-rejected only, until Task 15 makes soundness
      rules non-disableable at checks=off)
- [x] write positive test: a `val` lambda with no ordinal consumers still compiles
      (`valStoredLambdaWithoutOrdinalConsumersCompiles` — the stored lambda contains
      emit-only Kinetica DSL (`text`), pinning that the rule keys on ordinal
      consumers, not on any Kinetica call in a stored lambda)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 8
      (109/109 green: 105 prior + 4 new; consumer scan over val/var-stored lambda
      literals in runtime/persist/test/browser/bench/samples found none containing
      ordinal consumers, so no new Task 19 fallout expected)

### Task 8: Reject non-literal @UiComponent content arguments (F8, extends rule C)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [x] write failing test from the review probe: hoisted content
      `val content: @UiComponent ComponentScope.() -> Unit = { Badge() }` passed to
      `render` — IR cannot wrap it, runtime throws; must become a FIR error telling
      the author to pass a lambda literal
      (probe `ruleC_hoistedComponentContentArgumentIsReported`, red before the fix:
      `render(content)` reported nothing — the only pre-existing diagnostic was a
      rule B error on `Badge()` inside the stored lambda, because FIR drops the
      `@UiComponent` type annotation from the stored literal's inferred type; the new
      rule adds the actionable "must be a lambda literal" error at the render site)
- [x] extend the existing rule C (`REGION_CONTENT_NOT_LITERAL` already rejects
      non-literal region content) to cover every parameter IR declines to wrap:
      any argument whose parameter type carries `@UiComponent` must be a lambda
      literal (matching IR's `as? IrFunctionExpression` restriction) — one rule, not
      a parallel new one; this also covers the "region argument is not a lambda
      literal" IR bail-out so Task 10 does not duplicate it
      (note: literal-only-as-written would reject the framework's own SOUND
      forwarding — `KineticaRuntime.render(content)` → two-arg overload,
      `Boundary.kt:85`, `HeadlessTestRoot`/`BrowserKineticaApp`/gtk/appkit stored
      content, `RouterSmokeTest.ShellNavHost`, `EachKeyedFlagTest.renderFlags` —
      values already frame-wrapped at their literal site. The rule therefore exempts
      reads of value parameters and NON-local properties (`FirValueParameterSymbol` /
      `FirPropertySymbol` minus `FirLocalPropertySymbol`) plus literal `null`; the
      local hoist — the F8 probe, the shape IR provably never wraps — is rejected,
      as are function references and call results. The gate helper
      `hasComponentTypedLambdaArgument()` was replaced by
      `unwrappableComponentContentArguments()` — Task 17's reorder target renamed
      accordingly)
- [x] add a test for the nested-local-fun path (`fun render() = ...` wrapper) — after
      Task 4 it compiles because entry points are no longer consumers; pin that with
      an explicit test so the old accidental-compile reason is replaced by a
      deliberate one
      (`ruleC_localFunctionRenderWrapperCompiles`)
- [x] write positive tests: literal content lambdas unaffected
      (`ruleC_literalComponentContentArgumentsCompile` — literal to entry point and
      to a user content wrapper — plus `ruleC_forwardedContentValuesRemainAllowed`
      pinning all three sound forwarding shapes the framework relies on)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 9
      (113/113 green: 109 prior + 4 new; consumer scan over every `@UiComponent`-typed
      parameter call site in runtime/test/persist/browser/gtk/appkit/router/samples/
      docs/bench/examples found only value-parameter and member-property forwards —
      all exempt — so no new Task 19 fallout expected)

### Task 9: Runtime — scope each-region eviction per render pass (F7)

**Files:**
- Modify: `kinetica-runtime/src/ComponentScope.kt`
- Modify: `kinetica-runtime/src/Frames.kt`
- Modify: `kinetica-runtime/test/EachIdentitySemanticsTest.kt`

- [x] write failing test from the review probe: `for (batch in listOf(a, b)) { each(batch, ...) }`
      rendered 3 times must give `initCount == 4` (state preserved), not 12
      (`eachInsideLoopPreservesRowStateAcrossRenders` — red before the fix with 8 inits
      by render 2, the F7 re-init signature; post-fix clicks pin that row events survive
      commit too)
- [x] implement the mechanism (region forking does NOT apply — `enterRegionChild` is
      unreachable from a raw loop body, see Context): preferred — defer keyed eviction
      from `renderEachRegion` to `Frame.commitChecks`, which already collects unkept
      children by generation; alternative — accumulate the `seen` set per
      `(ordinal, render generation)` on `Frame` so eviction only drops keys unseen by
      EVERY invocation in the pass
      (note: preferred mechanism implemented — `renderEachRegion` stamps the ordinal via
      `Frame.beginKeyedEvictionPass` and `commitChecks` disposes-and-removes unkept rows
      of stamped ordinals only; unstamped keyed ordinals — `keyed {}`, exit groups —
      keep retain-on-deactivate. Deferral also disposes rows of a pass that vanished
      entirely when the loop count shrinks, which per-call scoped eviction cannot)
- [x] decide overlapping-keys semantics for two loop iterations sharing a user key
      (`for (batch in listOf(listOf(1,2), listOf(1,2)))` — rows currently alias via
      `enterKeyedChild(ordinal, key)`): include the render-pass invocation index in
      the keyed-child identity (recommended — keeps the blessed pattern sound), or
      reject `each`/`keyed` in un-keyed loops in FIR; update
      `keyedAndEachRemainAllowedDirectlyInsideLoops` accordingly and add the
      overlapping-keys test
      (decision: recommended option — each-row identity is `keyedPassChildKey(pass,
      key)`; pass 0 keeps the bare user key so single-invocation identity and all
      existing frames are unchanged, and state follows (pass, key) across renders.
      `keyedAndEachRemainAllowedDirectlyInsideLoops` needs NO update — the pattern stays
      blessed. `keyed` itself needs no pass indexing: its content is a compiler-wrapped
      region, so Task 3's re-entry fork already forks same-key invocations — pinned by
      new `keyedInvocationsSharingKeyInLoopKeepIndependentState`, green pre-fix; the
      overlapping-keys test is `eachInvocationsSharingUserKeysKeepIndependentRowState`,
      red pre-fix with 2 aliased rows instead of 4. `disposeKeyScope` matching extended
      so pass-indexed `KeyedPassKey` rows still match their user key)
- [x] verify keyed-child disposal still happens when items genuinely disappear
      between renders (existing eviction tests stay green)
      (`rowDisposalOnKeyExitRunsExactlyOnce`, `removedThenReaddedKeyGetsFresh...` et al
      stay green; new `eachInsideLoopStillDisposesRowsWhoseKeysLeave` and
      `vanishedEachInvocationDisposesItsRows` pin per-batch disposal and vanished-pass
      disposal without state resurrection)
- [x] write test for the single-batch case (unchanged behavior, matches the test
      added in 657eef5)
      (`eachInsideLoopWithSingleBatchKeepsExistingBehavior` — the findings' control
      probe: one batch in a loop, 3 renders, initCount == 4)
- [x] run `./kotlin test -m kinetica-runtime --platform jvm` - must pass before task 10
      (runtime 222/222: 216 prior + 6 new; compiler suite re-run as a sanity check,
      113/113 — no compiler change, no republish needed)

### Task 10: No silent IR bail-outs — suspendSubtree and persistent keys (F9)

**Files:**
- Modify: `kinetica-compiler/src/KineticaIrTransform.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [x] write failing test from the review probe: `suspendSubtree(key = id, ...)` inside
      a component compiles clean today and throws `MissingKineticaPluginException` on
      first render — must become a compile error
      (`suspendSubtreeExplicitKeyIsReported`, red before the fix — the probe compiled
      clean; the checks=off backstop `suspendSubtreeExplicitKeyFailsCompileWhenChecksAreOff`
      was red too, pinning that the IR decline is now a located ERROR, not LOGGING)
- [x] audit the repo for `state(persistent = ..., key = ...)`-style explicit-key
      usages (kinetica-persist is the likely user) BEFORE flipping that bail-out to an
      error; if legitimate usages exist, the fix is IR support or a scoped exemption,
      not a blanket error — record the decision here
      (decision: NO runtime `state` overload has a `key` parameter at all — persistent
      state is addressed exclusively by `slotId` (kinetica-persist included) and there
      are zero `state(key = …)` call sites repo-wide; the IR bail-out survives from the
      PSI-era key addressing and the form is inexpressible against the pinned runtime.
      The blanket error therefore stands, kept as version-skew defense; positive
      coverage declares a simulated legacy `state(key, …)` overload in
      `io.heapy.kinetica` inside the test sources — the only possible probe)
- [x] add FIR rules for the two remaining documented IR bail-outs: `suspendSubtree`
      with explicit non-null key, persistent state with explicit key (the non-literal
      region argument bail-out is covered by Task 8's rule C extension)
      (note: implemented as one rule G with a single `UNSUPPORTED_EXPLICIT_KEY`
      factory2 — the second rendered parameter carries the per-callee consequence, so
      Task 16's advice mechanism has nothing to unwind. Absent and literal-null keys
      stay exempt (the Task 5 sound proxy), and IR's persistent-state gate now applies
      the same `isNullConst` exemption — previously `state(key = null)` would have
      tripped IR's bail-out while FIR stayed silent; the FIR gate also mirrors IR's
      other guards: `slotId`-overload callees and non-literal `persistent` are skipped)
- [x] upgrade `KineticaIrTransform.report` decline-to-transform paths from
      `CompilerMessageSeverity.LOGGING` to `ERROR`, threading a
      `CompilerMessageSourceLocation` from the `IrCall`'s file/offset — an ERROR
      without a location is not actionable
      (note: `report` gained defaulted severity+location parameters; the frame
      transformer routes info lines through `log(...)` and exactly the three
      F9-documented decline paths — suspendSubtree explicit key, persistent-state
      explicit key, non-literal region content — through `reportDecline(...)` = ERROR
      + `getSourceRangeInfo` location, keeping the "left on the legacy path" needle.
      `stageComponentCall`'s "left unstaged" stays LOGGING for Task 11's checkbox, and
      the two content-lambda-shape reports stay LOGGING: their harm is conditional and
      an ERROR there could reject sound emit-only content. checks=off IR ERRORS are
      pinned with location assertions by the two `...FailsCompileWhenChecksAreOff`
      tests plus `regionContentNotLiteralFailsCompileWhenChecksAreOff`)
- [x] write tests: `suspendSubtree(key = null, ...)` and implicit-key forms still
      compile and transform
      (`suspendSubtreeNullOrImplicitKeyCompilesAndTransforms` — content lambdas use
      `awaitCancellation()` so both renders deterministically emit the fallbacks — and
      `nullLiteralOrAbsentPersistentKeysStayOnTheCompilerPath` pinning keyless and
      literal-null-key persistent state on the compiler SlotId path end to end)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 11
      (120/120 green: 113 prior + 7 new; consumer scan: the only `suspendSubtree` call
      sites outside the compiler — RuntimeSmokeResourceTest — pass no key, and the
      explicit-key `state` form is inexpressible, so no new Task 19 fallout expected)

### Task 11: Component-call receivers IR cannot stage (F10)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaIrFrames.kt`
- Modify: `kinetica-runtime/src/ComponentScope.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [x] write failing tests from the review probes: `panes.first().Badge()` compiles
      clean and throws at render; the nested-argument variant steals the enclosing
      component's staged ordinal and renders into the wrong frame
      (probes ported as `ruleH_componentCallResultReceiverIsReported` and
      `ruleH_nestedArgumentReceiverStealIsReported` — both shapes compiled clean
      pre-fix; the wrong-frame steal itself is pinned at kernel level by
      `stagedOrdinalConsumedInDifferentFrameThrowsImmediately`, since post-fix the
      compiled shape no longer exists at either checks mode)
- [x] add FIR rule: a `@UiComponent` call's extension receiver must be a simple
      variable or `this` (mirror IR's `IrGetValue` requirement)
      (rule H, new `COMPONENT_RECEIVER_NOT_SIMPLE` factory; whitelist mirrors
      IrGetValue exactly — `this`, value parameters, plain non-delegated local vals.
      Probe-verified BEFORE writing the rule: IR leaves safe-call (`maybe?.Badge()`)
      AND smart-cast (`if (maybe != null) maybe.Badge()`) receivers unstaged — fir2ir
      wraps both variable reads — so rule H rejects those too, pinned by
      `ruleH_safeCallAndSmartCastReceiversAreReported`; migration is binding to a
      plain local val of the scope type)
- [x] upgrade the IR "left unstaged" report to `ERROR` with source location (same
      mechanism as Task 10)
      (routes through `reportDecline`; pinned with a location assertion by
      `componentCallNonTrivialReceiverFailsCompileWhenChecksAreOff` —
      `assertSingleIrDeclineError` gained a `pathMarker` parameter because "left
      unstaged" is the accurate needle here, not "left on the legacy path": the call
      is not retargeted, only its staging is missing)
- [x] runtime hardening: add a stack-discipline check so `consumeStagedOrdinal` throws
      immediately on a mismatched pop instead of corrupting frames — do NOT tag staged
      entries with the callee: `beginComponentFrame` is public plugin↔runtime ABI, and
      changing its contract forces plugin/runtime version lockstep for every mavenLocal
      consumer
      (implemented as frame-pairing: `ordinal(n)` records the current frame in a
      parallel internal array — no ABI change — and `consumeStagedOrdinal` throws
      IllegalStateException BEFORE entering any frame when the popped entry was staged
      in a different frame. Catches every cross-frame steal immediately; a SAME-frame
      steal is indistinguishable without callee tagging (the forbidden ABI change) and
      still fails loudly within the same render via the existing empty-stack throw at
      the enclosing call's prologue. Deliberately NO commit-time empty-stack assert:
      entries legitimately linger when an error boundary catches a throw between
      staging and the staged call; beginRender drops them)
- [x] write positive tests: `this.Badge()`, `scope.Badge()` via local val — unaffected
      (`ruleH_simpleReceiversCompile` — implicit/explicit `this`, parameter, local
      val — plus the end-to-end render probe
      `componentCallsOnSimpleReceiversStageAndRenderStably`; kernel positives
      `argumentPositionStagingKeepsLifoDiscipline` and
      `stagingInsideRegionFrameIsConsumedInThatFrame` pin the blessed staging shapes
      under the new check)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` and
      `./kotlin test -m kinetica-runtime --platform jvm` - must pass before task 12
      (compiler 126/126: 120 prior + 6 new; runtime 225/225: 222 prior + 3 new;
      consumer scan for call-result/safe-call receivers before capitalized calls found
      only java.net.http builder GET() noise, so no new Task 19 fallout expected)

### Task 12: Unify call classification in the FIR checker (F11)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [x] write failing test: a `@UiComponent` symbol without a `callableId` inside
      `forEach { Badge() }` must be a rule D/F error, not silence (probe via the same
      synthetic-symbol path that motivated the `?: callee.name.asString()` fallback)
      (note: red-before-fix was IMPOSSIBLE — the reviewed shape is inexpressible in
      Kotlin 2.4.10. Bytecode-verified in kotlin-compiler-embeddable 2.4.10:
      `FirFunctionSymbol` stores a NON-null `CallableId` (local functions get the
      `<local>` package), and the only null-returning symbol, `FirLocalPropertySymbol`,
      is never a `FirFunctionCall` callee — F11 was a code-inspection finding (no
      "probe-verified" tag in the findings file) and its hole is latent, not reachable.
      The new probe `localComponentFunctionCallsInRepeatedContextsAreReported` pins the
      nearest expressible shape — a local `@UiComponent` fun called in `forEach` (rule F)
      and a `for` loop (rule D), green before AND after — and becomes the real red probe
      if a future toolchain nulls local callableIds)
- [x] in `consumesCompilerOrdinal`, check `hasAnnotation(UI_COMPONENT_CLASS_ID)` before
      the `callableId ?: return false` bail
      (note: subsumed by the classification helper below — the component verdict is now
      `hasAnnotation` with NO callableId dependence at all; latent defense in 2.4.10,
      where every function callee carries a callableId, see the checkbox above)
- [x] extract one shared classification helper (slot call / component call / host event /
      entry point) used by both `check()` and `consumesCompilerOrdinal`, so the five
      facts are derived once
      (note: `KineticaCallClassification` (name + isSlotDsl/isRegionConstruct/
      isLoopSafeRegion/isComponentCall, built once by `classifyKineticaCall`) now feeds
      both the rule dispatch in `check()` and `consumesCompilerOrdinal()`, which carries
      the host-event literal-null exemption; entry points are the none-of-the-above
      case handled via `unwrappableComponentContentArguments`. The
      `FirFunctionCall.consumesCompilerOrdinal(session)` extension survives only for
      `multiRunLambdaHost`'s nested-consumer walk)
- [x] verify the full existing checker suite stays green
      (127/127 — the unification is behavior-identical in this toolchain, provably: with
      non-null callableIds everywhere, old and new predicates agree on every callee, so
      no new Task 19 fallout is possible; consumer scan found no local `@UiComponent`
      declarations outside the compiler tests either)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 13
      (127/127 green: 126 prior + 1 new pin)

### Task 13: Fix nested-report suppression and drop the dead source matcher (F14 + S3)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [x] write failing test: an ordinal consumer nested in the argument of an outer
      consumer that exits early via rule A or B — the inner
      `CALL_IN_MULTI_RUN_LAMBDA` must still be reported
      (probe `multiRunReportSurvivesOuterConsumerRuleAEarlyExit`: outer `state` inside
      entry content exits at rule A while inner `Badge` reaches rule F with host
      'forEach' — red before the fix, only the rule-A error surfaced. The rule-B
      flavor is inexpressible: restoring the inner call's containment needs a
      component-typed lambda between outer and inner, and that same lambda is a
      numbering boundary that ends the inner rule-F walk, so one rule-A probe covers
      the finding)
- [x] change `nestedUnderOrdinalConsumer` to suppress only when the enclosing call
      actually produced a report (track reported calls per file/function instead of
      re-deriving "would report")
      (note: implemented as `multiRunReportedCalls` — a per-checker-instance (one per
      session, hence per compilation) concurrent identity set of calls that actually
      reported CALL_IN_MULTI_RUN_LAMBDA; node identity cannot collide across files, so
      no per-file scoping is needed, and only erroring calls are retained. Checkers
      visit enclosing calls before nested ones, so the outer verdict is recorded before
      the inner check runs; chained suppression still works because the scan range
      starts at the unsafe lambda. Suppression now also survives rule D/H early exits —
      same principle as A/B; strictly narrower suppression can only ADD diagnostics to
      code that already carried the outer error, so no new Task 19 fallout is possible.
      The `FirFunctionCall.consumesCompilerOrdinal(session)` extension, kept in Task 12
      solely for this walk, is now dead and deleted)
- [x] delete the dead source-offset branch in `findLambdaHost` and the whole
      `hasSameSourceAs` (measured 0 hits across the suite; only branches able to match
      the wrong lambda)
      (`findLambdaHost` matches by lambda symbol identity alone; `hasSameSourceAs`
      deleted together with its only caller, the old suppression guard)
- [x] verify suppression still works for the legitimate symbol-identity cases in the
      existing suite
      (`multiRunNestedOrdinalCallsReportOnlyTheOutermostConsumer` — button reports,
      nested event stays silent — stays green; it would fail with 2 errors if the
      traversal-order assumption or the reported-set mechanism were wrong)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 14
      (128/128 green: 127 prior + 1 new probe)

### Task 14: Per-function FIR/IR alignment for top-level Kinetica helpers (F12 remainder)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFramePolicy.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt`

- [x] write failing tests for the four mismatched top-level functions: `peek`,
      `derive`, `invalidate`, `serverActionStub` — for each, FIR verdict and IR
      numbering must agree (the review probe: `derive { state { 1 }.value }` errors
      at `checks=error` but numbers fine at `checks=off`)
      (note: red-before-fix is no longer reproducible at HEAD — Task 1's shared
      predicate plus Task 2's oracle gating already closed the mismatch as a side
      effect; the findings file carries the probe-verified red state at 657eef5. The
      new tests pin the agreement: FIR halves
      `deriveComputeLambdaOrdinalConsumerIsReported` /
      `invalidatePredicateOrdinalConsumerIsReported` /
      `serverActionStubHandlerOrdinalConsumerIsReported` (rule F at checks=error); IR
      halves at checks=off `deriveComputeSlotCallsFailFastWhenChecksAreOff` — the
      review probe verbatim, now MissingKineticaPluginException instead of unsound
      numbering — plus `invalidatePredicateStaysUnnumberedWhenChecksAreOff` and
      `serverActionStubHandlerStaysUnnumberedWhenChecksAreOff`, which pin the IR
      verdict via the framed slot count (slots=1, not 2) because those lambdas never
      execute during render. `peek` matches (findings' own enumeration) and its
      agreement is already pinned end to end by `firAndIrAgreeOnSingleRunLambdaHosts`)
- [x] decide per function in `KineticaFramePolicy` (Task 1's shared predicate is the
      single place to encode it) whether its lambda is a numbering context (IR
      descends + FIR allows) or not (IR skips + FIR rejects); `derive`, `invalidate`,
      `serverActionStub` lambdas re-run reactively — classify multi-run on both sides
      unless the runtime slot semantics prove otherwise
      (decision: all three multi-run — runtime sources confirm `derive`'s compute
      re-runs per dependency change (`DerivedCell`), `invalidate`'s predicate runs per
      cached key at invalidation time, `serverActionStub`'s handler runs per dispatch;
      `peek` runs inline exactly once and stays the sole single-run top-level helper.
      Encoded as the `MULTI_RUN_TOP_LEVEL_FUNCTIONS` ledger next to
      `SINGLE_RUN_TOP_LEVEL_FUNCTIONS` — deliberately NOT consulted by `isKineticaDsl`
      (absence from the single-run set already means multi-run on both phases); it
      records the per-function decision so the enumeration drift test can force one
      for every future helper, per its KDoc)
- [x] add a drift test enumerating all top-level `io.heapy.kinetica` lambda-taking
      functions and asserting FIR/IR agreement for each
      (`everyTopLevelKineticaLambdaFunctionHasAnExplicitFramePolicyClassification`:
      reflection-enumerates the runtime's file facades on the test classpath for
      top-level lambda-taking functions WITHOUT a ComponentScope receiver — the exact
      scope of F12's enumeration; extensions/members are classified per parameter by
      Task 6's tables and Task 1's drift tests — then asserts scanned =
      single-run ∪ multi-run in both directions (no unclassified, no stale entries,
      sets disjoint) and that the shared predicate's verdict matches the set for every
      name. Found a fifth top-level function the findings missed: `synchronizedOn`,
      Kotlin-internal (bytecode-public inline), uncallable from consumer source —
      excluded via a documented test-side list. Mutation-verified: removing `derive`
      from the ledger fails the test with "unclassified … [derive]")
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 15
      (135/135 green: 128 prior + 7 new — 3 FIR probes, 3 IR probes, 1 enumeration
      drift test; no compiler-behavior change in this task, so no mavenLocal republish
      needed and no new Task 19 fallout possible)

### Task 15: checks=off must not remove soundness rules (S1)

**Files:**
- Modify: `kinetica-compiler/src/KineticaCompilerRegistrar.kt`
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/src/KineticaCommandLineProcessor.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`
- Modify: `kinetica-compiler/test/KineticaIrFrameCompileTest.kt` (➕ its checks=off
  probes from Tasks 2/6/7/14 compiled unsound shapes and had to follow the semantics
  change — see the conversion note below)
- Modify: `kinetica-gradle-plugin/src/KineticaGradlePlugin.kt`,
  `kinetica-gradle-plugin/src/KineticaExtension.kt`,
  `kinetica-gradle-plugin/test/KineticaPluginContractTest.kt` (➕ the Gradle-side
  `CHECKS` allowlist was `setOf("error", "off")` — it rejected `warning` with a
  GradleException, making the new style-only warning mode unreachable from Gradle
  builds; now accepts all three documented values, extension KDoc updated, and a new
  contract test pins the allowlist so it cannot lag again; gradle-plugin suite 7/7)

- [x] write failing test: with `checks=off`, the val-lambda aliasing probe (Task 7)
      and the multi-run rule F probes must still be compile errors
      (`checksOffKeepsValStoredLambdaRuleAnError` and
      `checksOffKeepsMultiRunOrdinalRuleAnError` — both red before the fix — plus the
      flipped `checksOffKeepsSlotCallOutsideComponentAnError` (was
      `checksOffLeavesViolationsUnreported`) pinning rule A, and
      `checksWarningKeepsSoundnessRulesAsErrors` pinning that `warning` never
      downgrades soundness either)
- [x] split diagnostics into soundness (rules that prevent runtime crash/aliasing —
      always error, not configurable) and style (governed by `checks`)
      (decision: style = rule E ONLY (COMPONENT_WITHOUT_SCOPE_RECEIVER); every other
      rule guards a crash or aliasing — A/B/F violations fail fast at render, D
      aliases silently, rule C's F8 half has no IR backstop, G/H mirror the located IR
      declines. Making E style required closing its crash shadow:
      `classifyContainment` now grants COMPONENT_BODY only to @UiComponent functions
      WITH a ComponentScope receiver — exactly IR's `isUiComponentWithScopeReceiver`
      framing predicate — so slot/component calls inside a scope-free component stay
      OUTSIDE for rules A/B in every mode, pinned by
      `scopeFreeComponentBodyStaysOutsideForSoundnessRules`)
- [x] implement the severity mechanism `checks=warning` needs (it is currently a
      behavioral no-op alias for `error` — the registrar's only branch is `!= "off"`
      and all factories are fixed-severity `error1`/`error2`): either parallel
      warning factories or severity remapping at report time — pick one and record
      the choice here
      (decision: parallel warning factory — `COMPONENT_WITHOUT_SCOPE_RECEIVER_WARNING`
      via `warning1`, sharing the error factory's message template; report-time
      severity remapping is not possible with fixed-severity `KtDiagnosticFactory`.
      The declaration checker takes the factory to report; the new
      `KineticaChecksMode` (ERROR/WARNING/OFF, unknown option values → ERROR) selects
      it, and OFF registers no declaration checker at all. Pinned by
      `checksWarningDowngradesStyleDiagnosticsToWarnings` — red before the fix, since
      `warning` was an error alias — and `checksOffSuppressesStyleDiagnostics`)
- [x] `checks=off` keeps style rules off but registers the FIR extension with
      soundness rules active; `checks=warning` downgrades style rules only
      (the registrar registers the FIR extension unconditionally and passes the mode.
      Conversion note — the pre-S1 checks=off probes compiled unsound shapes and were
      updated to the new semantics: 7 runtime fail-fast probes in
      `KineticaIrFrameCompileTest` (List/derived, button handler, each key selector,
      val-stored lambda, derive/invalidate/serverActionStub) now pin the rule F
      compile error at checks=off; the 4 `...FailsCompileWhenChecksAreOff` FIR probes
      now stop at the FIR rule C/G/H error before IR runs, so the IR located-ERROR
      declines became untestable defense-in-depth (the dead
      `assertSingleIrDeclineError` helper was removed);
      `unstagedComponentCallThrowsMissingPlugin` became the rule B compile pin
      `rawComponentCallOutsideComponentsFailsCompileWhenChecksAreOff`;
      `emptyOracleFallsBackToNameListsForScopeFunctionLambdas` was renamed
      `nameListFallbackNumbersScopeFunctionLambdas` per its own Task 15 NOTE; and two
      sound-but-rule-A-rejected probes
      (`componentContentParametersWrapInsideComponentBodies` and the F3 fork probe
      `contentLambdaInvokedTwiceForksRegionFramesPerInvocation`) moved their slot
      calls into child components — the blessed shape, matching what every consumer
      already writes at the default checks=error)
- [x] update the CLI option help text: `checks` is no longer an escape hatch for
      soundness
      (`<error|warning|off>`; the text names the style scope and states that the
      soundness rules cannot be disabled because their absence crashes or aliases at
      render)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 16
      (140/140 green: 135 prior + 5 new checks-mode tests; consumer scan: no module in
      the repo sets `checks` at all — everyone already builds at the default `error` —
      so no mavenLocal republish is needed and no new Task 19 fallout is possible)
- ⚠️ known gap surfaced while converting (predates this task and is checks-mode
  independent): `listOf(1).forEach { helper { Badge() } }` — a content-wrapper call
  inside a multi-run lambda — compiles clean at EVERY checks mode (the rule-F walk
  stops at the component-typed lambda boundary, and post-F1 the wrapper consumes no
  ordinal) yet crashes at first render, because the IR walker never descends into the
  multi-run lambda to wrap the content. Pinned by
  `multiRunCallWithComponentTypedLambdaArgumentCompiles` (compile-only) and
  `multiRunComponentTypedHelperFailsFastWhenChecksAreOff` (runtime fail-fast, kept).
  Task 20's findings walk must weigh this against the Overview promise; fixing it is
  new scope (either FIR rejects wrapper calls in multi-run lambdas, or IR wraps
  content arguments even in undescended lambdas).
  RESOLVED in Task 20: FIR now rejects it (new soundness factory
  `COMPONENT_CONTENT_IN_MULTI_RUN_LAMBDA`, every checks mode), keyed on
  COMPONENT_BODY containment + a @UiComponent content lambda literal + the existing
  rule-F multi-run/stored-lambda host walk — exactly the positions IR's gated walker
  never reaches, so entry content (wrapped ungated by transformEntryPoints) stays
  compiling. The two pins named above became
  `multiRunComponentTypedHelperFailsCompileWhenChecksAreOff` (compile-error pin) and
  the forEach half of `multiRunCallWithComponentTypedLambdaArgumentCompiles` moved to
  `componentContentWrapperInMultiRunLambdaIsReported`; see Task 20 for the full list.

### Task 16: Context-aware diagnostic advice (S2)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`
- Modify: `kinetica-compiler/test/KineticaFirCheckerTest.kt`

- [x] write failing tests asserting message content: when the flagged call IS
      `each`/`keyed`, the message must not advise "Use each(...) or keyed(...)";
      when the consumer is an entry point idiom, advice must fit
      (note: `multiRunKeyedConstructGetsHoistAdviceInsteadOfEachKeyedAdvice` — flagged
      `keyed` inside `forEach`, with an explicit no-default-advice assertion — plus the
      flipped `each` expectation in `multiRunRegionAndComponentCallsAreReported`; both
      red before the fix. The entry-point half of S2 can no longer produce a message at
      all: Task 4/F1 made entry points non-consumers, so rule F never fires on
      `render { … }` — pinned by the existing `renderEntryPointInsideRepeatCompilesAndRuns`
      and `renderEntryPointInsideAssertFailsWithCompiles`; there is no advice left to fix)
- [x] implement the mechanism: the template lives in `KineticaFirErrorRenderers.MAP`
      in `KineticaFirExtension.kt` and `CALL_IN_MULTI_RUN_LAMBDA` is a
      `KtDiagnosticFactory2<String, String>` with a fixed per-factory template —
      either add a third rendered parameter carrying the advice string, or split into
      separate factories per advice shape; pick one and record the choice here
      (decision: third rendered parameter — the factory is now
      `KtDiagnosticFactory3<String, String, String>` via `error3` and the template ends
      in `{2}`; the report site picks the advice string. Mirrors Task 10's
      UNSUPPORTED_EXPLICIT_KEY rendered-consequence mechanism, keeps ONE factory so the
      F14 suppression set tracks nothing new, and leaves every default-advice message
      pin byte-identical; parallel factories would split rule F's identity over wording)
- [x] make the advice depend on the flagged callee: slot call → suggest `each`/`keyed`;
      `each`/`keyed` themselves → suggest hoisting out of the loop or keying the outer
      construct
      (selector: `facts.isLoopSafeRegion` — all four loop-safe regions
      (`keyed`/`suspendKeyed`/`each`/`lazyEach`) key their own content, so all four get
      "hoist it out of the multi-run lambda, or key the outer repetition with
      keyed(...)"; slot calls, component calls, and non-loop-safe regions such as
      `errorBoundary` keep the each/keyed advice, which fits them — wrapping in
      each/keyed is exactly their migration)
- [x] verify all existing message-matching tests updated consistently
      (the shared `multiRunMessage` helper already carried the default advice verbatim,
      so its 29 call sites needed no edits; only the flagged-`each` line moved to the
      new `multiRunKeyedRegionMessage` helper, and KineticaIrFrameCompileTest's two
      contains-assertions match the message prefix before the advice — unaffected)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 17
      (141/141 green: 140 prior + 1 new; message wording cannot change whether a
      consumer compiles, so no mavenLocal republish is needed and no new Task 19
      fallout is possible)

### Task 17: Move the component-lambda probe behind the cheap guard (S4)

**Files:**
- Modify: `kinetica-compiler/src/KineticaFirExtension.kt`

- [x] reorder `check()` so `unwrappableComponentContentArguments()` (renamed from
      `hasComponentTypedLambdaArgument()` in Task 8; argument-mapping walk +
      cone-type + annotation resolution) runs only after the cheap early-return guard
      rejects the common case — it now also feeds the end-of-check rule C block, so
      compute it lazily rather than skipping it
      (implemented as a local `by lazy(LazyThreadSafetyMode.NONE)` delegate: the probe
      is now the guard's final conjunct, so Kinetica-construct calls short-circuit past
      it on the cheap facts and pay it at most once — in the end-of-check rule C block —
      while only the none-of-the-four call probes at the guard; the delegate has exactly
      those two readers, verified by grep)
- [x] confirm no diagnostic changes: full checker suite green (this is the test — the
      change is pure reordering; no new test cases apply to a no-behavior-change
      reorder, the existing suite is the coverage)
      (the probe is pure — evaluating it 0 or 1 times instead of always-once cannot
      change any diagnostic; suite confirms)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 18
      (141/141 green, same count as after Task 16 — no new tests by design; diagnostics
      unchanged, so no mavenLocal republish is needed and no Task 19 fallout is possible)

### Task 18: Clean up harness temp directories (S5)

**Files:**
- Modify: `kinetica-compiler/test/KineticaCompilationHarness.kt`
- ➕ Create: `kinetica-compiler/test/KineticaCompilationHarnessTest.kt` (repo
  convention is one test class per `*Test.kt` file, so the S5 tests live beside the
  harness rather than inside it)

- [x] make `compileExpectingErrors` (and any sibling entry point) delete its
      `kinetica-compile-*` temp tree in a `finally` block / `deleteRecursively`
      (note: `compileExpectingErrors` deletes in a `finally` around its assertion;
      the sibling `compile` deletes on its failure path before `fail(...)` and on
      success hands the root to `CompiledKineticaModule`, whose `close()` deletes it
      AFTER closing the classLoader — classes load lazily from `outputDir`, so
      deletion cannot happen earlier; every `compile` result in the suite is already
      `.use{}`/`.close()`-terminated, verified by grep. `compileInternal` itself
      deletes on exceptional exit, so ownership passes to callers only on normal
      return and no path leaks)
- [x] write a test asserting the temp root contains no `kinetica-compile-*` entries
      after a compile-expecting-errors run
      (note: asserted as no NEW `kinetica-compile-*` entries against a pre-run
      snapshot, not as an empty temp root — the literal reading is unsatisfiable:
      the shared temp dir holds ~3.7k trees leaked by pre-fix suite runs, and
      sweeping entries this process did not create would be unsafe. Three tests in
      `KineticaCompilationHarnessTest`: expect-errors run leaves nothing; successful
      compile keeps its tree while classes still load inside `use` and deletes it on
      close; failed compile deletes before `fail` throws. The `startsWith
      ("kinetica-compile-")` filter keeps the trailing hyphen so the JVM-lifetime
      `kinetica-compiler-plugin*.jar` can never enter the diff)
- [x] run `./kotlin test -m kinetica-compiler --platform jvm` - must pass before task 19
      (144/144 green: 141 prior + 3 new; empirically re-verified with a temp-dir
      snapshot around a full suite rerun — 0 new entries, where a pre-fix run left
      ~104. Test-harness-only change: no compiler-behavior delta, no mavenLocal
      republish needed, no Task 19 fallout possible)

### Task 19: Publish, clean, and force-rebuild every consumer — the check that caches defeated

**Files:**
- Modify: none expected (fix fallout only; if fixes are needed, add ➕ tasks)

- [x] publish the rebuilt plugin: `./kotlin publish mavenLocal -m kinetica-compiler`
      — consumers pin `io.heapy.kinetica:kinetica-compiler:0.4.0` from mavenLocal
      (`common.module-template.yaml`), so this OVERWRITES the 0.4.0 coordinate; verify
      the artifact timestamp in `~/.m2/repository/io/heapy/kinetica/kinetica-compiler/0.4.0/`
      changed before rebuilding anything
      (verified BEFORE any rebuild: jar 320881 → 334412 bytes, sha256
      fbfcf85f… → fc7361bd…, all timestamps fresh — the previous artifact was Task 4's
      republish, stale relative to Tasks 5-18)
- [x] clean consumer outputs so no stale compile task survives (remove the toolchain
      `build/` outputs for the consumer modules, or use the toolchain clean command)
      (`./kotlin clean` — the project `build/` directory was gone entirely, so every
      consumer fragment recompiled from scratch)
- [x] rebuild ALL consumers of `common.module-template.yaml`, not just the six that
      broke at 657eef5: `kinetica-runtime`, `kinetica-test`, `kinetica-persist`,
      `kinetica-data`, `kinetica-forms`, `kinetica-browser`, `kinetica-router`,
      `kinetica-appkit`, `kinetica-gtk`, `kinetica-markdown`, `kinetica-motion`,
      `kinetica-theme`, `bench-jvm`, `docs/docs-client`, `samples/*`
      (28 of 30 template consumers rebuilt via `./kotlin build -m …` after the clean;
      `kinetica-render-core` and `docs-site` included too — the list above omitted
      them but they apply the template. EXCLUDED: `kinetica-gtk` +
      `samples/native-counter-gtk` — documented Linux/CI-only (module.yaml: GTK dev
      headers required, `cinterop/gtk4.def` generated on Linux by `generate-def.sh`,
      not committed, KNT-0047); their linuxX64 cinterop cannot run on this macOS host.
      Baseline parity holds: the 657eef5 forced rebuild produced zero gtk errors, so
      gtk was never part of this verification on this machine; its real verification
      is the first CI run (Post-Completion). The three compose samples do not apply
      the template — not plugin consumers, not rebuilt)
- [x] confirm zero compile errors (the 657eef5 baseline was 28: runtime/test 19,
      persist/test 5, bench-jvm 4)
      (0 errors across 126 fragment compilations — and the comparison is
      like-for-like: the exact fragments that carried the baseline all compiled clean,
      including `kinetica-runtime` + `kinetica-persist` TEST fragments, `bench-jvm`
      src, and the ~57 `KineticaTest.render` sites in kinetica-test/data/forms test
      fragments, which `./kotlin build` compiles — `[jvm tests]` lines confirmed)
- [x] run the module test suites:
      `./kotlin test -m kinetica-runtime --platform jvm`,
      `./kotlin test -m kinetica-test --platform jvm`,
      `./kotlin test -m kinetica-persist --platform jvm`
      (225/225, 52/52, 4/4 — all green against the freshly published plugin; renders
      succeed, so the plugin demonstrably ran — no MissingKineticaPluginException, no
      stale-plugin false pass)
- [x] run the review's silent-aliasing probes end-to-end via
      `KineticaCompilationHarness` one final time — every probe must now be either a
      compile error or correct runtime behavior; none may crash or alias
      (`./kotlin test -m kinetica-compiler --platform jvm` — 144/144; every review
      probe was ported into this suite by Tasks 2-14, so this run IS the probe sweep)
- [x] run tests - must pass before task 20
      (all of the above in one post-clean cycle: compiler 144, runtime 225,
      kinetica-test 52, persist 4 — zero failures)

### Task 20: Verify acceptance criteria
- [x] verify all 20 findings from `20260821-ordinal-soundness-findings.md` are
      addressed (walk the findings file's mapping table item by item; mark each with
      the task that fixed it)
      (walk recorded IN the findings file — new section "Task 20 verification walk"
      maps every ID to verdict, fixing task, and its dedicated tests; all 20 ✅)
- [x] verify edge cases are handled (platform types, vararg lambdas, nested local
      functions, overlapping keys across loop iterations, `checks=off`/`warning` modes)
      (platform types — `platformTypedHandlerInLoopIsReported` +
      `typeParameterTypedHandlerInLoopIsReported`; vararg lambdas —
      `varargLambdaElementWithOrdinalConsumersIsReported`; nested local functions —
      `ruleC_localFunctionRenderWrapperCompiles` +
      `localComponentFunctionCallsInRepeatedContextsAreReported`; overlapping keys
      across loop iterations — Task 9's each/keyed tests PLUS the lazyEach hazard
      flagged there, closed by the ➕ item below; exitGroup (also named in Task 9's
      decision) evaluated clean: exitGroupRegion has NO per-call eviction sweep
      (retain-on-deactivate, unstamped ordinal) and same-key re-entry cannot alias
      slots because its content is a compiler-wrapped region — Task 3's fork, the
      exact mechanism pinned for keyed by
      keyedInvocationsSharingKeyInLoopKeepIndependentState; the scope-level
      ExitGroupState shared per key is the construct's documented global
      exit-coordination identity, not ordinal state; checks modes — the six Task 15
      checks-mode tests plus the new
      `multiRunComponentTypedHelperFailsCompileWhenChecksAreOff`. The sweep also
      re-probed the entry-content double-wrap suspicion and retired it — WRONGLY, as
      the post-completion review proved: the double wrap was real (bytecode: 2×
      beginRegionFrame + 2× ordinal(0) per wrapped nested wrapper), but the green
      probe's leaked entries stayed in ONE frame so nothing consumed them; a wrapper
      inside a component-call argument (`Outer(label = run { helper { Badge() }; "x"
      })`) crashed at first render on the Task 11 frame-pairing check. Fixed after
      review: wrapFreshRegionOf is idempotent (wrappedContentLambdas) and
      transformArgumentsSelectively never descends into @UiComponent-typed literals;
      pinned by `entryContentWrapperInsideComponentCallArgumentRendersOnce` and
      `contractSingleRunContentWrapperWrapsContentExactlyOnce`)
- ➕ [x] close the Task 15 ⚠️ gap (in scope of the Overview promise: compile-clean
      code crashed at first render): new FIR soundness rule
      `COMPONENT_CONTENT_IN_MULTI_RUN_LAMBDA` rejects a @UiComponent content lambda
      literal passed from inside a multi-run or stored lambda WITHIN a component body
      — the exact positions IR's gated walker never wraps; entry content (ungated
      `transformEntryPoints` wrap) and single-run hosts (`for` loops, `run`, contract
      hosts) keep compiling. FIR option A2 chosen over teaching IR to wrap inside
      undescended lambdas: it is the plan's own F9/F10 pattern ("every construct IR
      declines gets a matching FIR error"), additive-errors-only for Task 19's
      verification, and nothing that hits this shape today works (it crashes), so no
      working code breaks. Tests: `componentContentWrapperInMultiRunLambdaIsReported`,
      `componentContentWrapperInStoredLambdaIsReported`,
      `componentContentWrapperInEntryContentCompiles`,
      `multiRunComponentTypedHelperFailsCompileWhenChecksAreOff` (converted),
      `entryContentUserWrapperRendersWithStableIdentity`, and the reshaped
      `multiRunCallWithComponentTypedLambdaArgumentCompiles` (direct/loop/run hosts
      stay clean). Consumer scan: the only @UiComponent-typed content wrappers
      repo-wide are `render` (entry), Router's NavHost/navLazyEach (@UiComponent —
      already rule-F consumers), and the gradle-ssr example's island/SiteLayout
      (called directly in component bodies); `host`/`column` content params carry no
      @UiComponent, so the ubiquitous host-in-forEach shapes are untouched —
      confirmed by runtime 229/229, kinetica-test 52/52, persist 4/4 against the
      republished plugin
- ➕ [x] close the Task 9 lazyEach flag (F7 hazard shape, in scope of the "never
      silently aliases" promise): `lazyEachRegion` rows now carry the render-pass
      invocation index exactly like `each` rows (`Frame.beginKeyedPass` — pass
      counting split out of `beginKeyedEvictionPass` WITHOUT the commit-time eviction
      stamp, because lazyEach retention is policy-driven and hidden rows must stay
      retained), and the VisibleOnly/PersistentSlots sweeps only touch rows their own
      pass owns (`keyedPassUserKey`). Pass 0 keeps bare keys — single-invocation
      identity and existing frames unchanged. Red-before-fix verified by stashing the
      runtime fix: 2 aliased inits instead of 4 (Keyed overlap) and 12 re-inits
      instead of 4 (VisibleOnly and PersistentSlots cross-invocation eviction).
      Accepted semantic, mirroring Task 9's vanished-pass note: a loop pass that
      vanishes between renders deactivate-retains its rows under
      VisibleOnly/PersistentSlots instead of disposing them (no per-render sweep owns
      them anymore). Tests: `lazyEachInvocationsSharingUserKeysKeepIndependentRowState`,
      `lazyEachVisibleOnlyInLoopKeepsSiblingInvocationRows`,
      `lazyEachPersistentSlotsInLoopKeepsSiblingInvocationRows`,
      `lazyEachVisibleOnlySingleInvocationStillDisposesHiddenRows`
- [x] run full test suite: `./kotlin test -m kinetica-compiler --platform jvm` and
      `./kotlin test -m kinetica-runtime --platform jvm`
      (compiler 148/148: 144 prior + 4 new; runtime 229/229: 225 prior + 4 new;
      compiler republished to mavenLocal BEFORE the runtime/test/persist runs — jar
      334412 → 337258 bytes — so the consumer suites compiled under the new rule:
      kinetica-test 52/52, kinetica-persist 4/4)
- [x] no e2e suite in this repo — Task 19's downstream rebuild stands in; confirm it
      was completed
      (confirmed complete: every Task 19 checkbox done — artifact hash verified
      before rebuild, `./kotlin clean`, 28 of 30 template consumers rebuilt with 0
      errors across 126 fragment compilations, gtk pair documented Linux/CI-only, and
      the three module suites green. Task 20's compiler change is additive-errors-only
      and the scan above found no consumer hitting the new rule, so Task 19's zero-
      error verdict stands without a second full rebuild)
- [x] verify test coverage: every finding has at least one dedicated test case
      (per-finding test list in the findings file's verification walk; one documented
      exception carried from Task 17: S4 is a pure evaluation-order reorder whose only
      honest check is the full suite — inventing a dedicated test would pin nothing)

### Task 21: [Final] Update documentation
- [x] update `plan.md` module status table if test counts changed
      (the only table with test counts is the KSND soundness-suite table; its
      kinetica-runtime row counts the 2 KSND files — `EachIdentitySemanticsTest.kt` +
      `ReactivityParityTest.kt` — and Task 9 added 6 @Test fns to the former (10 → 16),
      so the row's @Test fns cell went 20 → 26 with an inline non-KSND note; KSND case
      counts unchanged (no new KSND-tagged tests), browser and kinetica-test rows
      verified unchanged old-vs-now, so left as-is)
- [x] record the FIR/IR-policy-must-stay-shared invariant in `AGENTS.md` (the 0-byte
      guidance file; `CLAUDE.md` just references it via `@AGENTS.md`)
      (written: shared tables live only in `KineticaFramePolicy`, oracle verdicts flow
      FIR→IR with the name-list fallback on BOTH sides, no silent IR bail-outs, and the
      `firAndIrAgreeOn*` drift tests pin it — all symbol names verified against source)
- [x] move this plan to `docs/plans/completed/`
      (not moved — the plan-execution harness relocates the plan itself after all
      phases finish; moving it mid-run breaks the later review/finalize phases)

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
  receivers, val-stored lambdas with ordinal consumers, and (Task 20) content-wrapper
  calls passing `@UiComponent` lambda literals from inside multi-run or stored lambdas
  within component bodies (`items.forEach { section { … } }` — migration: hoist the
  wrapper out of the multi-run lambda, or use `each(items, key = …)`/`keyed(…)`)
- behavior fix (not an error): `lazyEach` invoked repeatedly from a loop no longer
  aliases rows sharing a user key and no longer lets one invocation's
  VisibleOnly/PersistentSlots sweep evict a sibling invocation's rows

**External system updates:**
- the Gradle consumer example (`examples/`) and any published-plugin consumers pick up
  the fixes with the next plugin release
