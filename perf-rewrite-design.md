# Kinetica renderer performance — open items

Status: 2026-07-06 · P0–P3 landed (registry eviction, retained renderer with keyed LIS diff +
event delegation, each-row memoization, allocation hygiene, benchmark packaging). Geomean
15.2× → **1.27×** vs fastest per op (React 1.29×, Vue 1.25×, Preact 1.13×, Svelte 1.04×,
vanilla 1.05×). The full design, root-cause analysis and phase history live in this file's git
history (up to commit `7cfde69`); what follows is only what's still worth exploring or fixing.

Verify any change with: `./kotlin build -m browser-bench && cd bench && node run-all.mjs
--frameworks=kinetica`, then `node scripts/verify-browser.mjs` (15 self-tests must stay green).

## 1. Intern slot/event key strings per scope — now urgent

Every slot/event call builds `"$prefix/$local"` strings per render, and the 2026-07-06
slot-collision hardening added kind suffixes (`slot-N:state`, `effect-N:watch`, …) on top:
measured cost **+6–8% on the 10k-row ops** (min-based, not noise), geomean 1.20× → 1.27×,
startup mount 22.6 → 25.6ms. The suffix is folded only inside the key factories
(`ComponentScope.nextSlotKey/nextEventKey/nextEffectKey`), deliberately so an interner can key
on `(kind, cursor)` per key-scope and eliminate both the suffix cost and the pre-existing
string building in one move. Steady-state renders should allocate zero key strings.

## 2. 10k-table partial operations

The last remaining bench gap, already named the next target before the hardening regression:
select-10k 9.6ms, swap-10k 44.6ms, remove-10k 66.6ms, update-every-10th-10k 51.0ms. The scaling
suite still flags swap at **n^0.84** against the near-flat 0.6 threshold. Partial ops on 10k
rows pay the O(rows) reference-walk diff, LIS over large child arrays, and the row-cache sweep
even when 2 rows changed. Re-profile after item 1; candidates: dirty-row short lists from cell
subscriptions instead of full-tree walks, LIS skip when moves are adjacent transpositions.

## 3. Unify the two diffs; serializable patch ops; journal `DomPatch`

Deferred from P1: the browser patcher is a recursive diff-and-apply in `kinetica-browser`
(`BrowserKineticaApp.kt` + `ListReconcile.kt`), while server components still use the old
positional `diffNodes` (`Node.kt`). The design wants one keyed reconciler emitting a
serializable op list used by both, with debug-mode `JournalKind.DomPatch` entries completing
the causal chain (event → cell writes → render → concrete DOM ops). This is a debuggability
feature as much as a perf one — the project's stated priority order.

## 4. Component-scoped re-rendering

Invalidation is still root-per-runtime: one dispatch → one full render (cheap now thanks to
memoization, but O(app) not O(component)). The per-cell `renderDependencies` machinery already
exists to scope re-renders to the components that read a written cell. Follow-up, not urgent
while memoized full renders stay ~single-digit ms on 1k-row apps; revisit if real apps show
render-cost growth with app size.

## 5. Toolchain DCE / release mode (upstream)

The benchmark and docs pages run from post-link esbuild bundles, but library consumers of the
`js/app` preview product still get the unminified multi-file ES-module graph (~1.6MB raw) with
no DCE. Track Kotlin Toolchain release-mode/DCE support; revisit packaging when `js/app`
leaves preview.

## 6. exitGroup/motion contract with the retained patcher

Spec keeps exiting subtrees alive (`leaving` semantics); the patcher must treat them as
"retain + mark", never remove. The rebuild renderer sidestepped this; the retained one has no
decided contract yet. Open spec question — needs a decision plus browser tests for exit
transitions under patching (motion battery interplay).

## 7. lazyEach/virtualization vs keyed moves

Windowed rendering makes keys enter/leave constantly at window edges; the diff should handle
it as edge inserts/removes, but this was never verified with a lazyEach bench case. Add one
before relying on virtualization.

## 8. Controlled inputs: IME

Property-based writes, skip-if-equal value sync, and typing-during-invalidation tests landed
with P1. IME composition (the classic retained-mode input bug) remains untested — add a
browser-tests case with composition events.

## 9. Memory gate not met

Heap after 1k rows: **5.7MB** against the ≤5MB gate (React 4.4MB, vanilla 1.9MB). Close but
above; the shadow tree and row caches are the suspects. Re-check after item 1 (key strings are
also heap) and attack only if a real gap remains.
