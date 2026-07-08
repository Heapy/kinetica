# Kinetica framework benchmark harness

A local reimplementation of the keyed scenario of
[js-framework-benchmark](https://github.com/krausest/js-framework-benchmark) (krausest) — the
suite historically used to compare React, Preact, Vue and Svelte — extended with **Kinetica**
(this repo's Kotlin/JS UI framework, app in `../samples/browser-bench`). Everything runs in one
environment: one machine, one local static server, one vendored Chromium, one driver.

Beyond the classic 9 operations the suite measures: partial operations on the 10k table,
GC time inside every operation, scaling curves (complexity-class regression detection),
sustained-update frame timing (dbmonster-style), a deep-tree suite (UIBench-style), memory
churn/leak probes, startup script/blocking time, an optional CPU-throttled pass, plus JVM
microbenchmarks (`../bench-jvm`) and a bundle-size baseline check (`../scripts/size-report.mjs`).

This README is written to be self-sufficient context for an agent asked to add a framework,
re-run the suite, or modify the harness. Read it fully before changing anything.

## Quick start

```sh
cd bench
npm install                 # once; installs framework deps + esbuild
node run-all.mjs            # build everything → bench all frameworks → merge → report
open report/index.html      # the local results page
```

Common variants:

```sh
node run-all.mjs --frameworks=kinetica            # re-bench one framework; other results kept
node run-all.mjs --frameworks=solid               # bench a newly added framework
node run-all.mjs --samples=5 --warmup=1           # quick noisy pass (default 10 + 3)
node run-all.mjs --skip-build                     # bundles unchanged, skip builds
node run-all.mjs --report-only                    # only re-merge parts + regenerate the page
node run-all.mjs --tree                           # also run the deep-tree suite
node run-all.mjs --scaling                        # also run scaling curves
node run-all.mjs --throttle=4                     # 4x CPU-throttled pass → results/throttled/
node run-all.mjs --skip-main --tree               # only the tree suite, keep main parts
node driver/bench.mjs --frameworks=react --bench=04 --samples=3    # one op, for debugging
node driver/bench.mjs --frameworks=react --bench=anim              # only the animation bench
node driver/tree.mjs --frameworks=kinetica --samples=3             # tree suite directly
node driver/scaling.mjs --frameworks=kinetica --sizes=1000,5000,20000 --strict
```

A full default run takes ~20–30 minutes (more with `--tree`/`--scaling`). Frameworks are
benchmarked **sequentially, never in parallel** — parallel Chromiums contend for CPU and corrupt
the timings. Don't run anything CPU-heavy on the machine during a run, and don't run two
`run-all`/driver invocations at once.

## Directory map

```
frameworks.config.mjs   ← REGISTRY: every benchmarked framework (order = chart color slot)
run-all.mjs             ← orchestrator: builds → sequential benches → merges → report
build.mjs               ← esbuild bundling for the JS frameworks (has its own `targets` list)
build-kinetica.mjs      ← Kotlin/JS link + esbuild production bundle for Kinetica
frameworks/<name>/      ← table app (main.*) and tree app (tree.* / Tree*.svelte) per framework
frameworks/shared/      ← data.mjs + tree-data.mjs (generators), styles.css (shared page CSS)
driver/common.mjs       ← shared machinery: args, Playwright, trace parsing (incl. GC), stats
driver/bench.mjs        ← main driver: 13 ops + startup + memory churn + animation
driver/tree.mjs         ← deep-tree driver (create/update/reverse/no-op on 1,555 nodes)
driver/scaling.mjs      ← scaling curves: ops at 1k–50k rows, log-log slope fit
driver/server.mjs       ← static file server rooted at the REPO ROOT (port 4573)
driver/merge.mjs        ← merges part files → merged results JSON (generic per-section)
results/part-<name>.json       ← canonical per-framework results (kept between runs)
results/part-*-before.json     ← historical snapshots; excluded from merges; feed the
                                 report's before/after section
results/tree/ scaling/ throttled/  ← per-framework parts for the optional suites, merged to
                                     results/tree.json, scaling.json, throttled.json
results/jvm/results.json       ← output of ../bench-jvm (JVM microbenchmarks)
report/generate.mjs     ← results JSONs → report/index.html (self-contained page)
../bench-jvm/           ← JVM microbenchmarks: reactive core, render pipeline, markdown SSR
../scripts/size-report.mjs     ← bundle sizes vs ../bench/size-baseline.json (CI-enforced)
```

The Kinetica apps live outside this directory: `../samples/browser-bench/` (module
`browser-bench`, built through `build-kinetica.mjs`: publishes the Kinetica compiler plugin
to the toolchain-local repo, runs `../kotlin build -m browser-bench` (which compiles through
that plugin — IR const-props interning + static leaf-host hoisting), then esbuild
bundles/minifies the linked Kotlin/JS graph into
`../build/tasks/_browser-bench_bundle/browser-bench.bundle.mjs`). Table app at
`../samples/browser-bench/web/index.html`, tree app at the same page with `?app=tree`.
`part-kinetica-preplugin-before.json` snapshots the last pre-plugin numbers. Renderer work:
see git history for the retired `perf-rewrite-design.md` and `compiler-perf-design.md`.

The Compose HTML app lives at `../samples/browser-bench-compose/` (module
`browser-bench-compose`, plain Kotlin/JS + JetBrains Compose Multiplatform's DOM library
— `org.jetbrains.compose.html:html-core` — no Kinetica compiler plugin involved), built
through `build-compose.mjs`: runs `../kotlin build -m browser-bench-compose`, then esbuild
bundles/minifies the linked output into
`../build/tasks/_browser-bench-compose_bundle/browser-bench-compose.bundle.mjs`. Table app
at `../samples/browser-bench-compose/web/index.html`, tree app at the same page with
`?app=tree`. It's the one framework here that isn't hand-tuned for this workload — general-
purpose Compose Multiplatform runtime (slot-table composition, snapshot state) rather than a
web-specific fine-grained or vdom renderer — and its numbers reflect that (see Interpreting
results below).

## What is measured

### Main suite (driver/bench.mjs)

Thirteen operations on a keyed table (medians over N samples, after warmup):

| id | operation | measured click | asserted end state |
|---|---|---|---|
| 01_run1k | create 1,000 rows | `run` | 1000 `tbody tr` |
| 02_replace1k | replace all 1,000 | `run` (table full) | first row's `data-id` changed |
| 03_update10th1k | update every 10th label | `update` | first label ends with `" !!!"` |
| 04_select1k | select a row | row label link | that `tr` has class `danger` |
| 05_swap1k | swap rows 2 and 999 | `swaprows` | ids at positions 2/999 exchanged |
| 06_remove1k | remove row 5 | row remove link | 999 rows, row-5 id changed |
| 07_create10k | create 10,000 rows | `runlots` | 10000 rows |
| 08_append1k | append 1,000 to 1,000 | `add` | 2000 rows |
| 09_clear1k | clear 1,000 rows | `clear` | 0 rows |
| 10_select10k | select a row in 10k | row label link | `danger` on that row |
| 11_swap10k | swap rows 2/999 in 10k | `swaprows` | ids exchanged |
| 12_remove10k | remove row 5 from 10k | row remove link | 9999 rows |
| 13_update10th10k | update every 10th of 10k | `update` | first label ends with `" !!!"` |

Ops 10–13 do the same DOM work as their 1k counterparts but with 10× the framework
bookkeeping — accidental O(n²) reconciliation shows there first. State is reset between
samples so every measured click does identical work.

**Timing methodology** (krausest-style): the driver clicks with a trusted Playwright event while
a Chrome trace (`devtools.timeline`) records; duration = first click `EventDispatch` start →
end of the last `Paint`/`Commit` event. Not wall-clock around the click — it includes async
flushes, layout and paint. Headless "Chrome for Testing", no CPU throttling by default.
**GC time** per sample is the summed duration of `MinorGC`/`MajorGC`/`V8.GC*`/`BlinkGC.*`
events inside the same measured window (`gcMedianMs`/`gcMaxMs`/`gcMeanCount` per op).
The post-click settle before `stopTracing()` (`measureTracedClick` in `driver/common.mjs`) is
700ms, and Playwright's default per-action timeout is set to 180s (`openPage`) — both were
widened from tighter defaults (280ms / 30s) when compose-web's heavier GC pressure and (on the
10k ops) synchronous reconciliation cost pushed past them; both changes are global, so they
cost fast frameworks nothing (they already paint and click well inside the old limits) and
don't change any already-recorded duration, since the extra time is dead time after what's
actually measured, not part of it.

Also collected per framework:

- **startup** — navigation → toolbar rendered, 5 untraced cold loads (comparable with historical
  parts); JS bytes raw/gzip from actual loaded resources; plus 2 *traced* cold loads yielding
  `scriptMs` (merged compile+evaluate intervals) and `tbtMs` (long-task time >50ms, approximate).
- **memory** — JSHeapUsedSize after forced GC at six checkpoints: after load, after 1k rows,
  after 5× replace, after 10× create+clear cycles, after `window.__unmount()`, and after five
  `__mount()`/`__unmount()` cycles. The last two are the leak probe: growth between the first
  unmount and the fifth means the app retains memory per mount cycle.
- **animation** (sustained updates, dbmonster-style) — the driver clicks the app's `animate`
  toggle; the app re-labels every 10th row on every `requestAnimationFrame` until toggled off.
  An injected rAF collector records real frame deltas for `--anim-seconds` (default 6, first
  500ms discarded); reported as fps, median/p95 frame time and % of frames >25ms.

`--throttle=N` applies CDP `Emulation.setCPUThrottlingRate` to the op and animation contexts
(startup/memory stay unthrottled). Via run-all, throttled parts land in `results/throttled/`
and render as a separate report section — never mixed with unthrottled numbers.

### Deep-tree suite (driver/tree.mjs, run-all --tree)

A keyed tree of depth 4 / fanout 6 → 1,555 nodes, 1,296 leaves (contract in
`frameworks/shared/tree-data.mjs`, Kotlin mirror in the browser-bench module):

| id | operation | asserted end state |
|---|---|---|
| t1_createTree | build a fresh tree | 1555 `.tree-node`, first `data-id` changed |
| t2_updateLeaves | re-label every 10th leaf (preorder) with `" !<tick>"` | status == tick, first leaf suffixed |
| t3_reverseTop | reverse the root's six subtrees (big keyed moves) | first subtree root id changed |
| t4_noopRender | bump only the status counter; tree data unchanged | status == tick |

`t4_noopRender` isolates pure re-render/reconciliation overhead: vdom frameworks re-render all
1,555 components, fine-grained frameworks should approach zero tree work. Tree apps must not use
user-land memoization (no `React.memo` etc.) — framework-internal reuse is the thing measured.

### Scaling curves (driver/scaling.mjs, run-all --scaling)

Select / swap / update measured at 1k, 2k, 5k, 10k, 20k, 50k rows (sizes reached through the
standard buttons: `run`/`runlots` + repeated `add`; sizes must be multiples of 1,000), then
fitted as duration ∝ n^exponent (log-log least squares). Select/swap should be near-flat
(threshold 0.6), update near-linear (threshold 1.3); exceeding the threshold flags the op
`superlinear` in the JSON and ⚠ in the report. `--strict` exits 1 on any superlinear op.
Update rebuilds the table between samples (labels mutate cumulatively); select/swap don't.

### JVM microbenchmarks (../bench-jvm)

`../kotlin run -m bench-jvm` (flags after `--`: `--warmup`, `--samples`, `--filter`, `--out`).
Measures what the browser suite can't isolate, in minutes instead of half an hour:
reactive-core propagation (10k-wide derived fan, 1k-deep chain, diamond, cached-read staleness
walk), render-pipeline Node construction (create/update a 1k-row table tree without DOM), and
markdown SSR throughput over the real docs pages. Hand-rolled harness (nanoTime, warmup,
medians, volatile blackhole) — coarser than JMH; treat cross-machine numbers as incomparable.
Output: `results/jvm/results.json`. CI runs it as a smoke test only.

### Bundle sizes (../scripts/size-report.mjs)

`node scripts/size-report.mjs` from the repo root reports gzip/raw sizes of Kinetica's linked
JS output and the bench bundles, compares against `bench/size-baseline.json`, and fails (exit 1)
on >10% gzip growth. CI enforces it; intentional growth is accepted with `--update-baseline`
committed in the same PR. `--measure-build` additionally times a clean and an incremental
`browser-bench` build.

## The app contract (fairness rules — follow exactly)

Every implementation must produce the same page so the DOM work being measured is identical:

1. Render into `<div id="main">` from the page-shell template `build.mjs` generates (it includes
   the `window.__mountMs` snippet the driver needs — don't hand-write pages for JS frameworks).
2. Toolbar of seven `<button>`s addressable as `run`, `runlots`, `add`, `update`, `clear`,
   `swaprows`, `animate` — via `id` or `data-testid` (declare which in the config entry).
3. `<table class="test-data"><tbody>` with one `<tr data-id="<id>">` per row and exactly four
   cells: `td.col-id` (id text) · `td.col-label` containing the clickable **select** control with
   the label text · `td.col-remove` containing the clickable **remove** control wrapping
   `<span class="remove-icon">` · empty `td.col-rest`.
4. The select/remove control is `<a>` (or `<button>` only if the framework cannot attach click
   handlers to anchors — Kinetica's case; declare it as `rowControl` in the config).
5. Selected row carries class `danger` (and only the selected one).
6. Rows MUST be keyed by row id (the point of the suite is keyed reconciliation).
7. Data: the standard generator — copy the logic of `frameworks/shared/data.mjs` (adjective +
   colour + noun, `Math.round(Math.random()*1000)%len`, ids from a global counter). JS frameworks
   import it directly; non-JS frameworks (like Kinetica) replicate it in their language.
8. Operations do what the table above says; swap guards `length > 998`; update mutates every
   10th label by appending `" !!!"`. No custom shortcuts (no manual DOM writes from app code in a
   framework implementation — that's what the `vanilla` baseline is for).
9. **animate** toggles a `requestAnimationFrame` loop: each frame, every 10th row's label becomes
   `label.split(" !")[0] + " !" + tick` (`tick` increments per frame — constant work per frame,
   labels don't grow). The mutation must go through normal framework state → render flow
   (vanilla writes text nodes directly, as its baseline role allows). Toggling off cancels the loop.
10. **`window.__mount()` / `window.__unmount()`** hooks: `__unmount` fully unmounts the app
    through the framework's teardown path (leaving `#main` empty), `__mount` mounts a fresh
    instance. Used (unmeasured) by the memory leak probe.
11. Production build, minified, no dev-mode flags. Same shared CSS; no extra hover styles (hover
    repaints would pollute the paint-based timing).

### Tree app contract (for `treeUrl`)

Toolbar buttons `run`, `update`, `reverse`, `noop` plus a status element (`#status` or
`data-testid="status"`) showing the tick counter. Tree data from
`frameworks/shared/tree-data.mjs` (depth 4, fanout 6, preorder ids, labels `"node <id>"`).
DOM: nested `<div class="tree-node" data-id data-depth>`; leaves contain
`<span class="tree-leaf">`, branches `<span class="tree-label">` + children. Children keyed by
node id. `update` and `noop` both increment the shared tick; `update` re-labels every 10th leaf
(preorder) to `label.split(" !")[0] + " !" + tick`; `reverse` reverses the root's children;
`noop` changes only the status text. No user-land memoization on tree nodes.

## Adding a new framework (checklist)

Example: adding Solid.

1. `npm install solid-js` (in `bench/`).
2. Create `frameworks/solid/main.jsx` implementing the app contract (incl. animate + hooks).
   Crib from `frameworks/preact/main.jsx` (hooks style) or `frameworks/svelte/App.svelte`
   (compiled style). Optionally add `frameworks/solid/tree.jsx` for the tree suite.
3. Register the build(s) in `build.mjs` `targets` (entry point, title; add a plugin/jsx config
   only if the framework needs one — see the svelte entries).
4. **Append** an entry to `frameworks.config.mjs` (never reorder existing entries — list position
   is the chart color slot, and colors must stay stable per framework):
   ```js
   { name: "solid", label: "Solid", url: "/bench/dist/solid/index.html",
     treeUrl: "/bench/dist/solid-tree/index.html",
     buttons: "id", rowControl: "a", version: { package: "solid-js" } }
   ```
5. Smoke it: `node run-all.mjs --frameworks=solid --samples=2 --warmup=0 --tree` — checks build,
   selectors and all assertions end-to-end.
6. Real numbers: `node run-all.mjs --frameworks=solid` (other frameworks' existing parts are
   reused in the merged report; re-run everything only when the environment changed).
7. Sanity-check `report/index.html`: the new column appears, values are plausible (a keyed
   framework lands between vanilla ~1× and React ~1.3× on the geomean; if you see 10×+ on
   partial ops, the implementation probably isn't keyed or is in dev mode).

For a framework whose app lives outside `bench/` (like Kinetica, or Compose HTML in
`../samples/browser-bench-compose/`): host the page anywhere under the repo root, include the
`__mountMs` snippet in its HTML (copy from `../samples/browser-bench/web/index.html`), and
declare a `build` command in its config entry.

## Environment assumptions (things that break silently)

- **Vendored browser**: `driver/common.mjs` imports Playwright from `../.tools/playwright/` and
  launches `../.playwright-browsers/chromium-1228/chrome-mac-arm64/...` — macOS-arm64 paths. On
  another machine, adjust both constants there (or set `PLAYWRIGHT_IMPORT` /
  `PLAYWRIGHT_CHROMIUM_EXECUTABLE`).
- **Port 4573** (`BENCH_PORT` to override); the static server serves the whole repo root.
- Numbers are only comparable **within one machine + one Chromium**. After changing either,
  re-run ALL frameworks, and treat `results/part-*-before.json` snapshots as stale history.
- Kinetica's payload/TTI figures use the benchmark's esbuild production bundle over the Kotlin
  Toolchain linked JS output. If you bypass `build-kinetica.mjs` and load
  `_browser-bench_linkJs/browser-bench.mjs` directly, startup will regress to the unminified
  multi-file preview output.
- `results*.json`/`report/index.html` are generated; edit `report/generate.mjs`, not the page.
- Old part files (9 ops, no churn/animation fields) merge cleanly: the report renders missing
  cells as "—". A framework's part gets the new metrics on its next re-bench.

## Interpreting results

- The page's headline is **geometric-mean slowdown** vs the per-operation fastest framework;
  the table shades cells by that factor. Medians everywhere; hover cells/bars for distributions
  (op cells include GC time in the hover).
- Reference points from the 2026-07-07 run set (M4 Max, Chromium 149, 13-op suite; kinetica +
  vanilla re-benched after frame ordinals, stored parts for the rest): Svelte 1.03×, vanilla
  1.04×, Preact 1.12×, Vue 1.24×, React 1.28×, **Kinetica 1.24×** — and **0.969× vs React**
  head-to-head. Kinetica is ahead of React on create-10k (284 ms vs 316 ms), startup
  (24.5 ms vs 27.3 ms), and both swap cases (8.1 ms vs 22.5 ms on 1k; 42.1 ms vs 54.1 ms on
  10k). remove-10k and update-10th-10k still drive the remaining gap.
  Re-run all frameworks before
  comparing headline geomeans across suite changes.
- Kinetica context: 1k partial ops sit at the paint floor via keyed row memoization; the remaining
  browser gap is large-table partial work (ops 10–13), create-op Node construction + GC pressure
  (see the GC section of the report), and the benchmark's Kotlin runtime payload. History, phase
  gates and root-cause analysis: git history of the retired `perf-rewrite-design.md`.
  When touching Kinetica code, run its own tests too
  (`../kotlin test -m kinetica-runtime --platform jvm`,
  `node ../build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs` after
  `../kotlin build -m kinetica-browser`).
- **Compose HTML context** (`html-core` 1.11.1, latest stable at time of writing; 2026-07-07
  run, samples=5/warmup=1 — noisier than the standard 10/3 but the medians are stable):
  single-row ops that Compose can skip via row-level memoization are competitive (select-1k
  10 ms, select-10k 25 ms, update-10th-1k 13 ms) — confirming `RowItem` in
  `browser-bench-compose/src/main.kt` is correctly extracted as its own skippable composable
  (inlining row markup into the list loop instead defeats recomposition scoping entirely and
  is much slower; don't regress that). Full-list ops are markedly heavier than every other
  framework (run-1k 238 ms, create-10k 8.0 s) from Compose Multiplatform's general-purpose
  slot-table/snapshot overhead per composable — expected, it's not a web-specific
  fine-grained or vdom renderer. The striking result is medial reorder/remove on large lists:
  swap-1k 562 ms and remove-1k 522 ms (vs. low tens of ms for every other framework), scaling
  to swap-10k 9.6 s and **remove-10k 49.6 s** — not proportional to the ~997-row affected
  range (constant across the 1k/10k cases per the op contract), which points to Compose's
  default `for`+`key()` reconciliation re-walking/diffing the whole list rather than doing an
  LIS-style minimal-move patch. This is exactly the accidental-O(n²) signal ops 10–13 are
  designed to surface (see "What is measured" above), not an artifact of this implementation
  — there's no lower-level Compose HTML list primitive that would avoid it while staying
  idiomatic. It's also why `measureTracedClick`'s settle and Playwright's action timeout were
  widened (see Timing methodology above): remove-10k's ~50s runs
  synchronously inside the click dispatch, well past the old 30s default.
- **Bench on AC power only.** Below ~10% battery, macOS low-power throttling delays every
  headless-Chrome paint to ~2 vsyncs (~30 ms) — for every framework — and every partial op
  reads as ~30 ms with suspiciously tight stddev. Verify the environment with a quick canary
  (`node driver/bench.mjs --frameworks=react --bench=04 --samples=3`; healthy ≈ 7 ms) before
  trusting a run. The same applies to the animation bench (healthy vanilla ≈ display refresh).
