// Calibration: ask each measurement path to time a click whose true cost is known in advance.
//
// Every other number this suite produces is relative — framework against framework, run against
// run. That catches drift but not a path that measures the wrong thing entirely, which is what
// happened to the canvas renderer: it reported 45ms for a click that provably occupied 1001ms,
// and nothing in the results looked wrong, because there was nothing absolute to check against.
//
// Here the click handler burns a known K ms before making one minimal visible change, so
// reported = K + a fixed per-path overhead.
//
// The pass/fail criterion is the residual at the largest K: |reported - K| must stay inside
// TOLERANCE of K. That is the direct question ("did it see the 500ms?") and it is robust, whereas
// a slope fitted through only two points inherits the full noise of the K=0 point — a 5ms wobble
// there is 1% of slope. Slope and intercept are still reported: over a denser --spins set they
// show whether a path is subtly biased rather than plainly broken, and two paths agreeing on the
// intercept is what licenses putting their numbers on one page.
//
//   node bench/driver/calibrate.mjs                       # two points, the default the suite runs
//   node bench/driver/calibrate.mjs --spins=0,50,200,1000 # denser, when a slope is in question
//   node bench/driver/calibrate.mjs --out=<path>.json
//
// Exits non-zero if a slope leaves TOLERANCE. Runs as the `calibration` suite of bench/run.mjs,
// so a path that stops measuring elapsed time fails the run rather than waiting to be noticed.

import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { launchChromium, measureTracedClick, openPage, parseArgs, PORT, repoRoot, round2 } from "./common.mjs";
import { startServer } from "./server.mjs";
import { makeCanvasHarness } from "./canvas-harness.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const args = parseArgs();
// Two points are enough to catch a path that has stopped measuring elapsed time; the denser set
// is for pinning down a slope that is close to, but not quite, 1.0.
const SPINS = (args.spins ?? "0,500").split(",").map(Number).filter((n) => Number.isFinite(n));
const SAMPLES = Number(args.samples ?? 5);
const TOLERANCE = 0.03;
const OUT = args.out ?? null;
const BASE = `http://127.0.0.1:${PORT}`;

// Written rather than tracked: it is a fixture for this script alone, and bench/dist is generated.
const domPage = join(here, "..", "dist", "calibration", "index.html");
mkdirSync(dirname(domPage), { recursive: true });
writeFileSync(domPage, `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>DOM calibration</title>
<link rel="stylesheet" href="../../frameworks/shared/styles.css">
<script>
  window.__mountMs = undefined;
  (function () {
    function check() { if (document.querySelector("#run")) { window.__mountMs = performance.now(); return true; } return false; }
    if (!check()) { var mo = new MutationObserver(function () { if (check()) mo.disconnect(); }); mo.observe(document.documentElement, { childList: true, subtree: true }); }
  })();
</script></head>
<body><div id="main"></div>
<script>
const SPIN = Number(new URLSearchParams(location.search).get("spin") ?? 0);
let rows = [], nextId = 1;
function spin(ms) { const end = performance.now() + ms; let sink = 0; while (performance.now() < end) sink += Math.random(); return sink; }
function build(n) { rows = Array.from({ length: n }, () => ({ id: nextId++, label: "row label" })); }
function render() {
  const tbody = document.querySelector("tbody");
  tbody.textContent = "";
  const frag = document.createDocumentFragment();
  for (const r of rows) {
    const tr = document.createElement("tr");
    tr.setAttribute("data-id", String(r.id));
    const id = document.createElement("td"); id.className = "col-id"; id.textContent = String(r.id);
    const label = document.createElement("td"); label.className = "col-label";
    const a = document.createElement("a"); a.className = "lbl"; a.textContent = r.label; label.appendChild(a);
    const rem = document.createElement("td"); rem.className = "col-remove";
    const ra = document.createElement("a"); ra.className = "remove";
    const span = document.createElement("span"); span.className = "remove-icon"; ra.appendChild(span); rem.appendChild(ra);
    const rest = document.createElement("td"); rest.className = "col-rest";
    tr.append(id, label, rem, rest);
    frag.appendChild(tr);
  }
  tbody.appendChild(frag);
}
document.getElementById("main").innerHTML =
  '<div class="jumbotron"><h1>DOM calibration</h1><div class="toolbar">' +
  '<button id="run">Create 1,000 rows</button><button id="update">Spin, then touch one label</button>' +
  '<button id="clear">Clear</button></div></div><table class="test-data"><tbody></tbody></table>';
document.getElementById("run").addEventListener("click", () => { build(1000); render(); });
document.getElementById("clear").addEventListener("click", () => { rows = []; render(); });
document.getElementById("update").addEventListener("click", () => {
  spin(SPIN);
  rows[0].label = "row label !!!";
  document.querySelector("tbody tr td.col-label a").textContent = rows[0].label;
});
</script></body></html>
`);

