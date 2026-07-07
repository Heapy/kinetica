// Single source of truth for benchmarked frameworks, shared by driver/bench.mjs,
// report/generate.mjs and run-all.mjs.
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
  },
  {
    name: "react",
    label: "React",
    url: "/bench/dist/react/index.html",
    treeUrl: "/bench/dist/react-tree/index.html",
    buttons: "id",
    rowControl: "a",
    version: { package: "react" },
  },
  {
    name: "preact",
    label: "Preact",
    url: "/bench/dist/preact/index.html",
    treeUrl: "/bench/dist/preact-tree/index.html",
    buttons: "id",
    rowControl: "a",
    version: { package: "preact" },
  },
  {
    name: "vue",
    label: "Vue",
    url: "/bench/dist/vue/index.html",
    treeUrl: "/bench/dist/vue-tree/index.html",
    buttons: "id",
    rowControl: "a",
    version: { package: "vue" },
  },
  {
    name: "svelte",
    label: "Svelte",
    url: "/bench/dist/svelte/index.html",
    treeUrl: "/bench/dist/svelte-tree/index.html",
    buttons: "id",
    rowControl: "a",
    version: { package: "svelte" },
  },
  {
    name: "vanilla",
    label: "Vanilla JS",
    url: "/bench/dist/vanilla/index.html",
    treeUrl: "/bench/dist/vanilla-tree/index.html",
    buttons: "id",
    rowControl: "a",
    version: "n/a",
  },
  {
    name: "compose-web",
    label: "Compose HTML",
    url: "/samples/browser-bench-compose/web/index.html",
    treeUrl: "/samples/browser-bench-compose/web/index.html?app=tree",
    buttons: "id",
    rowControl: "a",
    version: "1.11.1",
    build: { cmd: process.execPath, args: ["bench/build-compose.mjs"] },
  },
];

// Validated categorical palette (dataviz reference, light/dark pairs), assigned by
// config position. 8 slots available; 7 in use.
export const paletteSlots = [
  ["#2a78d6", "#3987e5"], // blue
  ["#1baf7a", "#199e70"], // aqua
  ["#eda100", "#c98500"], // yellow
  ["#008300", "#008300"], // green
  ["#4a3aa7", "#9085e9"], // violet
  ["#e34948", "#e66767"], // red
  ["#e87ba4", "#d55181"], // magenta
  ["#eb6834", "#d95926"], // orange
];

export function frameworkByName(name) {
  return frameworks.find((f) => f.name === name);
}

export function colorFor(name) {
  const index = frameworks.findIndex((f) => f.name === name);
  return paletteSlots[index % paletteSlots.length];
}
