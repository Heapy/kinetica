# Performance

<!-- code: bench/results/results.json, bench/driver/bench.mjs, bench/README.md -->

Kinetica is benchmarked continuously against React, Preact, Vue, Svelte and a vanilla-JS
baseline using a local reimplementation of the **js-framework-benchmark** (krausest) keyed
suite: identical table apps, one machine, one Chromium, one Playwright/Chrome-trace driver.
Duration = trusted click → end of last paint.

## Where Kinetica stands (2026-07-07, M4 Max, Chrome 150)

<!-- code: bench/results/results.json, bench/results/part-kinetica.json -->

Median milliseconds; geometric-mean slowdown vs the per-operation fastest framework. React,
Preact, Vue, Svelte and Vanilla were freshly re-measured this pass against Google Chrome
150.0.7871.47 (previously measured against the vendored Playwright "Chrome for Testing" build,
Chromium 149.0.7827.55); the Kinetica and Compose HTML columns are retained from the prior
Chromium 149 snapshot — Kinetica because an in-progress branch carrying unreviewed
runtime/compiler memory optimizations is currently ahead of it and will get its own
re-measurement once that work lands, Compose HTML because it isn't part of that rewrite at all
(see below).

| Operation | Kinetica | React | Preact | Vue | Svelte | Vanilla | Compose HTML |
|---|---:|---:|---:|---:|---:|---:|---:|
| create 1,000 rows | 33.5 | 33.2 | 34.1 | 29.3 | 28.5 | 29.5 | 238.6 |
| replace all 1,000 rows | 33.1 | 34.7 | 36.9 | 31.1 | 32.2 | 32.1 | 360.9 |
| partial update (every 10th row) | 7.5 | 7.3 | 8.2 | 8.3 | 8.1 | 7.8 | 13.3 |
| select row | 7.5 | 6.6 | 8.4 | 7.8 | 7.3 | 7.0 | 12.1 |
| swap two rows | 8.0 | 27.1 | 7.3 | 8.9 | 8.0 | 7.4 | 554.0 |
| remove one row | 8.3 | 7.2 | 7.9 | 7.9 | 7.6 | 7.6 | 507.2 |
| create 10,000 rows | 297 | 328 | 260 | 249 | 220 | 210 | 7.52 s |
| append 1,000 rows to 1,000 | 36.3 | 30.5 | 36.1 | 29.1 | 30.6 | 31.4 | 240.7 |
| clear 1,000 rows | 7.8 | 7.0 | 7.5 | 7.2 | 7.4 | 6.8 | 22.0 |
| select row (10k table) | 9.1 | 7.7 | 9.2 | 15.2 | 8.5 | 8.8 | 23.6 |
| swap two rows (10k table) | 41.5 | 51.0 | 33.1 | 44.2 | 30.8 | 28.1 | 9.27 s |
| remove one row (10k table) | 52.8 | 40.7 | 42.7 | 52.9 | 43.4 | 43.0 | 49.2 s |
| partial update (every 10th of 10k) | 42.8 | 34.9 | 38.1 | 46.0 | 31.8 | 32.6 | 112.4 |
| **geometric mean** | **1.23×** | 1.24× | 1.16× | 1.22× | 1.06× | 1.04× | 16.6× |

Kinetica's 13-op headline against React directly is **0.96×** — still ahead of React on the DOM
operations. It wins create-10k (297 ms vs 316 ms) and both swap cases (8.0 ms vs 22.4 ms on 1k;
41.5 ms vs 54.1 ms on 10k), and remove-10k improved to 1.31× React (from 1.48×). The 1k partial
operations sit near the paint floor. The open items: update-every-10th-10k (1.24× React) and
remove-10k still scale with row count, and **startup regressed** — 32.6 ms against React's
27.3 ms, up from 24.5 ms before the post-frame-ordinals soundness fixes added mount-time work.

Compose HTML (JetBrains Compose Multiplatform for Web, added 2026-07-08) is included for
reference, not as part of Kinetica's own rewrite history below — its 16.6× geomean is driven
almost entirely by swap/remove on large keyed lists; see "Live demos" for why.

## Live demos

<!-- code: scripts/bundle-bench-static.mjs, docs/docs-site/src/main.kt, bench/build.mjs -->

