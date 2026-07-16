#!/usr/bin/env node

// Unified benchmark orchestrator. It provisions project-local Node/Playwright
// dependencies, publishes the current compiler plugin once, builds selected apps,
// runs browser/JVM/size suites, merges browser parts, and regenerates the report.

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  cpSync,
  existsSync,
  mkdtempSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { arch, cpus, platform, tmpdir, totalmem } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { frameworks } from "./frameworks.config.mjs";
import { resolveVersion } from "./driver/common.mjs";
import { generateComparison, updatePerformanceDocs } from "./report/comparison.mjs";

const benchDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(benchDir, "..");
const kotlin = process.platform === "win32" ? "kotlin.bat" : "./kotlin";

process.on("uncaughtException", (error) => {
  console.error(`error: ${error.message}`);
  if (process.env.BENCH_DEBUG) console.error(error.stack);
  process.exit(1);
});

const SUITE_SCENARIOS = {
  main: [
    "01_run1k", "02_replace1k", "03_update10th1k", "04_select1k", "05_swap1k",
    "06_remove1k", "07_create10k", "08_append1k", "09_clear1k", "10_select10k",
    "11_swap10k", "12_remove10k", "13_update10th10k", "startup", "memory", "animation",
  ],
  tree: ["t1_createTree", "t2_updateLeaves", "t3_reverseTop", "t4_noopRender"],
  scaling: ["select", "swap", "update"],
  stress: ["select", "swap", "update"],
  extra: ["append1k", "remove1", "clear"],
  jvm: [
    "derived_fanout_10k", "derived_fanout_cached_read", "derived_chain_lazy_1k",
    "derived_chain_lazy_500", "derived_diamond_100", "render_create_1k",
    "render_update_1k", "markdown_parse_docs", "ssr_render_docs",
  ],
  size: ["size"],
  build: ["build"],
};
const ALL_SUITES = Object.keys(SUITE_SCENARIOS);
const DEFAULT_SUITES = ["main", "tree", "scaling", "jvm", "size", "build"];
const DEFAULT_BROWSER_SUITES = ["main", "tree", "scaling"];
const BROWSER_SUITES = [...DEFAULT_BROWSER_SUITES, "stress", "extra"];
const STRESS_FRAMEWORK_NAMES = ["kinetica", "react", "vanilla"];
const PROFILE_SCENARIOS = ["04_select1k", "06_remove1k", "08_append1k", "12_remove10k", "13_update10th10k"];

function parseArgs(argv) {
  const result = {};
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith("--")) throw new Error(`unexpected positional argument: ${arg}`);
    if (arg.startsWith("--no-")) {
      result[arg.slice(5)] = false;
      continue;
    }
    const eq = arg.indexOf("=");
    if (eq >= 0) {
      result[arg.slice(2, eq)] = arg.slice(eq + 1);
      continue;
    }
    const key = arg.slice(2);
    const next = argv[i + 1];
    if (next && !next.startsWith("--")) {
      result[key] = next;
      i++;
    } else {
      result[key] = true;
    }
  }
  return result;
}

function csv(value) {
  if (value === undefined || value === true || value === "") return [];
  return String(value).split(",").map((item) => item.trim()).filter(Boolean);
}

function numberOption(name, value, fallback, { min = 0 } = {}) {
  const number = value === undefined ? fallback : Number(value);
  if (!Number.isFinite(number) || number < min) {
    throw new Error(`--${name} must be a number >= ${min}, got '${value}'`);
  }
  return number;
}

function shellArg(value) {
  return /^[A-Za-z0-9_./:=,@+-]+$/.test(value) ? value : JSON.stringify(value);
}

function printHelp() {
  console.log(`Kinetica unified benchmark runner

Usage:
  node bench/run.mjs [options]

Selection:
  --suites=LIST          main,tree,scaling,stress,extra,jvm,size,build
                         default/all: main,tree,scaling,jvm,size,build
                         browser: main,tree,scaling (stress/extra are opt-in)
  --frameworks=LIST      browser frameworks (default: all)
  --filter=LIST          scenario filters; substring or '*' wildcard
                         scope with suite:, e.g. main:01_run1k,jvm:derived_*
  --list                 print suites, scenarios and frameworks

Common measurement options:
  --warmup=N             warmups for standard timed suites (default: 3)
  --iterations=N         samples for standard timed suites (default: 10)
  --samples=N            backward-compatible alias for --iterations
  --throttle=N           Chromium CPU throttle for browser suites
  --anim-seconds=N       sustained-animation duration (default: 6)
  --sizes=LIST           scaling row sizes (default: 1000,2000,5000,10000,20000)
  --stress-sizes=LIST    opt-in stress sizes (default: 10000,20000,50000,100000)
  --stress-warmup=N      stress warmups (default: 1)
  --stress-iterations=N  stress samples (default: 3)
  --reuse-from=SPEC      reuse compatible unselected browser results from latest, accepted, or a run directory
  --extra-size=N         ad-hoc append/remove/clear table size (default: 100000)
  --extra-warmup=N       ad-hoc mutation warmups (default: 0)
  --extra-iterations=N   ad-hoc mutation samples (default: 1)
  --row-sizes=LIST       enable JVM row scenarios, e.g. 10k,100k,1000k
  --strict               fail on superlinear scaling (size baseline is always enforced)
  --profile              capture V8 CPU profiles for selected main scenarios
                         using readable bundles for all selected browser frameworks
  --profile-interval-us=N  CPU sampling interval in microseconds (default: 100)
  --profile-top=N        functions retained per scenario in summary.json (default: 20)

Provisioning/output:
  --skip-install         require existing npm and Playwright dependencies
  --skip-build           reuse existing browser bundles
  --skip-plugin-publish  reuse the compiler plugin already in mavenLocal
  --with-deps            let Playwright install Chromium OS dependencies too
  --out-dir=PATH         exact results directory (default: a new bench/results/runs/<id>)
  --compare-to=SPEC      latest, accepted, git:<ref>, or a results directory
  --comparison-threshold=N  practical delta threshold in percent (default: 5)
  --allow-incompatible   allow promotion across a detected environment mismatch
  --accept               promote this run to the canonical bench/results artifacts
  --update-docs          accept a complete run and refresh performance.md/static assets
  --dry-run              validate and print the execution plan without changing anything
  --help                 show this help

Examples:
  node bench/run.mjs
  node bench/run.mjs --suites=main,jvm --frameworks=kinetica,react --warmup=2 --iterations=5
  node bench/run.mjs '--filter=main:07_create10k,main:animation,jvm:render_*'
  node bench/run.mjs --suites=tree,scaling --frameworks=kinetica --iterations=3
  node bench/run.mjs --suites=stress
  node bench/run.mjs --frameworks=kinetica --reuse-from=latest --compare-to=accepted
  node bench/run.mjs --suites=extra --frameworks=kinetica
  node bench/run.mjs --compare-to=latest
  node bench/run.mjs --compare-to=git:main
  node bench/run.mjs --suites=main --frameworks=kinetica,react --filter=main:12_remove10k --profile --iterations=3
  node bench/run.mjs --update-docs
`);
}

function printCatalog(rowSizes) {
  console.log("Suites and scenarios:");
  for (const suite of ALL_SUITES) {
    const scenarios = suite === "jvm"
      ? [...SUITE_SCENARIOS.jvm, ...jvmRowScenarios(rowSizes)]
      : SUITE_SCENARIOS[suite];
    const optIn = ["stress", "extra"].includes(suite) ? " (opt-in)" : "";
    console.log(`  ${suite.padEnd(8)} ${scenarios.join(", ")}${optIn}`);
  }
  console.log(`\nFrameworks:\n  ${frameworks.map((fw) => fw.name).join(", ")}`);
  console.log(`\nStress/extra frameworks:\n  ${STRESS_FRAMEWORK_NAMES.join(", ")}`);
}

function expandSuites(values) {
  const requested = values.length ? values : ["all"];
  const expanded = [];
  for (const value of requested) {
    const items = value === "all" ? DEFAULT_SUITES : value === "browser" ? DEFAULT_BROWSER_SUITES : [value];
    for (const item of items) {
      if (!ALL_SUITES.includes(item)) throw new Error(`unknown suite '${item}'`);
      if (!expanded.includes(item)) expanded.push(item);
    }
  }
  return expanded;
}

function formatRowSize(value) {
  const normalized = value.trim().toLowerCase();
  const match = normalized.match(/^(\d+)([km])?$/);
  if (!match) throw new Error(`invalid JVM row size '${value}'`);
  const multiplier = match[2] === "m" ? 1_000_000 : match[2] === "k" ? 1_000 : 1;
  const count = Number(match[1]) * multiplier;
  return count % 1000 === 0 ? `${count / 1000}k` : String(count);
}

function jvmRowScenarios(rowSizes) {
  return rowSizes.flatMap((value) => {
    const suffix = formatRowSize(value);
    return [
      `row_memo_hit_${suffix}`,
      `row_update10th_${suffix}`,
      `row_no_memo_hit_${suffix}`,
      `row_no_memo_update10th_${suffix}`,
    ];
  });
}

function wildcardMatch(value, pattern) {
  if (!pattern.includes("*")) return value.includes(pattern);
  const escaped = pattern.replace(/[.+?^${}()|[\]\\]/g, "\\$&").replaceAll("*", ".*");
  return new RegExp(`^${escaped}$`).test(value);
}

function selectScenarios(suite, available, filters) {
  if (filters.length === 0) return available;
  const patterns = filters.flatMap((filter) => {
    const colon = filter.indexOf(":");
    if (colon < 0) return [filter];
    return filter.slice(0, colon) === suite ? [filter.slice(colon + 1)] : [];
  });
  if (patterns.length === 0) return [];
  return available.filter((scenario) => patterns.some((pattern) => wildcardMatch(scenario, pattern)));
}

const raw = parseArgs(process.argv.slice(2));
if (raw.help) {
  printHelp();
  process.exit(0);
}

const rowSizes = csv(raw["row-sizes"]);
if (raw.list) {
  printCatalog(rowSizes);
  process.exit(0);
}

const iterationsValue = raw.iterations ?? raw.samples;
if (raw.iterations !== undefined && raw.samples !== undefined && String(raw.iterations) !== String(raw.samples)) {
  throw new Error("--iterations and --samples disagree; pass only one value");
}

