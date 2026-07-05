import { gzipSync } from "node:zlib";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { cpus, arch, platform, totalmem } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { startServer } from "./server.mjs";
import { frameworks } from "../frameworks.config.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..", "..");
const PORT = Number(process.env.BENCH_PORT ?? 4573);
const BASE = `http://127.0.0.1:${PORT}`;

const args = Object.fromEntries(
  process.argv.slice(2).filter((a) => a.startsWith("--")).map((a) => {
    const [k, v] = a.replace(/^--/, "").split("=");
    return [k, v ?? "true"];
  }),
);

const WARMUP = Number(args.warmup ?? 3);
const SAMPLES = Number(args.samples ?? 10);
const OUT = args.out ?? join(here, "..", "results", "results.json");

const vendoredPlaywright = join(repoRoot, ".tools", "playwright", "node_modules", "playwright", "index.mjs");
const vendoredChromium = join(
  repoRoot,
  ".playwright-browsers",
  "chromium-1228",
  "chrome-mac-arm64",
  "Google Chrome for Testing.app",
  "Contents",
  "MacOS",
  "Google Chrome for Testing",
);
const playwrightImport = process.env.PLAYWRIGHT_IMPORT ??
  (existsSync(vendoredPlaywright) ? vendoredPlaywright : "playwright");
const { chromium } = await import(
  playwrightImport.startsWith("/") ? pathToFileURL(playwrightImport).href : playwrightImport
);
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE ??
  (existsSync(vendoredChromium) ? vendoredChromium : undefined);

function resolveVersion(spec) {
  if (typeof spec === "string") return spec;
  if (spec?.package) {
    return JSON.parse(
      readFileSync(join(here, "..", "node_modules", spec.package, "package.json"), "utf8"),
    ).version;
  }
  return "n/a";
}

const FRAMEWORKS = Object.fromEntries(
  frameworks.map((fw) => [
    fw.name,
    {
      url: `${BASE}${fw.url}`,
      button: fw.buttons === "testid" ? (name) => `[data-testid="${name}"]` : (id) => `#${id}`,
      rowLink: (n) => `tbody tr:nth-child(${n}) td.col-label ${fw.rowControl}`,
      rowRemove: (n) => `tbody tr:nth-child(${n}) td.col-remove ${fw.rowControl}`,
      version: resolveVersion(fw.version),
    },
  ]),
);

const selectedFrameworks = (args.frameworks ?? Object.keys(FRAMEWORKS).join(","))
  .split(",")
  .filter((f) => f in FRAMEWORKS);

// --- helpers bound to a page + framework config ---

function makeHarness(page, fw) {
  const h = {
    page,
    async clickButton(name) {
      await page.click(fw.button(name));
    },
    async waitRows(count) {
      await page.waitForFunction(
        (expected) => document.querySelectorAll("tbody tr").length === expected,
        count,
        { polling: 100, timeout: 60_000 },
      );
    },
    async rowCount() {
      return page.evaluate(() => document.querySelectorAll("tbody tr").length);
    },
    async rowId(n) {
      return page.evaluate(
        (i) => document.querySelector(`tbody tr:nth-child(${i})`)?.getAttribute("data-id"),
        n,
      );
    },
    async firstLabel() {
      return page.evaluate(() => document.querySelector("tbody tr td.col-label")?.textContent ?? "");
    },
    async waitFn(fn, arg) {
      await page.waitForFunction(fn, arg, { polling: 100, timeout: 60_000 });
    },
  };
  return h;
}

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
];

const selectedBenchmarks = args.bench
  ? BENCHMARKS.filter((b) => b.id.includes(args.bench))
  : BENCHMARKS;

// --- trace parsing: click EventDispatch -> last Paint end, krausest-style ---

function parseTrace(buffer) {
  const { traceEvents } = JSON.parse(buffer.toString());
  const clicks = traceEvents.filter(
    (e) => e.name === "EventDispatch" && e.args?.data?.type === "click",
  );
  if (clicks.length === 0) return { error: "no click in trace" };
  const clickStart = Math.min(...clicks.map((e) => e.ts));
  const clickDur = Math.max(...clicks.map((e) => (e.dur ?? 0))) / 1000;
  let paintEnd = -1;
  let sawPaint = false;
  for (const e of traceEvents) {
    if (e.ts < clickStart) continue;
    if (e.name === "Paint" || e.name === "PaintImage") {
      sawPaint = true;
      paintEnd = Math.max(paintEnd, e.ts + (e.dur ?? 0));
    } else if (e.name === "Commit" || e.name === "CompositeLayers") {
      paintEnd = Math.max(paintEnd, e.ts + (e.dur ?? 0));
    }
  }
  if (!sawPaint || paintEnd < clickStart) return { error: "no paint after click" };
  return { durationMs: (paintEnd - clickStart) / 1000, clickDispatchMs: clickDur };
}

// --- stats ---

function stats(samples) {
  const sorted = [...samples].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  const median = sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
  const mean = samples.reduce((a, b) => a + b, 0) / samples.length;
  const stddev = Math.sqrt(samples.reduce((a, b) => a + (b - mean) ** 2, 0) / samples.length);
  return { median: round2(median), mean: round2(mean), stddev: round2(stddev), min: round2(sorted[0]), max: round2(sorted[sorted.length - 1]) };
}

const round2 = (x) => Math.round(x * 100) / 100;

