import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const resultsPath = process.argv[2] ?? join(here, "..", "results", "results.json");
const outPath = process.argv[3] ?? join(here, "index.html");
const data = JSON.parse(readFileSync(resultsPath, "utf8"));

import { colorFor, frameworks } from "../frameworks.config.mjs";

const FW_ORDER = frameworks.map((f) => f.name).filter((f) => f in data.results);
const FW_LABEL = Object.fromEntries(frameworks.map((f) => [f.name, f.label]));
// categorical slots by config position (validated light+dark, color follows entity)
const FW_COLOR = Object.fromEntries(frameworks.map((f) => [f.name, colorFor(f.name)]));
// sequential blue ramp for the table shading (factor vs fastest)
const RAMP = ["#cde2fb", "#b7d3f6", "#9ec5f4", "#86b6ef", "#6da7ec", "#5598e7", "#3987e5", "#2a78d6", "#256abf", "#1c5cab", "#184f95", "#104281", "#0d366b"];

const benches = data.benchmarks.filter((b) => FW_ORDER.some((f) => data.results[f]?.[b.id]));

function fastest(benchId) {
  return Math.min(...FW_ORDER.map((f) => data.results[f]?.[benchId]?.median ?? Infinity));
}

function geoMean(fw) {
  const factors = benches
    .map((b) => {
      const r = data.results[fw]?.[b.id];
      return r ? r.median / fastest(b.id) : null;
    })
    .filter(Boolean);
  return Math.exp(factors.reduce((a, x) => a + Math.log(x), 0) / factors.length);
}

const fmt = (x, d = 1) => Number(x).toLocaleString("en-US", { minimumFractionDigits: d, maximumFractionDigits: d });

function rampColor(factor, maxFactor) {
  // log scale: 1.0 -> lightest, maxFactor -> darkest
  const t = Math.min(1, Math.log(factor) / Math.log(Math.max(maxFactor, 1.01)));
  return RAMP[Math.round(t * (RAMP.length - 1))];
}

// --- chart builders (plain HTML/CSS horizontal bars) ---

function barChart({ title, subtitle, rows, unit, note }) {
  const max = Math.max(...rows.map((r) => r.value));
  const bars = rows
    .map((r) => {
      const pct = Math.max(0.8, (r.value / max) * 86); // cap at 86% so end labels always fit
      return `
      <div class="bar-row" data-tip="${r.tip ?? ""}">
        <span class="bar-name">${r.name}</span>
        <span class="bar-track"><span class="bar" data-fw="${r.fw}" style="width:${pct.toFixed(1)}%"></span>
        <span class="bar-value">${r.label}</span></span>
      </div>`;
    })
    .join("");
  return `
  <figure class="chart">
    <figcaption><strong>${title}</strong>${subtitle ? `<span class="sub">${subtitle}</span>` : ""}</figcaption>
    <div class="bars" data-unit="${unit ?? "ms"}">${bars}</div>
    ${note ? `<p class="chart-note">${note}</p>` : ""}
  </figure>`;
}

const geo = FW_ORDER.map((f) => ({ fw: f, v: geoMean(f) })).sort((a, b) => a.v - b.v);
const heroChart = barChart({
  title: "Geometric mean slowdown",
  subtitle: "across all measured operations, relative to the fastest framework per operation — lower is better",
  rows: geo.map(({ fw, v }) => ({
    fw,
    name: FW_LABEL[fw],
    value: v,
    label: `${fmt(v, 2)}×`,
    tip: `${FW_LABEL[fw]}: ${fmt(v, 2)}× the per-operation fastest, geometric mean over ${benches.length} operations`,
  })),
  unit: "×",
});

// results table
const maxFactor = Math.max(
  ...benches.flatMap((b) =>
    FW_ORDER.map((f) => (data.results[f]?.[b.id]?.median ?? NaN) / fastest(b.id)).filter((x) => !Number.isNaN(x)),
  ),
);

