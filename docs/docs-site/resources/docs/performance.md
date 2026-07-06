# Performance

Kinetica is benchmarked continuously against React, Preact, Vue, Svelte and a vanilla-JS
baseline using a local reimplementation of the **js-framework-benchmark** (krausest) keyed
suite: identical table apps, one machine, one Chromium, one Playwright/Chrome-trace driver.
Duration = trusted click → end of last paint.

## Where Kinetica stands (2026-07-06, M4 Max, Chromium 149)

Median milliseconds; geometric-mean slowdown vs the per-operation fastest framework. All six
frameworks measured back to back in one 13-operation run:

| Operation | Kinetica | React | Preact | Vue | Svelte | Vanilla |
|---|---:|---:|---:|---:|---:|---:|
| create 1,000 rows | 41.1 | 33.5 | 32.6 | 36.1 | 26.3 | 28.0 |
| replace all 1,000 rows | 41.0 | 37.4 | 33.2 | 34.2 | 31.4 | 31.3 |
| partial update (every 10th row) | 9.4 | 7.0 | 7.6 | 8.1 | 7.2 | 7.3 |
| select row | 7.3 | 7.6 | 7.4 | 7.2 | 7.4 | 7.5 |
| swap two rows | 8.5 | 22.4 | 7.3 | 8.3 | 7.8 | 7.4 |
| remove one row | 9.7 | 7.4 | 7.4 | 7.2 | 7.2 | 7.1 |
| create 10,000 rows | 291 | 316 | 245 | 234 | 204 | 208 |
| append 1,000 rows to 1,000 | 44.4 | 32.8 | 39.3 | 30.9 | 32.3 | 30.1 |
| clear 1,000 rows | 8.5 | 7.2 | 6.8 | 6.9 | 6.9 | 7.0 |
| select row (10k table) | 9.7 | 7.9 | 7.9 | 15.2 | 7.7 | 8.0 |
| swap two rows (10k table) | 42.2 | 54.1 | 35.6 | 42.7 | 29.9 | 28.7 |
| remove one row (10k table) | 54.7 | 40.3 | 40.8 | 53.8 | 38.8 | 44.9 |
| partial update (every 10th of 10k) | 48.6 | 34.4 | 36.6 | 45.1 | 29.4 | 31.8 |
| **geometric mean** | **1.35×** | 1.27× | 1.11× | 1.23× | 1.02× | 1.04× |

Kinetica's current 13-op headline is **1.35×**, behind React's **1.27×** in this run. It is still
ahead of React on create-10k (291 ms vs 316 ms), startup (20.1 ms vs 27.3 ms), and the 1k-row swap
case (8.5 ms vs 22.4 ms). The 1k partial operations sit near the paint floor; the 10k partial
operations show the remaining work: large-table swap/remove/update still scale with row count.

## How it got there

The July 2026 rewrite moved the original 9-op geometric mean from **15.2× to roughly 1.3×**. The
current 13-op headline is **1.35×** after adding the 10k-table partial operations:

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
6. **Browser fast-paths + benchmark bundling.** The browser renderer avoids repeated tag/attribute
   classification work, and the benchmark now loads Kinetica through a post-link esbuild production
   bundle instead of the Kotlin Toolchain preview multi-file graph. Startup is 20.1 ms with one JS
   file and a 77 KB gzip payload.

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
- 10k-table partial operations are the remaining browser target. The scaling suite marks select as
  safe (`n^0.37`) and update as sublinear-to-linear (`n^0.80`), but swap exceeds its near-flat
  threshold (`n^0.76` vs 0.6).
- The benchmark payload uses a post-link esbuild bundle over Kotlin/JS output while the Kotlin
  Toolchain `js/app` product is still a preview. Kinetica now starts in 20.1 ms with one
  77 KB gzip JS file in the benchmark, versus React's 27.3 ms and 60 KB gzip.

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
