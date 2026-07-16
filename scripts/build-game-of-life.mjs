// Builds all four production Game of Life implementations and stages a self-contained
// asset tree under build/tasks/_game-of-life_dist for the docs server and benchmark.

import {
  copyFileSync,
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";
import { run } from "./lib/run.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const requireFromBench = createRequire(join(repoRoot, "bench", "package.json"));
const skipKotlinBuild = process.argv.includes("--skip-kotlin-build");

let esbuild;
try {
  ({ build: esbuild } = requireFromBench("esbuild"));
} catch (error) {
  console.error("Missing benchmark dependencies. Run `npm ci --prefix bench` first.");
  throw error;
}

run(process.execPath, [
  join(repoRoot, "samples", "game-of-life-shared", "life-core.test.mjs"),
], { cwd: repoRoot });

if (!skipKotlinBuild) {
  const kotlin = process.platform === "win32" ? "kotlin.bat" : "./kotlin";
  run(kotlin, ["publish", "mavenLocal", "-m", "kinetica-compiler"], { cwd: repoRoot });
  run(kotlin, ["build", "-m", "browser-game-of-life"], { cwd: repoRoot });
  run(kotlin, ["build", "-m", "browser-game-of-life-compose"], { cwd: repoRoot });
}

const targets = [
  {
    id: "kinetica",
    label: "Kinetica",
    mark: "K",
    source: join(repoRoot, "build", "tasks", "_browser-game-of-life_linkJs", "browser-game-of-life.mjs"),
    bundle: join(repoRoot, "build", "tasks", "_browser-game-of-life_bundle", "main.mjs"),
  },
  {
    id: "react",
    label: "React",
    mark: "R",
    source: join(repoRoot, "samples", "browser-game-of-life-react", "src", "main.jsx"),
    bundle: join(repoRoot, "build", "tasks", "_browser-game-of-life-react_bundle", "main.mjs"),
  },
  {
    id: "compose-html",
    label: "Compose HTML",
    mark: "C",
    source: join(
      repoRoot,
      "build",
      "tasks",
      "_browser-game-of-life-compose_linkJs",
      "browser-game-of-life-compose.mjs",
    ),
    bundle: join(repoRoot, "build", "tasks", "_browser-game-of-life-compose_bundle", "main.mjs"),
  },
  {
    id: "vanilla",
    label: "Vanilla",
    mark: "V",
    source: join(repoRoot, "samples", "browser-game-of-life-vanilla", "src", "main.mjs"),
    bundle: join(repoRoot, "build", "tasks", "_browser-game-of-life-vanilla_bundle", "main.mjs"),
  },
];

for (const target of targets) {
  if (!existsSync(target.source)) {
    throw new Error(`Missing ${target.label} entrypoint: ${target.source}`);
  }
  mkdirSync(dirname(target.bundle), { recursive: true });
  await esbuild({
    entryPoints: [target.source],
    bundle: true,
    minify: true,
    treeShaking: true,
    legalComments: "none",
    format: "esm",
    platform: "browser",
    target: "es2020",
    outfile: target.bundle,
    nodePaths: [join(repoRoot, "bench", "node_modules")],
    define: {
      "process.env.NODE_ENV": '"production"',
    },
    jsx: "automatic",
    jsxImportSource: "react",
    logLevel: "warning",
  });
  const bytes = readFileSync(target.bundle);
  target.rawBytes = bytes.length;
  target.gzipBytes = gzipSync(bytes).length;
  console.log(
    `built Game of Life / ${target.label}: ${(bytes.length / 1024).toFixed(1)}KB raw / ` +
      `${(target.gzipBytes / 1024).toFixed(1)}KB gzip`,
  );
}

const stage = join(repoRoot, "build", "tasks", "_game-of-life_dist");
const sharedCss = join(repoRoot, "samples", "game-of-life-shared", "styles.css");
rmSync(stage, { recursive: true, force: true });
mkdirSync(stage, { recursive: true });

const mountSnippet = `
    window.__mountMs = undefined;
    new MutationObserver(function (records, observer) {
      if (document.querySelector('[data-testid="life-grid"]')) {
        window.__mountMs = performance.now();
        observer.disconnect();
      }
    }).observe(document.documentElement, { childList: true, subtree: true });`;

for (const target of targets) {
  const targetDir = join(stage, target.id);
  mkdirSync(targetDir, { recursive: true });
  copyFileSync(target.bundle, join(targetDir, "main.js"));
  copyFileSync(sharedCss, join(targetDir, "styles.css"));
  writeFileSync(join(targetDir, "index.html"), `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="description" content="Conway's Game of Life implemented with ${target.label}.">
  <title>${target.label} · Conway’s Game of Life</title>
  <link rel="stylesheet" href="./styles.css">
  <script>${mountSnippet}
  </script>
</head>
<body>
  <div id="app"></div>
  <noscript>This demo needs JavaScript to evolve the universe.</noscript>
  <script type="module" src="./main.js"></script>
</body>
</html>
`);
}

writeFileSync(join(stage, "build-metadata.json"), `${JSON.stringify({
  generatedAt: new Date().toISOString(),
  implementations: Object.fromEntries(targets.map((target) => [target.id, {
    label: target.label,
    rawBytes: target.rawBytes,
    gzipBytes: target.gzipBytes,
  }])),
}, null, 2)}\n`);

const results = join(repoRoot, "bench", "results", "game-of-life", "results.json");
if (existsSync(results)) copyFileSync(results, join(stage, "results.json"));

const report = join(repoRoot, "bench", "game-of-life", "report.html");
if (existsSync(report)) cpSync(report, join(stage, "benchmark.html"));

console.log(`staged Game of Life apps -> ${stage}`);
