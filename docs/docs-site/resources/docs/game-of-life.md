# Game of Life, four ways

<!-- code: samples/browser-game-of-life/src/main.kt, samples/browser-game-of-life-react/src/main.jsx, samples/browser-game-of-life-compose/src/main.kt, samples/browser-game-of-life-vanilla/src/main.mjs -->

One 72 × 48 finite Conway universe, implemented with four rendering stacks. Every version uses
the same dark visual system, B3/S23 rules, preset data, speed controls, accessibility labels,
cell-editing behavior, and browser verification contract. Only the state/rendering layer changes.

## Play the implementations

<!-- code: scripts/build-game-of-life.mjs, samples/game-of-life-shared/styles.css -->

- **[Kinetica](/game-of-life/kinetica/)** — Kotlin/JS with Kinetica's retained DOM renderer.
- **[React](/game-of-life/react/)** — React 19 with memoized cell components.
- **[Compose HTML](/game-of-life/compose-html/)** — Kotlin/JS with JetBrains Compose HTML.
- **[Vanilla](/game-of-life/vanilla/)** — direct browser DOM updates as the baseline.

[Open the full benchmark report](/game-of-life/) for per-operation slowdown factors, bundle
sizes, methodology, and links back to every live app. [Raw samples and environment
metadata](/game-of-life/results.json) are published alongside it.

## Performance snapshot

<!-- code: bench/results/game-of-life/results.json, bench/game-of-life/run.mjs, bench/game-of-life/benchmark.mjs -->

Median milliseconds from the 2026-07-16 run on an Apple M4 Max with Chromium 149.0.7827.55,
5 measured samples after 1 warmup. Interaction traces request reduced motion so the shared
160 ms cell-birth animation cannot hide renderer work. Lower is better.

| Operation | Kinetica | React | Compose HTML | Vanilla |
|---|---:|---:|---:|---:|
| Cold startup + 3,456-cell mount | 66.90 | 56.00 | 107.40 | **35.60** |
| Load Pulsar preset | **21.42** | 21.87 | 34.62 | 27.18 |
| Advance Pulsar | **24.23** | 28.43 | 31.85 | 24.82 |
| Randomize 24% of the board | 140.39 | 155.99 | 140.28 | **118.71** |
| Advance randomized board | 121.97 | 149.87 | 128.78 | **116.83** |
| Toggle one cell | 14.50 | 18.93 | 13.75 | **13.41** |
| Clear Pulsar | 21.00 | **20.15** | 33.28 | 20.25 |
| Production bundle, gzip | 98.3 KB | 62.3 KB | 177.3 KB | **4.1 KB** |

The numbers are machine-specific, so the committed JSON retains every sample instead of
presenting the medians as universal rankings. Sparse operations sit close together; randomized
evolution makes the cost of recomputing and updating a large fraction of the board more visible.
The seeded generator advances between samples but yields the exact same cells in all four apps.

## Shared behavior, reproducible measurement

<!-- code: samples/game-of-life-model/src/LifeModel.kt, samples/game-of-life-shared/life-core.mjs, samples/game-of-life-shared/life-core.test.mjs, scripts/verify-browser.mjs -->

Kinetica and Compose HTML import one tested Kotlin model. React and Vanilla import one JavaScript
model that runs the same stability, oscillator, spaceship, boundary, preset, and editing parity
tests. A Playwright gate then drives the same selectors through all four production bundles and
checks the rendered 3,456-cell board, preset populations, stepping, editing, speed, run, and pause.

The benchmark uses Kinetica's existing Chrome-trace parser: duration begins at the trusted click
`EventDispatch` and ends at the last `Paint` or `Commit`. Reproduce it from the repository root:

```sh
node bench/game-of-life/run.mjs --warmup=1 --samples=5
```
