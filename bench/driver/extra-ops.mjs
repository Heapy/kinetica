// Extra large-table operations that are outside scaling.mjs's select/swap/update set.
// Sets up an N-row table unmeasured, then measures one traced click for append/remove/clear.
//
//   node bench/driver/extra-ops.mjs --frameworks=kinetica,react,vanilla --size=100000 --samples=1

import { mkdirSync, writeFileSync } from "node:fs";
import { arch, cpus, platform, totalmem } from "node:os";
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
  stats,
} from "./common.mjs";

const args = parseArgs();
const SIZE = Number(args.size ?? 100000);
const WARMUP = Number(args.warmup ?? 0);
const SAMPLES = Number(args.samples ?? 1);
const THROTTLE = Number(args.throttle ?? 0);
const OUT = args.out ?? join(driverDir, "..", "results", "extra-ops", `part-extra-${SIZE}.json`);

if (SIZE < 1000 || SIZE % 1000 !== 0) {
  console.error("error: size must be a multiple of 1000");
  process.exit(1);
}

const FRAMEWORKS = Object.fromEntries(frameworks.map((fw) => [fw.name, frameworkSelectors(fw)]));
const selectedFrameworks = (args.frameworks ?? "kinetica,react,vanilla")
  .split(",")
  .filter((f) => f in FRAMEWORKS);

const OPS = [
  {
    id: "append1k",
    label: `append 1,000 rows to ${SIZE.toLocaleString()}`,
    async action(h) {
      await h.clickButton("add");
    },
    async wait(h) {
      await h.waitRows(SIZE + 1000);
    },
  },
  {
    id: "remove1",
    label: `remove one row from ${SIZE.toLocaleString()}`,
    async action(h, fw) {
      await h.page.click(fw.rowRemove(5));
    },
    async wait(h) {
      await h.waitRows(SIZE - 1);
    },
  },
  {
    id: "clear",
    label: `clear ${SIZE.toLocaleString()} rows`,
    async action(h) {
      await h.clickButton("clear");
    },
    async wait(h) {
      await h.waitRows(0);
    },
  },
];

const benchPatterns = args.bench
  ? args.bench.split(",").map((value) => value.trim()).filter(Boolean)
  : [];
const selectedOps = benchPatterns.length > 0
  ? OPS.filter((op) => benchPatterns.some((pattern) => op.id.includes(pattern)))
  : OPS;

if (selectedOps.length === 0) {
  console.error(`error: no extra-ops benchmark matches: ${args.bench}`);
  process.exit(1);
}

async function ensureRows(h, size) {
  await h.clickButton("clear");
  await h.waitRows(0);
  let count;
  if (size >= 10000) {
    await h.clickButton("runlots");
    await h.waitRows(10000);
    count = 10000;
  } else {
    await h.clickButton("run");
    await h.waitRows(1000);
    count = 1000;
  }
  while (count < size) {
    await h.clickButton("add");
    count += 1000;
    await h.waitRows(count);
  }
}

const server = await startServer(repoRoot, PORT);
const browser = await launchChromium();
console.log(`chromium ${browser.version()}, extra ops size: ${SIZE}`);
console.log(`frameworks: ${selectedFrameworks.join(", ")}; ${WARMUP} warmup + ${SAMPLES} samples`);

const results = {};

for (const fwName of selectedFrameworks) {
  const fw = FRAMEWORKS[fwName];
  console.log(`\n=== ${fwName} ===`);
  const perOp = {};

  for (const op of selectedOps) {
    const samples = [];
    const setupSamples = [];
    let pageErrors = [];

    for (let i = 0; i < WARMUP + SAMPLES; i++) {
      const { context, page, pageErrors: errors } = await openPage(browser, fw.url, { throttle: THROTTLE });
      pageErrors = pageErrors.concat(errors);
      const h = makeHarness(page, fw);

      const setupStart = performance.now();
      await ensureRows(h, SIZE);
      const setupMs = performance.now() - setupStart;

      await page.waitForTimeout(120);
      const parsed = await measureTracedClick(
        browser,
        page,
        () => op.action(h, fw),
        () => op.wait(h),
      );
      await context.close();

      if (parsed.error) {
        throw new Error(`${fwName}/${op.id}: trace failed (${parsed.error})`);
      }

      if (i >= WARMUP) {
        samples.push(parsed.durationMs);
        setupSamples.push(setupMs);
      }
    }

    perOp[op.id] = {
      label: op.label,
      duration: stats(samples),
      setupMs: stats(setupSamples),
      pageErrors: pageErrors.length,
    };
    console.log(
      `  ${op.id}: median ${perOp[op.id].duration.median}ms ` +
      `(setup ${perOp[op.id].setupMs.median}ms)`,
    );
  }

  results[fwName] = perOp;
}

const out = {
  meta: {
    date: new Date().toISOString(),
    machine: {
      cpu: cpus()[0]?.model,
      arch: arch(),
      platform: platform(),
      memGb: Math.round(totalmem() / 1073741824),
    },
    chromium: browser.version(),
    size: SIZE,
    warmup: WARMUP,
    samples: SAMPLES,
    cpuThrottle: THROTTLE > 1 ? THROTTLE : null,
  },
  results,
};

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(out, null, 2));
console.log(`\nresults written to ${OUT}`);

await browser.close();
server.close();
