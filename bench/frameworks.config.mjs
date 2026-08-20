// Append only: config position fixes each framework's report color. See bench/README.md for the
// registry schema and app contract.

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
  // Canvas entries share a build but never participate in DOM rankings or baselines.
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

export function supportsSuite(fw, suite) {
  return fw.suites === undefined || fw.suites.includes(suite);
}

export const paletteSlots = [
  ["#2a78d6", "#3987e5"],
  ["#1baf7a", "#199e70"],
  ["#eda100", "#c98500"],
  ["#008300", "#008300"],
  ["#4a3aa7", "#9085e9"],
  ["#e34948", "#e66767"],
  ["#e87ba4", "#d55181"],
  ["#eb6834", "#d95926"],
  ["#5a6570", "#98a4b0"],
];

export function frameworkByName(name) {
  return frameworks.find((f) => f.name === name);
}

export function colorFor(name) {
  const index = frameworks.findIndex((f) => f.name === name);
  return paletteSlots[index % paletteSlots.length];
}
