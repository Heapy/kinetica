import { build } from "esbuild";
import sveltePlugin from "esbuild-svelte";
import { cpSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const dist = join(root, "dist");

const mountSnippet = `
    window.__mountMs = undefined;
    (function () {
      function check() {
        if (document.querySelector("#run, [data-testid=run]")) {
          window.__mountMs = performance.now();
          return true;
        }
        return false;
      }
      if (!check()) {
        var mo = new MutationObserver(function () { if (check()) mo.disconnect(); });
        mo.observe(document.documentElement, { childList: true, subtree: true });
      }
    })();
`;

function page(title) {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${title}</title>
  <link rel="stylesheet" href="./styles.css">
  <script>${mountSnippet}</script>
</head>
<body>
  <div id="main"></div>
  <script type="module" src="./main.js"></script>
</body>
</html>
`;
}

const targets = [
  { name: "vanilla", title: "Vanilla JS keyed benchmark", entry: "frameworks/vanilla/main.mjs" },
  { name: "react", title: "React keyed benchmark", entry: "frameworks/react/main.jsx" },
  { name: "preact", title: "Preact keyed benchmark", entry: "frameworks/preact/main.jsx" },
  { name: "vue", title: "Vue keyed benchmark", entry: "frameworks/vue/main.mjs" },
  { name: "svelte", title: "Svelte keyed benchmark", entry: "frameworks/svelte/main.mjs" },
];

for (const target of targets) {
  const outDir = join(dist, target.name);
  mkdirSync(outDir, { recursive: true });
  await build({
    entryPoints: [join(root, target.entry)],
    bundle: true,
    minify: true,
    format: "esm",
    outfile: join(outDir, "main.js"),
    define: {
      "process.env.NODE_ENV": '"production"',
      "__VUE_OPTIONS_API__": "false",
      "__VUE_PROD_DEVTOOLS__": "false",
      "__VUE_PROD_HYDRATION_MISMATCH_DETAILS__": "false",
    },
    jsx: "automatic",
    jsxImportSource: target.name === "preact" ? "preact" : "react",
    plugins: target.name === "svelte" ? [sveltePlugin({ compilerOptions: { css: "injected" } })] : [],
    logLevel: "warning",
  });
  writeFileSync(join(outDir, "index.html"), page(target.title));
  cpSync(join(root, "frameworks/shared/styles.css"), join(outDir, "styles.css"));
  console.log(`built ${target.name}`);
}