const tableRows = benches
  .map((b) => {
    const best = fastest(b.id);
    const cells = FW_ORDER.map((f) => {
      const r = data.results[f]?.[b.id];
      if (!r) return `<td class="cell empty">—</td>`;
      const factor = r.median / best;
      const bg = rampColor(factor, maxFactor);
      const darkText = RAMP.indexOf(bg) >= 6;
      return `<td class="cell" style="--cell:${bg}" data-dark="${darkText}"
        title="${FW_LABEL[f]} · ${b.label}: median ${fmt(r.median)}ms (mean ${fmt(r.mean)} ± ${fmt(r.stddev)}, min ${fmt(r.min)}, max ${fmt(r.max)}, n=${r.samples.length})">
        <span class="ms">${fmt(r.median)}</span><span class="factor">${fmt(factor, 1)}×</span></td>`;
    }).join("");
    return `<tr><th scope="row">${b.label}</th>${cells}</tr>`;
  })
  .join("");

const geoCells = FW_ORDER.map((f) => {
  const v = geoMean(f);
  const bg = rampColor(v, maxFactor);
  return `<td class="cell geo" style="--cell:${bg}" data-dark="${RAMP.indexOf(bg) >= 6}"><span class="ms">${fmt(v, 2)}×</span></td>`;
}).join("");

// per-benchmark small multiples
const smallMultiples = benches
  .map((b) => {
    const rows = FW_ORDER.map((f) => {
      const r = data.results[f]?.[b.id];
      return r
        ? {
            fw: f,
            name: FW_LABEL[f],
            value: r.median,
            label: `${fmt(r.median)}`,
            tip: `${FW_LABEL[f]}: median ${fmt(r.median)}ms · mean ${fmt(r.mean)} ± ${fmt(r.stddev)} · range ${fmt(r.min)}–${fmt(r.max)}ms · ${r.samples.length} samples`,
          }
        : null;
    }).filter(Boolean);
    return barChart({ title: b.label, rows, unit: "ms" });
  })
  .join("");

// startup & memory
const weightChart = barChart({
  title: "JS payload (gzip)",
  subtitle: "kilobytes of JavaScript shipped to render the app",
  rows: FW_ORDER.filter((f) => data.startup?.[f]).map((f) => ({
    fw: f,
    name: FW_LABEL[f],
    value: data.startup[f].gzipBytes / 1024,
    label: `${fmt(data.startup[f].gzipBytes / 1024, 0)} KB`,
    tip: `${FW_LABEL[f]}: ${fmt(data.startup[f].gzipBytes / 1024, 0)} KB gzipped · ${fmt(data.startup[f].jsBytes / 1024, 0)} KB raw · ${data.startup[f].files} file(s)`,
  })),
  unit: "KB",
});

const startupChart = barChart({
  title: "Time to interactive",
  subtitle: "navigation → toolbar rendered, local server, median of 5 cold loads",
  rows: FW_ORDER.filter((f) => data.startup?.[f]).map((f) => ({
    fw: f,
    name: FW_LABEL[f],
    value: data.startup[f].median,
    label: `${fmt(data.startup[f].median)} ms`,
    tip: `${FW_LABEL[f]}: median ${fmt(data.startup[f].median)}ms over ${data.startup[f].mountMs.length} cold loads (${data.startup[f].mountMs.map((x) => fmt(x, 0)).join(", ")})`,
  })),
  unit: "ms",
});

const memoryChart = barChart({
  title: "JS heap after creating 1,000 rows",
  subtitle: "forced GC, then JSHeapUsedSize",
  rows: FW_ORDER.filter((f) => data.memory?.[f]).map((f) => ({
    fw: f,
    name: FW_LABEL[f],
    value: data.memory[f].after1kMb,
    label: `${fmt(data.memory[f].after1kMb)} MB`,
    tip: `${FW_LABEL[f]}: ${fmt(data.memory[f].after1kMb)} MB after 1k rows · ${fmt(data.memory[f].afterLoadMb)} MB right after load`,
  })),
  unit: "MB",
});

