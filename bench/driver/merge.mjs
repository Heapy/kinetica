import { readFileSync, writeFileSync } from "node:fs";

const [out, ...parts] = process.argv.slice(2);
if (!out || parts.length === 0) {
  console.error("usage: node merge.mjs <out.json> <part1.json> <part2.json> ...");
  process.exit(1);
}

const merged = { meta: null, benchmarks: null, results: {}, startup: {}, memory: {} };
for (const part of parts) {
  const data = JSON.parse(readFileSync(part, "utf8"));
  if (!merged.meta) {
    merged.meta = data.meta;
    merged.benchmarks = data.benchmarks;
  } else {
    Object.assign(merged.meta.versions, data.meta.versions);
  }
  Object.assign(merged.results, data.results);
  Object.assign(merged.startup, data.startup);
  Object.assign(merged.memory, data.memory);
}
writeFileSync(out, JSON.stringify(merged, null, 2));
console.log(`merged ${parts.length} parts -> ${out}`);
