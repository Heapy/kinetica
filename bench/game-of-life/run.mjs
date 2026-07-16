// One-command Game of Life benchmark lifecycle:
// dependencies -> model tests -> production builds -> measurements -> docs sync -> validation -> comparison.

import { execFileSync, spawnSync } from "node:child_process";
import { constants, accessSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..", "..");
const node = process.execPath;
const npm = process.platform === "win32" ? "npm.cmd" : "npm";
const kotlin = process.platform === "win32" ? "kotlin.bat" : "./kotlin";
const benchmarkArgs = process.argv.slice(2);

const resultsPath = join(repoRoot, "bench", "results", "game-of-life", "results.json");
const reportPath = join(here, "report.html");
const docsPath = join(repoRoot, "docs", "docs-site", "resources", "docs", "game-of-life.md");
const stageDir = join(repoRoot, "build", "tasks", "_game-of-life_dist");
const publishedArtifacts = [
  resultsPath,
  reportPath,
  docsPath,
  join(stageDir, "results.json"),
  join(stageDir, "benchmark.html"),
];
const snapshots = snapshotFiles(publishedArtifacts);
const previousResults = readJsonIfPresent(resultsPath);

try {
  ensureBenchDependencies();
  const browserEnvironment = ensurePlaywright();
  const benchmarkPort = process.env.BENCH_PORT ?? String(await findOpenPort());

  runStep(kotlin, ["test", "-m", "game-of-life-model", "--platform", "jvm"]);
  runStep(node, [join(repoRoot, "scripts", "build-game-of-life.mjs")]);
  runStep(node, [join(here, "benchmark.mjs"), ...benchmarkArgs], {
    env: {
      ...process.env,
      ...browserEnvironment,
      BENCH_PORT: benchmarkPort,
    },
  });

  const currentResults = readJson(resultsPath);
  syncDocsSnapshot(currentResults);
  runStep(node, [join(here, "validate-results.mjs")]);
  printComparison(previousResults, currentResults);

  console.log(`\nGame of Life benchmark complete: ${resultsPath}`);
} catch (error) {
  restoreFiles(snapshots);
  console.error(`\nGame of Life benchmark failed; published artifacts restored.\n${error.message}`);
  process.exitCode = 1;
}

function ensureBenchDependencies() {
  const requiredPackages = ["esbuild", "react", "react-dom"];
  const missing = requiredPackages.some((name) =>
    !existsSync(join(repoRoot, "bench", "node_modules", name, "package.json"))
  );
  if (missing) {
    runStep(npm, ["ci", "--prefix", "bench", "--no-audit", "--no-fund"]);
  }
}

function ensurePlaywright() {
  const roots = repositoryRoots();
  let playwrightImport = process.env.PLAYWRIGHT_IMPORT;
  let playwrightPackageDir = null;

  if (!playwrightImport) {
    for (const root of roots) {
      const candidate = join(root, ".tools", "playwright", "node_modules", "playwright", "index.mjs");
      if (existsSync(candidate)) {
        playwrightImport = candidate;
        playwrightPackageDir = dirname(candidate);
        break;
      }
    }
  } else if (playwrightImport.startsWith("/")) {
    playwrightPackageDir = dirname(playwrightImport);
  }

  if (!playwrightImport) {
    const toolsDir = join(repoRoot, ".tools", "playwright");
    mkdirSync(toolsDir, { recursive: true });
    runStep(npm, ["install", "--prefix", toolsDir, "--no-save", "--no-audit", "--no-fund", "playwright"]);
    playwrightPackageDir = join(toolsDir, "node_modules", "playwright");
    playwrightImport = join(playwrightPackageDir, "index.mjs");
  }

  let chromiumExecutable = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE;
  if (!chromiumExecutable) {
    const browserRoots = [
      ...roots.map((root) => join(root, ".playwright-browsers")),
      process.env.PLAYWRIGHT_BROWSERS_PATH,
      join(homedir(), "Library", "Caches", "ms-playwright"),
      join(homedir(), ".cache", "ms-playwright"),
    ].filter(Boolean);
    chromiumExecutable = browserRoots.map(findChromiumExecutable).find(Boolean);
  }

  const environment = { PLAYWRIGHT_IMPORT: playwrightImport };
  if (chromiumExecutable) {
    environment.PLAYWRIGHT_CHROMIUM_EXECUTABLE = chromiumExecutable;
    return environment;
  }

  if (!playwrightPackageDir) {
    throw new Error("Cannot install Chromium for a non-filesystem PLAYWRIGHT_IMPORT.");
  }
  const browsersPath = join(repoRoot, ".playwright-browsers");
  runStep(node, [join(playwrightPackageDir, "cli.js"), "install", "chromium"], {
    env: { ...process.env, PLAYWRIGHT_BROWSERS_PATH: browsersPath },
  });
  environment.PLAYWRIGHT_BROWSERS_PATH = browsersPath;
  return environment;
}

function repositoryRoots() {
  const roots = [repoRoot];
  try {
    const commonDir = execFileSync(
      "git",
      ["rev-parse", "--path-format=absolute", "--git-common-dir"],
      { cwd: repoRoot, encoding: "utf8" },
    ).trim();
    const mainCheckout = dirname(commonDir);
    if (!roots.includes(mainCheckout)) roots.push(mainCheckout);
  } catch {
    // A source archive has no linked-worktree tools to discover.
  }
  return roots;
}

function findChromiumExecutable(root) {
  if (!root || !existsSync(root)) return null;
  const executableNames = new Set([
    "Google Chrome for Testing",
    "chrome",
    "chrome-headless-shell",
    "headless_shell",
  ]);

  function visit(directory, depth) {
    if (depth > 10) return null;
    let entries;
    try {
      entries = readdirSync(directory, { withFileTypes: true });
    } catch {
      return null;
    }
    for (const entry of entries) {
      const path = join(directory, entry.name);
      if ((entry.isFile() || entry.isSymbolicLink()) && executableNames.has(entry.name)) {
        try {
          accessSync(path, constants.X_OK);
          return path;
        } catch {
          // Keep looking for an executable browser binary.
        }
      }
    }
    for (const entry of entries) {
      if (!entry.isDirectory()) continue;
      const found = visit(join(directory, entry.name), depth + 1);
      if (found) return found;
    }
    return null;
  }

  return visit(root, 0);
}

function runStep(command, args, { env = process.env } = {}) {
  console.log(`$ ${command} ${args.join(" ")}`);
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    env,
    stdio: "inherit",
    shell: process.platform === "win32",
  });
  if (result.status !== 0) {
    const detail = result.signal ? `signal ${result.signal}` : `exit ${result.status ?? "unknown"}`;
    throw new Error(`${command} failed (${detail})`);
  }
}