// optional before/after section for the kinetica event-registry fix
const beforePath = join(here, "..", "results", "part-kinetica-before.json");
let fixSection = "";
if (existsSync(beforePath) && data.results.kinetica) {
  const before = JSON.parse(readFileSync(beforePath, "utf8")).results.kinetica;
  const rows = benches
    .filter((b) => before[b.id] && data.results.kinetica[b.id])
    .map((b) => {
      const prev = before[b.id].median;
      const now = data.results.kinetica[b.id].median;
      const speedup = prev / now;
      return `<tr>
        <th scope="row">${b.label}</th>
        <td class="num">${fmt(prev)}</td>
        <td class="num">${fmt(now)}</td>
        <td class="num delta">${speedup >= 1.05 ? fmt(speedup, 1) + "× faster" : "≈ same"}</td>
      </tr>`;
    })
    .join("");
  fixSection = `
  <section>
    <h2>One fix, measured</h2>
    <p class="section-sub">Profiling traced 60–67% of all CPU to <code>KineticaRuntime.registerEvent</code>:
    an O(n) linear scan of a never-evicted identity list, run once per handler per render — O(n²) per
    operation and growing with app lifetime. Replacing the list with a hash map
    (phase 0, part 1 of <code>perf-rewrite-design.md</code>) produced the numbers used throughout this
    report. The remaining gap versus the keyed frameworks is the full-DOM rebuild, addressed by the
    retained-renderer phase.</p>
    <div class="table-scroll">
      <table class="results fix-table">
        <thead>
          <tr><th></th><th scope="col">before (ms)</th><th scope="col">after (ms)</th><th scope="col">improvement</th></tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    </div>
  </section>`;
}

const legend = FW_ORDER.map(
  (f) => `<span class="legend-item"><span class="swatch" data-fw="${f}"></span>${FW_LABEL[f]}<span class="ver">${data.meta.versions?.[f] && data.meta.versions[f] !== "n/a" ? " " + data.meta.versions[f] : ""}</span></span>`,
).join("");

const fwColorCss = FW_ORDER.map(
  (f) => `  [data-fw="${f}"] { --series: var(--c-${f}); }`,
).join("\n");
const fwColorTokens = (mode) =>
  FW_ORDER.map((f) => `  --c-${f}: ${FW_COLOR[f][mode]};`).join("\n");

