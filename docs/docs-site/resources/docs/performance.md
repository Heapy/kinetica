# Performance

<!-- code: bench/results/results.json, bench/driver/bench.mjs, bench/README.md -->

Kinetica is benchmarked continuously against React, Preact, Vue, Svelte and a vanilla-JS
baseline using a local reimplementation of the **js-framework-benchmark** (krausest) keyed
suite: identical table apps, one machine, one Chromium, one Playwright/Chrome-trace driver.
Duration = trusted click → end of last paint.

## Where Kinetica stands (2026-07-07, M4 Max, Chromium 149)

<!-- code: bench/results/results.json, bench/results/part-kinetica.json -->

Median milliseconds; geometric-mean slowdown vs the per-operation fastest framework. The
Kinetica and vanilla columns were re-measured after the frame-ordinals rewrite (vanilla median
drift vs its stored part ≤2%, so cross-part comparison holds); the other columns are stored
same-day parts from the full suite.

| Operation | Kinetica | React | Preact | Vue | Svelte | Vanilla |
|---|---:|---:|---:|---:|---:|---:|
| create 1,000 rows | 34.9 | 33.5 | 32.6 | 36.1 | 26.3 | 25.5 |
| replace all 1,000 rows | 33.6 | 37.4 | 33.2 | 34.2 | 31.4 | 32.6 |
| partial update (every 10th row) | 8.0 | 7.0 | 7.6 | 8.1 | 7.2 | 7.6 |
| select row | 7.8 | 7.6 | 7.4 | 7.2 | 7.4 | 7.6 |
| swap two rows | 8.1 | 22.5 | 7.3 | 8.3 | 7.8 | 7.4 |
| remove one row | 8.5 | 7.4 | 7.4 | 7.2 | 7.2 | 7.3 |
| create 10,000 rows | 284 | 316 | 245 | 234 | 204 | 206 |
| append 1,000 rows to 1,000 | 34.6 | 32.8 | 39.3 | 30.9 | 32.3 | 28.2 |
| clear 1,000 rows | 7.0 | 7.2 | 6.8 | 6.9 | 6.9 | 7.0 |
| select row (10k table) | 8.7 | 7.9 | 7.9 | 15.2 | 7.7 | 7.9 |
| swap two rows (10k table) | 42.1 | 54.1 | 35.6 | 42.7 | 30.0 | 28.3 |
| remove one row (10k table) | 59.8 | 40.3 | 40.8 | 53.8 | 38.8 | 44.4 |
| partial update (every 10th of 10k) | 43.3 | 34.4 | 36.6 | 45.1 | 29.4 | 32.1 |
| **geometric mean** | **1.24×** | 1.28× | 1.12× | 1.24× | 1.03× | 1.04× |

Kinetica's 13-op headline against React directly is **0.969×** — ahead of React overall for the
first time. It wins create-10k (284 ms vs 316 ms), startup (24.5 ms vs 27.3 ms), and both swap
cases (8.1 ms vs 22.5 ms on 1k; 42.1 ms vs 54.1 ms on 10k). The 1k partial operations sit near
the paint floor; remove-10k (1.48× React) and update-every-10th-10k (1.26×) are the remaining
open items — large-table remove/update still scale with row count.

## How it got there

<!-- code: kinetica-browser/src@js/BrowserKineticaApp.kt (retained renderer), kinetica-runtime/src/Frames.kt (frame ordinals), kinetica-runtime/src/ComponentScope.kt (each memoization), kinetica-compiler/src/KineticaIrFrames.kt -->

The July 2026 rewrite moved the original 9-op geometric mean from **15.2× to roughly 1.1×**;
frame ordinals then took the 13-op headline below React for the first time (**0.97×** vs
React):

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
   first time: swap1k 0.36×, swap10k 0.78×, replace1k and create10k 0.90×; remove10k (1.48×)
   and update-every-10th-10k (1.26×) are the remaining open items.

The full root-cause analysis, per-step measurements and methodology live in the
repository's git history.

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
  mount/unmount cycles through each app's teardown path. This gate is currently the one open
  regression: heap after 1k rows is 5.7 MB against a ≤5 MB target (React 4.4 MB, vanilla
  1.9 MB).
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
  safe (`n^0.23`) and update as sublinear-to-linear (`n^0.86`), but swap exceeds its near-flat
  threshold (`n^0.84` vs 0.6).
- The benchmark payload uses a post-link esbuild bundle over Kotlin/JS output while the Kotlin
  Toolchain `js/app` product is still a preview. Kinetica now starts in 24.5 ms with one
  85 KB gzip JS file in the benchmark, versus React's 27.3 ms and 62 KB gzip.

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