function syncDocsSnapshot(results) {
  const docs = readFileSync(docsPath, "utf8");
  const startMarker = "Median milliseconds from the ";
  const endMarker = "\n\nThe numbers are machine-specific";
  const start = docs.indexOf(startMarker);
  const end = docs.indexOf(endMarker, start);
  if (start < 0 || end < 0) throw new Error("Cannot locate the Game of Life docs performance snapshot.");

  const ids = ["kinetica", "react", "compose-html", "vanilla"];
  const rows = [
    ["Cold startup + 3,456-cell mount", (implementation) => implementation.startup.median, "ms"],
    ["Load Pulsar preset", (implementation) => implementation.operations.load_pulsar.median, "ms"],
    ["Advance Pulsar", (implementation) => implementation.operations.step_pulsar.median, "ms"],
    ["Randomize 24% of the board", (implementation) => implementation.operations.randomize.median, "ms"],
    ["Advance randomized board", (implementation) => implementation.operations.step_random.median, "ms"],
    ["Toggle one cell", (implementation) => implementation.operations.toggle_center.median, "ms"],
    ["Clear Pulsar", (implementation) => implementation.operations.clear_pulsar.median, "ms"],
    ["Production bundle, gzip", (implementation) => implementation.bundle.gzipBytes / 1024, "KB"],
  ];
  const tableRows = rows.map(([label, read, unit]) => {
    const values = ids.map((id) => read(results.implementations[id]));
    const fastest = Math.min(...values);
    const cells = values.map((value) => {
      const formatted = unit === "KB" ? `${value.toFixed(1)} KB` : value.toFixed(2);
      return value === fastest ? `**${formatted}**` : formatted;
    });
    return `| ${label} | ${cells.join(" | ")} |`;
  });

  const snapshot = [
    `Median milliseconds from the ${results.generatedAt.slice(0, 10)} run on an ${escapeMarkdown(results.environment.cpu)} with Chromium ${results.environment.chromium},`,
    `${results.methodology.samples} measured samples after ${results.methodology.warmup} warmup. Interaction traces request reduced motion so the shared`,
    "160 ms cell-birth animation cannot hide renderer work. Lower is better.",
    "",
    "| Operation | Kinetica | React | Compose HTML | Vanilla |",
    "|---|---:|---:|---:|---:|",
    ...tableRows,
  ].join("\n");
  writeFileSync(docsPath, docs.slice(0, start) + snapshot + docs.slice(end));
}

