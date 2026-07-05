Code review — kinetica (whole project, ~7,200 lines)

Method: No git repo, so no diff — you scoped this to the entire project. I ran 11 finder angles (5 correctness clusters + 6 cross-cutting lenses) over every module, deduped, then ran a verifier per candidate (each re-read the file and returned CONFIRMED/PLAUSIBLE/REFUTED), plus a fresh gap sweep over Router/Boundary/Refs that others hadn't reached.

Result: ~40 correctness findings survived verification (36 CONFIRMED, 4 PLAUSIBLE). That's well past the 15-finding cap, so the top 15 are detailed below and the rest are listed compactly so nothing verified is dropped. Cleanup findings (duplication/efficiency) are excluded — correctness outranks them at the cap. No CLAUDE.md governs the Kotlin source, so there are no convention findings.

Everything below was confirmed by a verifier quoting the exact lines.

Top 15 (ranked most-severe first)

1. kinetica-runtime/src/ComponentScope.kt:116 — skippable components silently stop re-rendering.
stateWriteVersion is bumped only by state() cells' onWrite (lines 453/485). Writes to store()/derived/FrameValue never bump it, so a skippable component that reads a hoisted store() cell reuses its cached node and, because it skips the render, drops the cell from renderDependencies (KineticaRuntime.kt:134-145) — after which no future write re-renders it. Core reactivity breaks for a common pattern.

Status: FIXED 2026-07-04. Added failing regression `RuntimeSmokeTest.skippableNodeRerendersWhenHoistedStoreChanges`, fixed skippable caches to capture observable-cell dependency versions and re-record cached dependencies on skipped renders. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (59 tests passed).

2. kinetica-runtime/src/Node.kt:70 — diffNodes never emits prop-only diffs. (found independently by two angles)
The same-class branch (left != right) only recurses into children; it never compares a node's own props/tag/key/semantics. Two HostNodes differing only in a prop, with equal children, produce zero diffs, so hydrationPlan/patch omits the change and the client keeps the stale attribute (e.g. an enabled→disabled button never updates).

Status: FIXED 2026-07-04. Added a `RuntimeSmokeTest.nodeDiffCoversRootLeafAndRecursiveChildChanges` regression for HostNode prop-only changes with identical children, fixed `diffNodes` to replace container nodes when non-child fields differ before recursing into children. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (59 tests passed).

3. kinetica-runtime/src/KineticaRuntime.kt:153 — event ids churn every render (leak + broken client binding).
registerEvent reuses an id only when it.identity === identity, but call sites pass the user's callback lambda (HostDsl.kt:95…). A capturing lambda (onClick = { count++ }) is a fresh instance each render, so === never matches: a new event-N id is minted every render, events/eventIdentities grow unbounded, and the emitted event:onClick id changes each frame — breaking any client handler bound to a stable id. (Non-capturing lambdas compile to singletons and are safe.)

Status: FIXED 2026-07-04. Added failing regression `RuntimeSmokeTest.directHostEventCallbacksKeepStableIdsAcrossRenders`, changed Host DSL event registration to use render-stable event keys and changed `registerEvent` identity reuse to equality. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (60 tests passed).

4. kinetica-runtime/src/Effects.kt:117 — watch never re-fires on its source.
The source lambda is evaluated under a no-op ReadTracking.collect(observer = {}), and by the time runAfterCommit runs the render observer is already popped. Cells read only inside the watch source are never registered as render deps, so writing them never invalidates and the watch never runs again. (Masked only if the same cell is also read in the render body.)

Status: FIXED 2026-07-04. Added failing regression `RuntimeSmokeTest.watchSourceInvalidatesWhenOnlyReadByWatch`, fixed `WatchEffectState` to collect source observable dependencies and keep source subscriptions that invalidate the runtime. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (61 tests passed).