// --- main ---

const server = await startServer(repoRoot, PORT);
const browser = await chromium.launch({ headless: true, executablePath });
console.log(`chromium ${browser.version()}, serving ${repoRoot} on :${PORT}`);
console.log(`frameworks: ${selectedFrameworks.join(", ")}; benchmarks: ${selectedBenchmarks.map((b) => b.id).join(", ")}`);
console.log(`${WARMUP} warmup + ${SAMPLES} measured samples per benchmark`);

const results = {};
const startup = {};
const memory = {};

for (const fwName of selectedFrameworks) {
  const fw = FRAMEWORKS[fwName];
  results[fwName] = {};
  console.log(`\n=== ${fwName} (${fw.url}) ===`);

  for (const bench of selectedBenchmarks) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    const pageErrors = [];
    page.on("pageerror", (e) => pageErrors.push(e.message));
    await page.goto(fw.url, { waitUntil: "networkidle" });
    await page.waitForFunction(() => window.__mountMs !== undefined, undefined, { polling: 100 });
    const h = makeHarness(page, fw);

    const samples = [];
    const clickDispatch = [];
    let failures = 0;
    for (let i = 0; i < WARMUP + SAMPLES; i++) {
      const measured = i >= WARMUP;
      const iterCtx = Object.create(bench);
      await iterCtx.prep(h, fw, i);
      await page.waitForTimeout(120);
      if (measured) {
        await browser.startTracing(page, {
          screenshots: false,
          categories: ["devtools.timeline", "disabled-by-default-devtools.timeline"],
        });
        await page.waitForTimeout(60);
      }
      await iterCtx.action(h, fw);
      await iterCtx.wait(h);
      if (measured) {
        await page.waitForTimeout(280);
        const trace = await browser.stopTracing();
        const parsed = parseTrace(trace);
        if (parsed.error) {
          failures++;
          if (failures > 4) throw new Error(`${fwName}/${bench.id}: too many trace failures (${parsed.error})`);
          i--; // retry this sample
          continue;
        }
        samples.push(parsed.durationMs);
        clickDispatch.push(parsed.clickDispatchMs);
      }
    }
    if (pageErrors.length) {
      console.log(`  !! page errors: ${pageErrors.slice(0, 3).join(" | ")}`);
    }
    const s = stats(samples);
    results[fwName][bench.id] = { samples: samples.map(round2), ...s, pageErrors: pageErrors.length };
    console.log(`  ${bench.id}: median ${s.median}ms (mean ${s.mean} ± ${s.stddev})`);
    await context.close();
  }

  // startup: fresh context per repetition
  const mountTimes = [];
  let resources = [];
  for (let i = 0; i < 5; i++) {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    await page.goto(fw.url, { waitUntil: "networkidle" });
    await page.waitForFunction(() => window.__mountMs !== undefined, undefined, { polling: 100 });
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
  startup[fwName] = { mountMs: mountTimes.map(round2), ...stats(mountTimes), jsBytes, gzipBytes, files: resources.length };
  console.log(`  startup: median ${startup[fwName].median}ms, weight ${(jsBytes / 1024).toFixed(0)}KB raw / ${(gzipBytes / 1024).toFixed(0)}KB gz (${resources.length} files)`);

  // memory: heap after load, heap after creating 1k rows
  {
    const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const page = await context.newPage();
    const cdp = await context.newCDPSession(page);
    await cdp.send("Performance.enable");
    await cdp.send("HeapProfiler.enable");
    await page.goto(fw.url, { waitUntil: "networkidle" });
    await page.waitForFunction(() => window.__mountMs !== undefined, undefined, { polling: 100 });
    const heap = async () => {
      await cdp.send("HeapProfiler.collectGarbage");
      await cdp.send("HeapProfiler.collectGarbage");
      await page.waitForTimeout(150);
      const { metrics } = await cdp.send("Performance.getMetrics");
      return metrics.find((m) => m.name === "JSHeapUsedSize")?.value ?? 0;
    };
    const afterLoad = await heap();
    const h = makeHarness(page, fw);
    await h.clickButton("run");
    await h.waitRows(1000);
    const after1k = await heap();
    memory[fwName] = { afterLoadMb: round2(afterLoad / 1048576), after1kMb: round2(after1k / 1048576) };
    console.log(`  memory: ${memory[fwName].afterLoadMb}MB after load, ${memory[fwName].after1kMb}MB after 1k rows`);
    await context.close();
  }
}

const out = {
  meta: {
    date: new Date().toISOString(),
    machine: { cpu: cpus()[0]?.model, arch: arch(), platform: platform(), memGb: Math.round(totalmem() / 1073741824) },
    chromium: browser.version(),
    warmup: WARMUP,
    samples: SAMPLES,
    methodology:
      "Playwright trusted clicks; per-operation Chrome trace (devtools.timeline); duration = click EventDispatch start -> end of last Paint/Commit event; headless Chrome for Testing; no CPU throttling; local static server.",
    versions: Object.fromEntries(selectedFrameworks.map((f) => [f, FRAMEWORKS[f].version])),
  },
  benchmarks: BENCHMARKS.map(({ id, label }) => ({ id, label })),
  results,
  startup,
  memory,
};
mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, JSON.stringify(out, null, 2));
console.log(`\nresults written to ${OUT}`);

await browser.close();
server.close();