function printComparison(previous, current) {
  if (!previous) {
    console.log("\nNo previous Game of Life result was available for comparison.");
    return;
  }

  const environmentChanged = ["chromium", "os", "arch", "cpu"].some(
    (field) => previous.environment[field] !== current.environment[field],
  );
  const methodologyChanged = ["warmup", "samples", "viewport"].some(
    (field) => previous.methodology[field] !== current.methodology[field],
  );
  console.log("\nBenchmark comparison: previous -> current (negative delta is faster/smaller)");
  if (environmentChanged || methodologyChanged) {
    console.log("WARNING: environment or methodology changed; deltas are not a controlled A/B comparison.");
  }

  const metrics = [
    ["Startup", (implementation) => implementation.startup.median, "ms", 2],
    ["Load Pulsar", (implementation) => implementation.operations.load_pulsar.median, "ms", 2],
    ["Step Pulsar", (implementation) => implementation.operations.step_pulsar.median, "ms", 2],
    ["Randomize", (implementation) => implementation.operations.randomize.median, "ms", 2],
    ["Step random", (implementation) => implementation.operations.step_random.median, "ms", 2],
    ["Toggle", (implementation) => implementation.operations.toggle_center.median, "ms", 2],
    ["Clear", (implementation) => implementation.operations.clear_pulsar.median, "ms", 2],
    ["Bundle gzip", (implementation) => implementation.bundle.gzipBytes / 1024, "KB", 1],
  ];

  for (const id of ["kinetica", "react", "compose-html", "vanilla"]) {
    const before = previous.implementations[id];
    const after = current.implementations[id];
    if (!before || !after) continue;
    console.log(`\n${after.label}`);
    for (const [label, read, unit, digits] of metrics) {
      const oldValue = read(before);
      const newValue = read(after);
      const percent = ((newValue / oldValue) - 1) * 100;
      const sign = percent >= 0 ? "+" : "";
      console.log(
        `  ${label.padEnd(14)} ${oldValue.toFixed(digits)} -> ${newValue.toFixed(digits)} ${unit} (${sign}${percent.toFixed(1)}%)`,
      );
    }
  }
}

function snapshotFiles(paths) {
  return new Map(paths.map((path) => [path, existsSync(path) ? readFileSync(path) : null]));
}

function restoreFiles(saved) {
  for (const [path, content] of saved) {
    if (content === null) {
      rmSync(path, { force: true });
    } else {
      mkdirSync(dirname(path), { recursive: true });
      writeFileSync(path, content);
    }
  }
}

function readJsonIfPresent(path) {
  return existsSync(path) ? readJson(path) : null;
}

function readJson(path) {
  return JSON.parse(readFileSync(path, "utf8"));
}

function escapeMarkdown(value) {
  return String(value).replaceAll("|", "\\|");
}

function findOpenPort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.unref();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close((error) => error ? reject(error) : resolve(port));
    });
  });
}
