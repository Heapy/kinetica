# Kinetica plan — KNT tickets

Renderer-perf and spec backlog. The K5/K6 review-fix stream (KNT-0001–0022 plus the
review-surfaced KNT-0032) fully landed 2026-07-07 via the codex-TDD pipeline, squashed into
`39692ea` (per-ticket commits preserved on `frame-ordinals-pre-rebase-backup`); the ticket
bodies, statuses, review-round history and verification evidence live in this file's git
history. Ticket numbering continues from KNT-0033.

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

## Open backlog

### KNT-0024 (was perf §2) — 10k-table partial operations
- The last remaining bench gap, narrowed by frame ordinals: remove-10k 59.8ms (1.48× React) and update-every-10th-10k 43.3ms (1.26×) are still above React, while select-10k (8.7ms, 1.09×) and swap-10k (42.1ms, 0.78×) have effectively closed. Partial ops on 10k rows pay the O(rows) reference-walk diff and LIS over large child arrays even when 2 rows changed. Candidates: dirty-row short lists from cell subscriptions instead of full-tree walks, LIS skip when moves are adjacent transpositions.

### KNT-0025 (was perf §3) — Unify the two diffs; serializable patch ops; journal `DomPatch`
- Deferred from P1: the browser patcher is a recursive diff-and-apply in `kinetica-browser` (`BrowserKineticaApp.kt` + `ListReconcile.kt`), while server components still use the old positional `diffNodes` (`Node.kt`). The design wants one keyed reconciler emitting a serializable op list used by both, with debug-mode `JournalKind.DomPatch` entries completing the causal chain (event → cell writes → render → concrete DOM ops). This is a debuggability feature as much as a perf one — the project's stated priority order.
- Related: KNT-0004 (landed) normalizes TemplateNodes via `materializeDeep` at the `diffNodes` entry and every wire boundary — the unified reconciler must keep that boundary.

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

### KNT-0031 (was perf §9) — Memory gate not met
- Heap after 1k rows: **5.7MB** against the ≤5MB gate (React 4.4MB, vanilla 1.9MB). Close but above; the shadow tree and row caches are the suspects. First step: re-measure now that frame ordinals landed (the key strings were also heap); attack only if a real gap remains.

## Order of work

1. The backlog is unscheduled; KNT-0031 starts with a re-measure, KNT-0028 starts with the spec decision.
2. The compiler plugin is MANDATORY for every module: any pass touching it starts with `./kotlin publish mavenLocal -m kinetica-compiler && ./kotlin test -m kinetica-compiler --platform jvm` before building dependents — publish FIRST: the plugin resolves from the toolchain-local repo and even the compiler's own test fragment (via kinetica-runtime) needs the published artifact (mirrors ci.yml:26-30).
3. Each pass: build + module tests before moving on.

## Verification

- Full sweeps: `./kotlin test -m kinetica-runtime --platform jvm`, `./kotlin test -m kinetica-compiler --platform jvm`, kinetica-browser build + `node build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs`, both annotated samples (JVM + JS).
- Perf-backlog changes (KNT-0024+): `./kotlin build -m browser-bench && cd bench && node run-all.mjs --frameworks=kinetica`, then `node scripts/verify-browser.mjs` from the repo root — the script lives in root `scripts/`, not `bench/` (15 self-tests must stay green). Locally it needs what ci.yml:99-103 provides: a static server first (`node -e 'import("./bench/driver/server.mjs").then(m => m.startServer(process.cwd(), 4173))' &`) and `PLAYWRIGHT_IMPORT=.tools/playwright/node_modules/playwright/index.mjs` (the script, unlike bench/driver/common.mjs, has no vendored-playwright fallback), plus built browser-tests/counter/todo samples.
- Size check: `node scripts/size-report.mjs` within baseline tolerance.
