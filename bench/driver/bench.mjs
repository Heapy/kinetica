import { gzipSync } from "node:zlib";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { cpus, arch, platform, totalmem } from "node:os";
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
  parseStartupTrace,
  percentile,
  PORT,
  repoRoot,
  round2,
  stats,
} from "./common.mjs";

const args = parseArgs();

const WARMUP = Number(args.warmup ?? 3);
const SAMPLES = Number(args.samples ?? 10);
const THROTTLE = Number(args.throttle ?? 0);
const ANIM_SECONDS = Number(args["anim-seconds"] ?? 6);
const OUT = args.out ?? join(driverDir, "..", "results", "results.json");

const FRAMEWORKS = Object.fromEntries(frameworks.map((fw) => [fw.name, frameworkSelectors(fw)]));

const selectedFrameworks = (args.frameworks ?? Object.keys(FRAMEWORKS).join(","))
  .split(",")
  .filter((f) => f in FRAMEWORKS);

// Each benchmark: prep runs unmeasured, action is the single measured click,
// wait resolves when the operation's DOM effect is complete.
const BENCHMARKS = [
  {
    id: "01_run1k",
    label: "create 1,000 rows",
    async prep(h) {
      await h.clickButton("clear");
      await h.waitRows(0);
    },
    async action(h, fw) {
      await h.clickButton("run");
    },
    async wait(h) {
      await h.waitRows(1000);
    },
  },
  {
    id: "02_replace1k",
    label: "replace all 1,000 rows",
    async prep(h) {
      await h.clickButton("run");
      await h.waitRows(1000);
      this.prevId = await h.rowId(1);
    },
    async action(h) {
      await h.clickButton("run");
    },
    async wait(h) {
      const prev = this.prevId;
      await h.waitFn(
        (p) =>
          document.querySelectorAll("tbody tr").length === 1000 &&
          document.querySelector("tbody tr")?.getAttribute("data-id") !== p,
        prev,
      );
    },
  },
  {
    id: "03_update10th1k",
    label: "partial update (every 10th row)",
    async prep(h) {
      await h.clickButton("run");
      await h.waitRows(1000);
    },
    async action(h) {
      await h.clickButton("update");
    },
    async wait(h) {
      await h.waitFn(() =>
        (document.querySelector("tbody tr td.col-label")?.textContent ?? "").endsWith(" !!!"),
      );
    },
  },
  {
    id: "04_select1k",
    label: "select row",
    async prep(h, fw, i) {
      const count = await h.rowCount();
      if (count !== 1000) {
        await h.clickButton("run");
        await h.waitRows(1000);
      }
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
    id: "05_swap1k",
    label: "swap two rows",
    async prep(h) {
      const count = await h.rowCount();
      if (count !== 1000) {
        await h.clickButton("run");
        await h.waitRows(1000);
      }
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
    id: "06_remove1k",
    label: "remove one row",
    async prep(h) {
      await h.clickButton("run");
      await h.waitRows(1000);
    },
    async action(h, fw) {
      await h.page.click(fw.rowRemove(5));
    },
    async wait(h) {
      await h.waitRows(999);
    },
  },
  {
    id: "07_create10k",
    label: "create 10,000 rows",
    async prep(h) {
      await h.clickButton("clear");
      await h.waitRows(0);
    },
    async action(h) {
      await h.clickButton("runlots");
    },
    async wait(h) {
      await h.waitRows(10000);
    },
  },
  {
    id: "08_append1k",
    label: "append 1,000 rows to 1,000",
    async prep(h) {
      await h.clickButton("run");
      await h.waitRows(1000);
    },
    async action(h) {
      await h.clickButton("add");
    },
    async wait(h) {
      await h.waitRows(2000);
    },
  },
  {
    id: "09_clear1k",
    label: "clear 1,000 rows",
    async prep(h) {
      await h.clickButton("run");
      await h.waitRows(1000);
    },
    async action(h) {
      await h.clickButton("clear");
    },
    async wait(h) {
      await h.waitRows(0);
    },
  },
  // Partial operations on the 10k table: same DOM effect as the 1k variants, but the
  // framework's bookkeeping runs over 10x the rows — this is where accidental O(n²)
  // reconciliation shows up while the 1k ops still look healthy.
  {
    id: "10_select10k",
    label: "select row (10k table)",
    async prep(h, fw, i) {
      const count = await h.rowCount();
      if (count !== 10000) {
        await h.clickButton("runlots");
        await h.waitRows(10000);
      }
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
    id: "11_swap10k",
    label: "swap two rows (10k table)",
    async prep(h) {
      const count = await h.rowCount();
      if (count !== 10000) {
        await h.clickButton("runlots");
        await h.waitRows(10000);
      }
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
    id: "12_remove10k",
    label: "remove one row (10k table)",
    async prep(h) {
      await h.clickButton("runlots");
      await h.waitRows(10000);
    },
    async action(h, fw) {
      await h.page.click(fw.rowRemove(5));
    },
    async wait(h) {
      await h.waitRows(9999);
    },
  },
  {
    id: "13_update10th10k",
    label: "partial update (every 10th of 10k)",
    async prep(h) {
      await h.clickButton("runlots");
      await h.waitRows(10000);
    },
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

const selectedBenchmarks = args.bench && args.bench !== "anim"
  ? BENCHMARKS.filter((b) => b.id.includes(args.bench))
  : BENCHMARKS;
const runOps = args.bench !== "anim";
const runAnimation = !args.bench || args.bench === "anim";
const runStartupAndMemory = !args.bench;

// --- main ---

const server = await startServer(repoRoot, PORT);
const browser = await launchChromium();
console.log(`chromium ${browser.version()}, serving ${repoRoot} on :${PORT}`);
console.log(`frameworks: ${selectedFrameworks.join(", ")}; benchmarks: ${selectedBenchmarks.map((b) => b.id).join(", ")}`);
console.log(`${WARMUP} warmup + ${SAMPLES} measured samples per benchmark${THROTTLE > 1 ? `; CPU throttle ${THROTTLE}x` : ""}`);

const results = {};
const startup = {};
const memory = {};
const animation = {};

for (const fwName of selectedFrameworks) {
  const fw = FRAMEWORKS[fwName];
  results[fwName] = {};
  console.log(`\n=== ${fwName} (${fw.url}) ===`);

  if (runOps) {
    for (const bench of selectedBenchmarks) {
      const { context, page, pageErrors } = await openPage(browser, fw.url, { throttle: THROTTLE });
      const h = makeHarness(page, fw);

      const samples = [];
      const clickDispatch = [];
      const gcSamples = [];
      const gcCounts = [];
      let failures = 0;
      for (let i = 0; i < WARMUP + SAMPLES; i++) {
        const measured = i >= WARMUP;
        const iterCtx = Object.create(bench);
        await iterCtx.prep(h, fw, i);
        await page.waitForTimeout(120);
        if (!measured) {
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
          if (failures > 4) throw new Error(`${fwName}/${bench.id}: too many trace failures (${parsed.error})`);
          i--; // retry this sample
          continue;
        }
        samples.push(parsed.durationMs);
        clickDispatch.push(parsed.clickDispatchMs);
        gcSamples.push(parsed.gcMs);
        gcCounts.push(parsed.gcCount);
      }
      if (pageErrors.length) {
        console.log(`  !! page errors: ${pageErrors.slice(0, 3).join(" | ")}`);
      }
      const s = stats(samples);
      const gc = stats(gcSamples);
      results[fwName][bench.id] = {
        samples: samples.map(round2),
        ...s,
        gcMedianMs: gc.median,
        gcMaxMs: gc.max,
        gcMeanCount: round2(gcCounts.reduce((a, b) => a + b, 0) / gcCounts.length),
        pageErrors: pageErrors.length,
      };
      console.log(`  ${bench.id}: median ${s.median}ms (mean ${s.mean} ± ${s.stddev}; gc ${gc.median}ms)`);
      await context.close();
    }
  }

  // startup: 5 untraced cold loads for mount time + resource weight (comparable with
  // historical parts), then 2 traced loads for script-evaluate time and TBT.
  if (runStartupAndMemory) {
    const mountTimes = [];
    let resources = [];
    for (let i = 0; i < 5; i++) {
      const { context, page } = await openPage(browser, fw.url);
      mountTimes.push(await page.evaluate(() => window.__mountMs));
      if (i === 4) {
        resources = await page.evaluate(() =>
          performance.getEntriesByType("resource")
            .filter((r) => /\.(m?js)(\?|$)/.test(r.name))
            .map((r) => ({ name: new URL(r.name).pathname, bytes: r.decodedBodySize })),
        );
      }
      await context.close();
    }
    const jsBytes = resources.reduce((a, r) => a + r.bytes, 0);
    let gzipBytes = 0;
    for (const r of resources) {
      try {
        gzipBytes += gzipSync(readFileSync(join(repoRoot, r.name.replace(/^\//, "")))).length;
      } catch {
        gzipBytes += r.bytes; // fallback if the URL doesn't map to a file
      }
    }

    const scriptTimes = [];
    const tbtTimes = [];
    for (let i = 0; i < 2; i++) {
      const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
      const page = await context.newPage();
      await browser.startTracing(page, {
        screenshots: false,
        categories: ["devtools.timeline", "disabled-by-default-devtools.timeline"],
      });
      await page.goto(fw.url, { waitUntil: "networkidle" });
      await page.waitForFunction(() => window.__mountMs !== undefined, undefined, { polling: 100 });
      const parsed = parseStartupTrace(await browser.stopTracing());
      scriptTimes.push(parsed.scriptMs);
      if (parsed.tbtMs !== null) tbtTimes.push(parsed.tbtMs);
      await context.close();
    }

    startup[fwName] = {
      mountMs: mountTimes.map(round2),
      ...stats(mountTimes),
      jsBytes,
      gzipBytes,
      files: resources.length,
      scriptMs: round2(Math.min(...scriptTimes)),
      tbtMs: tbtTimes.length ? round2(Math.min(...tbtTimes)) : null,
    };
    console.log(
      `  startup: median ${startup[fwName].median}ms, weight ${(jsBytes / 1024).toFixed(0)}KB raw / ` +
      `${(gzipBytes / 1024).toFixed(0)}KB gz (${resources.length} files), ` +
      `script ${startup[fwName].scriptMs}ms, tbt ${startup[fwName].tbtMs ?? "n/a"}ms`,
    );
  }

  // memory: heap after load and after creating 1k rows, then churn/leak probes —
  // 5x replace, 10x create+clear cycles, and unmount/remount via the app hooks.
  if (runStartupAndMemory) {
    const { context, page } = await openPage(browser, fw.url);
    const cdp = await context.newCDPSession(page);
    await cdp.send("Performance.enable");
    await cdp.send("HeapProfiler.enable");
    const heap = async () => {
      await cdp.send("HeapProfiler.collectGarbage");
      await cdp.send("HeapProfiler.collectGarbage");
      await page.waitForTimeout(150);
      const { metrics } = await cdp.send("Performance.getMetrics");
      return metrics.find((m) => m.name === "JSHeapUsedSize")?.value ?? 0;
    };
    const mb = (bytes) => round2(bytes / 1048576);

    const afterLoad = await heap();
    const h = makeHarness(page, fw);
    await h.clickButton("run");
    await h.waitRows(1000);
    const after1k = await heap();

    for (let i = 0; i < 5; i++) {
      const prev = await h.rowId(1);
      await h.clickButton("run");
      await h.waitFn(
        (p) =>
          document.querySelectorAll("tbody tr").length === 1000 &&
          document.querySelector("tbody tr")?.getAttribute("data-id") !== p,
        prev,
      );
    }
    const after5xReplace = await heap();

    for (let i = 0; i < 10; i++) {
      await h.clickButton("run");
      await h.waitRows(1000);
      await h.clickButton("clear");
      await h.waitRows(0);
    }
    const afterCreateClear10 = await heap();

    const hasHooks = await page.evaluate(
      () => typeof window.__unmount === "function" && typeof window.__mount === "function",
    );
    let afterUnmount = null;
    let after5xRemount = null;
    if (hasHooks) {
      const mainEmpty = () => document.getElementById("main").childElementCount === 0;
      await page.evaluate(() => window.__unmount());
      await h.waitFn(mainEmpty);
      afterUnmount = await heap();
      for (let i = 0; i < 5; i++) {
        await page.evaluate(() => window.__mount());
        await h.waitFn(() => document.getElementById("main").childElementCount > 0);
        await page.evaluate(() => window.__unmount());
        await h.waitFn(mainEmpty);
      }
      after5xRemount = await heap();
    }

    memory[fwName] = {
      afterLoadMb: mb(afterLoad),
      after1kMb: mb(after1k),
      after5xReplaceMb: mb(after5xReplace),
      afterCreateClear10Mb: mb(afterCreateClear10),
      afterUnmountMb: afterUnmount === null ? null : mb(afterUnmount),
      after5xRemountMb: after5xRemount === null ? null : mb(after5xRemount),
    };
    const m = memory[fwName];
    console.log(
      `  memory: ${m.afterLoadMb}MB load, ${m.after1kMb}MB 1k, ${m.after5xReplaceMb}MB 5x replace, ` +
      `${m.afterCreateClear10Mb}MB 10x create/clear, ` +
      `${m.afterUnmountMb ?? "n/a"}MB unmounted, ${m.after5xRemountMb ?? "n/a"}MB after 5x remount`,
    );
    await context.close();
  }

  // sustained updates: the app's "animate" toggle mutates every 10th row per rAF;
  // an injected collector records real frame deltas while the loop runs.
  if (runAnimation) {
    const { context, page, pageErrors } = await openPage(browser, fw.url, { throttle: THROTTLE });
    const h = makeHarness(page, fw);
    const hasAnimate = await page.$(fw.button("animate"));
    if (!hasAnimate) {
      console.log("  animation: no animate button, skipped");
      await context.close();
    } else {
      await h.clickButton("run");
      await h.waitRows(1000);
      await page.waitForTimeout(200);
      await page.evaluate(() => {
        window.__frameDeltas = [];
        window.__collect = true;
        let last;
        const loop = (ts) => {
          if (last !== undefined) window.__frameDeltas.push(ts - last);
          last = ts;
          if (window.__collect) requestAnimationFrame(loop);
        };
        requestAnimationFrame(loop);
      });
      await h.clickButton("animate");
      await page.waitForTimeout(ANIM_SECONDS * 1000);
      await h.clickButton("animate");
      const deltas = await page.evaluate(() => {
        window.__collect = false;
        return window.__frameDeltas;
      });
      const mutated = await page.evaluate(() =>
        /( !\d+)$/.test(document.querySelector("tbody tr td.col-label")?.textContent ?? ""),
      );
      // drop the first 500ms as ramp-up
      let ramp = 0;
      let skip = 0;
      while (skip < deltas.length && ramp < 500) ramp += deltas[skip++];
      const measured = deltas.slice(skip);
      if (!mutated || measured.length === 0) {
        console.log(`  !! animation did not mutate rows (mutated=${mutated}, frames=${measured.length})`);
        animation[fwName] = { error: "animate loop had no observable effect" };
      } else {
        const meanMs = measured.reduce((a, b) => a + b, 0) / measured.length;
        animation[fwName] = {
          seconds: ANIM_SECONDS,
          frames: measured.length,
          fps: round2(1000 / meanMs),
          meanMs: round2(meanMs),
          medianMs: round2(percentile(measured, 0.5)),
          p95Ms: round2(percentile(measured, 0.95)),
          longFramePct: round2(100 * measured.filter((d) => d > 25).length / measured.length),
          pageErrors: pageErrors.length,
        };
        const a = animation[fwName];
        console.log(
          `  animation: ${a.fps} fps over ${a.frames} frames (median ${a.medianMs}ms, ` +
          `p95 ${a.p95Ms}ms, long frames ${a.longFramePct}%)`,
        );
      }
      await context.close();
    }
  }
}

const out = {
  meta: {
    date: new Date().toISOString(),
    machine: { cpu: cpus()[0]?.model, arch: arch(), platform: platform(), memGb: Math.round(totalmem() / 1073741824) },
    chromium: browser.version(),
    warmup: WARMUP,
    samples: SAMPLES,
    cpuThrottle: THROTTLE > 1 ? THROTTLE : null,
    methodology:
      "Playwright trusted clicks; per-operation Chrome trace (devtools.timeline); duration = click EventDispatch start -> end of last Paint/Commit event; headless Chrome for Testing; local static server." +
      (THROTTLE > 1 ? ` CPU throttled ${THROTTLE}x via CDP for ops and animation.` : " No CPU throttling."),
    versions: Object.fromEntries(selectedFrameworks.map((f) => [f, FRAMEWORKS[f].version])),
  },
  benchmarks: BENCHMARKS.map(({ id, label }) => ({ id, label })),
  results,
  startup,
  memory,
  animation,
};
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(out, null, 2));
console.log(`\nresults written to ${OUT}`);

await browser.close();
server.close();
