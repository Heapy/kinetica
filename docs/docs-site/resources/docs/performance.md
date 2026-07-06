# Performance

Kinetica is benchmarked continuously against React, Preact, Vue, Svelte and a vanilla-JS
baseline using a local reimplementation of the **js-framework-benchmark** (krausest) keyed
suite: identical table apps, one machine, one Chromium, one Playwright/Chrome-trace driver.
Duration = trusted click → end of last paint.

## Where Kinetica stands (2026-07-06, M4 Max, Chromium 149)

Median milliseconds; geometric-mean slowdown vs the per-operation fastest framework. All six
frameworks measured back to back in one run:

| Operation | Kinetica | React | Preact | Vue | Svelte | Vanilla |
|---|---|---|---|---|---|---|
| create 1,000 rows | 38.4 | 32.1 | 29.4 | 32.9 | 30.9 | 30.7 |
| replace all 1,000 rows | 37.5 | 37.7 | 31.8 | 33.6 | 32.5 | 30.0 |
| partial update (every 10th row) | 8.1 | 7.4 | 7.7 | 7.7 | 7.5 | 7.0 |
| select row | 7.8 | 7.2 | 7.1 | 7.2 | 7.4 | 7.3 |
| swap two rows | 8.8 | 29.2 | 7.7 | 7.9 | 8.1 | 7.6 |
| remove one row | 9.4 | 6.9 | 7.9 | 8.1 | 7.3 | 7.1 |
| create 10,000 rows | 314 | 317 | 265 | 230 | 213 | 203 |
| append 1,000 rows to 1,000 | 40.8 | 31.8 | 36.2 | 31.9 | 29.8 | 31.1 |
| clear 1,000 rows | 7.2 | 7.0 | 6.8 | 6.8 | 6.8 | 6.8 |
| **geometric mean** | **1.25×** | 1.29× | 1.09× | 1.09× | 1.05× | 1.02× |

**Kinetica's geometric mean is now ahead of React's in this run.** It is ahead of React on
swap-rows (LIS-planned moves vs React's reconciler) and create-10k, and its partial operations —
select, swap, remove, update, clear — run within ~1–2 ms of the vanilla-JS paint floor.

## How it got there

The July 2026 rewrite moved the geometric mean from **15.2× to 1.25×** in six measured steps:

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
   maintained key-scope prefix. Create-10k dropped from 474 ms to 301 ms — past React.
6. **Browser fast-paths + benchmark bundling (1.29× → 1.25×).** The browser renderer avoids
   repeated tag/attribute classification work, and the benchmark now loads Kinetica through a
   post-link esbuild production bundle instead of the Kotlin Toolchain preview multi-file graph.
   Startup dropped from 54.3 ms and 223 JS files to 20.7 ms and one JS file.

The full root-cause analysis, phase gates and measurement methodology live in
`perf-rewrite-design.md` at the repository root.

## What the suite measures

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
  mount/unmount cycles through each app's teardown path.
- **CPU-throttled pass** — the whole suite under 4× CDP throttling as a low-end-device proxy.
- **JVM microbenchmarks** (`bench-jvm` module) — reactive-core propagation, render-pipeline
  Node construction and markdown SSR throughput, minutes-fast for per-PR regression checks.
- **Bundle-size baseline** — CI fails when a tracked artifact's gzip size grows >10% over
  `bench/size-baseline.json`.

## Performance model

- An event costs: handler + node-tree build + **diff O(tree) + patch O(changes)** + paint.
- Unchanged subtrees compare cheaply; reference-equal subtrees (memoized `each` rows,
  `skippableNode`) short-circuit the diff in O(1), so a partial update builds only the
  changed rows' Nodes.
- `clear`-style teardowns and paint-bound operations run at the vanilla-JS floor already.
- The benchmark payload uses a post-link esbuild bundle over Kotlin/JS output while the Kotlin
  Toolchain `js/app` product is still a preview. Kinetica now starts in 20.7 ms with one
  75 KB gzip JS file in the benchmark, versus React's 26.2 ms and 60 KB gzip.

## Reproducing

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
