import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

export const driverDir = dirname(fileURLToPath(import.meta.url));
export const repoRoot = join(driverDir, "..", "..");
export const PORT = Number(process.env.BENCH_PORT ?? 4573);
export const BASE = `http://127.0.0.1:${PORT}`;

export function parseArgs(argv = process.argv.slice(2)) {
  return Object.fromEntries(
    argv.filter((a) => a.startsWith("--")).map((a) => {
      const [k, v] = a.replace(/^--/, "").split("=");
      return [k, v ?? "true"];
    }),
  );
}

export async function launchChromium() {
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
  // Headed/slow runs are demos only; display compositing makes their timings incomparable.
  const headless = process.env.BENCH_HEADED !== "1";
  const slowMo = Number(process.env.BENCH_SLOWMO ?? 0);
  return chromium.launch({ headless, executablePath, slowMo });
}

export function resolveVersion(spec) {
  if (typeof spec === "string") return spec;
  if (spec?.package) {
    return JSON.parse(
      readFileSync(join(driverDir, "..", "node_modules", spec.package, "package.json"), "utf8"),
    ).version;
  }
  return "n/a";
}

export function frameworkSelectors(fw) {
  const button = fw.buttons === "testid" ? (name) => `[data-testid="${name}"]` : (id) => `#${id}`;
  return {
    name: fw.name,
    url: `${BASE}${fw.url}`,
    treeUrl: fw.treeUrl ? `${BASE}${fw.treeUrl}` : null,
    button,
    status: fw.buttons === "testid" ? '[data-testid="status"]' : "#status",
    rowLink: (n) => `tbody tr:nth-child(${n}) td.col-label ${fw.rowControl}`,
    rowRemove: (n) => `tbody tr:nth-child(${n}) td.col-remove ${fw.rowControl}`,
    driver: fw.driver ?? "dom",
    renderer: fw.renderer ?? "dom",
    suites: fw.suites ?? null,
    version: resolveVersion(fw.version),
  };
}