5. kinetica-runtime/src/Cell.kt:162 — derived equality dedup is dead.
The per-dependency callback does dirty = true; listeners.forEach { it() } unconditionally on every upstream write. The EqualityPolicy check (167-169) only gates whether cached is overwritten — it never gates notification. So derived { count > 0 } re-renders on every count change even when the boolean is unchanged.

Status: FIXED 2026-07-04. Added a failing `RuntimeSmokeTest.equalityPoliciesAndDerivedCellsCoverReferentialAndEqualValueEdges` regression asserting unchanged derived output does not invalidate render, updated the older derived-store expectation, and fixed `DerivedCell` to recompute on dependency writes but notify/version-bump only when the equality policy reports a changed derived value. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (61 tests passed).

6. kinetica-runtime/src/Resources.kt:133 & 333 — a cancelled load poisons the resource permanently.
Both loader paths catch (error: Throwable) and store Failed(error) without re-throwing CancellationException. A transient cancellation is cached as a permanent failure; every future read()/await() re-throws it until TTL/invalidation.

Status: FIXED 2026-07-04. Added failing regression `RuntimeSmokeTest.resourceCancellationDoesNotCachePermanentFailure` covering both direct `await()` and render-triggered `read()` loaders, fixed resource cancellation to clear the in-flight cache entry, reset local state to Idle, and leave the resource retryable instead of Failed. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (62 tests passed).

7. kinetica-runtime/src/Resources.kt:150 — stale loader overwrites a fresh result.
generation lives on the entry (var generation = 0, line 41) and resets to 0 when invalidate removes+recreates the entry. The old in-flight loader (gen 1) then satisfies the recreated entry's gen check (typed.generation != generation is false) and writes its stale value over the newer one.

Status: FIXED 2026-07-04. Added failing regression `RuntimeSmokeTest.staleResourceCompletionCannotOverwriteFreshLoadAfterInvalidation`, fixed resource flight generations to be monotonic per cache key across invalidation/recreation so stale completions cannot match a fresh entry. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (63 tests passed).

8. kinetica-runtime/src/Resources.kt:191 — inverted cache-scope eviction.
On last-listener dispose, the only branch that removes from entries is gated on scope == CacheScope.App. So shared App-lifetime entries are evicted when a component unmounts, while Component/Request entries (which should be freed) are never released — leak + wrong lifetime, exactly backwards.

Status: FIXED 2026-07-04. Reworked the cache lifetime regression as `RuntimeSmokeTest.resourceCacheEvictionMatchesScopeLifetime`, covering App survival plus Component/Request eviction after last live resource disposal, and fixed the eviction branch to remove non-App entries only. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (63 tests passed).

9. kinetica-runtime/src/Resources.kt:55 — App-scoped resources bleed across runtime instances.
ResourceRegistry is a process-global object and CacheScope.App resolves to the constant owner "app" (ComponentScope.kt:209). Every KineticaRuntime shares the same namespace in the same global map, so a second app root / SSR request / next test reads another instance's cached value or failure. Cross-instance data bleed and test contamination.

Status: FIXED 2026-07-04. Added failing regression `RuntimeSmokeTest.appResourcesAreIsolatedByRuntimeInstance`, gave each `KineticaRuntime` a stable resource-cache id, and changed App resource namespaces to use the runtime-specific id instead of the process-global `"app"`. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (64 tests passed).

10. kinetica-runtime/src/ServerComponents.kt:317 — every enum-typed server action is rejected.
toJsonValueKind maps SerialKind.ENUM (and Map/class) to JsonValueKind.Object, but enums serialize as JSON strings. validate() then tests the string against Object → false → Failure("field … expected object"), and the handler never runs, for any valid enum input.

Status: FIXED 2026-07-04. Added enum schema assertions and an enum-DTO dispatcher regression in `RuntimeSmokeTest.serverActionSchemasValidateKindsFieldsNullsUnknownsAndSerializers` / `serverActionDispatcherInvokesTypedStubsAndVerifiesCapability`, then mapped `SerialKind.ENUM` to `JsonValueKind.String`. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (64 tests passed).

