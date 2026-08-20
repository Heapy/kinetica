// Single source of truth for benchmarked frameworks, shared by driver/bench.mjs,
// report/generate.mjs and run.mjs.
//
// Rules for adding a framework (see README.md for the full contract):
// - APPEND new entries at the end. Position in this list assigns the chart color
//   (validated categorical palette, color follows the entity) — never reorder.
// - `name` is the results key and the part-file name (results/part-<name>.json).
// - `url` is served from the REPO ROOT by the driver's static server.
// - `buttons`: how toolbar buttons are addressed — "id" (#run) or "testid" ([data-testid=run]).
// - `rowControl`: the clickable element inside td.col-label / td.col-remove — "a" or "button".
// - `version`: literal string, or { package: "<npm-name>" } resolved from bench/node_modules.
// - `build`: optional extra build step run from the repo root (JS bundles are always built
//   via `node build.mjs`, which reads TARGETS in build.mjs).
// - `treeUrl`: the framework's deep-tree benchmark app (driver/tree.mjs); omit to skip
//   that framework in the tree bench.
// - `profile`: recipe for a readable production-mode bundle used by run.mjs --profile.
//   `build-target` reuses a target exported by build.mjs; `linked-js` bundles a Kotlin/JS
//   link output. Use `{ unsupported: "reason" }` only for an explicit browser limitation.
// - `driver`: "canvas" swaps the DOM harness for the window.__bench bridge and switches the
//   trace anchor (driver/canvas-harness.mjs). Default "dom".
// - `renderer`: "canvas" moves the entry out of the main table, the geometric mean and the
//   per-operation baseline into its own report section. Default "dom".
// - `suites`: restrict an entry to specific suites; omit to take part in all of them.

export const frameworks = [
  {
    name: "kinetica",
    label: "Kinetica",
    url: "/samples/browser-bench/web/index.html",
    treeUrl: "/samples/browser-bench/web/index.html?app=tree",
    buttons: "testid",
    rowControl: "button",
    version: "dev",
    build: { cmd: process.execPath, args: ["bench/build-kinetica.mjs"] },
    profile: {
      kind: "linked-js",
      entry: "build/artifacts/CompiledWebArtifact/browser-benchjsrelease/kotlin-output/browser-bench.mjs",
      define: { KINETICA_DEBUG_DIAGNOSTICS: "false" },
    },
  },
  {
    name: "react",
    label: "React",
    url: "/bench/dist/react/index.html",
    treeUrl: "/bench/dist/react-tree/index.html",
    buttons: "id",
    rowControl: "a",
    version: { package: "react" },
    profile: { kind: "build-target", target: "react" },
  },
  {
    name: "preact",
    label: "Preact",
    url: "/bench/dist/preact/index.html",
    treeUrl: "/bench/dist/preact-tree/index.html",
    buttons: "id",
    rowControl: "a",
    version: { package: "preact" },
    profile: { kind: "build-target", target: "preact" },
  },
  {
    name: "vue",
    label: "Vue",
    url: "/bench/dist/vue/index.html",
    treeUrl: "/bench/dist/vue-tree/index.html",
    buttons: "id",
    rowControl: "a",
    version: { package: "vue" },
    profile: { kind: "build-target", target: "vue" },
  },
  {
    name: "svelte",
    label: "Svelte",
    url: "/bench/dist/svelte/index.html",
    treeUrl: "/bench/dist/svelte-tree/index.html",
    buttons: "id",
    rowControl: "a",
    version: { package: "svelte" },
    profile: { kind: "build-target", target: "svelte" },
  },
  {
    name: "vanilla",
    label: "Vanilla JS",
    url: "/bench/dist/vanilla/index.html",
    treeUrl: "/bench/dist/vanilla-tree/index.html",
    buttons: "id",
    rowControl: "a",
    version: "n/a",
    profile: { kind: "build-target", target: "vanilla" },
  },
  {
    name: "compose-web",
    label: "Compose HTML",
    url: "/samples/browser-bench-compose/web/index.html",
    treeUrl: "/samples/browser-bench-compose/web/index.html?app=tree",
    buttons: "id",
    rowControl: "a",
    version: "1.12.0-rc01",
    build: { cmd: process.execPath, args: ["bench/build-compose.mjs"] },
    profile: {
      kind: "linked-js",
      entry: "build/artifacts/CompiledWebArtifact/browser-bench-composejsrelease/kotlin-output/browser-bench-compose.mjs",
    },
  },
  // Canvas renderers. They build no DOM at all, so they cannot satisfy the app contract the
  // DOM frameworks are held to, and the report keeps them out of the main table, the geometric
  // mean and the per-operation "fastest" baseline (see report/generate.mjs). `driver: "canvas"`
  // switches the harness to the window.__bench bridge (driver/canvas-harness.mjs); `buttons` and
  // `rowControl` are DOM concepts and deliberately absent. Both entries are one app, one build.
  {
    name: "compose-canvas",
    label: "Compose canvas",
    renderer: "canvas",
    driver: "canvas",
    suites: ["main"],
    url: "/samples/browser-bench-compose-canvas/web/index.html?list=column",
    version: "1.12.0-rc01",
    build: { cmd: process.execPath, args: ["bench/build-compose-canvas.mjs"] },
    profile: { unsupported: "Kotlin/Wasm output is a .wasm binary; there is no readable JS bundle to profile" },
  },
  {
    name: "compose-canvas-lazy",
    label: "Compose canvas (Lazy)",
    renderer: "canvas",
    driver: "canvas",
    suites: ["main"],
    url: "/samples/browser-bench-compose-canvas/web/index.html?list=lazy",
    version: "1.12.0-rc01",
    build: { cmd: process.execPath, args: ["bench/build-compose-canvas.mjs"] },
    profile: { unsupported: "Kotlin/Wasm output is a .wasm binary; there is no readable JS bundle to profile" },
  },
];

// Whether a framework takes part in a suite. Entries without `suites` are in every suite they
// have a URL for; canvas entries opt into `main` only.
export function supportsSuite(fw, suite) {
  return fw.suites === undefined || fw.suites.includes(suite);
}

// Validated categorical palette (dataviz reference, light/dark pairs), assigned by
// config position. 9 slots available; 9 in use. The slate pair was appended for the second
// canvas entry — appending is safe, reordering is not (colour follows the entity).
export const paletteSlots = [
  ["#2a78d6", "#3987e5"], // blue
  ["#1baf7a", "#199e70"], // aqua
  ["#eda100", "#c98500"], // yellow
  ["#008300", "#008300"], // green
  ["#4a3aa7", "#9085e9"], // violet
  ["#e34948", "#e66767"], // red
  ["#e87ba4", "#d55181"], // magenta
  ["#eb6834", "#d95926"], // orange
  ["#5a6570", "#98a4b0"], // slate
];

export function frameworkByName(name) {
  return frameworks.find((f) => f.name === name);
}

export function colorFor(name) {
  const index = frameworks.findIndex((f) => f.name === name);
  return paletteSlots[index % paletteSlots.length];
}