// Keep page-state access behind this boundary so DOM and canvas runs share benchmark definitions.
export function makeHarness(page, fw) {
  return {
    page,
    async clickButton(name) {
      await page.click(fw.button(name));
    },
    async hasButton(name) {
      return (await page.$(fw.button(name))) !== null;
    },
    async clickRowSelect(n) {
      await page.click(fw.rowLink(n));
    },
    async clickRowRemove(n) {
      await page.click(fw.rowRemove(n));
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
    async statusText() {
      return page.evaluate((sel) => document.querySelector(sel)?.textContent ?? "", fw.status);
    },
    async waitFn(fn, arg) {
      await page.waitForFunction(fn, arg, { polling: 100, timeout: 60_000 });
    },
    async waitReplaced(prevId, count) {
      await this.waitFn(
        ({ p, c }) =>
          document.querySelectorAll("tbody tr").length === c &&
          document.querySelector("tbody tr")?.getAttribute("data-id") !== p,
        { p: prevId, c: count },
      );
    },
    async waitLabelSuffix(suffix) {
      await this.waitFn(
        (s) => (document.querySelector("tbody tr td.col-label")?.textContent ?? "").endsWith(s),
        suffix,
      );
    },
    async waitSelected(n) {
      await this.waitFn(
        (r) => document.querySelector(`tbody tr:nth-child(${r})`)?.classList.contains("danger"),
        n,
      );
    },
    async waitSwapped(prevRow2) {
      await this.waitFn(
        (p) =>
          document.querySelector("tbody tr:nth-child(999)")?.getAttribute("data-id") === p &&
          document.querySelector("tbody tr:nth-child(2)")?.getAttribute("data-id") !== p,
        prevRow2,
      );
    },
    async labelHasTick() {
      return page.evaluate(() =>
        /( !\d+)$/.test(document.querySelector("tbody tr td.col-label")?.textContent ?? ""),
      );
    },
  };
}

export async function harnessFor(page, fw) {
  if (fw.driver === "canvas") {
    const { makeCanvasHarness } = await import("./canvas-harness.mjs");
    return makeCanvasHarness(page);
  }
  return makeHarness(page, fw);
}

export async function openPage(browser, url, { throttle = 0 } = {}) {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  // This automation guard is outside trace-derived durations and must allow deliberately slow
  // 10k operations to finish on every framework.
  page.setDefaultTimeout(180_000);
  const pageErrors = [];
  page.on("pageerror", (e) => pageErrors.push(e.message));
  if (throttle > 1) {
    const cdp = await context.newCDPSession(page);
    await cdp.send("Emulation.setCPUThrottlingRate", { rate: throttle });
  }
  await page.goto(url, { waitUntil: "networkidle" });
  await page.waitForFunction(() => window.__mountMs !== undefined, undefined, { polling: 100 });
  return { context, page, pageErrors };
}

// GC time includes V8 and Blink collectors inside the measured trace window.

const GC_EVENT = /^(MinorGC|MajorGC|GCEvent|BlinkGC\.|V8\.GC)/;
export function parseTrace(buffer) {
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
  let gcUs = 0;
  let gcCount = 0;
  for (const e of traceEvents) {
    if (!GC_EVENT.test(e.name)) continue;
    if (e.ts < clickStart || e.ts > paintEnd) continue;
    gcUs += e.dur ?? 0;
    gcCount++;
  }
  return {
    durationMs: (paintEnd - clickStart) / 1000,
    clickDispatchMs: clickDur,
    gcMs: gcUs / 1000,
    gcCount,
  };
}

// Startup trace: script compile/evaluate time (merged intervals, so nested compile
// events are not double-counted) and an approximation of Total Blocking Time from
// top-level RunTask events longer than 50ms. Window = the whole trace (tracing starts
// right before navigation and stops once the app has mounted).

const SCRIPT_EVENT = /^(EvaluateScript|CompileScript|CompileModule|v8\.compile|v8\.evaluateModule)$/;

export function parseStartupTrace(buffer) {
  const { traceEvents } = JSON.parse(buffer.toString());
  const scriptIntervals = [];
  let tbtUs = 0;
  let sawRunTask = false;
  for (const e of traceEvents) {
    if (e.dur === undefined) continue;
    if (SCRIPT_EVENT.test(e.name)) scriptIntervals.push([e.ts, e.ts + e.dur]);
    if (e.name === "RunTask") {
      sawRunTask = true;
      if (e.dur > 50_000) tbtUs += e.dur - 50_000;
    }
  }
  return {
    scriptMs: mergedLength(scriptIntervals) / 1000,
    tbtMs: sawRunTask ? tbtUs / 1000 : null,
  };
}

function mergedLength(intervals) {
  if (intervals.length === 0) return 0;
  intervals.sort((a, b) => a[0] - b[0]);
  let total = 0;
  let [start, end] = intervals[0];
  for (const [s, e] of intervals.slice(1)) {
    if (s > end) {
      total += end - start;
      [start, end] = [s, e];
    } else if (e > end) {
      end = e;
    }
  }
  return total + (end - start);
}

export const round2 = (x) => Math.round(x * 100) / 100;

export function stats(samples) {
  const sorted = [...samples].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  const median = sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
  const mean = samples.reduce((a, b) => a + b, 0) / samples.length;
  const stddev = Math.sqrt(samples.reduce((a, b) => a + (b - mean) ** 2, 0) / samples.length);
  return {
    median: round2(median),
    mean: round2(mean),
    stddev: round2(stddev),
    min: round2(sorted[0]),
    max: round2(sorted[sorted.length - 1]),
  };
}

export function percentile(samples, p) {
  const sorted = [...samples].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor(p * (sorted.length - 1) + 0.5))];
}

// Least-squares fit of ln(y) = a + b*ln(x); returns the exponent b and R².
export function logLogSlope(xs, ys) {
  const pts = xs.map((x, i) => [Math.log(x), Math.log(Math.max(ys[i], 0.01))]);
  const n = pts.length;
  const mx = pts.reduce((a, p) => a + p[0], 0) / n;
  const my = pts.reduce((a, p) => a + p[1], 0) / n;
  let sxy = 0;
  let sxx = 0;
  let syy = 0;
  for (const [x, y] of pts) {
    sxy += (x - mx) * (y - my);
    sxx += (x - mx) ** 2;
    syy += (y - my) ** 2;
  }
  const slope = sxy / sxx;
  const r2 = syy === 0 ? 1 : (sxy * sxy) / (sxx * syy);
  return { exponent: round2(slope), r2: round2(r2) };
}

// The uniform 700ms settle preserves Paint events delayed by GC/compositor contention; it is
// outside the trace-derived result.
export async function measureTracedClick(browser, page, action, wait) {
  await browser.startTracing(page, {
    screenshots: false,
    categories: ["devtools.timeline", "disabled-by-default-devtools.timeline"],
  });
  await page.waitForTimeout(60);
  await action();
  await wait();
  await page.waitForTimeout(700);
  const trace = await browser.stopTracing();
  return parseTrace(trace);
}