Every benchmarked framework is hosted live at `/bench/`, unmodified — the same production
bundles the numbers above come from.

- **[Full comparison report](/bench/report/)** — the interactive charts `bench/report/generate.mjs` renders locally, published as a static page.
- **Table apps** — [Kinetica](/bench/kinetica/) · [React](/bench/react/) · [Preact](/bench/preact/) · [Vue](/bench/vue/) · [Svelte](/bench/svelte/) · [Vanilla JS](/bench/vanilla/) · [Compose HTML](/bench/compose-web/)
- **Tree apps** (1,555-node keyed tree) — [Kinetica](/bench/kinetica/?app=tree) · [React](/bench/react-tree/) · [Preact](/bench/preact-tree/) · [Vue](/bench/vue-tree/) · [Svelte](/bench/svelte-tree/) · [Vanilla JS](/bench/vanilla-tree/) · [Compose HTML](/bench/compose-web/?app=tree)
- **Raw numbers** — [results.json](/bench/results/results.json) · [tree.json](/bench/results/tree.json) · [scaling.json](/bench/results/scaling.json)

A heads-up before you click around the Compose HTML page: its `swap`/`remove` on large keyed
lists scale close to O(n²), not a fluke of one run — clicking "Create 10,000 rows" then
"Remove" will visibly hang the tab for tens of seconds.

## How it got there

<!-- code: kinetica-browser/src@js/BrowserKineticaApp.kt (retained renderer), kinetica-runtime/src/Frames.kt (frame ordinals), kinetica-runtime/src/ComponentScope.kt (each memoization), kinetica-runtime/src/Node.kt (ChildRegion), kinetica-compiler/src/KineticaIrFrames.kt -->

The July 2026 rewrite moved the original 9-op geometric mean from **15.2× to roughly 1.1×**;
frame ordinals then took the 13-op headline below React for the first time, where it remains
(**0.96×** vs React today):

1. **Event registry fix (15.2× → 3.0×).** Profiling showed 60–67% of all CPU in an O(n)
   identity scan run per handler per render — O(n²) per operation and leaking. A hash map with
   commit-time eviction removed it; create-10k went from 20.3 s to 0.4 s.
2. **Retained renderer (3.0× → 1.93×).** The original renderer rebuilt the entire DOM on every
   event. The [current renderer](/docs/browser-renderer) diffs against a mounted shadow tree:
   keyed LIS reconciliation, prop-level patches, root event delegation, focus preserved.
3. **Debug costs gated.** Journal sampling, path attributes and focus bookkeeping only run with
   `debug = true`.
4. **Keyed row memoization (1.93× → 1.42×).** `each` caches every row's output by item
   equality, tracked cell versions and context reads; unchanged rows re-emit the **same Node
   references**, which the diff skips with one identity comparison. Remove-row dropped from
   19 ms to 9 ms, select to the paint floor.
5. **Allocation hygiene (1.42× → 1.29×).** The remaining create-path cost was Kotlin stdlib
   map/hash work: props for host nodes are now flat arrays (`propsOf`) instead of hash maps,
   `host()` stops copying the props map, and slot/event key derivation reuses an incrementally
   maintained key-scope prefix. Create-10k dropped from 474 ms to roughly 300 ms.
6. **Browser fast-paths + benchmark bundling.** The browser renderer avoids repeated tag/attribute
   classification work, and the benchmark now loads Kinetica through a post-link esbuild production
   bundle instead of the Kotlin Toolchain preview multi-file graph. Startup dropped to 22.6 ms
   at this phase with one JS file and an 82 KB gzip payload (24.5 ms / 85 KB after frame
   ordinals).
7. **Frame ordinals (1.20× → 0.97×).** The compiler plugin became mandatory and slot identity
   moved to compile time: every `state`/`derived`/effect call site gets a static ordinal in a
   per-component frame, so the hot path reads slots by array index — no key strings, no
   metadata maps — and commit-time eviction touches only re-rendered frames instead of
   scanning all slots and events per render. The 13-op geomean crossed below React for the
   first time: swap1k 0.36×, swap10k 0.77×, replace1k 0.88× and create10k 0.94×; remove10k
   (1.31×) and update-every-10th-10k (1.24×) are the remaining DOM open items.

