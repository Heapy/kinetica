# Game of Life browser benchmark

This suite compares Kinetica, React, Compose HTML, and Vanilla implementations of the same
72 × 48 finite B3/S23 board. The apps share the visual stylesheet, UI/ARIA contract, presets,
speeds, model behavior, and a cross-language seeded random sequence so dense workloads are
cell-for-cell identical.

Run the complete lifecycle from the repository root:

```sh
node bench/game-of-life/run.mjs --warmup=1 --samples=5
```

The command installs missing local dependencies, runs the model tests, builds all four production
apps, measures them, refreshes the report and docs snapshot, validates the published artifacts,
and prints previous → current deltas. The browser and HTTP server are owned and closed by the
benchmark driver. If a later step fails, the runner restores the previously published artifacts.

The driver records cold startup plus six representative interactions. Interaction duration is
parsed from a Chrome trace from trusted click `EventDispatch` start through the final
`Paint`/`Commit`, matching the main framework benchmark methodology. Interaction contexts
emulate `prefers-reduced-motion: reduce` so the shared 160 ms cell-birth animation does not
become the measured bottleneck. Results are written to
`bench/results/game-of-life/results.json`; the self-contained report is
`bench/game-of-life/report.html` and is staged with the apps for the docs server.

Browser benchmark numbers are only comparable within the same machine, Chromium build, power
mode, viewport, and sample configuration. The JSON records those details and every raw sample.
