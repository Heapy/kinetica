// One-command pipeline: build everything, benchmark each framework SEQUENTIALLY
// (parallel runs would contend for CPU and skew timings), merge part files,
// generate the report page.
//
//   node run-all.mjs                            # everything, default settings
//   node run-all.mjs --frameworks=kinetica      # re-bench one framework, keep other parts
//   node run-all.mjs --samples=5 --warmup=1     # quicker, noisier
//   node run-all.mjs --skip-build               # reuse existing bundles
//   node run-all.mjs --report-only              # just merge existing parts + regenerate page
//   node run-all.mjs --tree                     # also run the deep-tree bench (driver/tree.mjs)
//   node run-all.mjs --scaling                  # also run scaling curves (driver/scaling.mjs)
//   node run-all.mjs --throttle=4               # 4x CPU-throttled pass -> results/throttled/
//   node run-all.mjs --skip-main --tree         # only the tree bench, keep main parts
//
// Results: results/part-<name>.json per framework, merged results/results.json,
// page report/index.html. Optional namespaces: results/throttled/part-*.json ->
// results/throttled.json, results/tree/part-*.json -> results/tree.json,
// results/scaling/part-*.json -> results/scaling.json. part-*-before.json files are
// kept out of the merge — they are historical snapshots used by the report's
// before/after section.

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

const throttle = Number(args.throttle ?? 0);

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

function passthrough(flags) {
  return flags.filter((f) => args[f] !== undefined).map((f) => `--${f}=${args[f]}`);
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
  if (!args["skip-main"]) {
    const partsDir = throttle > 1 ? join(here, "results", "throttled") : join(here, "results");
    for (const fw of selected) {
      const benchArgs = [
        join(here, "driver", "bench.mjs"),
        `--frameworks=${fw.name}`,
        `--out=${join(partsDir, `part-${fw.name}.json`)}`,
        ...passthrough(["samples", "warmup", "throttle", "anim-seconds"]),
      ];
      run(process.execPath, benchArgs, here);
    }
  }

  // 3. optional extra suites, still one framework at a time
  if (args.tree) {
    for (const fw of selected) {
      if (!fw.treeUrl) continue;
      run(process.execPath, [
        join(here, "driver", "tree.mjs"),
        `--frameworks=${fw.name}`,
        `--out=${join(here, "results", "tree", `part-${fw.name}.json`)}`,
        ...passthrough(["samples", "warmup", "throttle"]),
      ], here);
    }
  }
  if (args.scaling) {
    for (const fw of selected) {
      run(process.execPath, [
        join(here, "driver", "scaling.mjs"),
        `--frameworks=${fw.name}`,
        `--out=${join(here, "results", "scaling", `part-${fw.name}.json`)}`,
        ...passthrough(["sizes", "throttle", "strict"]),
      ], here);
    }
  }
}

// 4. merge every part file (config order first, unknown parts appended; skip *-before.json)
function mergeNamespace(dir, outFile) {
  if (!existsSync(dir)) return false;
  const partFiles = readdirSync(dir)
    .filter((f) => /^part-.+\.json$/.test(f) && !f.endsWith("-before.json"))
    .sort((a, b) => {
      const index = (file) => {
        const i = frameworks.findIndex((fw) => file === `part-${fw.name}.json`);
        return i === -1 ? frameworks.length : i;
      };
      return index(a) - index(b) || a.localeCompare(b);
    })
    .map((f) => join(dir, f));
  if (partFiles.length === 0) return false;
  run(process.execPath, [join(here, "driver", "merge.mjs"), outFile, ...partFiles], here);
  return true;
}

const resultsDir = join(here, "results");
if (!mergeNamespace(resultsDir, join(resultsDir, "results.json"))) {
  fail("no results/part-*.json files to merge");
}
mergeNamespace(join(resultsDir, "throttled"), join(resultsDir, "throttled.json"));
mergeNamespace(join(resultsDir, "tree"), join(resultsDir, "tree.json"));
mergeNamespace(join(resultsDir, "scaling"), join(resultsDir, "scaling.json"));

// 5. report
run(process.execPath, [join(here, "report", "generate.mjs")], here);

const reportPath = join(here, "report", "index.html");
console.log(`\ndone.`);
console.log(`report: ${reportPath}`);
console.log(`open with: open ${reportPath}`);
if (existsSync(join(resultsDir, "part-kinetica-before.json"))) {
  console.log("(before/after section uses results/part-kinetica-before.json)");
}
