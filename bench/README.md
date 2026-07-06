# Kinetica framework benchmark harness

A local reimplementation of the keyed scenario of
[js-framework-benchmark](https://github.com/krausest/js-framework-benchmark) (krausest) — the
suite historically used to compare React, Preact, Vue and Svelte — extended with **Kinetica**
(this repo's Kotlin/JS UI framework, app in `../samples/browser-bench`). Everything runs in one
environment: one machine, one local static server, one vendored Chromium, one driver.

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
node driver/bench.mjs --frameworks=react --bench=04 --samples=3   # one op, for debugging
```

A full default run takes ~20–30 minutes. Frameworks are benchmarked **sequentially, never in
parallel** — parallel Chromiums contend for CPU and corrupt the timings. Don't run anything
CPU-heavy on the machine during a run, and don't run two `run-all`/`bench.mjs` at once.

## Directory map

```
frameworks.config.mjs   ← REGISTRY: every benchmarked framework (order = chart color slot)
run-all.mjs             ← orchestrator: builds → sequential benches → merge → report
build.mjs               ← esbuild bundling for the JS frameworks (has its own `targets` list)
build-kinetica.mjs      ← Kotlin/JS link + esbuild production bundle for Kinetica
frameworks/<name>/      ← one app implementation per JS framework
frameworks/shared/      ← data.mjs (label generator), styles.css (shared page CSS)
driver/bench.mjs        ← the measuring driver (Playwright + Chrome tracing)
driver/server.mjs       ← static file server rooted at the REPO ROOT (port 4573)
driver/merge.mjs        ← merges results/part-*.json → results/results.json
results/part-<name>.json      ← canonical per-framework results (kept between runs)
results/part-*-before.json    ← historical snapshots; excluded from merges; feed the
                                report's before/after section
report/generate.mjs     ← results.json → report/index.html (self-contained page)
```

The Kinetica app lives outside this directory: `../samples/browser-bench/` (module
`browser-bench`, built through `build-kinetica.mjs`, page at
`../samples/browser-bench/web/index.html`). The script first runs `../kotlin build -m
browser-bench`, then bundles/minifies the linked Kotlin/JS graph into
`../build/tasks/_browser-bench_bundle/browser-bench.bundle.mjs`. Renderer performance work is
planned in `../perf-rewrite-design.md`.

## What is measured

Nine operations on a keyed table (medians over N samples, after warmup):

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

State is reset between samples so every measured click does identical work.

**Timing methodology** (krausest-style): the driver clicks with a trusted Playwright event while
a Chrome trace (`devtools.timeline`) records; duration = first click `EventDispatch` start →
end of the last `Paint`/`Commit` event. Not wall-clock around the click — it includes async
flushes, layout and paint. Headless "Chrome for Testing", no CPU throttling.

Also collected per framework: **startup** (navigation → toolbar rendered, 5 cold loads; JS bytes
raw/gzip from actual loaded resources), **memory** (JSHeapUsedSize after forced GC, after load
and after creating 1k rows).

## The app contract (fairness rules — follow exactly)

Every implementation must produce the same page so the DOM work being measured is identical:

1. Render into `<div id="main">` from the page-shell template `build.mjs` generates (it includes
   the `window.__mountMs` snippet the driver needs — don't hand-write pages for JS frameworks).
2. Toolbar of six `<button>`s addressable as `run`, `runlots`, `add`, `update`, `clear`,
   `swaprows` — via `id` or `data-testid` (declare which in the config entry).
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
9. Production build, minified, no dev-mode flags. Same shared CSS; no extra hover styles (hover
   repaints would pollute the paint-based timing).

## Adding a new framework (checklist)

Example: adding Solid.

1. `npm install solid-js` (in `bench/`).
2. Create `frameworks/solid/main.jsx` implementing the app contract. Crib from
   `frameworks/preact/main.jsx` (hooks style) or `frameworks/svelte/App.svelte` (compiled style).
3. Register the build in `build.mjs` `targets` (entry point, title; add a plugin/jsx config only
   if the framework needs one — see the svelte entry).
4. **Append** an entry to `frameworks.config.mjs` (never reorder existing entries — list position
   is the chart color slot, and colors must stay stable per framework):
   ```js
   { name: "solid", label: "Solid", url: "/bench/dist/solid/index.html",
     buttons: "id", rowControl: "a", version: { package: "solid-js" } }
   ```
5. Smoke it: `node run-all.mjs --frameworks=solid --samples=2 --warmup=0` — checks build,
   selectors and all nine assertions end-to-end.
6. Real numbers: `node run-all.mjs --frameworks=solid` (other frameworks' existing parts are
   reused in the merged report; re-run everything only when the environment changed).
7. Sanity-check `report/index.html`: the new column appears, values are plausible (a keyed
   framework lands between vanilla ~1× and React ~1.3× on the geomean; if you see 10×+ on
   partial ops, the implementation probably isn't keyed or is in dev mode).

For a framework whose app lives outside `bench/` (like Kinetica): host the page anywhere under
the repo root, include the `__mountMs` snippet in its HTML (copy from
`../samples/browser-bench/web/index.html`), and declare a `build` command in its config entry.

## Environment assumptions (things that break silently)

- **Vendored browser**: `driver/bench.mjs` imports Playwright from `../.tools/playwright/` and
  launches `../.playwright-browsers/chromium-1228/chrome-mac-arm64/...` — macOS-arm64 paths. On
  another machine, adjust both constants at the top of `driver/bench.mjs`.
- **Port 4573** (`BENCH_PORT` to override); the static server serves the whole repo root.
- Numbers are only comparable **within one machine + one Chromium**. After changing either,
  re-run ALL frameworks, and treat `results/part-*-before.json` snapshots as stale history.
- Kinetica's payload/TTI figures use the benchmark's esbuild production bundle over the Kotlin
  Toolchain linked JS output. If you bypass `build-kinetica.mjs` and load
  `_browser-bench_linkJs/browser-bench.mjs` directly, startup will regress to the unminified
  multi-file preview output.
- `results.json`/`report/index.html` are generated; edit `report/generate.mjs`, not the page.

## Interpreting results

- The page's headline is **geometric-mean slowdown** vs the per-operation fastest framework;
  the table shades cells by that factor. Medians everywhere; hover cells/bars for distributions.
- Reference points from the 2026-07-06 full run (M4 Max, Chromium 149): vanilla 1.02×,
  Svelte 1.05×, Preact/Vue 1.09×, **Kinetica 1.25×**, React 1.29× — Kinetica is ahead of React
  after the P0–P2 renderer rewrite, allocation hygiene, browser fast-paths and benchmark
  bundling (15.2× before the rewrite; the pre-rewrite snapshot is kept in
  `part-kinetica-before.json` and shown as before/after on the page).
- Kinetica context: partial ops sit at the paint floor via keyed row memoization; the remaining
  gap is create-op Node construction and the benchmark's Kotlin runtime payload. History, phase
  gates and root-cause analysis: `../perf-rewrite-design.md`. When touching Kinetica code, run
  its own tests too (`../kotlin test -m kinetica-runtime --platform jvm`,
  `node ../build/tasks/_kinetica-browser_linkJsTest/kinetica-browser_test.mjs` after
  `../kotlin build -m kinetica-browser`).
- **Bench on AC power only.** Below ~10% battery, macOS low-power throttling delays every
  headless-Chrome paint to ~2 vsyncs (~30 ms) — for every framework — and every partial op
  reads as ~30 ms with suspiciously tight stddev. Verify the environment with a quick canary
  (`node driver/bench.mjs --frameworks=react --bench=04 --samples=3`; healthy ≈ 7 ms) before
  trusting a run.
