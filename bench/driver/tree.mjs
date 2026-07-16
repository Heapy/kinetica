// Deep-tree benchmark driver (UIBench-style). Runs against each framework's tree app
// (config `treeUrl`; contract: depth 4 / fanout 6 keyed tree = 1555 nodes, 1296 leaves;
// buttons run/update/reverse/noop + a status counter). Measures where the flat keyed
// table can't: deep keyed subtree moves, propagation through nesting, and pure
// re-render overhead when data hasn't changed (noop).
//
//   node driver/tree.mjs --frameworks=kinetica --samples=3
//
// Results: results/tree/part-<framework>.json, merged by run-all into results/tree.json.

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { startServer } from "./server.mjs";
import { frameworks } from "../frameworks.config.mjs";
import {
  driverDir,
  frameworkSelectors,
  launchChromium,
  makeHarness,
  measureTracedClick,
  openPage,
  parseArgs,
  PORT,
  repoRoot,
  round2,
  stats,
} from "./common.mjs";

const args = parseArgs();
const WARMUP = Number(args.warmup ?? 3);
const SAMPLES = Number(args.samples ?? 10);
const THROTTLE = Number(args.throttle ?? 0);
const OUT = args.out ?? join(driverDir, "..", "results", "tree", "results.json");

const TOTAL_NODES = 1555;

const FRAMEWORKS = Object.fromEntries(frameworks.map((fw) => [fw.name, frameworkSelectors(fw)]));
const selectedFrameworks = (args.frameworks ?? Object.keys(FRAMEWORKS).join(","))
  .split(",")
  .filter((f) => f in FRAMEWORKS && FRAMEWORKS[f].treeUrl);

async function ensureTree(h) {
  const count = await h.page.evaluate(() => document.querySelectorAll(".tree-node").length);
  if (count !== TOTAL_NODES) {
    const prev = await firstNodeId(h);
    await h.clickButton("run");
    await waitTreeBuilt(h, prev);
  }
}

function firstNodeId(h) {
  return h.page.evaluate(() => document.querySelector(".tree-node")?.getAttribute("data-id") ?? null);
}

async function waitTreeBuilt(h, prevFirstId) {
  await h.waitFn(
    ({ total, prev }) => {
      const nodes = document.querySelectorAll(".tree-node");
      return nodes.length === total && nodes[0]?.getAttribute("data-id") !== prev;
    },
    { total: TOTAL_NODES, prev: prevFirstId },
  );
}

async function nextTick(h) {
  return Number(await h.statusText()) + 1;
}

const TREE_BENCHMARKS = [
  {
    id: "t1_createTree",
    label: `create tree (${TOTAL_NODES} nodes)`,
    async prep(h) {
      this.prevFirst = await firstNodeId(h);
    },
    async action(h) {
      await h.clickButton("run");
    },
    async wait(h) {
      await waitTreeBuilt(h, this.prevFirst);
    },
  },
  {
    id: "t2_updateLeaves",
    label: "update every 10th leaf",
    async prep(h) {
      await ensureTree(h);
      this.expected = await nextTick(h);
    },
    async action(h) {
      await h.clickButton("update");
    },
    async wait(h) {
      await h.waitFn(
        ({ tick, sel }) =>
          document.querySelector(sel)?.textContent === String(tick) &&
          (document.querySelector(".tree-leaf")?.textContent ?? "").endsWith(" !" + tick),
        { tick: this.expected, sel: h.fwStatus },
      );
    },
  },
  {
    id: "t3_reverseTop",
    label: "reverse top-level subtrees",
    async prep(h) {
      await ensureTree(h);
      this.prevChild = await h.page.evaluate(
        () => document.querySelectorAll(".tree-node")[1]?.getAttribute("data-id") ?? null,
      );
    },
    async action(h) {
      await h.clickButton("reverse");
    },
    async wait(h) {
      await h.waitFn(
        (prev) => document.querySelectorAll(".tree-node")[1]?.getAttribute("data-id") !== prev,
        this.prevChild,
      );
    },
  },
  {
    id: "t4_noopRender",
    label: "no-op re-render (data unchanged)",
    async prep(h) {
      await ensureTree(h);
      this.expected = await nextTick(h);
    },
    async action(h) {
      await h.clickButton("noop");
    },
    async wait(h) {
      await h.waitFn(
        ({ tick, sel }) => document.querySelector(sel)?.textContent === String(tick),
        { tick: this.expected, sel: h.fwStatus },
      );
    },
  },
];

const benchPatterns = args.bench
  ? args.bench.split(",").map((value) => value.trim()).filter(Boolean)
  : [];
const selectedBenchmarks = benchPatterns.length > 0
  ? TREE_BENCHMARKS.filter((b) => benchPatterns.some((pattern) => b.id.includes(pattern)))
  : TREE_BENCHMARKS;

if (selectedBenchmarks.length === 0) {
  console.error(`error: no tree benchmark matches: ${args.bench}`);
  process.exit(1);
}

const server = await startServer(repoRoot, PORT);
const browser = await launchChromium();
console.log(`chromium ${browser.version()}, tree bench (${TOTAL_NODES} nodes)`);
console.log(`frameworks: ${selectedFrameworks.join(", ")}; ${WARMUP} warmup + ${SAMPLES} samples`);

const tree = {};

for (const fwName of selectedFrameworks) {
  const fw = FRAMEWORKS[fwName];
  tree[fwName] = {};
  console.log(`\n=== ${fwName} (${fw.treeUrl}) ===`);

  for (const bench of selectedBenchmarks) {
    const { context, page, pageErrors } = await openPage(browser, fw.treeUrl, { throttle: THROTTLE });
    const h = makeHarness(page, fw);
    h.fwStatus = fw.status;

    const samples = [];
    const gcSamples = [];
    let failures = 0;
    for (let i = 0; i < WARMUP + SAMPLES; i++) {
      const iterCtx = Object.create(bench);
      await iterCtx.prep(h, fw, i);
      await page.waitForTimeout(120);
      if (i < WARMUP) {
        await iterCtx.action(h);
        await iterCtx.wait(h);
        continue;
      }
      const parsed = await measureTracedClick(
        browser,
        page,
        () => iterCtx.action(h),
        () => iterCtx.wait(h),
      );
      if (parsed.error) {
        failures++;
        if (failures > 4) throw new Error(`${fwName}/${bench.id}: too many trace failures (${parsed.error})`);
        i--;
        continue;
      }
      samples.push(parsed.durationMs);
      gcSamples.push(parsed.gcMs);
    }
    if (pageErrors.length) console.log(`  !! page errors: ${pageErrors.slice(0, 3).join(" | ")}`);
    const s = stats(samples);
    tree[fwName][bench.id] = {
      samples: samples.map(round2),
      ...s,
      gcMedianMs: stats(gcSamples).median,
      pageErrors: pageErrors.length,
    };
    console.log(`  ${bench.id}: median ${s.median}ms (mean ${s.mean} ± ${s.stddev})`);
    await context.close();
  }
}

const out = {
  meta: {
    date: new Date().toISOString(),
    chromium: browser.version(),
    warmup: WARMUP,
    samples: SAMPLES,
    totalNodes: TOTAL_NODES,
    cpuThrottle: THROTTLE > 1 ? THROTTLE : null,
  },
  treeBenchmarks: selectedBenchmarks.map(({ id, label }) => ({ id, label })),
  tree,
};
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(out, null, 2));
console.log(`\nresults written to ${OUT}`);

await browser.close();
server.close();
