// Browser benchmark for the four behavior-identical Game of Life implementations.
// Operation duration follows the repository's main benchmark methodology: trusted click
// EventDispatch start through the last Paint/Commit in a Chrome performance trace.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import os from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  BASE,
  PORT,
  launchChromium,
  measureTracedClick,
  openPage,
  parseArgs,
  repoRoot,
  round2,
  stats,
} from "../driver/common.mjs";
import { startServer } from "../driver/server.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const args = parseArgs();
const samplesPerMetric = Number(args.samples ?? 5);
const warmup = Number(args.warmup ?? 1);
const selected = (args.implementations ?? "kinetica,react,compose-html,vanilla").split(",");

if (!Number.isInteger(samplesPerMetric) || samplesPerMetric < 1) {
  throw new Error("--samples must be a positive integer");
}
if (!Number.isInteger(warmup) || warmup < 0) throw new Error("--warmup must be a non-negative integer");

const IMPLEMENTATIONS = {
  kinetica: { label: "Kinetica", version: "dev" },
  react: { label: "React", version: packageVersion("react") },
  "compose-html": { label: "Compose HTML", version: "1.11.1" },
  vanilla: { label: "Vanilla", version: "browser DOM" },
};

for (const id of selected) {
  if (!IMPLEMENTATIONS[id]) throw new Error(`Unknown implementation: ${id}`);
}

const OPERATIONS = [
  {
    id: "load_pulsar",
    label: "Load 48-cell Pulsar preset",
    async prepare(page) {
      await click(page, "preset-beacon");
      await waitStatus(page, { population: 8, generation: 0 });
    },
    action: (page) => click(page, "preset-pulsar"),
    wait: (page) => waitStatus(page, { population: 48, generation: 0 }),
  },
  {
    id: "step_pulsar",
    label: "Advance Pulsar one generation",
    async prepare(page) {
      await click(page, "preset-pulsar");
      await waitStatus(page, { population: 48, generation: 0 });
    },
    action: (page) => click(page, "step"),
    wait: (page) => waitStatus(page, { generation: 1 }),
  },
  {
    id: "randomize",
    label: "Populate 24% of the 3,456-cell board",
    async prepare(page) {
      await click(page, "clear");
      await waitStatus(page, { population: 0, generation: 0 });
    },
    action: (page) => click(page, "randomize"),
    wait: (page) => page.waitForFunction(
      () => Number(document.querySelector('[data-testid="population-value"]')?.textContent ?? "0") > 500,
      undefined,
      { polling: 50 },
    ),
  },
  {
    id: "step_random",
    label: "Advance a randomized board one generation",
    async prepare(page) {
      await click(page, "randomize");
      await page.waitForFunction(
        () => Number(document.querySelector('[data-testid="population-value"]')?.textContent ?? "0") > 500 &&
          document.querySelector('[data-testid="generation-value"]')?.textContent === "0",
        undefined,
        { polling: 50 },
      );
    },
    action: (page) => click(page, "step"),
    wait: (page) => waitStatus(page, { generation: 1 }),
  },
  {
    id: "toggle_center",
    label: "Toggle one editable board cell",
    async prepare(page) {
      await click(page, "clear");
      await waitStatus(page, { population: 0, generation: 0 });
    },
    action: (page) => click(page, "cell-36-24"),
    wait: (page) => page.waitForFunction(
      () => document.querySelector('[data-testid="cell-36-24"]')?.getAttribute("aria-pressed") === "true" &&
        document.querySelector('[data-testid="population-value"]')?.textContent === "1",
      undefined,
      { polling: 50 },
    ),
  },
  {
    id: "clear_pulsar",
    label: "Clear the 48-cell Pulsar",
    async prepare(page) {
      await click(page, "preset-pulsar");
      await waitStatus(page, { population: 48, generation: 0 });
    },
    action: (page) => click(page, "clear"),
    wait: (page) => waitStatus(page, { population: 0, generation: 0 }),
  },
];

function packageVersion(name) {
  return JSON.parse(readFileSync(join(repoRoot, "bench", "node_modules", name, "package.json"), "utf8")).version;
}

function urlFor(id) {
  return `${BASE}/build/tasks/_game-of-life_dist/${id}/index.html`;
}

async function click(page, testId) {
  await page.click(`[data-testid="${testId}"]`);
}

async function waitStatus(page, expected) {
  await page.waitForFunction(
    (values) => Object.entries(values).every(([name, value]) =>
      document.querySelector(`[data-testid="${name}-value"]`)?.textContent === String(value)
    ),
    expected,
    { polling: 50 },
  );
}

async function closeServer(server) {
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
}

const stageMetadataPath = join(repoRoot, "build", "tasks", "_game-of-life_dist", "build-metadata.json");
if (!existsSync(stageMetadataPath)) {
  throw new Error("Game of Life production builds are missing. Run `node scripts/build-game-of-life.mjs` first.");
}
const buildMetadata = JSON.parse(readFileSync(stageMetadataPath, "utf8"));

