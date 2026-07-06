// Merges part files into one results JSON. Sections are merged generically:
// - meta: first part wins, versions are unioned
// - benchmarks / treeBenchmarks: union by id, sorted by id (old parts may lack
//   newer benchmark ids; the report renders missing cells as "–")
// - every other object section (results, startup, memory, animation, tree,
//   scaling, ...) is a per-framework map and is shallow-merged
import { readFileSync, writeFileSync } from "node:fs";

const [out, ...parts] = process.argv.slice(2);
if (!out || parts.length === 0) {
  console.error("usage: node merge.mjs <out.json> <part1.json> <part2.json> ...");
  process.exit(1);
}

const BENCH_LISTS = ["benchmarks", "treeBenchmarks"];
const merged = { meta: null };

for (const part of parts) {
  const data = JSON.parse(readFileSync(part, "utf8"));
  if (!merged.meta) {
    merged.meta = data.meta;
  } else if (data.meta?.versions) {
    Object.assign(merged.meta.versions ??= {}, data.meta.versions);
  }
  for (const key of BENCH_LISTS) {
    if (!Array.isArray(data[key])) continue;
    merged[key] ??= [];
    for (const b of data[key]) {
      if (!merged[key].some((x) => x.id === b.id)) merged[key].push(b);
    }
  }
  for (const [key, value] of Object.entries(data)) {
    if (key === "meta" || BENCH_LISTS.includes(key)) continue;
    if (value && typeof value === "object" && !Array.isArray(value)) {
      merged[key] = Object.assign(merged[key] ?? {}, value);
    }
  }
}

for (const key of BENCH_LISTS) {
  merged[key]?.sort((a, b) => a.id.localeCompare(b.id));
}

writeFileSync(out, JSON.stringify(merged, null, 2));
console.log(`merged ${parts.length} parts -> ${out}`);
