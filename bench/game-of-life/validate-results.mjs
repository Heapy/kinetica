// Recomputes every committed benchmark summary and checks that the two published views
// (HTML report and docs page) still agree with the raw sample JSON.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..", "..");
const results = JSON.parse(readFileSync(join(repoRoot, "bench", "results", "game-of-life", "results.json")));
const report = readFileSync(join(here, "report.html"), "utf8");
const docs = readFileSync(join(repoRoot, "docs", "docs-site", "resources", "docs", "game-of-life.md"), "utf8");
const expectedImplementations = ["kinetica", "react", "compose-html", "vanilla"];
const expectedOperations = [
  "load_pulsar",
  "step_pulsar",
  "randomize",
  "step_random",
  "toggle_center",
  "clear_pulsar",
];

assert.deepEqual(Object.keys(results.implementations), expectedImplementations);
assert.equal(results.methodology.motion, "prefers-reduced-motion: reduce during interaction traces");
assert.match(results.methodology.randomWorkload, /identical 32-bit seeded sequence/);
assert.match(report, /same cross-language seeded sequence/);
assert.match(docs, /exact same cells in all four apps/);

for (const id of expectedImplementations) {
  const implementation = results.implementations[id];
  assert.deepEqual(Object.keys(implementation.operations), expectedOperations, `${id} operation coverage`);
  validateSummary(`${id}/startup`, implementation.startup);
  assert.match(report, new RegExp(`/game-of-life/${id}/`));

  for (const operation of expectedOperations) {
    validateSummary(`${id}/${operation}`, implementation.operations[operation]);
    const median = implementation.operations[operation].median.toFixed(2);
    assert.ok(report.includes(`${median} ms`), `report missing ${id}/${operation} median ${median}`);
    assert.ok(docs.includes(median), `docs missing ${id}/${operation} median ${median}`);
  }

  const bundleKb = (implementation.bundle.gzipBytes / 1024).toFixed(1);
  assert.ok(report.includes(`${bundleKb} KB gzip`), `report missing ${id} bundle size`);
  assert.ok(docs.includes(`${bundleKb} KB`), `docs missing ${id} bundle size`);
}

assert.match(report, /\/game-of-life\/results\.json/);
assert.equal(report.includes('href="./'), false, "report must work with and without a trailing slash URL");
assert.ok(docs.includes(results.generatedAt.slice(0, 10)), "docs benchmark date is stale");

console.log("Game of Life benchmark calculations and published metrics are consistent");

function validateSummary(name, summary) {
  assert.equal(summary.samples.length, results.methodology.samples, `${name} sample count`);
  assert.ok(summary.samples.every((value) => Number.isFinite(value) && value > 0), `${name} samples`);
  const recomputed = stats(summary.samples);
  for (const field of ["median", "mean", "stddev", "min", "max"]) {
    assert.ok(
      Math.abs(summary[field] - recomputed[field]) <= 0.02,
      `${name} ${field}: stored ${summary[field]}, recomputed ${recomputed[field]}`,
    );
  }
  const coefficientOfVariation = summary.stddev / summary.mean;
  assert.ok(coefficientOfVariation < 0.35, `${name} is too unstable to publish (CV ${coefficientOfVariation})`);
}

function stats(samples) {
  const sorted = [...samples].sort((a, b) => a - b);
  const midpoint = Math.floor(sorted.length / 2);
  const median = sorted.length % 2
    ? sorted[midpoint]
    : (sorted[midpoint - 1] + sorted[midpoint]) / 2;
  const mean = samples.reduce((sum, value) => sum + value, 0) / samples.length;
  const stddev = Math.sqrt(samples.reduce((sum, value) => sum + (value - mean) ** 2, 0) / samples.length);
  return {
    median: round2(median),
    mean: round2(mean),
    stddev: round2(stddev),
    min: round2(sorted[0]),
    max: round2(sorted.at(-1)),
  };
}

function round2(value) {
  return Math.round(value * 100) / 100;
}
