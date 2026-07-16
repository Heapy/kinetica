// Scaling curves: measure the same partial operation at several table sizes and fit
// a log-log slope per operation. A keyed framework should be ~flat for select/swap
// (the DOM work is constant) and ~linear for update (work is n/10 labels); a slope
// meaningfully above the threshold flags a complexity-class regression (the
// O(n²)-at-10k class of bug) even while absolute 1k numbers still look fine.
//
//   node driver/scaling.mjs                          # all frameworks, default sizes
//   node driver/scaling.mjs --frameworks=kinetica
//   node driver/scaling.mjs --sizes=1000,5000,20000 --samples=5 --warmup=1
//   node driver/scaling.mjs --strict                 # exit 1 if any op is superlinear
//
// Sizes must be multiples of 1,000 (reached via run/runlots + repeated add clicks).
// Results: results/scaling/part-<framework>.json, merged by run.mjs into
// results/scaling.json (or stress.json for the opt-in large-table tier).

import { mkdirSync, writeFileSync } from "node:fs";
import { arch, cpus, platform, totalmem } from "node:os";
import { dirname, join } from "node:path";
import { startServer } from "./server.mjs";
import { frameworks } from "../frameworks.config.mjs";
import {
  driverDir,
  frameworkSelectors,
  launchChromium,
  logLogSlope,
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
const WARMUP = Number(args.warmup ?? 1);
const SAMPLES = Number(args.samples ?? 5);
const THROTTLE = Number(args.throttle ?? 0);
const SIZES = (args.sizes ?? "1000,2000,5000,10000,20000").split(",").map(Number);
const OUT = args.out ?? join(driverDir, "..", "results", "scaling", "part-scaling.json");

if (SIZES.some((s) => s < 1000 || s % 1000 !== 0)) {
  console.error("error: sizes must be multiples of 1000 (reached via run/runlots + add)");
  process.exit(1);
}

const FRAMEWORKS = Object.fromEntries(frameworks.map((fw) => [fw.name, frameworkSelectors(fw)]));
const selectedFrameworks = (args.frameworks ?? Object.keys(FRAMEWORKS).join(","))
  .split(",")
  .filter((f) => f in FRAMEWORKS);

// resetBetweenSamples: update mutates labels cumulatively, so it needs a fresh table
// per sample; select/swap leave the table structurally equivalent.
const OPS = [
  {
    id: "select",
    label: "select row",
    threshold: 0.6,
    resetBetweenSamples: false,
    async prep(h, fw, i) {
      this.row = (i % 10) + 2;
    },
    async action(h, fw) {
      await h.page.click(fw.rowLink(this.row));
    },
    async wait(h) {
      await h.waitFn(
        (r) => document.querySelector(`tbody tr:nth-child(${r})`)?.classList.contains("danger"),
        this.row,
      );
    },
  },
  {
    id: "swap",
    label: "swap two rows",
    threshold: 0.6,
    resetBetweenSamples: false,
    async prep(h) {
      this.prevRow2 = await h.rowId(2);
    },
    async action(h) {
      await h.clickButton("swaprows");
    },
    async wait(h) {
      const prev = this.prevRow2;
      await h.waitFn(
        (p) =>
          document.querySelector("tbody tr:nth-child(999)")?.getAttribute("data-id") === p &&
          document.querySelector("tbody tr:nth-child(2)")?.getAttribute("data-id") !== p,
        prev,
      );
    },
  },
  {
    id: "update",
    label: "update every 10th row",
    threshold: 1.3,
    resetBetweenSamples: true,
    async prep(h) {},
    async action(h) {
      await h.clickButton("update");
    },
    async wait(h) {
      await h.waitFn(() =>
        (document.querySelector("tbody tr td.col-label")?.textContent ?? "").endsWith(" !!!"),
      );
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
  console.error(`error: no scaling benchmark matches: ${args.bench}`);
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
console.log(`chromium ${browser.version()}, scaling sizes: ${SIZES.join(", ")}`);
console.log(`frameworks: ${selectedFrameworks.join(", ")}; ${WARMUP} warmup + ${SAMPLES} samples per size`);

const scaling = {};
let superlinearSeen = false;

for (const fwName of selectedFrameworks) {
  const fw = FRAMEWORKS[fwName];
  console.log(`\n=== ${fwName} ===`);
  const perOp = {};

  for (const op of selectedOps) {
    const bySize = {};
    for (const size of SIZES) {
      const { context, page, pageErrors } = await openPage(browser, fw.url, { throttle: THROTTLE });
      const h = makeHarness(page, fw);
      await ensureRows(h, size);

      const samples = [];
      let failures = 0;
      for (let i = 0; i < WARMUP + SAMPLES; i++) {
        if (op.resetBetweenSamples && i > 0) {
          await ensureRows(h, size);
        }
        const iterCtx = Object.create(op);
        await iterCtx.prep(h, fw, i);
        await page.waitForTimeout(120);
        if (i < WARMUP) {
          await iterCtx.action(h, fw);
          await iterCtx.wait(h);
          continue;
        }
        const parsed = await measureTracedClick(
          browser,
          page,
          () => iterCtx.action(h, fw),
          () => iterCtx.wait(h),
        );
        if (parsed.error) {
          failures++;
          if (failures > 4) throw new Error(`${fwName}/${op.id}@${size}: too many trace failures (${parsed.error})`);
          i--;
          continue;
        }
        samples.push(parsed.durationMs);
      }
      if (pageErrors.length) console.log(`  !! page errors: ${pageErrors.slice(0, 3).join(" | ")}`);
      bySize[size] = stats(samples);
      await context.close();
    }

    const medians = SIZES.map((s) => bySize[s].median);
    const { exponent, r2 } = logLogSlope(SIZES, medians);
    const superlinear = exponent > op.threshold;
    if (superlinear) superlinearSeen = true;
    perOp[op.id] = { label: op.label, threshold: op.threshold, bySize, exponent, r2, superlinear };
    console.log(
      `  ${op.id}: ${SIZES.map((s) => `${s / 1000}k=${bySize[s].median}ms`).join(" ")} ` +
      `-> exponent ${exponent} (r² ${r2})${superlinear ? " ⚠ SUPERLINEAR" : ""}`,
    );
  }

  scaling[fwName] = { sizes: SIZES, ops: perOp };
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
    warmup: WARMUP,
    samples: SAMPLES,
    sizes: SIZES,
    cpuThrottle: THROTTLE > 1 ? THROTTLE : null,
  },
  scaling,
};
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(out, null, 2));
console.log(`\nresults written to ${OUT}`);

await browser.close();
server.close();

if (args.strict && superlinearSeen) {
  console.error("strict mode: superlinear scaling detected");
  process.exit(1);
}