const config = {
  warmup: numberOption("warmup", raw.warmup, 3),
  iterations: numberOption("iterations", iterationsValue, 10, { min: 1 }),
  throttle: numberOption("throttle", raw.throttle, 0),
  animSeconds: numberOption("anim-seconds", raw["anim-seconds"], 6, { min: 1 }),
  sizes: raw.sizes ?? "1000,2000,5000,10000,20000",
  stressSizes: raw["stress-sizes"] ?? "10000,20000,50000,100000",
  stressWarmup: numberOption("stress-warmup", raw["stress-warmup"], 1),
  stressIterations: numberOption("stress-iterations", raw["stress-iterations"], 3, { min: 1 }),
  extraSize: numberOption("extra-size", raw["extra-size"], 100000, { min: 1000 }),
  extraWarmup: numberOption("extra-warmup", raw["extra-warmup"], 0),
  extraIterations: numberOption("extra-iterations", raw["extra-iterations"], 1, { min: 1 }),
  strict: raw.strict === true || raw.strict === "true",
  install: raw.install !== false && !raw["skip-install"],
  build: raw.build !== false && !raw["skip-build"],
  publishPlugin: raw["plugin-publish"] !== false && !raw["skip-plugin-publish"],
  withDeps: raw["with-deps"] === true || raw["with-deps"] === "true",
  accept: raw.accept === true || raw.accept === "true" || raw["update-docs"] === true || raw["update-docs"] === "true",
  updateDocs: raw["update-docs"] === true || raw["update-docs"] === "true",
  allowIncompatible: raw["allow-incompatible"] === true || raw["allow-incompatible"] === "true",
  comparisonThreshold: numberOption("comparison-threshold", raw["comparison-threshold"], 5),
  profile: raw.profile === true || raw.profile === "true",
  profileIntervalUs: numberOption("profile-interval-us", raw["profile-interval-us"], 100, { min: 1 }),
  profileTop: numberOption("profile-top", raw["profile-top"], 20, { min: 1 }),
  dryRun: raw["dry-run"] === true || raw["dry-run"] === "true",
};

if (!Number.isInteger(config.stressWarmup)) throw new Error("--stress-warmup must be an integer");
if (!Number.isInteger(config.stressIterations)) throw new Error("--stress-iterations must be an integer");
if (!Number.isInteger(config.extraWarmup)) throw new Error("--extra-warmup must be an integer");
if (!Number.isInteger(config.extraIterations)) throw new Error("--extra-iterations must be an integer");
if (config.extraSize % 1000 !== 0) throw new Error("--extra-size must be a multiple of 1000");
for (const [option, values] of [["sizes", config.sizes], ["stress-sizes", config.stressSizes]]) {
  const parsedSizes = csv(values);
  if (parsedSizes.length === 0) throw new Error(`--${option} must contain at least one row size`);
  for (const size of parsedSizes) {
    const number = Number(size);
    if (!Number.isInteger(number) || number < 1000 || number % 1000 !== 0) {
      throw new Error(`invalid --${option} value '${size}'; sizes must be multiples of 1000`);
    }
  }
}
rowSizes.forEach(formatRowSize);

const requestedSuites = expandSuites(csv(raw.suites ?? raw.suite));
const filters = csv(raw.filter ?? raw.scenarios);
const availableBySuite = {
  ...SUITE_SCENARIOS,
  jvm: [...SUITE_SCENARIOS.jvm, ...jvmRowScenarios(rowSizes)],
};
const scenariosBySuite = Object.fromEntries(
  requestedSuites.map((suite) => [suite, selectScenarios(suite, availableBySuite[suite], filters)]),
);
const suites = requestedSuites.filter((suite) => scenariosBySuite[suite].length > 0);
if (suites.length === 0) {
  throw new Error(`no scenario matches --filter=${filters.join(",") || "<empty>"}`);
}

const requestedFrameworkNames = csv(raw.frameworks);
const selectedFrameworks = (requestedFrameworkNames.length ? requestedFrameworkNames : frameworks.map((fw) => fw.name))
  .map((name) => frameworks.find((fw) => fw.name === name) ?? (() => { throw new Error(`unknown framework '${name}'`); })());
const browserSuites = suites.filter((suite) => BROWSER_SUITES.includes(suite));
const stressFrameworks = selectedFrameworks.filter((framework) => STRESS_FRAMEWORK_NAMES.includes(framework.name));
if (suites.some((suite) => suite === "stress" || suite === "extra") && stressFrameworks.length === 0) {
  throw new Error(`stress and extra suites only support: ${STRESS_FRAMEWORK_NAMES.join(", ")}`);
}
const reuseFrom = raw["reuse-from"];
if (reuseFrom === true || reuseFrom === "") {
  throw new Error("--reuse-from requires 'latest', 'accepted', or a run directory");
}
if (reuseFrom !== undefined && browserSuites.length === 0) {
  throw new Error("--reuse-from requires at least one browser suite");
}
if (reuseFrom !== undefined && filters.length > 0) {
  throw new Error("--reuse-from requires unfiltered suites so cached parts have the same scenario coverage");
}
const frameworksBySuite = Object.fromEntries(browserSuites.map((suite) => [
  suite,
  suite === "stress" || suite === "extra" ? stressFrameworks : selectedFrameworks,
]));
const eligibleReusableFrameworksBySuite = Object.fromEntries(browserSuites.map((suite) => {
  const universe = suite === "stress" || suite === "extra"
    ? frameworks.filter((framework) => STRESS_FRAMEWORK_NAMES.includes(framework.name))
    : frameworks.filter((framework) => suite !== "tree" || framework.treeUrl);
  return [suite, reuseFrom === undefined
    ? []
    : universe.filter((framework) => !frameworksBySuite[suite].includes(framework))];
}));
if (reuseFrom !== undefined && !Object.values(eligibleReusableFrameworksBySuite).some((suiteFrameworks) => suiteFrameworks.length > 0)) {
  throw new Error("--reuse-from requires a partial framework selection, e.g. --frameworks=kinetica");
}
let reusableFrameworksBySuite = Object.fromEntries(browserSuites.map((suite) => [suite, []]));
let artifactFrameworksBySuite;
let artifactBrowserFrameworks;
function refreshArtifactFrameworkSelection() {
  artifactFrameworksBySuite = Object.fromEntries(Object.entries(frameworksBySuite).map(([suite, suiteFrameworks]) => [
    suite,
    frameworks.filter((framework) =>
      suiteFrameworks.includes(framework) || reusableFrameworksBySuite[suite].includes(framework),
    ),
  ]));
  artifactBrowserFrameworks = frameworks.filter((framework) =>
    Object.values(artifactFrameworksBySuite).some((suiteFrameworks) => suiteFrameworks.includes(framework)),
  );
}
refreshArtifactFrameworkSelection();
const executedBrowserFrameworks = frameworks.filter((framework) =>
  Object.values(frameworksBySuite).some((suiteFrameworks) => suiteFrameworks.includes(framework)),
);
const missingProfileRecipe = selectedFrameworks.filter((framework) => !framework.profile);
const skippedProfileFrameworks = selectedFrameworks.filter((framework) => framework.profile?.unsupported);
const profileFrameworks = selectedFrameworks.filter((framework) => framework.profile && !framework.profile.unsupported);
const profileScenarios = (scenariosBySuite.main ?? []).filter((scenario) => PROFILE_SCENARIOS.includes(scenario));
if (config.profile) {
  if (!suites.includes("main")) throw new Error("--profile requires --suites to include main");
  if (missingProfileRecipe.length) {
    throw new Error(`frameworks missing an explicit profiling recipe: ${missingProfileRecipe.map((framework) => framework.name).join(", ")}`);
  }
  if (profileFrameworks.length === 0) throw new Error("none of the selected frameworks supports browser profiling");
  if (profileScenarios.length === 0) {
    throw new Error(`no profile-capable scenario matches the main filter; choose one of: ${PROFILE_SCENARIOS.join(", ")}`);
  }
}
const acceptedResultsDir = join(benchDir, "results");
const runsDir = join(acceptedResultsDir, "runs");
const runId = new Date().toISOString().replace(/[-:.]/g, "");
const resultsDir = resolve(repoRoot, raw["out-dir"] ?? join("bench", "results", "runs", runId));
const playwrightDir = join(repoRoot, ".tools", "playwright");
const playwrightImport = join(playwrightDir, "node_modules", "playwright", "index.mjs");
const playwrightCli = join(playwrightDir, "node_modules", "playwright", "cli.js");
const browsersDir = join(repoRoot, ".playwright-browsers");
const baseEnv = {
  ...process.env,
  PLAYWRIGHT_IMPORT: playwrightImport,
  PLAYWRIGHT_BROWSERS_PATH: browsersDir,
};

