# Performance

Kinetica is benchmarked continuously against React, Preact, Vue, Svelte and a vanilla-JS
baseline using a local reimplementation of the **js-framework-benchmark** (krausest) keyed
suite: identical table apps, one machine, one Chromium, one Playwright/Chrome-trace driver.
Duration = trusted click → end of last paint.

## Where Kinetica stands (2026-07-05, M4 Max, Chromium 149)

Median milliseconds; geometric-mean slowdown vs the per-operation fastest framework:

| Operation | Kinetica | React | Preact | Vue | Svelte | Vanilla |
|---|---|---|---|---|---|---|
| create 1,000 rows | 51.7 | 31.7 | 39.4 | 34.1 | 34.3 | 33.4 |
| partial update (every 10th) | 19.1 | 7.5 | 7.6 | 8.4 | 8.4 | 7.6 |
| select row | 13.1 | 7.9 | 8.2 | 7.9 | 7.7 | 7.7 |
| swap two rows | 19.0 | 33.6 | 6.6 | 8.4 | 7.7 | 9.0 |
| remove one row | 18.8 | 8.4 | 7.5 | 8.5 | 7.0 | 7.6 |
| create 10,000 rows | 455 | 315 | 260 | 232 | 208 | 202 |
| **geometric mean** | **1.93×** | 1.35× | 1.12× | 1.12× | 1.06× | 1.06× |

Kinetica beats React on swap-rows (LIS-planned moves vs React's reconciler) and sits within
2–2.5× on partial operations, with further headroom planned (subtree memoization).

## How it got there

The July 2026 rewrite moved the geometric mean from **15.2× to 1.93×** in three measured steps:

1. **Event registry fix (15.2× → 3.0×).** Profiling showed 60–67% of all CPU in an O(n)
   identity scan run per handler per render — O(n²) per operation and leaking. A hash map with
   commit-time eviction removed it; create-10k went from 20.3 s to 0.4 s.
2. **Retained renderer (3.0× → 1.93×).** The original renderer rebuilt the entire DOM on every
   event. The [current renderer](/docs/browser-renderer) diffs against a mounted shadow tree:
   keyed LIS reconciliation, prop-level patches, root event delegation, focus preserved.
3. **Debug costs gated.** Journal sampling, path attributes and focus bookkeeping only run with
   `debug = true`.

The full root-cause analysis, phase gates and remaining plan (`each` row memoization via the
diff's reference-equality fast path; allocation hygiene) live in `perf-rewrite-design.md` at the
repository root.

## Performance model

- An event costs: handler + node-tree build + **diff O(tree) + patch O(changes)** + paint.
- Unchanged subtrees compare cheaply; reference-equal subtrees (memoized rows, `skippableNode`)
  short-circuit the diff in O(1).
- `clear`-style teardowns and paint-bound operations run at the vanilla-JS floor already.
- Payload today reflects the Kotlin Toolchain's preview `js/app` packaging (unminified
  multi-file ESM, ~250 KB gzipped); an esbuild post-link step is the planned mitigation until
  the toolchain ships DCE/minification.

## Reproducing

```
cd bench
npm install
node run-all.mjs                # build everything, bench all frameworks, generate the report
open report/index.html
```

The harness, app contract, and how to add another framework are documented in `bench/README.md`.