const html = `<title>Kinetica vs React · js-framework-benchmark</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root {
  --page: #f9f9f7;
  --surface: #fcfcfb;
  --ink: #0b0b0b;
  --ink-2: #52514e;
  --muted: #898781;
  --grid: #e1e0d9;
  --border: rgba(11, 11, 11, 0.1);
  --accent: #2a78d6;
${fwColorTokens(0)}
}
@media (prefers-color-scheme: dark) {
  :root {
    --page: #0d0d0d;
    --surface: #1a1a19;
    --ink: #ffffff;
    --ink-2: #c3c2b7;
    --muted: #898781;
    --grid: #2c2c2a;
    --border: rgba(255, 255, 255, 0.1);
    --accent: #3987e5;
${fwColorTokens(1)}
  }
}
:root[data-theme="dark"] {
  --page: #0d0d0d;
  --surface: #1a1a19;
  --ink: #ffffff;
  --ink-2: #c3c2b7;
  --muted: #898781;
  --grid: #2c2c2a;
  --border: rgba(255, 255, 255, 0.1);
  --accent: #3987e5;
${fwColorTokens(1)}
}
:root[data-theme="light"] {
  --page: #f9f9f7;
  --surface: #fcfcfb;
  --ink: #0b0b0b;
  --ink-2: #52514e;
  --muted: #898781;
  --grid: #e1e0d9;
  --border: rgba(11, 11, 11, 0.1);
  --accent: #2a78d6;
${fwColorTokens(0)}
}
${fwColorCss}

* { box-sizing: border-box; }
body {
  margin: 0;
  background: var(--page);
  color: var(--ink);
  font: 15px/1.55 system-ui, -apple-system, "Segoe UI", sans-serif;
}
.wrap { max-width: 1060px; margin: 0 auto; padding: 40px 20px 72px; }
.eyebrow {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px; letter-spacing: 0.08em; text-transform: uppercase;
  color: var(--accent); margin: 0 0 10px;
}
h1 { font-size: 30px; line-height: 1.15; margin: 0 0 6px; letter-spacing: -0.015em; text-wrap: balance; }
.lede { color: var(--ink-2); max-width: 62ch; margin: 0 0 18px; }
.meta-chips { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 8px; }
.chip {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px;
  color: var(--ink-2); background: var(--surface);
  border: 1px solid var(--border); border-radius: 999px; padding: 3px 10px;
}
.legend { display: flex; flex-wrap: wrap; gap: 6px 16px; margin: 18px 0 0; }
.legend-item { display: inline-flex; align-items: center; gap: 7px; font-size: 13.5px; color: var(--ink-2); }
.legend-item .ver { color: var(--muted); font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.swatch { width: 11px; height: 11px; border-radius: 3px; background: var(--series); }

section { margin-top: 44px; }
h2 { font-size: 20px; margin: 0 0 4px; letter-spacing: -0.01em; }
.section-sub { color: var(--ink-2); margin: 0 0 18px; max-width: 70ch; }

.chart {
  margin: 0; background: var(--surface); border: 1px solid var(--border);
  border-radius: 8px; padding: 16px 18px 14px;
}
.chart figcaption { margin-bottom: 12px; }
.chart figcaption strong { font-size: 14.5px; font-weight: 600; }
.chart figcaption .sub { display: block; color: var(--muted); font-size: 12.5px; margin-top: 2px; }
.bars { display: grid; gap: 7px; }
.bar-row { display: grid; grid-template-columns: 88px 1fr; align-items: center; gap: 10px; }
.bar-name { font-size: 12.5px; color: var(--ink-2); text-align: right; }
.bar-track { position: relative; display: flex; align-items: center; gap: 8px; min-height: 18px; }
.bar {
  height: 18px; background: var(--series);
  border-radius: 0 4px 4px 0; min-width: 2px;
  flex: none;
}
.bar-value {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px;
  color: var(--ink-2); white-space: nowrap; font-variant-numeric: tabular-nums;
}
.chart-note { color: var(--muted); font-size: 12.5px; margin: 10px 0 0; }

.hero-grid { display: grid; grid-template-columns: 1fr; gap: 16px; }
.multiples { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; }
.duo { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; }

.table-scroll { overflow-x: auto; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
table.results { border-collapse: collapse; width: 100%; min-width: 720px; }
table.results th, table.results td { padding: 8px 10px; font-size: 13px; }
table.results thead th {
  text-align: center; font-weight: 600; border-bottom: 1px solid var(--grid);
  padding-top: 12px;
}
table.results thead .swatch { display: inline-block; margin-right: 6px; vertical-align: -1px; }
table.results tbody th {
  text-align: left; font-weight: 500; color: var(--ink-2); white-space: nowrap;
  border-bottom: 1px solid var(--grid);
}
td.cell {
  text-align: center; border-bottom: 1px solid var(--grid);
  background: var(--cell); font-variant-numeric: tabular-nums;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: #0b0b0b;
}
td.cell[data-dark="true"] { color: #ffffff; }
td.cell .ms { display: block; font-size: 13px; font-weight: 600; }
td.cell .factor { display: block; font-size: 11px; opacity: 0.75; }
td.cell.geo .ms { font-size: 14px; }
tr.geo-row th { border-bottom: none; font-weight: 600; color: var(--ink); }
tr.geo-row td { border-bottom: none; }
td.empty { color: var(--muted); }
table.fix-table td.num {
  text-align: right; border-bottom: 1px solid var(--grid);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-variant-numeric: tabular-nums;
}
table.fix-table thead th { text-align: right; }
table.fix-table td.delta { color: #006300; font-weight: 600; }
:root[data-theme="dark"] table.fix-table td.delta { color: #0ca30c; }
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) table.fix-table td.delta { color: #0ca30c; } }

.callout {
  border: 1px solid var(--border); border-left: 3px solid var(--accent);
  background: var(--surface); border-radius: 8px; padding: 14px 18px; margin-top: 18px;
}
.callout h3 { margin: 0 0 6px; font-size: 15px; }
.callout p { margin: 6px 0; color: var(--ink-2); font-size: 14px; max-width: 78ch; }
.callout code, .method code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px;
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  border-radius: 4px; padding: 1px 5px;
}
.method { color: var(--ink-2); font-size: 14px; max-width: 78ch; }
.method dt { font-weight: 600; color: var(--ink); margin-top: 12px; }
.method dd { margin: 3px 0 0; }
footer { margin-top: 48px; color: var(--muted); font-size: 12.5px; }

#tip {
  position: fixed; pointer-events: none; z-index: 10; display: none;
  max-width: 340px; padding: 8px 11px; font-size: 12.5px; line-height: 1.45;
  background: var(--ink); color: var(--page);
  border-radius: 6px; box-shadow: 0 6px 24px rgba(0,0,0,0.25);
}
@media (prefers-reduced-motion: no-preference) {
  .bar { transition: width 0.5s cubic-bezier(0.22, 1, 0.36, 1); }
}
</style>

<div class="wrap">
  <header>
    <p class="eyebrow">js-framework-benchmark · keyed implementations</p>
    <h1>Kinetica vs React, Preact, Vue, Svelte &amp; vanilla JS</h1>
    <p class="lede">The classic krausest table benchmark — create, update, select, swap, remove and clear
    1,000-row tables — reimplemented for <strong>Kinetica</strong> and run against the same apps in the
    other frameworks, in one environment, on one machine, with one driver.</p>
    <div class="meta-chips">
      <span class="chip">${data.meta.machine.cpu ?? "unknown CPU"}</span>
      <span class="chip">${data.meta.machine.platform} ${data.meta.machine.arch} · ${data.meta.machine.memGb} GB</span>
      <span class="chip">Chromium ${data.meta.chromium} (headless)</span>
      <span class="chip">${new Date(data.meta.date).toISOString().slice(0, 10)}</span>
      <span class="chip">${data.meta.samples} samples + ${data.meta.warmup} warmup / op</span>
    </div>
    <div class="legend">${legend}</div>
  </header>

  <section>
    <h2>Overall</h2>
    <p class="section-sub">One number per framework: how many times slower it is than the fastest
    contender, averaged (geometric mean) over all ${benches.length} DOM operations. 1.00× would mean
    fastest at everything.</p>
    <div class="hero-grid">${heroChart}</div>
  </section>

  <section>
    <h2>Duration by operation</h2>
    <p class="section-sub">Median duration in milliseconds from the trusted click to the end of the last
    paint, measured from Chrome traces. The small factor under each value is the slowdown relative to
    the fastest framework in that row. Hover a cell for the full distribution.</p>
    <div class="table-scroll">
      <table class="results">
        <thead>
          <tr><th></th>${FW_ORDER.map((f) => `<th scope="col"><span class="swatch" data-fw="${f}"></span>${FW_LABEL[f]}</th>`).join("")}</tr>
        </thead>
        <tbody>
          ${tableRows}
          <tr class="geo-row"><th scope="row">geometric mean of factors</th>${geoCells}</tr>
        </tbody>
      </table>
    </div>
  </section>

  ${fixSection}

  <section>
    <h2>Per operation</h2>
    <p class="section-sub">Same data as the table, drawn to scale — each chart normalized to its own
    slowest bar. Lower is better.</p>
    <div class="multiples">${smallMultiples}</div>
  </section>

  <section>
    <h2>Startup &amp; memory</h2>
    <div class="duo">${weightChart}${startupChart}${memoryChart}</div>
    <div class="callout">
      <h3>Why Kinetica ships ${data.startup?.kinetica ? fmt(data.startup.kinetica.gzipBytes / 1024, 0) : "—"} KB &amp; ${data.startup?.kinetica ? data.startup.kinetica.files : "—"} files</h3>
      <p>The Kotlin Toolchain's <code>js/app</code> product is a preview: it links the app as an
      unminified multi-file ES-module graph (stdlib, coroutines, serialization included) with no
      dead-code elimination or minifier yet. The JS competitors are esbuild production bundles.
      Payload and time-to-interactive comparisons therefore measure the toolchain's current packaging,
      not just the framework.</p>
    </div>
  </section>

  <section>
    <h2>Reading the results</h2>
    <div class="callout">
      <h3>Kinetica's browser renderer rebuilds the whole DOM on every event</h3>
      <p><code>BrowserKineticaApp.render()</code> tears down the root and recreates every element from
      the fresh node tree on each dispatch — there is no keyed reconciliation on the client (the
      runtime's <code>diffNodes</code> exists but is only used for server-component patches). That is
      why <em>select row</em>, <em>partial update</em>, <em>swap</em> and <em>remove</em> cost roughly
      the same as rebuilding 1,000 rows from scratch, while React/Preact/Vue/Svelte touch only the
      changed nodes.</p>
      <p>The upside of measuring it honestly: a keyed DOM patcher driven by the existing
      <code>diffNodes</code> + <code>each(key=…)</code> machinery is the single highest-leverage
      performance change available to the framework.</p>
    </div>
  </section>

  <section>
    <h2>Methodology</h2>
    <dl class="method">
      <dt>Benchmark suite</dt>
      <dd>The keyed scenario of <em>js-framework-benchmark</em> (krausest): the app is a table of rows
      (id, label link that selects, remove icon); operations are create 1,000 / replace 1,000 /
      partial-update every 10th / select / swap rows 2↔999 / remove one / create 10,000 / append 1,000 /
      clear. Labels come from the benchmark's standard adjective-colour-noun generator.</dd>
      <dt>Measurement</dt>
      <dd>${data.meta.methodology} Each operation: ${data.meta.warmup} warmup runs, then
      ${data.meta.samples} measured samples; the table reports medians. State is reset between samples
      so every measured click does identical work.</dd>
      <dt>Fairness</dt>
      <dd>All apps render the same DOM structure (table/tr/td, same CSS file, no hover styles) from the
      same page shell on the same local server. Kinetica differs only where its DSL requires it:
      selectable labels and remove controls are <code>&lt;button&gt;</code> elements rather than
      <code>&lt;a&gt;</code>, and it is mounted with <code>KineticaRuntime(debug = false)</code>.
      React, Preact, Vue and Svelte are minified production builds (Vue includes its runtime template
      compiler; op timings are unaffected). Vanilla JS is a direct-DOM baseline with event delegation.</dd>
      <dt>Environment</dt>
      <dd>${data.meta.machine.cpu}, ${data.meta.machine.memGb} GB RAM, ${data.meta.machine.platform}/${data.meta.machine.arch},
      Chromium ${data.meta.chromium} headless, no CPU throttling, one framework at a time.</dd>
    </dl>
  </section>

  <footer>
    Generated ${new Date(data.meta.date).toISOString().slice(0, 16).replace("T", " ")} UTC ·
    versions: ${FW_ORDER.map((f) => `${FW_LABEL[f]} ${data.meta.versions?.[f] ?? "?"}`).join(" · ")} ·
    raw data in <code>bench/results/results.json</code>
  </footer>
</div>

<div id="tip" role="tooltip"></div>
<script>
(function () {
  var tip = document.getElementById("tip");
  document.addEventListener("mousemove", function (e) {
    var row = e.target.closest ? e.target.closest("[data-tip]") : null;
    if (row && row.getAttribute("data-tip")) {
      tip.textContent = row.getAttribute("data-tip");
      tip.style.display = "block";
      var x = Math.min(e.clientX + 14, window.innerWidth - tip.offsetWidth - 8);
      var y = Math.min(e.clientY + 14, window.innerHeight - tip.offsetHeight - 8);
      tip.style.left = x + "px";
      tip.style.top = y + "px";
    } else {
      tip.style.display = "none";
    }
  });
})();
</script>
`;

writeFileSync(outPath, html);
console.log(`report written to ${outPath}`);
