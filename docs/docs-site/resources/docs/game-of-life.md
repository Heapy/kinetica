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

<!-- code: bench/results/game-of-life/results.json, bench/game-of-life/benchmark.mjs -->

Median milliseconds from the 2026-07-16 run on an Apple M4 Max with Chromium 149.0.7827.55,
five measured samples after one warmup. Interaction traces request reduced motion so the shared
160 ms cell-birth animation cannot hide renderer work. Lower is better.

| Operation | Kinetica | React | Compose HTML | Vanilla |
|---|---:|---:|---:|---:|
| Cold startup + 3,456-cell mount | 65.60 | 57.00 | 107.00 | **40.40** |
| Load Pulsar preset | 26.63 | 21.40 | 29.16 | **20.71** |
| Advance Pulsar | 25.79 | 28.70 | 32.33 | **24.26** |
| Randomize 24% of the board | 123.40 | 153.11 | 133.73 | **114.25** |
| Advance randomized board | 120.31 | 144.94 | 129.48 | **110.92** |
| Toggle one cell | **13.40** | 19.83 | 14.78 | 14.18 |
| Clear Pulsar | 21.17 | **20.26** | 28.39 | 29.03 |
| Production bundle, gzip | 98.1 KB | 62.2 KB | 177.0 KB | **3.9 KB** |

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
npm ci --prefix bench
node scripts/build-game-of-life.mjs
node bench/game-of-life/benchmark.mjs --warmup=1 --samples=5
```
