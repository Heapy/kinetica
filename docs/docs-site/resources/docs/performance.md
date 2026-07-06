# Performance

Kinetica is benchmarked continuously against React, Preact, Vue, Svelte and a vanilla-JS
baseline using a local reimplementation of the **js-framework-benchmark** (krausest) keyed
suite: identical table apps, one machine, one Chromium, one Playwright/Chrome-trace driver.
Duration = trusted click → end of last paint.

## Where Kinetica stands (2026-07-05, M4 Max, Chromium 149)

Median milliseconds; geometric-mean slowdown vs the per-operation fastest framework. All six
frameworks measured back to back in one run:

| Operation | Kinetica | React | Preact | Vue | Svelte | Vanilla |
|---|---|---|---|---|---|---|
| create 1,000 rows | 43.5 | 33.4 | 37.0 | 31.9 | 32.2 | 30.1 |
| replace all 1,000 rows | 43.5 | 37.5 | 41.2 | 32.8 | 34.8 | 33.9 |
| partial update (every 10th row) | 9.9 | 7.5 | 7.2 | 8.4 | 8.0 | 7.8 |
| select row | 7.8 | 6.8 | 7.2 | 7.5 | 8.3 | 7.0 |
| swap two rows | 8.3 | 32.2 | 7.0 | 8.6 | 7.6 | 7.6 |
| remove one row | 8.9 | 7.2 | 7.4 | 8.7 | 7.4 | 7.0 |
| create 10,000 rows | 301 | 313 | 249 | 225 | 211 | 204 |
| append 1,000 rows to 1,000 | 45.8 | 35.1 | 39.2 | 36.6 | 34.6 | 34.5 |
| clear 1,000 rows | 7.4 | 6.5 | 7.1 | 7.0 | 7.1 | 6.4 |
| **geometric mean** | **1.29×** | 1.29× | 1.11× | 1.11× | 1.08× | 1.02× |

**Kinetica's geometric mean is tied with React's.** It is ahead of React on swap-rows
(LIS-planned moves vs React's reconciler) and create-10k, and its partial operations —
select, swap, remove, update, clear — run within ~1–2 ms of the vanilla-JS paint floor.

## How it got there

The July 2026 rewrite moved the geometric mean from **15.2× to 1.29×** in five measured steps:

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

The full root-cause analysis, phase gates and measurement methodology live in
`perf-rewrite-design.md` at the repository root.

## Performance model

- An event costs: handler + node-tree build + **diff O(tree) + patch O(changes)** + paint.
- Unchanged subtrees compare cheaply; reference-equal subtrees (memoized `each` rows,
  `skippableNode`) short-circuit the diff in O(1), so a partial update builds only the
  changed rows' Nodes.
- `clear`-style teardowns and paint-bound operations run at the vanilla-JS floor already.
- Payload today reflects the Kotlin Toolchain's preview `js/app` packaging (unminified
  multi-file ESM, ~250 KB gzipped vs React's 60 KB); an esbuild post-link step is the planned
  mitigation until the toolchain ships DCE/minification. This is also most of the remaining
  startup-time gap (49 ms vs React's 27 ms).

## Reproducing

```
cd bench
npm install
node run-all.mjs                # build everything, bench all frameworks, generate the report
open report/index.html
```

Benchmark on AC power only: below ~10% battery, macOS low-power throttling floors every
headless-Chrome operation at ~30 ms for every framework. The harness, app contract, and how
to add another framework are documented in `bench/README.md`.