const server = await startServer(repoRoot, PORT);
const browser = await launchChromium();
const measured = {};

console.log(`Game of Life benchmark: Chromium ${browser.version()}, ${warmup} warmup + ${samplesPerMetric} samples`);
console.log(`serving ${repoRoot} on :${PORT}`);

try {
  for (const id of selected) {
    const implementation = IMPLEMENTATIONS[id];
    console.log(`\n=== ${implementation.label} ${implementation.version} ===`);
    const startupSamples = [];
    for (let sample = 0; sample < samplesPerMetric; sample++) {
      const { context, page, pageErrors } = await openPage(browser, urlFor(id));
      startupSamples.push(await page.evaluate(() => window.__mountMs));
      if (pageErrors.length) throw new Error(`${id} startup errors: ${pageErrors.join(" | ")}`);
      await context.close();
    }
    console.log(`  startup: median ${stats(startupSamples).median}ms`);

    const operations = {};
    for (const operation of OPERATIONS) {
      const { context, page, pageErrors } = await openPage(browser, urlFor(id));
      await page.emulateMedia({ reducedMotion: "reduce" });
      const durations = [];
      const dispatchDurations = [];
      const gcDurations = [];
      let traceFailures = 0;

      for (let iteration = 0; iteration < warmup + samplesPerMetric; iteration++) {
        await operation.prepare(page);
        await page.waitForTimeout(120);
        if (iteration < warmup) {
          await operation.action(page);
          await operation.wait(page);
          continue;
        }
        const trace = await measureTracedClick(
          browser,
          page,
          () => operation.action(page),
          () => operation.wait(page),
        );
        if (trace.error) {
          traceFailures++;
          if (traceFailures > 4) throw new Error(`${id}/${operation.id}: ${trace.error}`);
          iteration--;
          continue;
        }
        durations.push(trace.durationMs);
        dispatchDurations.push(trace.clickDispatchMs);
        gcDurations.push(trace.gcMs);
      }
      if (pageErrors.length) throw new Error(`${id}/${operation.id} errors: ${pageErrors.join(" | ")}`);
      operations[operation.id] = {
        label: operation.label,
        samples: durations.map(round2),
        ...stats(durations),
        clickDispatchMedian: stats(dispatchDurations).median,
        gcMedian: stats(gcDurations).median,
      };
      console.log(`  ${operation.id}: median ${operations[operation.id].median}ms`);
      await context.close();
    }

    measured[id] = {
      ...implementation,
      bundle: buildMetadata.implementations[id],
      startup: { samples: startupSamples.map(round2), ...stats(startupSamples) },
      operations,
    };
  }
} finally {
  await browser.close();
  await closeServer(server);
}

const results = {
  schemaVersion: 1,
  generatedAt: new Date().toISOString(),
  methodology: {
    board: "finite 72x48 B3/S23",
    timing: "Chrome trace: trusted click EventDispatch start through final Paint/Commit",
    warmup,
    samples: samplesPerMetric,
    viewport: "1280x900",
    randomDensity: 0.24,
    randomWorkload: "identical 32-bit seeded sequence in every implementation",
    motion: "prefers-reduced-motion: reduce during interaction traces",
  },
  environment: {
    chromium: browser.version(),
    node: process.version,
    os: `${os.type()} ${os.release()}`,
    arch: os.arch(),
    cpu: os.cpus()[0]?.model ?? "unknown",
  },
  implementations: measured,
};

const outputDir = join(repoRoot, "bench", "results", "game-of-life");
mkdirSync(outputDir, { recursive: true });
const resultPath = join(outputDir, "results.json");
writeFileSync(resultPath, `${JSON.stringify(results, null, 2)}\n`);

const reportPath = join(here, "report.html");
writeFileSync(reportPath, renderReport(results));

const stageDir = join(repoRoot, "build", "tasks", "_game-of-life_dist");
writeFileSync(join(stageDir, "results.json"), `${JSON.stringify(results, null, 2)}\n`);
writeFileSync(join(stageDir, "benchmark.html"), renderReport(results));

console.log(`\nresults: ${resultPath}`);
console.log(`report:  ${reportPath}`);

