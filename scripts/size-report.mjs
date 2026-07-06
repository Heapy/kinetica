// Bundle-size (and optional build-time) tracking.
//
//   node scripts/size-report.mjs                     # report + compare against baseline
//   node scripts/size-report.mjs --update-baseline   # accept current sizes as the new baseline
//   node scripts/size-report.mjs --measure-build     # also time clean + incremental browser-bench builds
//
// Tracks the gzip size of Kinetica's shipped JS artifacts (and the bench bundles when
// present) against bench/size-baseline.json. Exits 1 when a tracked artifact's gzip
// size grows more than maxGrowthPct over its baseline — update the baseline in the
// same PR when the growth is intentional. Entries missing on disk are skipped, so the
// check works in CI stages that build only some artifacts.

import { execSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { gzipSync } from "node:zlib";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const baselinePath = join(repoRoot, "bench", "size-baseline.json");
const outPath = join(repoRoot, "bench", "results", "sizes.json");

const args = new Set(process.argv.slice(2));

// name -> { dir, pattern } (sums all matching files) or { file }
const TRACKED = {
  "kinetica/browser-bench-bundle": { file: "build/tasks/_browser-bench_bundle/browser-bench.bundle.mjs" },
  "kinetica/browser-bench-js": { dir: "build/tasks/_browser-bench_linkJs", pattern: /\.mjs$/ },
  "kinetica/docs-client-js": { dir: "build/tasks/_docs-client_linkJs", pattern: /\.mjs$/ },
  "bench/vanilla": { file: "bench/dist/vanilla/main.js" },
  "bench/react": { file: "bench/dist/react/main.js" },
  "bench/preact": { file: "bench/dist/preact/main.js" },
  "bench/vue": { file: "bench/dist/vue/main.js" },
  "bench/svelte": { file: "bench/dist/svelte/main.js" },
  "bench/vanilla-tree": { file: "bench/dist/vanilla-tree/main.js" },
  "bench/react-tree": { file: "bench/dist/react-tree/main.js" },
  "bench/preact-tree": { file: "bench/dist/preact-tree/main.js" },
  "bench/vue-tree": { file: "bench/dist/vue-tree/main.js" },
  "bench/svelte-tree": { file: "bench/dist/svelte-tree/main.js" },
};

function measure(spec) {
  if (spec.file) {
    const p = join(repoRoot, spec.file);
    if (!existsSync(p)) return null;
    const content = readFileSync(p);
    return { files: 1, rawBytes: content.length, gzipBytes: gzipSync(content).length };
  }
  const dir = join(repoRoot, spec.dir);
  if (!existsSync(dir)) return null;
  let files = 0;
  let rawBytes = 0;
  let gzipBytes = 0;
  const walk = (d) => {
    for (const name of readdirSync(d)) {
      const p = join(d, name);
      if (statSync(p).isDirectory()) {
        walk(p);
      } else if (spec.pattern.test(name)) {
        const content = readFileSync(p);
        files++;
        rawBytes += content.length;
        gzipBytes += gzipSync(content).length;
      }
    }
  };
  walk(dir);
  return files > 0 ? { files, rawBytes, gzipBytes } : null;
}

const kb = (b) => (b / 1024).toFixed(1);

const baseline = existsSync(baselinePath)
  ? JSON.parse(readFileSync(baselinePath, "utf8"))
  : { maxGrowthPct: 10, entries: {} };
const maxGrowthPct = baseline.maxGrowthPct ?? 10;

const sizes = {};
let failed = false;
let newEntries = 0;

console.log(`bundle sizes (gzip), baseline tolerance +${maxGrowthPct}%\n`);
for (const [name, spec] of Object.entries(TRACKED)) {
  const m = measure(spec);
  if (!m) {
    console.log(`  ${name.padEnd(30)} (not built, skipped)`);
    continue;
  }
  sizes[name] = m;
  const base = baseline.entries[name];
  let status;
  if (base === undefined) {
    status = "NEW (no baseline; add with --update-baseline)";
    newEntries++;
  } else {
    const deltaPct = (100 * (m.gzipBytes - base)) / base;
    const sign = deltaPct >= 0 ? "+" : "";
    if (deltaPct > maxGrowthPct) {
      status = `FAIL ${sign}${deltaPct.toFixed(1)}% over baseline ${kb(base)} KB`;
      failed = true;
    } else if (deltaPct < -5) {
      status = `${sign}${deltaPct.toFixed(1)}% (shrank; consider --update-baseline)`;
    } else {
      status = `${sign}${deltaPct.toFixed(1)}%`;
    }
  }
  console.log(
    `  ${name.padEnd(30)} ${kb(m.gzipBytes).padStart(8)} KB gz  ${kb(m.rawBytes).padStart(9)} KB raw  ${String(m.files).padStart(4)} file(s)  ${status}`,
  );
}

let buildTimes = null;
if (args.has("--measure-build")) {
  console.log("\nbuild times (browser-bench):");
  const time = (cmd) => {
    const start = Date.now();
    execSync(cmd, { cwd: repoRoot, stdio: "pipe" });
    return (Date.now() - start) / 1000;
  };
  execSync("./kotlin clean", { cwd: repoRoot, stdio: "pipe" });
  const cleanS = time("./kotlin build -m browser-bench");
  const incrementalS = time("./kotlin build -m browser-bench");
  buildTimes = { cleanS, incrementalS };
  console.log(`  clean build:       ${cleanS.toFixed(1)}s`);
  console.log(`  incremental no-op: ${incrementalS.toFixed(1)}s`);
}

writeFileSync(outPath, JSON.stringify({ date: new Date().toISOString(), sizes, buildTimes }, null, 2));
console.log(`\nsizes written to ${outPath}`);

if (args.has("--update-baseline")) {
  const entries = { ...baseline.entries };
  for (const [name, m] of Object.entries(sizes)) entries[name] = m.gzipBytes;
  writeFileSync(baselinePath, JSON.stringify({ maxGrowthPct, entries }, null, 2) + "\n");
  console.log(`baseline updated: ${baselinePath}`);
} else if (newEntries > 0) {
  console.log(`${newEntries} tracked artifact(s) have no baseline yet — run with --update-baseline to record them.`);
}

if (failed) {
  console.error(`\nsize check FAILED: an artifact grew more than ${maxGrowthPct}% over its baseline.`);
  console.error("If the growth is intentional, re-run with --update-baseline and commit bench/size-baseline.json.");
  process.exit(1);
}