function run(command, args, { cwd = repoRoot, env = baseEnv, capture = false } = {}) {
  console.log(`$ ${shellArg(command)} ${args.map(shellArg).join(" ")}`);
  if (config.dryRun) return { status: 0, stdout: "" };
  const result = spawnSync(command, args, {
    cwd,
    env,
    encoding: capture ? "utf8" : undefined,
    stdio: capture ? ["ignore", "pipe", "inherit"] : "inherit",
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`command exited with ${result.status}: ${command}`);
  return result;
}

function ensureCommand(command) {
  if (config.dryRun) return;
  const result = spawnSync(command, ["--version"], { cwd: repoRoot, env: baseEnv, stdio: "ignore" });
  if (result.status !== 0) throw new Error(`required command not found: ${command}`);
}

function ensureBenchDependencies() {
  ensureCommand("npm");
  const lockPath = join(benchDir, "package-lock.json");
  const fingerprint = createHash("sha256").update(readFileSync(lockPath)).digest("hex");
  const markerPath = join(repoRoot, ".npm-cache", "bench-package-lock.sha256");
  const installed = existsSync(join(benchDir, "node_modules", "esbuild", "package.json"));
  const current = existsSync(markerPath) && readFileSync(markerPath, "utf8").trim() === fingerprint;
  if (!installed || !current) {
    if (!config.install) {
      if (!installed) throw new Error("bench npm dependencies are missing; remove --skip-install");
      console.log("! reusing bench npm dependencies without validating package-lock (--skip-install)");
    } else {
      run("npm", ["ci", "--prefix", benchDir, "--cache", join(repoRoot, ".npm-cache"), "--prefer-offline", "--no-audit", "--no-fund"]);
      if (!config.dryRun) {
        mkdirSync(dirname(markerPath), { recursive: true });
        writeFileSync(markerPath, `${fingerprint}\n`);
      }
    }
  } else {
    console.log("✓ bench npm dependencies are current");
  }

  if (!existsSync(playwrightImport)) {
    if (!config.install) throw new Error("Playwright is missing; remove --skip-install");
    run("npm", ["install", "--prefix", playwrightDir, "--no-save", "--no-audit", "--no-fund", "playwright@1.61.1"]);
  } else {
    console.log("✓ Playwright package is installed");
  }

  if (config.dryRun) {
    console.log(`$ ${process.execPath} ${playwrightCli} install${config.withDeps ? " --with-deps" : ""} chromium  # if missing`);
    return;
  }
  const probeScript = `import { chromium } from ${JSON.stringify(pathToFileURL(playwrightImport).href)}; console.log(chromium.executablePath());`;
  const probe = spawnSync(process.execPath, ["--input-type=module", "-e", probeScript], {
    cwd: repoRoot,
    env: baseEnv,
    encoding: "utf8",
  });
  const executable = probe.status === 0 ? probe.stdout.trim() : "";
  if (!executable || !existsSync(executable)) {
    if (!config.install) throw new Error("Playwright Chromium is missing; remove --skip-install");
    run(process.execPath, [playwrightCli, "install", ...(config.withDeps ? ["--with-deps"] : []), "chromium"]);
  } else {
    console.log(`✓ Chromium is installed: ${executable}`);
  }
}

function mergeNamespace(dir, outFile, { expectedFrameworks = frameworks, preferredMetaFramework = null } = {}) {
  if (config.dryRun) {
    const inputs = expectedFrameworks.map((framework) => join(dir, `part-${framework.name}.json`));
    console.log(`$ merge ${inputs.join(", ")} -> ${outFile}`);
    return true;
  }
  if (!existsSync(dir)) return false;
  const registeredParts = new Set(expectedFrameworks.map((framework) => `part-${framework.name}.json`));
  const parts = readdirSync(dir)
    .filter((file) => registeredParts.has(file))
    .sort((a, b) => {
      const rank = (file) => {
        if (preferredMetaFramework && file === `part-${preferredMetaFramework.name}.json`) return -1;
        const index = frameworks.findIndex((fw) => file === `part-${fw.name}.json`);
        return index < 0 ? frameworks.length : index;
      };
      return rank(a) - rank(b) || a.localeCompare(b);
    })
    .map((file) => join(dir, file));
  if (parts.length === 0) return false;
  const benchmarkLists = ["benchmarks", "treeBenchmarks"];
  const merged = { meta: null };
  for (const part of parts) {
    const data = JSON.parse(readFileSync(part, "utf8"));
    if (!merged.meta) {
      merged.meta = data.meta;
    } else if (data.meta?.versions) {
      Object.assign(merged.meta.versions ??= {}, data.meta.versions);
    }
    for (const key of benchmarkLists) {
      if (!Array.isArray(data[key])) continue;
      merged[key] ??= [];
      for (const benchmark of data[key]) {
        if (!merged[key].some((existing) => existing.id === benchmark.id)) merged[key].push(benchmark);
      }
    }
    for (const [key, value] of Object.entries(data)) {
      if (key === "meta" || benchmarkLists.includes(key)) continue;
      if (value && typeof value === "object" && !Array.isArray(value)) {
        merged[key] = Object.assign(merged[key] ?? {}, value);
      }
    }
  }
  for (const key of benchmarkLists) merged[key]?.sort((a, b) => a.id.localeCompare(b.id));
  mkdirSync(dirname(outFile), { recursive: true });
  writeFileSync(outFile, JSON.stringify(merged, null, 2));
  console.log(`merged ${parts.length} parts -> ${outFile}`);
  return true;
}

function latestCompletedRun({
  completeOnly = false,
  requiredSuites = [],
  requiredFrameworksBySuite = {},
  anyFrameworksBySuite = {},
} = {}) {
  if (!existsSync(runsDir)) return null;
  return readdirSync(runsDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory())
    .map((entry) => join(runsDir, entry.name))
    .filter((dir) => resolve(dir) !== resultsDir && existsSync(join(dir, "run.json")))
    .filter((dir) => {
      const manifest = JSON.parse(readFileSync(join(dir, "run.json"), "utf8"));
      if (manifest.status !== "complete") return false;
      if (!requiredSuites.every((suite) => manifest.suites?.includes(suite))) return false;
      for (const [suite, requiredFrameworks] of Object.entries(requiredFrameworksBySuite)) {
        const availableFrameworks = manifest.frameworksBySuite?.[suite] ?? manifest.frameworks ?? [];
        if (!requiredFrameworks.every((framework) => availableFrameworks.includes(framework))) return false;
      }
      for (const [suite, candidateFrameworks] of Object.entries(anyFrameworksBySuite)) {
        const availableFrameworks = manifest.frameworksBySuite?.[suite] ?? manifest.frameworks ?? [];
        if (candidateFrameworks.length > 0 && !candidateFrameworks.some((framework) => availableFrameworks.includes(framework))) {
          return false;
        }
      }
      return !completeOnly || (
        DEFAULT_SUITES.every((suite) => manifest.suites?.includes(suite)) &&
        frameworks.every((framework) => manifest.frameworks?.includes(framework.name))
      );
    })
    .sort()
    .at(-1) ?? null;
}

function resolveBaselineSpec(spec) {
  if (!spec) return null;
  if (spec === "latest") {
    const dir = latestCompletedRun({
      completeOnly: config.updateDocs,
      requiredSuites: config.updateDocs ? DEFAULT_SUITES : suites,
      requiredFrameworksBySuite: config.updateDocs ? {} : Object.fromEntries(
        Object.entries(artifactFrameworksBySuite).map(([suite, suiteFrameworks]) => [
          suite,
          suiteFrameworks.map((framework) => framework.name),
        ]),
      ),
    });
    if (dir) return { kind: "directory", dir, label: `latest (${dir.split("/").at(-1)})` };
    if (config.updateDocs && existsSync(join(acceptedResultsDir, "results.json"))) {
      return { kind: "directory", dir: acceptedResultsDir, label: "accepted (no complete versioned run yet)" };
    }
    return null;
  }
  if (spec === "accepted") {
    if (!existsSync(join(acceptedResultsDir, "results.json"))) return null;
    return { kind: "directory", dir: acceptedResultsDir, label: "accepted" };
  }
  if (spec.startsWith("git:")) {
    const ref = spec.slice(4);
    if (!ref) throw new Error("--compare-to=git:<ref> requires a git ref");
    return { kind: "git", ref, label: spec };
  }
  const dir = resolve(repoRoot, spec);
  if (!existsSync(dir)) throw new Error(`comparison baseline directory does not exist: ${dir}`);
  return { kind: "directory", dir, label: spec };
}

const PART_SUITE_DIRS = {
  main: "",
  tree: "tree",
  scaling: "scaling",
  stress: "stress",
};

function suitePartPath(root, suite, framework) {
  const subdir = PART_SUITE_DIRS[suite];
  if (subdir === undefined) throw new Error(`suite '${suite}' does not use per-framework parts`);
  const effectiveSubdir = suite === "main" && config.throttle > 1 ? "throttled" : subdir;
  return join(root, effectiveSubdir, `part-${framework}.json`);
}

function suiteMergedPath(root, suite) {
  return join(root, suite === "main"
    ? config.throttle > 1 ? "throttled.json" : "results.json"
    : `${suite}.json`);
}

function normalizeReusablePart(suite, framework, data, sourcePath) {
  const meta = {
    ...(data.meta ?? {}),
    ...(data.meta?.versions ? { versions: { [framework]: data.meta.versions[framework] } } : {}),
  };
  if (suite === "main") {
    if (!data.results?.[framework]) throw new Error(`${sourcePath} has no main results for ${framework}`);
    return {
      meta,
      benchmarks: data.benchmarks ?? [],
      results: { [framework]: data.results[framework] },
      ...(data.startup?.[framework] ? { startup: { [framework]: data.startup[framework] } } : {}),
      ...(data.memory?.[framework] ? { memory: { [framework]: data.memory[framework] } } : {}),
      ...(data.animation?.[framework] ? { animation: { [framework]: data.animation[framework] } } : {}),
    };
  }
  if (suite === "tree") {
    if (!data.tree?.[framework]) throw new Error(`${sourcePath} has no tree results for ${framework}`);
    return {
      meta,
      treeBenchmarks: data.treeBenchmarks ?? [],
      tree: { [framework]: data.tree[framework] },
    };
  }
  if (suite === "scaling" || suite === "stress") {
    if (!data.scaling?.[framework]) throw new Error(`${sourcePath} has no ${suite} results for ${framework}`);
    return { meta, scaling: { [framework]: data.scaling[framework] } };
  }
  throw new Error(`cannot normalize reusable suite '${suite}'`);
}

function validateReusableCoverage(suite, framework, data, sourcePath) {
  if (suite === "main") {
    const benchmarkIds = new Set(data.benchmarks?.map((benchmark) => benchmark.id) ?? []);
    for (const scenario of SUITE_SCENARIOS.main) {
      if (["startup", "memory", "animation"].includes(scenario)) {
        if (!data[scenario]?.[framework]) throw new Error(`${sourcePath} is missing ${scenario} for ${framework}`);
      } else if (!benchmarkIds.has(scenario) || !data.results?.[framework]?.[scenario]) {
        throw new Error(`${sourcePath} is missing main:${scenario} for ${framework}`);
      }
    }
    return;
  }
  if (suite === "tree") {
    const benchmarkIds = new Set(data.treeBenchmarks?.map((benchmark) => benchmark.id) ?? []);
    for (const scenario of SUITE_SCENARIOS.tree) {
      if (!benchmarkIds.has(scenario) || !data.tree?.[framework]?.[scenario]) {
        throw new Error(`${sourcePath} is missing tree:${scenario} for ${framework}`);
      }
    }
    return;
  }
  if (suite === "scaling" || suite === "stress") {
    for (const scenario of SUITE_SCENARIOS[suite]) {
      if (!data.scaling?.[framework]?.ops?.[scenario]) {
        throw new Error(`${sourcePath} is missing ${suite}:${scenario} for ${framework}`);
      }
    }
  }
}

function loadReusablePart(root, suite, framework) {
  const directPath = suitePartPath(root, suite, framework);
  const mergedPath = suiteMergedPath(root, suite);
  const sourcePath = existsSync(mergedPath) ? mergedPath : directPath;
  if (!existsSync(sourcePath)) {
    throw new Error(`reuse source is missing ${suite} results for ${framework}: ${sourcePath}`);
  }
  const normalized = normalizeReusablePart(
    suite,
    framework,
    JSON.parse(readFileSync(sourcePath, "utf8")),
    sourcePath,
  );
  validateReusableCoverage(suite, framework, normalized, sourcePath);
  return { sourcePath, data: normalized };
}

function findReusableExtra(root, frameworkNames) {
  const dir = join(root, "extra-ops");
  if (!existsSync(dir)) throw new Error(`reuse source has no extra-ops directory: ${dir}`);
  const candidates = readdirSync(dir)
    .filter((file) => file.endsWith(".json"))
    .sort()
    .reverse();
  for (const file of candidates) {
    const sourcePath = join(dir, file);
    const data = JSON.parse(readFileSync(sourcePath, "utf8"));
    if (data.meta?.size !== config.extraSize) continue;
    if (frameworkNames.every((framework) => data.results?.[framework])) {
      for (const framework of frameworkNames) {
        for (const scenario of SUITE_SCENARIOS.extra) {
          if (!data.results[framework]?.[scenario]) {
            throw new Error(`${sourcePath} is missing extra:${scenario} for ${framework}`);
          }
        }
      }
      return { sourcePath, data };
    }
  }
  throw new Error(`reuse source has no extra results at size ${config.extraSize} for ${frameworkNames.join(",")}`);
}

function sourceFrameworkNames(root, suite, manifest) {
  const declared = manifest?.frameworksBySuite?.[suite] ??
    (manifest?.suites?.includes(suite) ? manifest.frameworks : null);
  if (Array.isArray(declared)) return declared;

  if (suite === "extra") {
    const dir = join(root, "extra-ops");
    if (!existsSync(dir)) return [];
    return readdirSync(dir)
      .filter((file) => file.endsWith(".json"))
      .map((file) => JSON.parse(readFileSync(join(dir, file), "utf8")))
      .filter((data) => data.meta?.size === config.extraSize)
      .map((data) => Object.keys(data.results ?? {}))
      .sort((a, b) => b.length - a.length)
      .at(0) ?? [];
  }

  const names = new Set();
  for (const framework of frameworks) {
    if (existsSync(suitePartPath(root, suite, framework.name))) names.add(framework.name);
  }
  const mergedPath = suiteMergedPath(root, suite);
  if (existsSync(mergedPath)) {
    const data = JSON.parse(readFileSync(mergedPath, "utf8"));
    const resultMap = suite === "main" ? data.results
      : suite === "tree" ? data.tree : data.scaling;
    for (const name of Object.keys(resultMap ?? {})) names.add(name);
  }
  return [...names];
}

function resolveReuseSource(spec) {
  if (spec === undefined) return null;
  let dir;
  let label;
  if (spec === "latest") {
    const requiredSuites = browserSuites.filter((suite) => eligibleReusableFrameworksBySuite[suite].length > 0);
    dir = latestCompletedRun({
      requiredSuites,
      anyFrameworksBySuite: Object.fromEntries(requiredSuites.map((suite) => [
        suite,
        eligibleReusableFrameworksBySuite[suite].map((framework) => framework.name),
      ])),
    });
    if (!dir) throw new Error("no completed compatible-scope run is available for --reuse-from=latest");
    label = `latest (${dir.split("/").at(-1)})`;
  } else if (spec === "accepted") {
    dir = acceptedResultsDir;
    label = "accepted";
  } else {
    if (String(spec).startsWith("git:")) {
      throw new Error("--reuse-from does not support git refs; use latest, accepted, or a results directory");
    }
    dir = resolve(repoRoot, String(spec));
    label = String(spec);
  }
  if (!existsSync(dir)) throw new Error(`reuse source directory does not exist: ${dir}`);
  if (resolve(dir) === resultsDir) throw new Error("reuse source and --out-dir must be different directories");
  const manifestPath = join(dir, "run.json");
  const manifest = existsSync(manifestPath) ? JSON.parse(readFileSync(manifestPath, "utf8")) : null;
  if (manifest?.status && manifest.status !== "complete") {
    throw new Error(`reuse source is not complete: ${manifestPath} has status '${manifest.status}'`);
  }
  reusableFrameworksBySuite = Object.fromEntries(browserSuites.map((suite) => {
    const available = new Set(sourceFrameworkNames(dir, suite, manifest));
    return [suite, eligibleReusableFrameworksBySuite[suite].filter((framework) => available.has(framework.name))];
  }));
  const missingCacheSuites = browserSuites.filter((suite) =>
    eligibleReusableFrameworksBySuite[suite].length > 0 && reusableFrameworksBySuite[suite].length === 0,
  );
  if (missingCacheSuites.length > 0) {
    throw new Error(`reuse source has no unselected framework results for: ${missingCacheSuites.join(", ")}`);
  }
  refreshArtifactFrameworkSelection();
  const inputs = {};
  for (const suite of browserSuites) {
    const reused = reusableFrameworksBySuite[suite].map((framework) => framework.name);
    if (reused.length === 0) continue;
    if (suite === "extra") {
      inputs.extra = findReusableExtra(dir, reused);
    } else {
      inputs[suite] = Object.fromEntries(reused.map((framework) => [
        framework,
        loadReusablePart(dir, suite, framework),
      ]));
    }
  }
  const mainMetaPath = join(dir, config.throttle > 1 ? "throttled.json" : "results.json");
  const mainMeta = existsSync(mainMetaPath) ? JSON.parse(readFileSync(mainMetaPath, "utf8")).meta : null;
  return {
    dir,
    label,
    manifest,
    manifestPath: existsSync(manifestPath) ? manifestPath : null,
    verification: manifest?.status === "complete" ? "manifest-complete" : "artifact-metadata-only",
    inputs,
    mainMeta,
  };
}

function reuseComparableValue(value) {
  if (value === undefined || value === null) return "<missing>";
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

function currentSuiteData(suite) {
  if (suite === "extra") {
    const path = join(resultsDir, "extra-ops", `results-${config.extraSize}.json`);
    return { path, data: JSON.parse(readFileSync(path, "utf8")) };
  }
  const measured = frameworksBySuite[suite]?.[0];
  if (!measured) throw new Error(`no freshly measured framework is available for ${suite}`);
  const path = suitePartPath(resultsDir, suite, measured.name);
  return { path, data: JSON.parse(readFileSync(path, "utf8")) };
}

function sourceFrameworkVersion(framework, sourceData) {
  return sourceData.meta?.versions?.[framework.name] ??
    reuseDefinition?.manifest?.frameworkVersions?.[framework.name] ??
    reuseDefinition?.mainMeta?.versions?.[framework.name] ??
    null;
}

function artifactFrameworkVersion(framework) {
  if (executedBrowserFrameworks.includes(framework)) return resolveVersion(framework.version);
  for (const suite of browserSuites) {
    if (!reusableFrameworksBySuite[suite]?.includes(framework)) continue;
    const source = suite === "extra"
      ? reuseDefinition?.inputs.extra
      : reuseDefinition?.inputs[suite]?.[framework.name];
    if (source) return sourceFrameworkVersion(framework, source.data);
  }
  return null;
}

function reuseSourceEntries(suite) {
  const reused = reusableFrameworksBySuite[suite] ?? [];
  return suite === "extra"
    ? reused.map((framework) => ({ framework, source: reuseDefinition.inputs.extra }))
    : reused.map((framework) => ({ framework, source: reuseDefinition.inputs[suite][framework.name] }));
}

function assertReuseCompatibility(compatibility) {
  if (compatibility.compatible) return;
  const details = compatibility.mismatches.slice(0, 10)
    .map((check) => `${check.scope}.${check.field}: cached=${check.source}, requested=${check.current}`)
    .join("; ");
  throw new Error(`cached framework results are incompatible (${details}); choose a matching completed run`);
}

function probeChromiumVersion() {
  if (config.dryRun) return null;
  const script = [
    `import { chromium } from ${JSON.stringify(pathToFileURL(playwrightImport).href)};`,
    "const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE || undefined;",
    "const browser = await chromium.launch({ headless: true, executablePath });",
    "console.log(browser.version());",
    "await browser.close();",
  ].join("\n");
  const probe = spawnSync(process.execPath, ["--input-type=module", "-e", script], {
    cwd: repoRoot,
    env: baseEnv,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  if (probe.status !== 0) {
    throw new Error(`could not probe Chromium version for cache validation: ${probe.stderr.trim() || `exit ${probe.status}`}`);
  }
  return probe.stdout.trim();
}

function validateReuseSourceBeforeRun(chromiumVersion) {
  if (!reuseDefinition) return null;
  const checks = [];
  const warnings = [];
  const currentMachine = {
    cpu: cpus()[0]?.model,
    arch: arch(),
    platform: platform(),
    memGb: Math.round(totalmem() / 1073741824),
  };
  const addCheck = (scope, field, currentValue, sourceValue) => {
    const current = reuseComparableValue(currentValue);
    const source = reuseComparableValue(sourceValue);
    checks.push({ scope, field, current, source, matches: current === source });
  };

  for (const suite of browserSuites) {
    for (const { framework, source } of reuseSourceEntries(suite)) {
      const scope = `${suite}:${framework.name}`;
      const sourceMeta = source.data.meta ?? {};
      const sourceMachine = sourceMeta.machine ?? reuseDefinition.mainMeta?.machine ?? {};
      const standardTiming = suite === "main" || suite === "tree" || suite === "scaling";
      const expectedWarmup = standardTiming ? config.warmup
        : suite === "stress" ? config.stressWarmup : config.extraWarmup;
      const expectedSamples = standardTiming ? config.iterations
        : suite === "stress" ? config.stressIterations : config.extraIterations;

      addCheck(scope, "machine.cpu", currentMachine.cpu, sourceMachine.cpu);
      addCheck(scope, "machine.arch", currentMachine.arch, sourceMachine.arch);
      addCheck(scope, "machine.platform", currentMachine.platform, sourceMachine.platform);
      addCheck(scope, "machine.memGb", currentMachine.memGb, sourceMachine.memGb);
      if (chromiumVersion) addCheck(scope, "chromium", chromiumVersion, sourceMeta.chromium);
      addCheck(scope, "cpuThrottle", config.throttle > 1 ? config.throttle : null, sourceMeta.cpuThrottle ?? null);
      addCheck(scope, "warmup", expectedWarmup, sourceMeta.warmup);
      addCheck(scope, "samples", expectedSamples, sourceMeta.samples);
      if (suite === "scaling") addCheck(scope, "sizes", csv(config.sizes).map(Number), sourceMeta.sizes);
      if (suite === "stress") addCheck(scope, "sizes", csv(config.stressSizes).map(Number), sourceMeta.sizes);
      if (suite === "extra") addCheck(scope, "size", config.extraSize, sourceMeta.size);
      if (suite === "main") {
        addCheck(scope, "animation.seconds", config.animSeconds, source.data.animation?.[framework.name]?.seconds);
      }

      const cachedVersion = sourceFrameworkVersion(framework, source.data);
      if (cachedVersion == null) {
        warnings.push(`${scope}: cached framework version was not recorded`);
      } else if (!config.dryRun) {
        addCheck(scope, "frameworkVersion", resolveVersion(framework.version), cachedVersion);
      }
    }
  }

  const mismatches = checks.filter((check) => !check.matches);
  const compatibility = {
    stage: "preflight",
    chromiumChecked: Boolean(chromiumVersion),
    compatible: mismatches.length === 0,
    checks,
    mismatches,
    warnings,
  };
  assertReuseCompatibility(compatibility);
  const verificationNote = reuseDefinition.verification === "manifest-complete"
    ? ""
    : " (source has no complete manifest; verified from artifact metadata)";
  console.log(
    `✓ cache preflight passed for ${reuseDefinition.label}` +
    verificationNote +
    `${warnings.length ? ` (${warnings.length} provenance warning${warnings.length === 1 ? "" : "s"})` : ""}`,
  );
  return compatibility;
}

function materializeReusedBrowserResults() {
  if (!reuseDefinition) return null;
  if (config.dryRun) {
    return {
      source: reuseDefinition.label,
      sourceDir: reuseDefinition.dir,
      sourceManifest: reuseDefinition.manifestPath,
      verification: reuseDefinition.verification,
      measuredFrameworksBySuite: Object.fromEntries(Object.entries(frameworksBySuite).map(([suite, suiteFrameworks]) => [
        suite,
        suiteFrameworks.map((framework) => framework.name),
      ])),
      reusedFrameworksBySuite: Object.fromEntries(Object.entries(reusableFrameworksBySuite).map(([suite, suiteFrameworks]) => [
        suite,
        suiteFrameworks.map((framework) => framework.name),
      ])),
      compatibility: reusePreflight,
      warnings: reusePreflight?.warnings ?? [],
    };
  }
  const checks = [];
  const warnings = [];
  const currentMachine = {
    cpu: cpus()[0]?.model,
    arch: arch(),
    platform: platform(),
    memGb: Math.round(totalmem() / 1073741824),
  };
  const addCheck = (scope, field, currentValue, sourceValue) => {
    const current = reuseComparableValue(currentValue);
    const source = reuseComparableValue(sourceValue);
    checks.push({ scope, field, current, source, matches: current === source });
  };

  for (const suite of browserSuites) {
    const reused = reusableFrameworksBySuite[suite];
    if (reused.length === 0) continue;
    const current = currentSuiteData(suite);
    const sourceEntries = reuseSourceEntries(suite);

    for (const { framework, source } of sourceEntries) {
      const scope = `${suite}:${framework.name}`;
      const currentMeta = current.data.meta ?? {};
      const sourceMeta = source.data.meta ?? {};
      const sourceMachine = sourceMeta.machine ?? reuseDefinition.mainMeta?.machine ?? {};
      addCheck(scope, "machine.cpu", currentMeta.machine?.cpu ?? currentMachine.cpu, sourceMachine.cpu);
      addCheck(scope, "machine.arch", currentMeta.machine?.arch ?? currentMachine.arch, sourceMachine.arch);
      addCheck(scope, "machine.platform", currentMeta.machine?.platform ?? currentMachine.platform, sourceMachine.platform);
      addCheck(scope, "machine.memGb", currentMeta.machine?.memGb ?? currentMachine.memGb, sourceMachine.memGb);
      addCheck(scope, "chromium", currentMeta.chromium, sourceMeta.chromium);
      addCheck(scope, "cpuThrottle", currentMeta.cpuThrottle ?? "none", sourceMeta.cpuThrottle ?? "none");
      addCheck(scope, "warmup", currentMeta.warmup, sourceMeta.warmup);
      addCheck(scope, "samples", currentMeta.samples, sourceMeta.samples);
      if (suite === "scaling" || suite === "stress") {
        addCheck(scope, "sizes", currentMeta.sizes, sourceMeta.sizes);
      }
      if (suite === "tree") addCheck(scope, "totalNodes", currentMeta.totalNodes, sourceMeta.totalNodes);
      if (suite === "extra") addCheck(scope, "size", currentMeta.size, sourceMeta.size);
      if (suite === "main") {
        addCheck(scope, "methodology", currentMeta.methodology, sourceMeta.methodology);
        addCheck(scope, "benchmarks", current.data.benchmarks, source.data.benchmarks);
      }
      if (suite === "tree") addCheck(scope, "benchmarks", current.data.treeBenchmarks, source.data.treeBenchmarks);
      if (suite === "scaling" || suite === "stress") {
        const currentOps = current.data.scaling?.[frameworksBySuite[suite][0]?.name]?.ops ?? {};
        const sourceOps = source.data.scaling?.[framework.name]?.ops ?? {};
        for (const scenario of SUITE_SCENARIOS[suite]) {
          addCheck(scope, `${scenario}.label`, currentOps[scenario]?.label, sourceOps[scenario]?.label);
          addCheck(scope, `${scenario}.threshold`, currentOps[scenario]?.threshold, sourceOps[scenario]?.threshold);
        }
      }
      if (suite === "extra") {
        for (const scenario of SUITE_SCENARIOS.extra) {
          addCheck(
            scope,
            `${scenario}.label`,
            current.data.results?.[frameworksBySuite.extra[0]?.name]?.[scenario]?.label,
            source.data.results?.[framework.name]?.[scenario]?.label,
          );
        }
      }
      if (suite === "main") {
        addCheck(
          scope,
          "animation.seconds",
          current.data.animation?.[frameworksBySuite.main[0]?.name]?.seconds,
          source.data.animation?.[framework.name]?.seconds,
        );
      }

      const cachedVersion = sourceFrameworkVersion(framework, source.data);
      if (cachedVersion == null) {
        warnings.push(`${scope}: cached framework version was not recorded`);
      } else {
        addCheck(scope, "frameworkVersion", resolveVersion(framework.version), cachedVersion);
      }
    }
  }

  const mismatches = checks.filter((check) => !check.matches);
  const compatibility = { compatible: mismatches.length === 0, checks, mismatches };
  assertReuseCompatibility(compatibility);

  for (const suite of browserSuites) {
    const reused = reusableFrameworksBySuite[suite];
    if (reused.length === 0) continue;
    if (suite === "extra") {
      const current = currentSuiteData(suite);
      for (const framework of reused) {
        current.data.results[framework.name] = reuseDefinition.inputs.extra.data.results[framework.name];
      }
      writeFileSync(current.path, `${JSON.stringify(current.data, null, 2)}\n`);
      continue;
    }
    for (const framework of reused) {
      const target = suitePartPath(resultsDir, suite, framework.name);
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, `${JSON.stringify(reuseDefinition.inputs[suite][framework.name].data, null, 2)}\n`);
    }
  }

  const result = {
    source: reuseDefinition.label,
    sourceDir: reuseDefinition.dir,
    sourceManifest: reuseDefinition.manifestPath,
    verification: reuseDefinition.verification,
    sourceReuse: reuseDefinition.manifest?.reuse ? {
      source: reuseDefinition.manifest.reuse.source,
      reusedFrameworksBySuite: reuseDefinition.manifest.reuse.reusedFrameworksBySuite,
    } : null,
    sourceArtifactsBySuite: Object.fromEntries(browserSuites.map((suite) => [
      suite,
      Object.fromEntries(reuseSourceEntries(suite).map(({ framework, source }) => [framework.name, source.sourcePath])),
    ])),
    measuredFrameworksBySuite: Object.fromEntries(Object.entries(frameworksBySuite).map(([suite, suiteFrameworks]) => [
      suite,
      suiteFrameworks.map((framework) => framework.name),
    ])),
    reusedFrameworksBySuite: Object.fromEntries(Object.entries(reusableFrameworksBySuite).map(([suite, suiteFrameworks]) => [
      suite,
      suiteFrameworks.map((framework) => framework.name),
    ])),
    compatibility,
    preflightCompatibility: reusePreflight,
    warnings: [...new Set([...(reusePreflight?.warnings ?? []), ...warnings])],
  };
  console.log(
    `${compatibility.compatible ? "✓" : "!"} reused cached browser results from ${reuseDefinition.label}` +
    `${warnings.length ? ` (${warnings.length} provenance warning${warnings.length === 1 ? "" : "s"})` : ""}`,
  );
  return result;
}

function annotateReuseArtifact(path, suite, reuseResult) {
  if (!reuseResult || !existsSync(path) || reusableFrameworksBySuite[suite]?.length === 0) return;
  const data = JSON.parse(readFileSync(path, "utf8"));
  data.reuse = {
    source: reuseResult.source,
    sourceDir: reuseResult.sourceDir,
    sourceManifest: reuseResult.sourceManifest,
    verification: reuseResult.verification,
    measuredFrameworks: reuseResult.measuredFrameworksBySuite[suite],
    reusedFrameworks: reuseResult.reusedFrameworksBySuite[suite],
    compatible: reuseResult.compatibility.compatible,
    mismatches: reuseResult.compatibility.mismatches.filter((check) => check.scope.startsWith(`${suite}:`)),
  };
  writeFileSync(path, `${JSON.stringify(data, null, 2)}\n`);
}

function materializeGitBaseline(ref) {
  const dir = mkdtempSync(join(tmpdir(), "kinetica-bench-baseline-"));
  const files = [
    "results.json", "throttled.json", "tree.json", "scaling.json", "sizes.json",
    "run.json", "jvm/results.json",
  ];
  let copied = 0;
  for (const file of files) {
    const gitPath = `bench/results/${file}`;
    const result = spawnSync("git", ["show", `${ref}:${gitPath}`], {
      cwd: repoRoot,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
    });
    if (result.status !== 0) continue;
    const out = join(dir, file);
    mkdirSync(dirname(out), { recursive: true });
    writeFileSync(out, result.stdout);
    copied++;
  }
  if (copied === 0) {
    rmSync(dir, { recursive: true, force: true });
    throw new Error(`git ref '${ref}' has no tracked canonical benchmark results`);
  }
  return dir;
}

function requireCompleteRun(action) {
  const missingSuites = DEFAULT_SUITES.filter((suite) => !suites.includes(suite));
  const optInSuites = suites.filter((suite) => !DEFAULT_SUITES.includes(suite));
  const missingFrameworks = DEFAULT_BROWSER_SUITES.flatMap((suite) => {
    const expected = frameworks.filter((framework) => suite !== "tree" || framework.treeUrl);
    const available = artifactFrameworksBySuite[suite] ?? [];
    return expected.filter((framework) => !available.includes(framework)).map((framework) => `${suite}:${framework.name}`);
  });
  const filtered = filters.length > 0 || suites.some((suite) =>
    scenariosBySuite[suite].length !== availableBySuite[suite].length);
  if (missingSuites.length || optInSuites.length || missingFrameworks.length || filtered || config.throttle > 1) {
    throw new Error(`${action} requires an unfiltered, unthrottled default run with every standard suite and framework`);
  }
}

function copyIfPresent(source, target) {
  if (!existsSync(source)) return false;
  mkdirSync(dirname(target), { recursive: true });
  cpSync(source, target, { recursive: true, force: true });
  return true;
}

function promoteRun({ reportPath, comparisonResult, manifest }) {
  const rootFiles = ["results.json", "throttled.json", "tree.json", "scaling.json", "sizes.json"];
  for (const file of rootFiles) copyIfPresent(join(resultsDir, file), join(acceptedResultsDir, file));
  for (const framework of frameworks) {
    const part = `part-${framework.name}.json`;
    copyIfPresent(join(resultsDir, part), join(acceptedResultsDir, part));
    copyIfPresent(join(resultsDir, "tree", part), join(acceptedResultsDir, "tree", part));
    copyIfPresent(join(resultsDir, "scaling", part), join(acceptedResultsDir, "scaling", part));
  }
  copyIfPresent(join(resultsDir, "jvm", "results.json"), join(acceptedResultsDir, "jvm", "results.json"));
  copyIfPresent(reportPath, join(benchDir, "report", "index.html"));
  copyIfPresent(join(dirname(reportPath), "report.js"), join(benchDir, "report", "report.js"));
  if (comparisonResult) {
    copyIfPresent(comparisonResult.jsonPath, join(acceptedResultsDir, "comparison.json"));
    copyIfPresent(comparisonResult.htmlPath, join(benchDir, "report", "comparison.html"));
  }
  const acceptedArtifacts = {
    browser: existsSync(join(acceptedResultsDir, "results.json")) ? "bench/results/results.json" : null,
    throttled: existsSync(join(acceptedResultsDir, "throttled.json")) ? "bench/results/throttled.json" : null,
    tree: existsSync(join(acceptedResultsDir, "tree.json")) ? "bench/results/tree.json" : null,
    scaling: existsSync(join(acceptedResultsDir, "scaling.json")) ? "bench/results/scaling.json" : null,
    jvm: existsSync(join(acceptedResultsDir, "jvm", "results.json")) ? "bench/results/jvm/results.json" : null,
    sizesAndBuild: existsSync(join(acceptedResultsDir, "sizes.json")) ? "bench/results/sizes.json" : null,
    report: "bench/report/index.html",
    comparisonJson: comparisonResult ? "bench/results/comparison.json" : null,
    comparisonHtml: comparisonResult ? "bench/report/comparison.html" : null,
  };
  writeFileSync(join(acceptedResultsDir, "run.json"), `${JSON.stringify({
    ...manifest,
    resultsDir: "bench/results",
    artifacts: acceptedArtifacts,
    comparison: manifest.comparison ? {
      ...manifest.comparison,
      json: "bench/results/comparison.json",
      html: "bench/report/comparison.html",
    } : undefined,
  }, null, 2)}\n`);
  writeFileSync(join(acceptedResultsDir, "accepted-run.json"), `${JSON.stringify({
    acceptedAt: new Date().toISOString(),
    source: resultsDir.split("/").at(-1),
    manifest: "bench/results/run.json",
  }, null, 2)}\n`);
}

async function buildBrowserApps(browserFrameworks = executedBrowserFrameworks) {
  const genericTargets = [];
  for (const framework of browserFrameworks.filter((candidate) => !candidate.build)) {
    const suiteEntries = Object.entries(frameworksBySuite)
      .filter(([, suiteFrameworks]) => suiteFrameworks.includes(framework));
    if (suiteEntries.some(([suite]) => suite !== "tree")) genericTargets.push(framework.name);
    if (suiteEntries.some(([suite]) => suite === "tree") && framework.treeUrl) {
      genericTargets.push(`${framework.name}-tree`);
    }
  }
  if (genericTargets.length > 0) {
    if (config.dryRun) {
      console.log(`$ build browser targets: ${genericTargets.join(",")}`);
    } else {
      const { buildTarget } = await import("./build.mjs");
      for (const target of genericTargets) await buildTarget(target);
    }
  }
  for (const framework of browserFrameworks) {
    if (framework.build) run(framework.build.cmd, framework.build.args);
  }
}

const profileMountSnippet = `
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

function profilingPage(title) {
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${title}</title>
  <link rel="stylesheet" href="./styles.css">
  <script>${profileMountSnippet}</script>
</head>
<body>
  <div id="main"></div>
  <script type="module" src="./main.js"></script>
</body>
</html>
`;
}

async function buildProfilingBundles() {
  if (config.dryRun) {
    console.log(`$ build readable profiling bundles: ${profileFrameworks.map((framework) => framework.name).join(",")}`);
    return;
  }
  const [{ build }, { buildTarget }] = await Promise.all([import("esbuild"), import("./build.mjs")]);
  const sharedCss = join(benchDir, "frameworks", "shared", "styles.css");
  for (const framework of profileFrameworks) {
    const recipe = framework.profile;
    const outDir = join(benchDir, "dist", `${framework.name}-profile`);
    if (recipe.kind === "build-target") {
      await buildTarget(recipe.target, {
        minify: false,
        outDir,
        title: `${framework.label} keyed benchmark (profile)`,
      });
      continue;
    }
    if (recipe.kind !== "linked-js") throw new Error(`unknown profiling recipe '${recipe.kind}' for ${framework.name}`);
    const linkEntry = join(repoRoot, recipe.entry);
    if (!existsSync(linkEntry)) {
      throw new Error(`${framework.name} profiling entry not found: ${linkEntry}`);
    }
    mkdirSync(outDir, { recursive: true });
    await build({
      entryPoints: [linkEntry],
      bundle: true,
      minify: false,
      treeShaking: true,
      format: "esm",
      platform: "browser",
      target: "es2020",
      outfile: join(outDir, "main.js"),
      logLevel: "warning",
      define: recipe.define ?? {},
    });
    writeFileSync(join(outDir, "index.html"), profilingPage(`${framework.label} keyed benchmark (profile)`));
    cpSync(sharedCss, join(outDir, "styles.css"));
    console.log(`built ${framework.name} readable profile bundle`);
  }
}

const PROFILE_OPS = {
  "04_select1k": {
    async prep(harness) {
      await harness.clickButton("run");
      await harness.waitRows(1000);
    },
    selector: (framework) => framework.rowLink(4),
  },
  "06_remove1k": {
    async prep(harness) {
      await harness.clickButton("run");
      await harness.waitRows(1000);
    },
    selector: (framework) => framework.rowRemove(5),
  },
  "08_append1k": {
    async prep(harness) {
      await harness.clickButton("run");
      await harness.waitRows(1000);
    },
    selector: (framework) => framework.button("add"),
  },
  "12_remove10k": {
    async prep(harness) {
      await harness.clickButton("runlots");
      await harness.waitRows(10000);
    },
    selector: (framework) => framework.rowRemove(5),
  },
  "13_update10th10k": {
    async prep(harness) {
      await harness.clickButton("runlots");
      await harness.waitRows(10000);
    },
    selector: (framework) => framework.button("update"),
  },
};

function shortProfileUrl(url) {
  if (!url) return "?";
  const benchIndex = url.indexOf("bench/dist/");
  return benchIndex >= 0 ? url.slice(benchIndex) : url.split("/").at(-1);
}

function summarizeProfiles(profiles) {
  const functions = new Map();
  const files = new Map();
  let totalHits = 0;
  let profilerSamples = 0;
  let deltaTotalUs = 0;
  let deltaCount = 0;
  for (const profile of profiles) {
    profilerSamples += profile.samples?.length ?? 0;
    for (const delta of profile.timeDeltas ?? []) {
      deltaTotalUs += delta;
      deltaCount++;
    }
    for (const node of profile.nodes ?? []) {
      const hits = node.hitCount ?? 0;
      if (hits === 0) continue;
      totalHits += hits;
      const frame = node.callFrame;
      const name = frame.functionName || "(anonymous)";
      const url = shortProfileUrl(frame.url);
      const key = `${name} — ${url}:${frame.lineNumber}`;
      functions.set(key, (functions.get(key) ?? 0) + hits);
      files.set(url, (files.get(url) ?? 0) + hits);
    }
  }
  const msPerHit = (deltaCount ? deltaTotalUs / deltaCount : config.profileIntervalUs) / 1000;
  const rows = (entries, limit) => [...entries]
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([name, hits]) => ({
      name,
      hits,
      estimatedSelfMs: Number((hits * msPerHit).toFixed(3)),
      sharePct: totalHits ? Number((hits / totalHits * 100).toFixed(2)) : 0,
    }));
  return {
    profiles: profiles.length,
    profilerSamples,
    estimatedSelfMs: Number((totalHits * msPerHit).toFixed(3)),
    byFile: rows(files, 12),
    topFunctions: rows(functions, config.profileTop),
  };
}

async function runCpuProfiles() {
  const outDir = join(resultsDir, "profiles");
  if (config.dryRun) {
    console.log(`$ V8 CPU profile ${profileFrameworks.map((framework) => framework.name).join(",")} scenarios=${profileScenarios.join(",")} warmup=${config.warmup} samples=${config.iterations} out=${outDir}`);
    return null;
  }
  for (const framework of profileFrameworks) {
    const bundle = join(benchDir, "dist", `${framework.name}-profile`, "main.js");
    if (!existsSync(bundle)) throw new Error(`profiling bundle is missing: ${bundle}; remove --skip-build`);
  }
  mkdirSync(outDir, { recursive: true });
  process.env.PLAYWRIGHT_BROWSERS_PATH = browsersDir;
  const [{ chromium }, { startServer }, { frameworkSelectors, makeHarness, PORT }] = await Promise.all([
    import(pathToFileURL(playwrightImport).href),
    import("./driver/server.mjs"),
    import("./driver/common.mjs"),
  ]);
  const server = await startServer(repoRoot, PORT);
  const browser = await chromium.launch({ headless: true });
  const grouped = {};
  try {
    console.log(`\nV8 CPU profiling: Chromium ${browser.version()}`);
    for (const frameworkConfig of profileFrameworks) {
      const framework = frameworkSelectors({
        ...frameworkConfig,
        url: `/bench/dist/${frameworkConfig.name}-profile/index.html`,
      });
      const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
      const page = await context.newPage();
      page.setDefaultTimeout(180_000);
      const pageErrors = [];
      page.on("pageerror", (error) => pageErrors.push(error.message));
      await page.goto(framework.url, { waitUntil: "networkidle" });
      await page.waitForFunction(() => window.__mountMs !== undefined, undefined, { polling: 100 });
      const harness = makeHarness(page, framework);
      const cdp = await context.newCDPSession(page);
      await cdp.send("Profiler.enable");
      await cdp.send("Profiler.setSamplingInterval", { interval: config.profileIntervalUs });
      grouped[framework.name] = {};
      for (const scenario of profileScenarios) {
        const operation = PROFILE_OPS[scenario];
        const selector = operation.selector(framework);
        const click = () => page.evaluate((target) => new Promise((resolveClick, rejectClick) => {
          const element = document.querySelector(target);
          if (!element) {
            rejectClick(new Error(`profile target not found: ${target}`));
            return;
          }
          element.click();
          requestAnimationFrame(() => requestAnimationFrame(resolveClick));
        }), selector);
        for (let i = 0; i < config.warmup; i++) {
          await operation.prep(harness);
          await click();
        }
        const profiles = [];
        for (let i = 0; i < config.iterations; i++) {
          await operation.prep(harness);
          await page.waitForTimeout(120);
          await cdp.send("Profiler.start");
          await click();
          const { profile } = await cdp.send("Profiler.stop");
          profiles.push(profile);
          const file = join(outDir, `${framework.name}-${scenario}-${i}.cpuprofile`);
          writeFileSync(file, JSON.stringify(profile));
        }
        grouped[framework.name][scenario] = summarizeProfiles(profiles);
        const result = grouped[framework.name][scenario];
        console.log(`  ${framework.name} ${scenario}: ${result.profilerSamples} profiler samples, ~${result.estimatedSelfMs.toFixed(1)}ms self-time`);
      }
      await cdp.send("Profiler.disable");
      await context.close();
      if (pageErrors.length) throw new Error(`${framework.name} profiling page errors: ${pageErrors.join("; ")}`);
    }
  } finally {
    await browser.close();
    server.close();
  }
  const summaryPath = join(outDir, "summary.json");
  writeFileSync(summaryPath, `${JSON.stringify({
    meta: {
      date: new Date().toISOString(),
      chromium: browser.version(),
      warmup: config.warmup,
      samples: config.iterations,
      samplingIntervalUs: config.profileIntervalUs,
      frameworks: profileFrameworks.map((framework) => framework.name),
      scenarios: profileScenarios,
    },
    results: grouped,
  }, null, 2)}\n`);
  console.log(`profile summary written to ${summaryPath}`);
  return { outDir, summaryPath };
}

const reuseDefinition = resolveReuseSource(reuseFrom);
let reusePreflight = null;
if (config.accept) requireCompleteRun(config.updateDocs ? "--update-docs" : "--accept");
const compareSpec = raw["compare-to"] ?? (config.updateDocs ? "latest" : config.accept ? "accepted" : null);
const baselineDefinition = resolveBaselineSpec(compareSpec);

console.log("Kinetica benchmark plan");
console.log(`  suites:     ${suites.join(", ")}`);
if (browserSuites.length > 0) {
  for (const suite of browserSuites) {
    console.log(`  ${`${suite} measure:`.padEnd(18)} ${frameworksBySuite[suite].map((fw) => fw.name).join(", ")}`);
    if (reusableFrameworksBySuite[suite].length > 0) {
      console.log(`  ${`${suite} reuse:`.padEnd(18)} ${reusableFrameworksBySuite[suite].map((fw) => fw.name).join(", ")}`);
    }
  }
} else {
  console.log("  frameworks: n/a");
}
if (suites.some((suite) => ["main", "tree", "scaling", "jvm"].includes(suite))) {
  console.log(`  timing:     ${config.warmup} warmup + ${config.iterations} measured iterations`);
}
if (suites.includes("stress")) {
  console.log(`  stress:     ${config.stressSizes} rows · ${config.stressWarmup} warmup + ${config.stressIterations} measured iterations`);
}
if (suites.includes("extra")) {
  console.log(`  extra:      ${config.extraSize} rows · ${config.extraWarmup} warmup + ${config.extraIterations} measured iterations`);
}
console.log(`  results:    ${resultsDir}`);
if (filters.length) console.log(`  filter:     ${filters.join(", ")}`);
if (config.profile) console.log(`  profiling:  ${profileFrameworks.map((framework) => framework.name).join(", ")} · ${profileScenarios.join(", ")}`);
if (config.profile && skippedProfileFrameworks.length) {
  for (const framework of skippedProfileFrameworks) {
    console.log(`  profile skip: ${framework.name} — ${framework.profile.unsupported}`);
  }
}
if (reuseDefinition) console.log(`  reuse source: ${reuseDefinition.label}`);
if (compareSpec) console.log(`  baseline:   ${baselineDefinition?.label ?? `${compareSpec} (not found)`}`);
if (config.accept) console.log(`  promotion:  ${config.updateDocs ? "accepted results + docs" : "accepted results"}`);
console.log("");

if (browserSuites.length > 0) ensureBenchDependencies();
if (reuseDefinition) reusePreflight = validateReuseSourceBeforeRun(probeChromiumVersion());
ensureCommand("node");

const needsPlugin = suites.includes("jvm") || suites.includes("build") ||
  executedBrowserFrameworks.some((fw) => fw.name === "kinetica");
if (needsPlugin) {
  ensureCommand(kotlin);
  if (config.publishPlugin) {
    run(kotlin, ["publish", "mavenLocal", "-m", "kinetica-compiler"]);
  }
  // build-kinetica.mjs normally self-publishes. The orchestrator either did that
  // above or was explicitly told to reuse mavenLocal, so suppress the duplicate.
  baseEnv.KINETICA_COMPILER_PUBLISHED = "1";
}

if (!config.dryRun) mkdirSync(resultsDir, { recursive: true });

if (browserSuites.length > 0 && config.build) {
  await buildBrowserApps(executedBrowserFrameworks);
}
if (config.profile && config.build) {
  await buildProfilingBundles();
}

const commonBrowserArgs = [
  `--warmup=${config.warmup}`,
  `--samples=${config.iterations}`,
  ...(config.throttle > 1 ? [`--throttle=${config.throttle}`] : []),
];

if (suites.includes("main")) {
  const partsDir = config.throttle > 1 ? join(resultsDir, "throttled") : resultsDir;
  for (const framework of frameworksBySuite.main) {
    run(process.execPath, [
      join(benchDir, "driver", "bench.mjs"),
      `--frameworks=${framework.name}`,
      `--out=${join(partsDir, `part-${framework.name}.json`)}`,
      `--scenarios=${scenariosBySuite.main.join(",")}`,
      `--anim-seconds=${config.animSeconds}`,
      ...commonBrowserArgs,
    ], { cwd: benchDir });
  }
}

if (suites.includes("tree")) {
  for (const framework of frameworksBySuite.tree.filter((fw) => fw.treeUrl)) {
    run(process.execPath, [
      join(benchDir, "driver", "tree.mjs"),
      `--frameworks=${framework.name}`,
      `--out=${join(resultsDir, "tree", `part-${framework.name}.json`)}`,
      `--bench=${scenariosBySuite.tree.join(",")}`,
      ...commonBrowserArgs,
    ], { cwd: benchDir });
  }
}

if (suites.includes("scaling")) {
  for (const framework of frameworksBySuite.scaling) {
    run(process.execPath, [
      join(benchDir, "driver", "scaling.mjs"),
      `--frameworks=${framework.name}`,
      `--out=${join(resultsDir, "scaling", `part-${framework.name}.json`)}`,
      `--bench=${scenariosBySuite.scaling.join(",")}`,
      `--sizes=${config.sizes}`,
      ...(config.strict ? ["--strict"] : []),
      ...commonBrowserArgs,
    ], { cwd: benchDir });
  }
}

if (suites.includes("stress")) {
  for (const framework of frameworksBySuite.stress) {
    run(process.execPath, [
      join(benchDir, "driver", "scaling.mjs"),
      `--frameworks=${framework.name}`,
      `--out=${join(resultsDir, "stress", `part-${framework.name}.json`)}`,
      `--bench=${scenariosBySuite.stress.join(",")}`,
      `--sizes=${config.stressSizes}`,
      `--warmup=${config.stressWarmup}`,
      `--samples=${config.stressIterations}`,
      ...(config.throttle > 1 ? [`--throttle=${config.throttle}`] : []),
      ...(config.strict ? ["--strict"] : []),
    ], { cwd: benchDir });
  }
}

if (suites.includes("extra")) {
  run(process.execPath, [
    join(benchDir, "driver", "extra-ops.mjs"),
    `--frameworks=${frameworksBySuite.extra.map((fw) => fw.name).join(",")}`,
    `--out=${join(resultsDir, "extra-ops", `results-${config.extraSize}.json`)}`,
    `--bench=${scenariosBySuite.extra.join(",")}`,
    `--size=${config.extraSize}`,
    `--warmup=${config.extraWarmup}`,
    `--samples=${config.extraIterations}`,
    ...(config.throttle > 1 ? [`--throttle=${config.throttle}`] : []),
  ], { cwd: benchDir });
}

const reuseResult = materializeReusedBrowserResults();
const profileArtifacts = config.profile ? await runCpuProfiles() : null;

if (suites.includes("jvm")) {
  run(kotlin, [
    "run", "-m", "bench-jvm", "--",
    `--warmup=${config.warmup}`,
    `--samples=${config.iterations}`,
    `--filter=${scenariosBySuite.jvm.join(",")}`,
    `--out=${join(resultsDir, "jvm", "results.json")}`,
    ...(rowSizes.length ? [`--row-sizes=${rowSizes.join(",")}`] : []),
  ]);
}

if (suites.includes("size") || suites.includes("build")) {
  run(process.execPath, [
    join(repoRoot, "scripts", "size-report.mjs"),
    ...(suites.includes("build") ? ["--measure-build"] : []),
  ], { env: { ...baseEnv, BENCH_RESULTS_DIR: resultsDir } });
}

let mainResults = false;
if (suites.includes("main")) {
  const mainDir = config.throttle > 1 ? join(resultsDir, "throttled") : resultsDir;
  const mainOut = config.throttle > 1 ? join(resultsDir, "throttled.json") : join(resultsDir, "results.json");
  mainResults = mergeNamespace(mainDir, mainOut, {
    expectedFrameworks: artifactFrameworksBySuite.main,
    preferredMetaFramework: frameworksBySuite.main[0],
  }) && config.throttle <= 1;
}
if (suites.includes("tree")) {
  mergeNamespace(join(resultsDir, "tree"), join(resultsDir, "tree.json"), {
    expectedFrameworks: artifactFrameworksBySuite.tree,
    preferredMetaFramework: frameworksBySuite.tree[0],
  });
}
if (suites.includes("scaling")) {
  mergeNamespace(join(resultsDir, "scaling"), join(resultsDir, "scaling.json"), {
    expectedFrameworks: artifactFrameworksBySuite.scaling,
    preferredMetaFramework: frameworksBySuite.scaling[0],
  });
}
if (suites.includes("stress")) {
  mergeNamespace(join(resultsDir, "stress"), join(resultsDir, "stress.json"), {
    expectedFrameworks: artifactFrameworksBySuite.stress,
    preferredMetaFramework: frameworksBySuite.stress[0],
  });
}
if (suites.includes("main")) {
  annotateReuseArtifact(
    join(resultsDir, config.throttle > 1 ? "throttled.json" : "results.json"),
    "main",
    reuseResult,
  );
}
if (suites.includes("tree")) annotateReuseArtifact(join(resultsDir, "tree.json"), "tree", reuseResult);
if (suites.includes("scaling")) annotateReuseArtifact(join(resultsDir, "scaling.json"), "scaling", reuseResult);
if (suites.includes("stress")) annotateReuseArtifact(join(resultsDir, "stress.json"), "stress", reuseResult);
if (suites.includes("extra")) {
  annotateReuseArtifact(join(resultsDir, "extra-ops", `results-${config.extraSize}.json`), "extra", reuseResult);
}

const mergedMainPath = join(resultsDir, "results.json");
const reportPath = raw["report-out"]
  ? resolve(repoRoot, raw["report-out"])
  : resultsDir === join(benchDir, "results")
    ? join(benchDir, "report", "index.html")
    : join(resultsDir, "report.html");
if (mainResults || (!config.dryRun && existsSync(mergedMainPath))) {
  run(process.execPath, [join(benchDir, "report", "generate.mjs"), mergedMainPath, reportPath], {
    cwd: benchDir,
    env: { ...baseEnv, BENCH_RESULTS_DIR: resultsDir },
  });
}

if (!config.dryRun) {
  const artifact = (path) => existsSync(path) ? path : null;
  const manifestPath = join(resultsDir, "run.json");
  const manifest = {
    date: new Date().toISOString(),
    status: "complete",
    suites,
    scenarios: Object.fromEntries(suites.map((suite) => [suite, scenariosBySuite[suite]])),
    frameworks: artifactBrowserFrameworks.map((fw) => fw.name),
    frameworksBySuite: Object.fromEntries(Object.entries(artifactFrameworksBySuite).map(([suite, suiteFrameworks]) => [
      suite,
      suiteFrameworks.map((framework) => framework.name),
    ])),
    measuredFrameworks: executedBrowserFrameworks.map((fw) => fw.name),
    measuredFrameworksBySuite: Object.fromEntries(Object.entries(frameworksBySuite).map(([suite, suiteFrameworks]) => [
      suite,
      suiteFrameworks.map((framework) => framework.name),
    ])),
    reusedFrameworksBySuite: Object.fromEntries(Object.entries(reusableFrameworksBySuite).map(([suite, suiteFrameworks]) => [
      suite,
      suiteFrameworks.map((framework) => framework.name),
    ])),
    frameworkVersions: Object.fromEntries(artifactBrowserFrameworks.map((framework) => [
      framework.name,
      artifactFrameworkVersion(framework),
    ])),
    reuse: reuseResult,
    warmup: config.warmup,
    iterations: config.iterations,
    stress: suites.includes("stress") ? {
      sizes: csv(config.stressSizes).map(Number),
      warmup: config.stressWarmup,
      iterations: config.stressIterations,
    } : null,
    extra: suites.includes("extra") ? {
      size: config.extraSize,
      warmup: config.extraWarmup,
      iterations: config.extraIterations,
    } : null,
    cpuThrottle: config.throttle > 1 ? config.throttle : null,
    profiling: config.profile ? {
      frameworks: profileFrameworks.map((framework) => framework.name),
      skippedFrameworks: skippedProfileFrameworks.map((framework) => ({
        framework: framework.name,
        reason: framework.profile.unsupported,
      })),
      scenarios: profileScenarios,
      samplingIntervalUs: config.profileIntervalUs,
      topFunctions: config.profileTop,
    } : null,
    resultsDir,
    artifacts: {
      browser: artifact(join(resultsDir, "results.json")),
      throttled: artifact(join(resultsDir, "throttled.json")),
      tree: artifact(join(resultsDir, "tree.json")),
      scaling: artifact(join(resultsDir, "scaling.json")),
      stress: artifact(join(resultsDir, "stress.json")),
      extra: artifact(join(resultsDir, "extra-ops", `results-${config.extraSize}.json`)),
      jvm: artifact(join(resultsDir, "jvm", "results.json")),
      sizesAndBuild: artifact(join(resultsDir, "sizes.json")),
      report: artifact(reportPath),
      profiles: profileArtifacts?.outDir ?? null,
      profileSummary: profileArtifacts?.summaryPath ?? null,
    },
  };
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

  let comparisonResult = null;
  let temporaryBaseline = null;
  if (baselineDefinition) {
    try {
      const baselineDir = baselineDefinition.kind === "git"
        ? (temporaryBaseline = materializeGitBaseline(baselineDefinition.ref))
        : baselineDefinition.dir;
      if (resolve(baselineDir) === resultsDir) throw new Error("current run cannot be its own comparison baseline");
      comparisonResult = generateComparison({
        currentDir: resultsDir,
        baselineDir,
        outDir: resultsDir,
        currentLabel: resultsDir.split("/").at(-1),
        baselineLabel: baselineDefinition.label,
        thresholdPct: config.comparisonThreshold,
        allowIncompatible: config.allowIncompatible,
      });
      manifest.comparison = {
        baseline: baselineDefinition.label,
        compatible: comparisonResult.comparison.compatibility.compatible,
        allowed: comparisonResult.allowed,
        metrics: comparisonResult.comparison.summary.metrics,
        json: comparisonResult.jsonPath,
        html: comparisonResult.htmlPath,
      };
      manifest.artifacts.comparisonJson = comparisonResult.jsonPath;
      manifest.artifacts.comparisonHtml = comparisonResult.htmlPath;
      writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
    } finally {
      if (temporaryBaseline) rmSync(temporaryBaseline, { recursive: true, force: true });
    }
  } else if (compareSpec) {
    console.log(`! no '${compareSpec}' baseline exists yet; continuing without comparison`);
  }

  if (comparisonResult && !comparisonResult.allowed) {
    console.log(`\nComparison written, but promotion is blocked by ${comparisonResult.comparison.compatibility.mismatches.length} environment mismatch(es).`);
    console.log(`comparison: ${comparisonResult.htmlPath}`);
    throw new Error("incompatible benchmark environments; rerun in the same environment or pass --allow-incompatible");
  }

  if (config.accept) {
    promoteRun({ reportPath, comparisonResult, manifest });
    if (config.updateDocs) {
      updatePerformanceDocs({
        currentDir: resultsDir,
        comparison: comparisonResult?.comparison ?? null,
        docsPath: join(repoRoot, "docs", "docs-site", "resources", "docs", "performance.md"),
      });
      // The build-time suite ends with a Kotlin clean. Restore production bundles before
      // creating the docs static tree so the published demos match the accepted run.
      if (suites.includes("build")) await buildBrowserApps();
      run(process.execPath, [join(repoRoot, "scripts", "bundle-bench-static.mjs")]);
    }
  }

  console.log("\nBenchmark run complete.");
  console.log(`results:    ${resultsDir}`);
  if (manifest.artifacts.report) console.log(`report:     ${manifest.artifacts.report}`);
  if (comparisonResult) console.log(`comparison: ${comparisonResult.htmlPath}`);
  if (config.accept) console.log(`accepted:   ${acceptedResultsDir}`);
  console.log(`manifest:   ${manifestPath}`);
} else {
  console.log("\nDry run complete; no commands were executed.");
}