The soundness fixes that followed traded roughly 8 ms of startup and 1.9 MB of after-1k heap
for correctness. The cost is on the **mount path**, not the child diff: the mixed static/keyed
reconciler runs only while patching updates, and reworking it from a browser-side heuristic into
runtime-declared region spans (the runtime marks each `each`/conditional region; the browser
reconciles by those boundaries) measured startup- and heap-neutral in a same-branch A/B. The
regression is the mount-time work the other fixes added — per-subscription cell-listener holders
and cumulative bundle growth — and regaining it is tracked as an open item. The DOM-operation
geomean was unaffected. The full root-cause analysis, per-step measurements and methodology live
in the repository's git history.

## What the suite measures

<!-- code: bench/run-all.mjs, bench/driver/tree.mjs, bench/driver/scaling.mjs, bench-jvm/src, scripts/size-report.mjs -->

Beyond the classic nine operations, the harness tracks the regressions that a single-size,
single-click table can't see:

- **10k-table partial operations** — select/swap/remove/update against 10,000 rows, where
  accidental O(n²) bookkeeping surfaces while the 1k numbers still look healthy.
- **GC accounting** — time spent in V8/Blink collection inside each measured operation, read
  from the same Chrome traces; separates allocation pressure from DOM work.
- **Scaling curves** — the same operation at 1k–20k rows fitted as duration ∝ n^exponent, with
  per-operation thresholds that flag superlinear behaviour.
- **Sustained updates** — a dbmonster-style animation loop (every 10th row re-labelled per
  frame) measured as fps, p95 frame time and dropped-frame share, not single-click latency.
- **Deep tree** — a 1,555-node keyed tree (create, leaf updates, subtree moves, and a no-op
  re-render that isolates pure reconciliation overhead per framework).
- **Memory churn and leaks** — heap after replace/create-clear cycles and after repeated
  mount/unmount cycles through each app's teardown path. This gate is an open regression: heap
  after 1k rows is 7.6 MB against a ≤5 MB target (React 4.4 MB, vanilla 1.9 MB), up from 5.7 MB
  before the soundness fixes added retained per-row state.
- **CPU-throttled pass** — the whole suite under 4× CDP throttling as a low-end-device proxy.
- **JVM microbenchmarks** (`bench-jvm` module) — reactive-core propagation, render-pipeline
  Node construction and markdown SSR throughput, minutes-fast for per-PR regression checks.
- **Bundle-size baseline** — CI fails when a tracked artifact's gzip size grows >10% over
  `bench/size-baseline.json`.

## Performance model

<!-- code: kinetica-runtime/src/Node.kt (diffNodes), kinetica-runtime/src/ComponentScope.kt (renderEachRegion), bench/results/sizes.json -->

- An event costs: handler + node-tree build + **diff O(tree) + patch O(changes)** + paint.
- Unchanged subtrees compare cheaply; reference-equal subtrees (memoized `each` rows,
  `skippableNode`) short-circuit the diff in O(1), so a partial update builds only the
  changed rows' Nodes.
- `clear`-style teardowns and paint-bound operations run at the vanilla-JS floor already.
- 10k-table partial operations are the remaining browser target. The scaling suite marks select as
  safe (`n^0.20`) and update as sublinear (`n^0.80`), but swap exceeds its near-flat
  threshold (`n^0.76` vs 0.6).
- The benchmark payload uses a post-link esbuild bundle over Kotlin/JS output while the Kotlin
  Toolchain `js/app` product is still a preview. Kinetica starts in 32.6 ms with one
  84 KB gzip JS file in the benchmark, versus React's 27.3 ms and 62 KB gzip — startup regressed
  from 24.5 ms with the post-frame-ordinals soundness fixes and is an open item.

## Reproducing

<!-- code: bench/run-all.mjs, bench/README.md, scripts/size-report.mjs -->

```
cd bench
npm install
node run-all.mjs                # build everything, bench all frameworks, generate the report
node run-all.mjs --tree --scaling   # include the deep-tree and scaling suites
open report/index.html

./kotlin run -m bench-jvm       # JVM microbenchmarks (from the repository root)
node scripts/size-report.mjs    # bundle sizes vs the committed baseline
```

Benchmark on AC power only: below ~10% battery, macOS low-power throttling floors every
headless-Chrome operation at ~30 ms for every framework. The harness, app contract, and how
to add another framework are documented in `bench/README.md`.
