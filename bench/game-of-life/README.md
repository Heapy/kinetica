# Game of Life browser benchmark

This suite compares Kinetica, React, Compose HTML, and Vanilla implementations of the same
72 × 48 finite B3/S23 board. The apps share the visual stylesheet, UI/ARIA contract, presets,
speeds, model behavior, and a cross-language seeded random sequence so dense workloads are
cell-for-cell identical.

Build and run from the repository root:

```sh
npm ci --prefix bench
node scripts/build-game-of-life.mjs
node bench/game-of-life/benchmark.mjs --warmup=1 --samples=5
node bench/game-of-life/validate-results.mjs
```

The driver records cold startup plus six representative interactions. Interaction duration is
parsed from a Chrome trace from trusted click `EventDispatch` start through the final
`Paint`/`Commit`, matching the main framework benchmark methodology. Interaction contexts
emulate `prefers-reduced-motion: reduce` so the shared 160 ms cell-birth animation does not
become the measured bottleneck. Results are written to
`bench/results/game-of-life/results.json`; the self-contained report is
`bench/game-of-life/report.html` and is staged with the apps for the docs server.

Browser benchmark numbers are only comparable within the same machine, Chromium build, power
mode, viewport, and sample configuration. The JSON records those details and every raw sample.