11. kinetica-runtime/src/Html.kt:79 + kinetica-browser/src@js/BrowserKineticaApp.kt:206 — URL-scheme XSS (server + client).
Neither the server HTML serializer (escapeHtmlAttribute only escapes & < > " ') nor the client applyPublicProps (setAttribute, value unchecked) validates URL schemes. href/src/srcdoc/formaction pass the name-only allowlist, so a data-driven javascript:alert(document.cookie) is emitted/set verbatim and executes on activation.

Status: FIXED 2026-07-04. Added unsafe URL/srcdoc assertions to `RuntimeSmokeTest.safeHtmlEscapesTextAttributesKeysAndClientRefs` and browser mapping coverage in `BrowserMappingTest.filtersUnsafeBrowserAttributeValues`, added shared runtime HTML attribute value sanitization, and reused it from browser prop filtering. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (64 tests passed), `./kotlin build -m kinetica-browser`, and `node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs` (exit 0).

12. kinetica-browser/src@js/BrowserKineticaApp.kt:318 — toggling a checkbox crashes the UI.
BrowserFocus.capture() reads input?.selectionStart guarded only by the as? HTMLInputElement cast. A focused <input type=checkbox> satisfies the cast but throws InvalidStateError on the selection getter; the exception propagates out of the change handler and aborts the whole re-render.

Status: FIXED 2026-07-04. Added `BrowserMappingTest.detectsInputTypesWithReadableTextSelection`, introduced `browserInputTypeSupportsTextSelection`, and guarded `BrowserFocus.capture()` so checkbox/radio/button inputs do not read `selectionStart`/`selectionEnd`. Verified with `./kotlin build -m kinetica-browser` and `node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs` (exit 0).

13. kinetica-compiler/src/KineticaGeneratedSourceEmitter.kt:300 — generated code fails to compile on $ (and generics, and prop types).
kotlinStringLiteral escapes \ " \n \r \t but not $, so @Preview("Total $sum") / @ServerAction(invalidates=["user_$id"]) emit live Kotlin templates → "unresolved reference". Two sibling codegen bugs are equally build-breaking: naive split(',') truncates generic param types like Map<String, Int> (KineticaSourceModel.kt:335), and serializablePropsType emits a bare, unqualified Fn.Props across packages (KineticaSourceModel.kt:307). Existing tests use only comma-free ()->Unit and pre-qualified names, so none catch these.

Status: FIXED 2026-07-04. Added compiler regressions for `$` in generated preview/action literals, `Map<String, Int>` component parameters, and qualified nested `app.AddTodoButton.Props`; fixed both Kotlin string literal helpers to escape `$`, replaced naive parameter splitting with top-level comma splitting, and qualified generated nested Props types with the package. Verified with `./kotlin test -m kinetica-compiler --platform jvm` (32 tests passed).

14. kinetica-forms/src/Forms.kt:20 — a never-validated form reports valid.
isValid = fields.all { it.error == null }, but error defaults to null and is set only by validate(). A form with a required/minLength field the user never touched passes if (form.isValid) submit(), letting empty/invalid data through.

Status: FIXED 2026-07-04. Added an untouched-field validity regression to `FormsSmokeTest.textInputEchoesFieldStateAndSuspendValidationControlsSubmission`, tracked field validation completion, and made `FormState.isValid` require each field to have successfully validated. Verified with `./kotlin test -m kinetica-forms --platform jvm` (3 tests passed).

15. kinetica-runtime/src/Boundary.kt:41 — errorBoundary turns "still loading" into a permanent error.
The bare catch (Throwable) also catches the ResourcePendingException control-flow signal (and CancellationException). A resource read inside an errorBoundary without an intervening loadingBoundary is captured via state.capture as a permanent error, so the fallback shows forever instead of resolving when the load completes.

Status: FIXED 2026-07-04. Added failing regression `RuntimeSmokeTest.errorBoundaryRethrowsPendingResourcesToNearestLoadingBoundary`, then made `errorBoundary` rethrow `ResourcePendingException` and `CancellationException` before capturing ordinary failures. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (66 tests passed).

Additional CONFIRMED findings (beyond the cap)

┌────────────────────────┬──────────────────────────────────────┬────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│           #            │               Location               │                                                                      Bug                                                                       │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C8                     │ ComponentScope.kt:361                │ writeSlot stores a hookless MutableCellImpl under the same key state(slotId) uses → getOrPut reuses it, onWrite never attaches → restored      │
│                        │                                      │ state never bumps stateWriteVersion (feeds #1)                                                                                                 │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C9                     │ ComponentScope.kt:341                │ Slot API uses bare stableKey; state(slotId) inside keyed{}/each{} uses keyScopePrefix/… → keyed-scope persistent state invisible to            │
│                        │                                      │ save/restore (silently dropped)                                                                                                                │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C38                    │ KineticaSourceModel.kt:836           │ injectSlotIds regex matches state(/state{ but not persistentState → persistent slots get no compiler SlotId → not persisted at runtime         │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C36                    │ KineticaSourceModel.kt:300           │ Static text() hoisting captures raw pre-unescape source then re-escapes → emits literal \n instead of a newline                                │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ S2                     │ Boundary.kt:38                       │ errorBoundary runs content() into the ambient frame (no collect) → partial emits + fallback both render (torn subtree [Header, Error])         │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ S3                     │ Router.kt:114                        │ NavHost disposes entry key-scopes from a post-commit effect with an unwound key-scope stack → prefix omits ancestor segments → retained-entry  │
│                        │                                      │ cleanup silently fails when NavHost is nested in keyed{} (leak + stale state)                                                                  │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ S4                     │ Refs.kt:26                           │ imperativeHandle getOrPut-memoizes Ref(factory()) → factory runs only first render → stale handle when it closes over changed state            │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C20                    │ Boundary.kt:273                      │ exitGroup pendingCallbacks is a plain Int decremented from multiple onExit coroutines on Dispatchers.Default (only tasks is locked) → lost     │
│                        │                                      │ decrement (element retained forever) or early completion (disposed mid-animation)                                                              │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C21                    │ KineticaRuntime.kt:319               │ RuntimeTaskHandle.markIdle non-atomic check-then-act, called from both invokeOnCompletion and awaitDispose → premature activeTasks==0 →        │
│                        │                                      │ awaitIdle() returns early → SSR/test serializes an incomplete tree                                                                             │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C15                    │ Resources.kt:241                     │ currentState plain non-@Volatile var written on Default, read on render thread → stale Loading read → fallback shown forever                   │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C17                    │ ServerComponents.kt:465              │ Deferred-subtree render catch(Throwable) without re-throwing CancellationException → cancelled render becomes a spurious BoundaryError chunk   │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C19                    │ Data.kt:234                          │ OfflineCache.load swallows CancellationException, returns stale cache (unlike retry/optimisticAction in the same file)                         │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C24                    │ Persist.kt:92                        │ saveSlot readSlot ?: return → a value cleared to null never overwrites/removes the persisted value → stale value wrongly restored              │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C26                    │ Boundary.kt:339                      │ FrameValue.snapTo uses exact ==; AnimatedFloat gates running on value != target → a NaN target makes the spring loop never settle (runs        │
│                        │                                      │ forever)                                                                                                                                       │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C25                    │ KineticaTest.kt:273                  │ dispatch: if (render().invalidated) render() else render() renders content twice per event; the branch is a no-op → doubled effects/journal    │
│                        │                                      │ entries in tests                                                                                                                               │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C22-related            │ BrowserKineticaApp.kt:39/306/290/113 │ async awaitIdle re-render drops focus; focus restored by positional path (wrong element after list change); hasElementAttributes omits         │
│ C33/C32/C31/C30        │                                      │ label/role (aria dropped); column/row skip applyPublicProps (id/title/data-* dropped)                                                          │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C39                    │ samples/browser-todo/src/main.kt:44  │ Todo id "todo-${size+1}" collides after a removal → duplicate each key → error("Duplicate key") crashes render under the default debug=true    │
├────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ C18                    │ ServerComponents.kt:357/227          │ Unknown actionId and raw exception message echoed to the client (info disclosure; reflected-XSS only if the host renders it unescaped)         │
└────────────────────────┴──────────────────────────────────────┴────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘

Additional status updates:

- C8/C9 Status: FIXED 2026-07-04. Added regressions to `RuntimeSmokeTest.manualSlotApisKeyScopedHelpersAndTransientDisposalAreExplicit` for restored manual slots reusing `state(slotId)` and keyed persistent slots round-tripping through `readSlot`/`writeSlot`. Fixed manual slot writes to install the same invalidating cell behavior as normal state slots and added scoped-key lookup/migration so bare restored slot ids can bind to `keyed { state(slotId = ...) }`. Verified with focused slot tests and `./kotlin test -m kinetica-runtime --platform jvm` (66 tests passed).
- C38 Status: FIXED 2026-07-04. Added a compiler source-transform regression for `var saved by persistentState { ... }`, then taught slot-id injection to recognize `persistentState`, preserve that call, inject the generated `SlotId`, and supply `restoredValue = null` for shorthand calls. Verified with the focused transformer test and `./kotlin test -m kinetica-compiler --platform jvm` (33 tests passed).
- C36 Status: FIXED 2026-07-04. Added an escaped-source static-text regression for `text("...\\n...")`, decoded Kotlin string escapes before generating hoisted `TextNode` source, and aligned static hoist template detection with unescaped `$` markers. Verified with the focused extractor test and `./kotlin test -m kinetica-compiler --platform jvm` (33 tests passed).
- S2 Status: FIXED 2026-07-04. Added `RuntimeSmokeTest.errorBoundaryDiscardsPartialContentBeforeRenderingFallback`, fixed `errorBoundary` to collect content in an isolated frame and emit it only after successful completion, and made `withErrorBoundary` return collected values. Verified with boundary-focused tests and `./kotlin test -m kinetica-runtime --platform jvm` (67 tests passed).
- S3 Status: FIXED 2026-07-04. Added `RouterSmokeTest.navHostDisposesDroppedEntryStateWhenNestedInKeyScope`, moved stale NavHost entry disposal out of the unwound `layoutEffect` and into render-time execution while ancestor key scopes are still active, leaving retained-stack bookkeeping post-commit. Verified with focused retention tests and `./kotlin test -m kinetica-router --platform jvm` (10 tests passed).
- S4 Status: FIXED 2026-07-04. Added `RuntimeSmokeTest.imperativeHandleRefreshesCurrentValueAcrossRenders`, then changed `imperativeHandle` to preserve the `Ref` slot while refreshing `current` from the factory on every render. Verified with ref-focused tests and `./kotlin test -m kinetica-runtime --platform jvm` (68 tests passed).
- C20 Status: FIXED 2026-07-04. Added `RuntimeSmokeTest.exitGroupCompletesConcurrentOnExitCallbacksAtomically`, reproduced the lost-decrement race with concurrent completions, and synchronized exit completion state transitions around `pendingCallbacks`, phase, and retained subtree cleanup. Verified with exit-group focused tests and `./kotlin test -m kinetica-runtime --platform jvm` (69 tests passed).
- C21 Status: FIXED 2026-07-04. Added `RuntimeSmokeTest.runtimeTaskHandleMarksIdleAtMostOnceUnderConcurrentCalls`, reproduced duplicate idle marking under concurrent calls, and changed `RuntimeTaskHandle` to use an atomic active-to-idle compare-and-set. Verified with active-task focused tests and `./kotlin test -m kinetica-runtime --platform jvm` (70 tests passed).
- C15 Status: FIXED 2026-07-04. Tightened `RuntimeSmokeTest.resourceReadReportsLoadingReadyFailedAndInvalidatedStates` to assert background loads publish `ResourceState.Ready` before a `read()` refresh, then changed `ResourceImpl.currentState` to an atomic reference so loader-thread updates are visible to render-thread readers. Verified with resource-focused tests and `./kotlin test -m kinetica-runtime --platform jvm` (70 tests passed).
- C17 Status: FIXED 2026-07-04. Added `RuntimeSmokeTest.serverRenderStreamRethrowsDeferredSubtreeCancellation`, fixed deferred server-subtree streaming to rethrow `CancellationException` and close the result channel with the cancellation cause so the receiver does not hang. Verified with deferred-stream focused tests and `./kotlin test -m kinetica-runtime --platform jvm` (72 tests passed).
- C19 Status: FIXED 2026-07-04. Added a cancellation assertion to `DataSmokeTest.offlineCacheSupportsCacheFirstAndStaleNetworkFallback`, then changed `OfflineCache.load` to rethrow `CancellationException` before falling back to stale cache. Verified with the focused offline-cache test and `./kotlin test -m kinetica-data --platform jvm` (10 tests passed).
- C24 Status: FIXED 2026-07-04. Added a persist regression for a present null slot clearing a stale backend value, then changed `saveSlot` to distinguish missing slots from present-null slots via `containsSlot` and call `backend.remove` for the latter. Verified with the focused persist test and `./kotlin test -m kinetica-persist --platform jvm` (4 tests passed).
- C26 Status: FIXED 2026-07-04. Added `MotionSmokeTest.springAnimationSettlesNanTargets`, made `FrameValue.snapTo` and `AnimatedFloat` running/settling checks treat NaN-to-NaN as equal, and stopped spring/tween animations once a NaN target is reached. Verified with `./kotlin test -m kinetica-motion --platform jvm` (10 tests passed) and `./kotlin test -m kinetica-runtime --platform jvm` (72 tests passed).
- C25 Status: FIXED 2026-07-04. Added `KineticaTestSmokeTest.headlessRootDispatchRendersOnlyOncePerHandledEvent`, confirmed the sync headless harness rendered content twice after dispatch, then changed `HeadlessTestRoot.dispatch` to match the suspend harness and render only once. Verified with `./kotlin test -m kinetica-test --platform jvm` (16 tests passed).
- C22-related/C33/C32/C31/C30 Status: FIXED 2026-07-04. Extended `samples/browser-tests` with browser-executed regressions for layout HostNode public props, semantic text role/label rendering, stable focus after keyed input reorder, and `awaitIdle` focus restoration after async invalidation. Fixed the browser renderer to apply public props to `column`/`row`, create text wrappers when role/label semantics require attributes, restore focus by stable `data-testid`/host key before falling back to positional path, and preserve focus during `awaitIdle` rerenders. Verified with `./kotlin build -m kinetica-browser`, `./kotlin build -m browser-tests`, `./kotlin build -m browser-counter`, `./kotlin build -m browser-todo`, `node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs` (exit 0), and `node scripts/verify-browser.mjs` via local Playwright (`Browser verification passed`).
- C39 Status: FIXED 2026-07-04. Added a browser verifier path that removes an existing todo and then adds new todos, reproducing the size-based duplicate key path before the fix. Replaced `todo-${size + 1}` with monotonic `nextTodoId` state in `samples/browser-todo`. Verified with `./kotlin build -m browser-todo` and `node scripts/verify-browser.mjs` via local Playwright (`Browser verification passed`).
- C18 Status: FIXED 2026-07-04. Tightened `RuntimeSmokeTest.serverActionDispatcherInvokesTypedStubsAndVerifiesCapability` so server action handler exceptions with sensitive messages still return a generic failure and unknown action ids are not echoed. Fixed typed server action stubs to return `Server action failed.` for ordinary exceptions and changed dispatcher unknown-action failures to `Unknown server action.`. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (74 tests passed).
- C2 Status: FIXED 2026-07-04. Added `RuntimeInternalJvmTest.derivedCellRecomputesAfterLastObserverClearsSubscriptions`, reproducing a stale cached read after the last observer disposed a `DerivedCell`'s dependency subscriptions. Fixed `DerivedCell.clearSubscriptions` to mark the derived value dirty so the next read recomputes and resubscribes. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (79 tests passed).
- C4/C5 Status: FIXED 2026-07-04. Added `RuntimeInternalJvmTest.readTrackingKeepsConcurrentCollectorsIsolatedByThread`, deterministically reproducing dependency recording into the wrong concurrent collector with the old process-global `ReadTracking.stack`, and moved the C2 internal regression to `RuntimeInternalJvmTest.derivedCellRecomputesAfterLastObserverClearsSubscriptions`. Replaced the shared stack with platform-local `ReadTrackingLocal` actuals (JVM/Android `ThreadLocal`, single-thread JS/Wasm stacks, macOS thread-local storage), serialized `DerivedCell` listener/subscription/cache state with a lock, and made the stale-resource test wait explicitly for async loader starts instead of relying on scheduler timing. Verified with `./kotlin test -m kinetica-runtime --platform jvm` (79 tests passed) and `./kotlin build -m kinetica-runtime` (all configured targets built).
- S5 Status: FIXED 2026-07-04. Added `RouterSmokeTest.hostBackDispatcherSnapshotsHandlersDuringDispatch`, reproducing a `ConcurrentModificationException` when a back handler registers another handler while `dispatchBack` iterates the reversed handler list. Added a router atomicfu dependency, synchronized handler registration/removal, and made dispatch iterate a snapshot so mutations affect subsequent dispatches only. Verified with `./kotlin test -m kinetica-router --platform jvm` (12 tests passed) and `./kotlin build -m kinetica-router` (all configured targets built).

PLAUSIBLE findings (now reproduced and fixed above)

- Cell.kt:174 (C2) — DerivedCell.clearSubscriptions leaves dirty=false; a reused instance would return a stale value and never re-subscribe. Status: fixed above.
- Cell.kt:133 (C4) / Cell.kt:36 (C5) — DerivedCell's plain collections and the process-global ReadTracking.stack are unsynchronized, while effects run on Dispatchers.Default and notifications fire on the writing thread. Status: fixed above.
- Router.kt:268 (S5) — dispatchBack iterates an unsynchronized list; a handler that directly registers/unregisters in onBack, or cross-thread access, would CME. Status: fixed above.

Themes / where to fix at depth

- Cancellation hygiene is the single most repeated bug (C12, C17, C19, S1): four separate catch (Throwable) sites swallow CancellationException/ResourcePendingException. A shared catch helper that always re-throws those two would fix all four at once.
- URL-scheme sanitization is missing in both render paths (C27/C28) and the safety predicates are duplicated between runtime and browser (a cleanup finder flagged BrowserMapping.kt copies Html.kt's allowlist verbatim) — so the fix belongs in one shared predicate, not two.
- The stateWriteVersion skip mechanism (C7/C8) is too coarse; the render-subscription machinery already tracks exactly which cells each component read, so skip should key off that, not a single global counter.
- Threading model: several bugs (C4, C5, C15, C20, C21) trace to the same unresolved question — the spec says writes are serialized on one UI loop, but the runtime notifies synchronously on Dispatchers.Default. Deciding and enforcing that model resolves the whole cluster.

I did not modify any code. Want me to (a) write fixes for a chosen subset (the cancellation-hygiene cluster and the two XSS paths are the cleanest high-impact starting points), (b) produce the separately-verified cleanup/duplication list, or (c) render this as a shareable HTML report?
