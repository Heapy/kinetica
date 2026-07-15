# Game of Life browser demo

Build the Kotlin/JS bundle from the repository root:

```sh
./kotlin build -m browser-game-of-life
```

Serve the repository root (the shared benchmark server is convenient):

```sh
node -e 'import("./bench/driver/server.mjs").then(m => m.startServer(process.cwd(), 4173))'
```

Open <http://127.0.0.1:4173/samples/browser-game-of-life/web/index.html>.

The demo uses a finite 72 × 48 B3/S23 board and includes Glider, Lightweight
Spaceship, Beacon, and Pulsar presets. Click any cell to edit the current field.
