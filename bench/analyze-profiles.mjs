// Aggregates self-time (hitCount) per function across all samples of one framework/op,
// grouped by "shortUrl:functionName" so repeated call sites collapse into one row.
// Reads <run>/profiles/<fw>-<op>-<i>.cpuprofile written by run.mjs --profile.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { repoRoot, parseArgs } from "./driver/common.mjs";

const args = parseArgs();
const DIR = args.dir ?? join(repoRoot, "bench", "results", "cpuprofiles");
const TOP = Number(args.top ?? 20);

function shortUrl(url) {
  if (!url) return "?";
  const i = url.indexOf("bench/dist/");
  return i >= 0 ? url.slice(i) : url.split("/").pop();
}

function analyze(files) {
  const self = new Map(); // key -> hits
  const totalByFile = new Map();
  let totalHits = 0;
  let totalSamples = 0;
  let intervalUs = 100;
  for (const file of files) {
    const p = JSON.parse(readFileSync(file, "utf8"));
    intervalUs = p.timeDeltas && p.timeDeltas.length
      ? p.timeDeltas.reduce((a, b) => a + b, 0) / p.timeDeltas.length
      : intervalUs;
    totalSamples += p.samples.length;
    for (const n of p.nodes) {
      const hits = n.hitCount ?? 0;
      if (hits === 0) continue;
      totalHits += hits;
      const name = n.callFrame.functionName || "(anonymous)";
      const url = shortUrl(n.callFrame.url);
      const key = `${name} — ${url}:${n.callFrame.lineNumber}`;
      self.set(key, (self.get(key) ?? 0) + hits);
      totalByFile.set(url, (totalByFile.get(url) ?? 0) + hits);
    }
  }
  const rows = [...self.entries()].sort((a, b) => b[1] - a[1]).slice(0, TOP);
  const fileRows = [...totalByFile.entries()].sort((a, b) => b[1] - a[1]);
  const msPerHit = intervalUs / 1000;
  return { rows, fileRows, totalHits, totalSamples, msPerHit, n: files.length };
}

const allFiles = readdirSync(DIR).filter((f) => f.endsWith(".cpuprofile"));
const groups = new Map();
for (const f of allFiles) {
  const m = f.match(/^(.+?)-(\d\d_\w+)-\d+\.cpuprofile$/);
  if (!m) continue;
  const key = `${m[1]}-${m[2]}`;
  if (!groups.has(key)) groups.set(key, []);
  groups.get(key).push(join(DIR, f));
}

const selectedOps = args.ops ? args.ops.split(",") : null;
for (const [key, files] of [...groups.entries()].sort()) {
  if (selectedOps && !selectedOps.some((o) => key.includes(o))) continue;
  const { rows, fileRows, totalHits, totalSamples, msPerHit, n } = analyze(files);
  console.log(`\n=== ${key} (${n} samples, ${totalSamples} profiler samples, ~${(totalHits * msPerHit).toFixed(1)}ms total self-time) ===`);
  console.log("-- by file --");
  for (const [url, hits] of fileRows.slice(0, 12)) {
    console.log(`  ${(hits * msPerHit).toFixed(2).padStart(8)}ms  ${(100 * hits / totalHits).toFixed(1).padStart(5)}%  ${url}`);
  }
  console.log("-- top functions --");
  for (const [name, hits] of rows) {
    console.log(`  ${(hits * msPerHit).toFixed(2).padStart(8)}ms  ${(100 * hits / totalHits).toFixed(1).padStart(5)}%  ${name}`);
  }
}
