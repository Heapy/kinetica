import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const out = join(repoRoot, "build", "tasks", "_bench_dist");

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

const jsDist = join(repoRoot, "bench", "dist");
if (!existsSync(jsDist)) {
  throw new Error("bench/dist is missing — run `node bench/build.mjs` first.");
}
for (const name of readdirSync(jsDist)) {
  cpSync(join(jsDist, name), join(out, name), { recursive: true });
}

// Main and tree apps share each production bundle through the `?app=tree` switch.
const sharedCss = join(repoRoot, "bench", "frameworks", "shared", "styles.css");

function stageKotlinApp(name, title, bundlePath) {
  if (!existsSync(bundlePath)) {
    throw new Error(`${name} bundle missing at ${bundlePath} — run its bench/build-*.mjs first.`);
  }
  const dir = join(out, name);
  mkdirSync(dir, { recursive: true });
  cpSync(bundlePath, join(dir, "main.js"));
  cpSync(sharedCss, join(dir, "styles.css"));
  writeFileSync(
    join(dir, "index.html"),
    `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${title}</title>
  <link rel="stylesheet" href="./styles.css">
</head>
<body>
  <div id="main"></div>
  <script type="module" src="./main.js"></script>
</body>
</html>
`,
  );
}

stageKotlinApp(
  "kinetica",
  "Kinetica keyed benchmark",
  join(repoRoot, "build", "tasks", "_browser-bench_bundle", "browser-bench.bundle.mjs"),
);
stageKotlinApp(
  "compose-web",
  "Compose HTML keyed benchmark",
  join(repoRoot, "build", "tasks", "_browser-bench-compose_bundle", "browser-bench-compose.bundle.mjs"),
);

mkdirSync(join(out, "report"), { recursive: true });
cpSync(join(repoRoot, "bench", "report", "index.html"), join(out, "report", "index.html"));
cpSync(join(repoRoot, "bench", "report", "report.js"), join(out, "report", "report.js"));
const comparisonReport = join(repoRoot, "bench", "report", "comparison.html");
if (existsSync(comparisonReport)) {
  cpSync(comparisonReport, join(out, "report", "comparison.html"));
}

// Publish canonical accepted JSON only; versioned runs and profiles remain local.
const sourceResults = join(repoRoot, "bench", "results");
const publicResults = join(out, "results");
mkdirSync(publicResults, { recursive: true });
for (const file of [
  "results.json", "tree.json", "scaling.json", "throttled.json", "sizes.json",
  "comparison.json", "run.json", "accepted-run.json",
]) {
  const source = join(sourceResults, file);
  if (existsSync(source)) cpSync(source, join(publicResults, file));
}
const jvmResults = join(sourceResults, "jvm", "results.json");
if (existsSync(jvmResults)) {
  mkdirSync(join(publicResults, "jvm"), { recursive: true });
  cpSync(jvmResults, join(publicResults, "jvm", "results.json"));
}
let totalBytes = 0;
let totalFiles = 0;
const walk = (dir) => {
  for (const name of readdirSync(dir, { withFileTypes: true })) {
    const path = join(dir, name.name);
    if (name.isDirectory()) {
      walk(path);
    } else {
      totalFiles += 1;
      totalBytes += readFileSync(path).length;
    }
  }
};
walk(out);
console.log(`staged bench static assets -> ${out} (${totalFiles} files, ${(totalBytes / 1024).toFixed(1)}KB raw)`);