function renderReport(data) {
  const ids = Object.keys(data.implementations);
  const rows = [
    { id: "startup", label: "Cold startup + 3,456-cell mount", read: (impl) => impl.startup.median },
    ...OPERATIONS.map((operation) => ({
      id: operation.id,
      label: operation.label,
      read: (impl) => impl.operations[operation.id].median,
    })),
  ];
  const tableRows = rows.map((row) => {
    const values = ids.map((id) => row.read(data.implementations[id]));
    const fastest = Math.min(...values);
    return `<tr><th>${escapeHtml(row.label)}</th>${values.map((value) =>
      `<td${value === fastest ? ' class="fastest"' : ""}><strong>${value.toFixed(2)} ms</strong>` +
      `<span>${(value / fastest).toFixed(2)}×</span></td>`
    ).join("")}</tr>`;
  }).join("\n");
  const appCards = ids.map((id) => {
    const impl = data.implementations[id];
    return `<a class="app-card" href="/game-of-life/${id}/"><span>${escapeHtml(impl.label)}</span>` +
      `<small>${escapeHtml(impl.version)} · ${(impl.bundle.gzipBytes / 1024).toFixed(1)} KB gzip</small></a>`;
  }).join("\n");
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Game of Life runtime comparison · Kinetica</title>
<style>
:root{color-scheme:dark;font-family:Inter,ui-sans-serif,system-ui,sans-serif;background:#070a12;color:#f4f7ff;--surface:#101624;--raised:#151d2e;--border:#28344a;--muted:#96a4bb;--mint:#7cf7c8}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 10% 0,rgb(70 103 181/.2),transparent 35rem),#070a12}.wrap{width:min(1120px,100%);margin:auto;padding:48px 20px 72px}.eyebrow{color:var(--mint);font:700 11px/1 ui-monospace,monospace;letter-spacing:.14em;text-transform:uppercase}h1{max-width:800px;margin:14px 0 12px;font-size:clamp(38px,6vw,68px);line-height:.98;letter-spacing:-.05em}.lede{max-width:720px;color:var(--muted);font-size:16px;line-height:1.65}.apps{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:30px 0 44px}.app-card{display:grid;gap:6px;padding:18px;border:1px solid var(--border);border-radius:14px;background:var(--surface);color:inherit;text-decoration:none}.app-card:hover{border-color:var(--mint);transform:translateY(-1px)}.app-card span{font-weight:750}.app-card small{color:var(--muted)}h2{margin:42px 0 8px;font-size:24px}.sub{margin:0 0 18px;color:var(--muted)}.table-wrap{overflow:auto;border:1px solid var(--border);border-radius:16px;background:var(--surface)}table{width:100%;min-width:820px;border-collapse:collapse}th,td{padding:14px 16px;border-bottom:1px solid var(--border)}thead th{text-align:center;color:var(--muted);font-size:12px}thead th:first-child{text-align:left}tbody th{text-align:left;font-size:13px;font-weight:600}td{text-align:right;font:12px ui-monospace,monospace}td strong{display:block;font-size:14px}td span{color:var(--muted)}td.fastest{background:rgb(124 247 200/.09)}td.fastest strong{color:var(--mint)}.meta{display:flex;flex-wrap:wrap;gap:8px;margin:20px 0}.chip{padding:6px 10px;border:1px solid var(--border);border-radius:999px;color:var(--muted);font:11px ui-monospace,monospace}.notes{margin-top:28px;padding:20px;border:1px solid var(--border);border-radius:14px;background:var(--raised);color:var(--muted);line-height:1.6}.notes a{color:var(--mint)}@media(max-width:760px){.apps{grid-template-columns:1fr 1fr}.wrap{padding-top:30px}}@media(max-width:440px){.apps{grid-template-columns:1fr}}
</style></head><body><main class="wrap"><p class="eyebrow">Same world · four renderers</p><h1>Game of Life runtime comparison</h1>
<p class="lede">Four production builds render the same finite board, controls, presets, accessibility contract, and visual system. Lower timings are better; the fastest result in each row is highlighted.</p>
<div class="apps">${appCards}</div><h2>Measured performance</h2><p class="sub">Median of ${data.methodology.samples} measured runs after ${data.methodology.warmup} warmup; milliseconds and slowdown versus the fastest implementation per operation.</p>
<div class="table-wrap"><table><thead><tr><th>Operation</th>${ids.map((id) => `<th>${escapeHtml(data.implementations[id].label)}</th>`).join("")}</tr></thead><tbody>${tableRows}</tbody></table></div>
<div class="meta"><span class="chip">Chromium ${escapeHtml(data.environment.chromium)}</span><span class="chip">${escapeHtml(data.environment.arch)}</span><span class="chip">${escapeHtml(data.methodology.viewport)}</span><span class="chip">${escapeHtml(data.generatedAt.slice(0,10))}</span></div>
<div class="notes">Durations use the same trace parser as Kinetica’s framework benchmark: click dispatch through the last paint/commit. Interaction traces request reduced motion so the shared 160 ms cell-birth decoration cannot mask renderer work. Startup includes loading the production bundle and mounting 3,456 interactive cells. Randomized runs use the same cross-language seeded sequence at 24% density, producing a fresh but identical workload for every renderer. Results are machine-specific. <a href="/game-of-life/results.json">Download raw samples and environment metadata</a>.</div>
</main></body></html>\n`;
}

function escapeHtml(value) {
  return String(value).replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;").replaceAll('"', "&quot;");
}
