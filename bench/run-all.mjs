// One-command pipeline: build everything, benchmark each framework SEQUENTIALLY
// (parallel runs would contend for CPU and skew timings), merge part files,
// generate the report page.
//
//   node run-all.mjs                            # everything, default settings
//   node run-all.mjs --frameworks=kinetica      # re-bench one framework, keep other parts
//   node run-all.mjs --samples=5 --warmup=1     # quicker, noisier
//   node run-all.mjs --skip-build               # reuse existing bundles
//   node run-all.mjs --report-only              # just merge existing parts + regenerate page
//
// Results: results/part-<name>.json per framework, merged results/results.json,
// page report/index.html. part-*-before.json files are kept out of the merge —
// they are historical snapshots used by the report's before/after section.

import { spawnSync } from "node:child_process";
import { existsSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { frameworks } from "./frameworks.config.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");

const args = Object.fromEntries(
  process.argv.slice(2).filter((a) => a.startsWith("--")).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k, v ?? "true"];
  }),
);

const selected = (args.frameworks ?? frameworks.map((f) => f.name).join(","))
  .split(",")
  .map((n) => frameworks.find((f) => f.name === n) ?? fail(`unknown framework: ${n}`))
  .filter(Boolean);

function fail(message) {
  console.error(`error: ${message}`);
  console.error(`known frameworks: ${frameworks.map((f) => f.name).join(", ")}`);
  process.exit(1);
}

function run(cmd, cmdArgs, cwd) {
  console.log(`\n$ ${cmd} ${cmdArgs.join(" ")}`);
  const result = spawnSync(cmd, cmdArgs, { cwd, stdio: "inherit" });
  if (result.status !== 0) {
    console.error(`error: '${cmd}' exited with ${result.status}`);
    process.exit(result.status ?? 1);
  }
}

if (!args["report-only"]) {
  // 1. builds: JS bundles once, plus each framework's own build step
  if (!args["skip-build"]) {
    run(process.execPath, [join(here, "build.mjs")], here);
    for (const fw of selected) {
      if (fw.build) run(fw.build.cmd, fw.build.args, repoRoot);
    }
  }

  // 2. benchmarks, one framework at a time
  for (const fw of selected) {
    const benchArgs = [
      join(here, "driver", "bench.mjs"),
      `--frameworks=${fw.name}`,
      `--out=${join(here, "results", `part-${fw.name}.json`)}`,
    ];
    if (args.samples) benchArgs.push(`--samples=${args.samples}`);
    if (args.warmup) benchArgs.push(`--warmup=${args.warmup}`);
    run(process.execPath, benchArgs, here);
  }
}

// 3. merge every part file (config order first, unknown parts appended; skip *-before.json)
const partsDir = join(here, "results");
const partFiles = readdirSync(partsDir)
  .filter((f) => /^part-.+\.json$/.test(f) && !f.endsWith("-before.json"))
  .sort((a, b) => {
    const index = (file) => {
      const i = frameworks.findIndex((fw) => file === `part-${fw.name}.json`);
      return i === -1 ? frameworks.length : i;
    };
    return index(a) - index(b) || a.localeCompare(b);
  })
  .map((f) => join(partsDir, f));
if (partFiles.length === 0) fail("no results/part-*.json files to merge");
run(process.execPath, [join(here, "driver", "merge.mjs"), join(partsDir, "results.json"), ...partFiles], here);

// 4. report
run(process.execPath, [join(here, "report", "generate.mjs")], here);

const reportPath = join(here, "report", "index.html");
console.log(`\ndone. merged ${partFiles.length} part file(s).`);
console.log(`report: ${reportPath}`);
console.log(`open with: open ${reportPath}`);
if (existsSync(join(partsDir, "part-kinetica-before.json"))) {
  console.log("(before/after section uses results/part-kinetica-before.json)");
}