const server = await startServer(repoRoot, PORT);
const browser = await launchChromium();
const median = (xs) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];

// The DOM path under test is the real one: measureTracedClick over the harness's own predicates.
const domHarness = (page) => ({
  clickButton: (n) => page.click(`#${n}`),
  waitRows: (c) => page.waitForFunction((e) => document.querySelectorAll("tbody tr").length === e, c, { polling: 50, timeout: 120_000 }),
  waitDone: () => page.waitForFunction(() => (document.querySelector("tbody tr td.col-label")?.textContent ?? "").endsWith(" !!!"), null, { polling: 20, timeout: 120_000 }),
});

const canvasHarness = (page) => {
  const h = makeCanvasHarness(page);
  h.waitDone = () => h.waitLabelSuffix(" !!!");
  return h;
};

async function calibrate(label, url, makeH) {
  const points = [];
  for (const spin of SPINS) {
    const { context, page } = await openPage(browser, `${url}${url.includes("?") ? "&" : "?"}spin=${spin}`);
    const h = makeH(page);
    const samples = [];
    for (let i = 0; i < SAMPLES + 1; i++) {
      await h.clickButton("run");
      await h.waitRows(1000);
      await page.waitForTimeout(150);
      const parsed = h.measureOperation
        ? await h.measureOperation(() => h.clickButton("update"), () => h.waitDone())
        : await measureTracedClick(browser, page, () => h.clickButton("update"), () => h.waitDone());
      if (parsed.error) {
        console.log(`  ${label} K=${spin}: ${parsed.error}`);
      } else if (i > 0) {
        samples.push(parsed.durationMs); // first iteration is warmup
      }
      await h.clickButton("clear");
      await h.waitRows(0);
    }
    const value = round2(median(samples));
    points.push({ spin, value });
    console.log(`  ${label.padEnd(22)} K=${String(spin).padStart(4)}ms -> ${String(value).padStart(9)}ms   [${samples.map((x) => x.toFixed(0)).join(", ")}]`);
    await context.close();
  }
  const n = points.length;
  const sx = points.reduce((a, p) => a + p.spin, 0);
  const sy = points.reduce((a, p) => a + p.value, 0);
  const sxy = points.reduce((a, p) => a + p.spin * p.value, 0);
  const sxx = points.reduce((a, p) => a + p.spin * p.spin, 0);
  const slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
  return { label, slope: round2(slope * 10000) / 10000, overhead: round2((sy - slope * sx) / n), points };
}

console.log(`chromium ${browser.version()}; ${SAMPLES} samples per point, medians\n`);
const results = [
  await calibrate("DOM (trace)", `${BASE}/bench/dist/calibration/index.html`, domHarness),
  await calibrate("canvas (page clock)", `${BASE}/samples/browser-bench-compose-canvas/web/index.html?list=column`, canvasHarness),
];

const largestK = Math.max(...SPINS);
console.log(`\n=== does each path see a click of known cost? (largest point: K=${largestK}ms) ===`);
let failed = false;
for (const r of results) {
  const at = r.points.find((p) => p.spin === largestK);
  r.residualMs = at ? round2(at.value - largestK) : null;
  const bad = at === undefined || largestK === 0 || Math.abs(r.residualMs) > TOLERANCE * largestK;
  if (bad) failed = true;
  console.log(
    `${r.label.padEnd(22)} K=${largestK}ms -> ${at ? at.value.toFixed(1) : "?"}ms ` +
    `(off by ${r.residualMs ?? "?"} ms)   slope ${r.slope.toFixed(4)}, overhead ${r.overhead.toFixed(1)} ms` +
    `${bad ? "   <-- OUT OF TOLERANCE" : ""}`,
  );
}
const spread = round2(Math.abs(results[0].overhead - results[1].overhead));
console.log(`\noverheads differ by ${spread.toFixed(1)} ms — the two paths report the same quantity to within that.`);

if (OUT) {
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, `${JSON.stringify({
    meta: { date: new Date().toISOString(), chromium: browser.version(), spins: SPINS, samples: SAMPLES, tolerance: TOLERANCE },
    paths: results,
    overheadSpreadMs: spread,
    largestK,
    ok: !failed,
  }, null, 2)}\n`);
  console.log(`calibration written to ${OUT}`);
}

await browser.close();
server.close();
if (failed) {
  console.error(`\na path missed a click of known cost by more than ${TOLERANCE * 100}%: its numbers do not measure elapsed time`);
  process.exitCode = 1;
}
