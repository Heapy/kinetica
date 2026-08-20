import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { updatePerformanceDocs } from "./comparison.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..", "..");

function tempDir(t) {
  const dir = mkdtempSync(join(tmpdir(), "kinetica-report-test-"));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

function result(median) {
  return {
    median,
    mean: median,
    stddev: 0,
    min: median,
    max: median,
    samples: [median],
  };
}

function meta() {
  return {
    date: "2026-08-21T00:00:00.000Z",
    chromium: "test",
    warmup: 0,
    samples: 1,
    cpuThrottle: null,
    methodology: "test methodology",
    versions: {},
    machine: {
      cpu: "test CPU",
      platform: "test",
      arch: "test",
      memGb: 1,
    },
  };
}

test("performance docs keep canvas out of the DOM ranking and compare Compose variants separately", (t) => {
  const dir = tempDir(t);
  writeFileSync(join(dir, "results.json"), JSON.stringify({
    meta: meta(),
    benchmarks: [{ id: "create", label: "create rows" }],
    results: {
      kinetica: { create: result(10) },
      "compose-web": { create: result(20) },
      "compose-canvas": { create: result(2) },
      "compose-canvas-lazy": { create: result(1) },
    },
  }));
  const docsPath = join(dir, "performance.md");
  writeFileSync(docsPath, [
    "# Performance",
    "",
    "<!-- BENCHMARK_RESULTS:START -->",
    "old results",
    "<!-- BENCHMARK_RESULTS:END -->",
    "",
  ].join("\n"));

  updatePerformanceDocs({ currentDir: dir, docsPath });

  const output = readFileSync(docsPath, "utf8");
  const [domSection, composeSection] = output.split("### Compose renderer variants");
  assert.match(domSection, /\| Operation \| Kinetica \| Compose HTML \|/);
  assert.doesNotMatch(domSection, /Compose canvas/);
  assert.match(domSection, /geometric mean vs per-operation fastest DOM framework.*\*\*1\.00×\*\* \| \*\*2\.00×\*\*/);
  assert.match(composeSection, /\| Operation \| Compose HTML \| Compose canvas \| Compose canvas \(Lazy\) \|/);
  assert.match(composeSection, /geometric mean duration ratio vs Compose HTML.*\*\*1\.00×\*\* \| \*\*0\.10×\*\* \| \*\*0\.05×\*\*/);
});

test("summary-only canvas results still render a canvas section", (t) => {
  const dir = tempDir(t);
  const resultsPath = join(dir, "results.json");
  const outPath = join(dir, "report.html");
  writeFileSync(resultsPath, JSON.stringify({
    meta: meta(),
    benchmarks: [],
    results: { "compose-canvas": {}, "compose-canvas-lazy": {} },
    startup: {
      "compose-canvas": { median: 12, jsBytes: 2_000_000, gzipBytes: 1_000_000 },
      "compose-canvas-lazy": { median: 10, jsBytes: 2_000_000, gzipBytes: 1_000_000 },
    },
    memory: {
      "compose-canvas": { afterLoadMb: 1, after1kMb: 2, after5xReplaceMb: 2, afterCreateClear10Mb: 2 },
      "compose-canvas-lazy": { afterLoadMb: 1, after1kMb: 1.5, after5xReplaceMb: 1.5, afterCreateClear10Mb: 1.5 },
    },
    animation: {
      "compose-canvas": { fps: 60, frames: 60, medianMs: 16, p95Ms: 17, longFramePct: 0 },
      "compose-canvas-lazy": { fps: 90, frames: 90, medianMs: 11, p95Ms: 12, longFramePct: 0 },
    },
  }));

  execFileSync(process.execPath, [join(here, "generate.mjs"), resultsPath, outPath], {
    cwd: repoRoot,
    env: { ...process.env, BENCH_RESULTS_DIR: dir },
  });

  const output = readFileSync(outPath, "utf8");
  assert.match(output, /<h2>Canvas renderers<\/h2>/);
  assert.match(output, /Compose canvas \(Lazy\)/);
  assert.match(output, /time to interactive/);
  assert.match(output, /JS heap after 1k rows/);
  assert.match(output, /sustained updates/);
});

test("canvas factors below one use a valid ramp color", (t) => {
  const dir = tempDir(t);
  const resultsPath = join(dir, "results.json");
  const outPath = join(dir, "report.html");
  writeFileSync(resultsPath, JSON.stringify({
    meta: meta(),
    benchmarks: [{ id: "create", label: "create rows" }],
    results: {
      kinetica: { create: result(10) },
      "compose-canvas": { create: result(5) },
      "compose-canvas-lazy": { create: result(4) },
    },
    startup: {},
    memory: {},
    animation: {},
  }));

  execFileSync(process.execPath, [join(here, "generate.mjs"), resultsPath, outPath], {
    cwd: repoRoot,
    env: { ...process.env, BENCH_RESULTS_DIR: dir },
  });

  const output = readFileSync(outPath, "utf8");
  assert.doesNotMatch(output, /--cell:undefined/);
  assert.match(output, /0\.5× the fastest DOM framework/);
  assert.match(output, /0\.4× the fastest DOM framework/);
});
