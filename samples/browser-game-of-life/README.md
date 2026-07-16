# Game of Life browser comparison

The Kinetica version is one of four behavior- and design-matched implementations. Build
Kinetica, React, Compose HTML, and Vanilla from the repository root:

```sh
npm ci --prefix bench
node scripts/build-game-of-life.mjs
```

Serve the repository root (the shared benchmark server is convenient):

```sh
node -e 'import("./bench/driver/server.mjs").then(m => m.startServer(process.cwd(), 4173))'
```

Open the production builds under <http://127.0.0.1:4173/build/tasks/_game-of-life_dist/>:

- `kinetica/index.html`
- `react/index.html`
- `compose-html/index.html`
- `vanilla/index.html`

The demo uses a finite 72 × 48 B3/S23 board and includes Glider, Lightweight
Spaceship, Beacon, and Pulsar presets. All implementations share the stylesheet, DOM/test
contract, control timings, and model behavior. Click any cell to edit the current field.
