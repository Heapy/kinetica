import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from "node:fs";
import { basename, join, resolve } from "node:path";
import { frameworks } from "../frameworks.config.mjs";

const LOWER_IS_BETTER = "lower";
const HIGHER_IS_BETTER = "higher";
const FRAMEWORK_LABELS = Object.fromEntries(frameworks.map((framework) => [framework.name, framework.label]));

const SECTION_LABELS = {
  main: "Main browser suite",
  "browser-ops": "Browser operations",
  startup: "Startup and payload",
  memory: "Memory",
  animation: "Sustained updates",
  tree: "Deep tree",
  scaling: "Scaling",
  stress: "Large-table scaling stress",
  extra: "Large-table mutation stress",
  jvm: "JVM microbenchmarks",
  size: "Bundle sizes",
  build: "Build time",
};

const escapeHtml = (value) => String(value)
  .replaceAll("&", "&amp;")
  .replaceAll("<", "&lt;")
  .replaceAll(">", "&gt;")
  .replaceAll('"', "&quot;");

const readJson = (path) => existsSync(path) ? JSON.parse(readFileSync(path, "utf8")) : null;

const BROWSER_REUSE_SUITES = ["main", "tree", "scaling", "stress", "extra"];

function frameworkNames(value) {
  return Array.isArray(value)
    ? value.filter((name) => typeof name === "string" && name.length > 0)
    : [];
}

function reuseProvenance(run) {
  const manifestReuse = run.manifest?.reuse ?? {};
  const manifestReused = manifestReuse.reusedFrameworksBySuite ??
    run.manifest?.reusedFrameworksBySuite ?? {};
  const manifestMeasured = manifestReuse.measuredFrameworksBySuite ??
    run.manifest?.measuredFrameworksBySuite ??
    run.manifest?.executedFrameworksBySuite ?? {};
  const suites = {};
  const sources = new Set();

  for (const suite of BROWSER_REUSE_SUITES) {
    const artifactReuse = (suite === "main" ? run.main?.reuse ?? run.throttled?.reuse : run[suite]?.reuse) ?? {};
    const reusedFrameworks = [...new Set([
      ...frameworkNames(manifestReused[suite]),
      ...frameworkNames(artifactReuse.reusedFrameworks),
    ])];
    if (reusedFrameworks.length === 0) continue;

    const source = artifactReuse.source ?? manifestReuse.source ?? run.manifest?.reuseSource ?? null;
    const sourceDir = artifactReuse.sourceDir ?? manifestReuse.sourceDir ?? null;
    if (source) sources.add(source);
    if (sourceDir) sources.add(sourceDir);
    suites[suite] = {
      measuredFrameworks: [...new Set([
        ...frameworkNames(manifestMeasured[suite]),
        ...frameworkNames(artifactReuse.measuredFrameworks),
      ])],
      reusedFrameworks,
      source,
      sourceDir,
    };
  }

  return {
    active: Object.keys(suites).length > 0,
    sources: [...sources],
    suites,
  };
}

function findExtraResult(dir) {
  const extraDir = join(dir, "extra-ops");
  if (!existsSync(extraDir)) return null;
  const file = readdirSync(extraDir)
    .filter((name) => name.endsWith(".json"))
    .sort()
    .at(-1);
  return file ? join(extraDir, file) : null;
}

export function loadRunArtifacts(dir) {
  const root = resolve(dir);
  const paths = {
    manifest: join(root, "run.json"),
    main: join(root, "results.json"),
    throttled: join(root, "throttled.json"),
    tree: join(root, "tree.json"),
    scaling: join(root, "scaling.json"),
    stress: join(root, "stress.json"),
    jvm: join(root, "jvm", "results.json"),
    sizes: join(root, "sizes.json"),
    extra: findExtraResult(root),
  };
  return {
    root,
    paths,
    manifest: readJson(paths.manifest),
    main: readJson(paths.main),
    throttled: readJson(paths.throttled),
    tree: readJson(paths.tree),
    scaling: readJson(paths.scaling),
    stress: readJson(paths.stress),
    jvm: readJson(paths.jvm),
    sizes: readJson(paths.sizes),
    extra: paths.extra ? readJson(paths.extra) : null,
  };
}

function compatibilityCheck(current, baseline) {
  const checks = [];
  const compare = (scope, field, currentValue, baselineValue) => {
    if (currentValue == null || baselineValue == null) return;
    const matches = String(currentValue) === String(baselineValue);
    checks.push({ scope, field, current: currentValue, baseline: baselineValue, matches });
  };

  for (const suite of ["main", "tree", "scaling", "stress", "extra"]) {
    const currentMeta = current[suite]?.meta;
    const baselineMeta = baseline[suite]?.meta;
    if (!currentMeta || !baselineMeta) continue;
    compare(`browser.${suite}`, "chromium", currentMeta.chromium, baselineMeta.chromium);
    compare(`browser.${suite}`, "cpuThrottle", currentMeta.cpuThrottle ?? "none", baselineMeta.cpuThrottle ?? "none");
    compare(`browser.${suite}`, "warmup", currentMeta.warmup, baselineMeta.warmup);
    compare(`browser.${suite}`, "samples", currentMeta.samples, baselineMeta.samples);
    compare(`browser.${suite}`, "cpu", currentMeta.machine?.cpu, baselineMeta.machine?.cpu);
    compare(`browser.${suite}`, "arch", currentMeta.machine?.arch, baselineMeta.machine?.arch);
    compare(`browser.${suite}`, "platform", currentMeta.machine?.platform, baselineMeta.machine?.platform);
  }
  compare("jvm", "jvm", current.jvm?.meta?.jvm, baseline.jvm?.meta?.jvm);
  compare("jvm", "os", current.jvm?.meta?.os, baseline.jvm?.meta?.os);
  compare("extra", "size", current.extra?.meta?.size, baseline.extra?.meta?.size);
  compare("stress", "sizes", current.stress?.meta?.sizes?.join(","), baseline.stress?.meta?.sizes?.join(","));

  const mismatches = checks.filter((check) => !check.matches);
  return { compatible: mismatches.length === 0, checks, mismatches };
}

function labelsById(list) {
  return Object.fromEntries((list ?? []).map((item) => [item.id, item.label]));
}

function pushMetric(metrics, {
  section,
  framework = null,
  scenario,
  label,
  metric,
  before,
  after,
  unit,
  direction = LOWER_IS_BETTER,
  thresholdPct,
}) {
  if (!Number.isFinite(before) || !Number.isFinite(after)) return;
  const rawDelta = after - before;
  const rawDeltaPct = before === 0 ? null : (rawDelta / Math.abs(before)) * 100;
  const impactPct = rawDeltaPct == null
    ? null
    : direction === HIGHER_IS_BETTER ? -rawDeltaPct : rawDeltaPct;
  const status = impactPct == null || Math.abs(impactPct) < thresholdPct
    ? "stable"
    : impactPct > 0 ? "regression" : "improvement";
  metrics.push({
    key: [section, framework, scenario, metric].filter(Boolean).join("/"),
    section,
    framework,
    scenario,
    label,
    metric,
    before,
    after,
    unit,
    direction,
    delta: rawDelta,
    deltaPct: rawDeltaPct,
    impactPct,
    status,
  });
}

function collectFrameworkResults(metrics, section, currentMap, baselineMap, labels, thresholdPct, excluded) {
  for (const framework of Object.keys(currentMap ?? {})) {
    if (excluded.has(framework)) continue;
    if (!baselineMap?.[framework]) continue;
    for (const [scenario, currentResult] of Object.entries(currentMap[framework] ?? {})) {
      const baselineResult = baselineMap[framework]?.[scenario];
      pushMetric(metrics, {
        section,
        framework,
        scenario,
        label: labels[scenario] ?? currentResult.label ?? scenario,
        metric: "median",
        before: baselineResult?.median,
        after: currentResult?.median,
        unit: "ms",
        thresholdPct,
      });
    }
  }
}

function collectMetrics(current, baseline, thresholdPct) {
  const metrics = [];
  const reuse = reuseProvenance(current);
  const excluded = (suite) => new Set(reuse.suites[suite]?.reusedFrameworks ?? []);
  const mainExcluded = excluded("main");

  collectFrameworkResults(
    metrics,
    "browser-ops",
    current.main?.results,
    baseline.main?.results,
    { ...labelsById(baseline.main?.benchmarks), ...labelsById(current.main?.benchmarks) },
    thresholdPct,
    mainExcluded,
  );
  collectFrameworkResults(
    metrics,
    "tree",
    current.tree?.tree,
    baseline.tree?.tree,
    { ...labelsById(baseline.tree?.treeBenchmarks), ...labelsById(current.tree?.treeBenchmarks) },
    thresholdPct,
    excluded("tree"),
  );

  const addFrameworkFields = (section, currentMap, baselineMap, fields, excludedFrameworks) => {
    for (const framework of Object.keys(currentMap ?? {})) {
      if (excludedFrameworks.has(framework)) continue;
      if (!baselineMap?.[framework]) continue;
      for (const field of fields) {
        pushMetric(metrics, {
          section,
          framework,
          scenario: field.key,
          label: field.label,
          metric: field.key,
          before: baselineMap[framework]?.[field.key],
          after: currentMap[framework]?.[field.key],
          unit: field.unit,
          direction: field.direction,
          thresholdPct,
        });
      }
    }
  };

  addFrameworkFields("startup", current.main?.startup, baseline.main?.startup, [
    { key: "median", label: "mount time", unit: "ms" },
    { key: "scriptMs", label: "script compile + evaluate", unit: "ms" },
    { key: "tbtMs", label: "total blocking time", unit: "ms" },
    { key: "gzipBytes", label: "JavaScript payload", unit: "bytes" },
  ], mainExcluded);
  addFrameworkFields("memory", current.main?.memory, baseline.main?.memory, [
    { key: "afterLoadMb", label: "heap after load", unit: "MB" },
    { key: "after1kMb", label: "heap after 1k rows", unit: "MB" },
    { key: "after5xReplaceMb", label: "heap after 5× replace", unit: "MB" },
    { key: "afterCreateClear10Mb", label: "heap after 10× create/clear", unit: "MB" },
    { key: "afterUnmountMb", label: "heap after unmount", unit: "MB" },
    { key: "after5xRemountMb", label: "heap after 5× remount", unit: "MB" },
  ], mainExcluded);
  addFrameworkFields("animation", current.main?.animation, baseline.main?.animation, [
    { key: "fps", label: "animation throughput", unit: "fps", direction: HIGHER_IS_BETTER },
    { key: "medianMs", label: "median frame time", unit: "ms" },
    { key: "p95Ms", label: "p95 frame time", unit: "ms" },
    { key: "longFramePct", label: "frames over 25 ms", unit: "%" },
  ], mainExcluded);

  const collectScaling = (section, currentArtifact, baselineArtifact) => {
    const excludedFrameworks = excluded(section);
    for (const framework of Object.keys(currentArtifact?.scaling ?? {})) {
      if (excludedFrameworks.has(framework)) continue;
      const currentFw = currentArtifact.scaling[framework];
      const baselineFw = baselineArtifact?.scaling?.[framework];
      if (!baselineFw) continue;
      for (const [op, currentOp] of Object.entries(currentFw.ops ?? {})) {
        const baselineOp = baselineFw.ops?.[op];
        if (!baselineOp) continue;
        pushMetric(metrics, {
          section,
          framework,
          scenario: op,
          label: `${currentOp.label ?? op} exponent`,
          metric: "exponent",
          before: baselineOp.exponent,
          after: currentOp.exponent,
          unit: "exponent",
          thresholdPct,
        });
        for (const size of currentFw.sizes ?? []) {
          pushMetric(metrics, {
            section,
            framework,
            scenario: `${op}@${size}`,
            label: `${currentOp.label ?? op} at ${size.toLocaleString()} rows`,
            metric: "median",
            before: baselineOp.bySize?.[size]?.median,
            after: currentOp.bySize?.[size]?.median,
            unit: "ms",
            thresholdPct,
          });
        }
      }
    }
  };
  collectScaling("scaling", current.scaling, baseline.scaling);
  collectScaling("stress", current.stress, baseline.stress);

  for (const [id, currentBench] of Object.entries(current.jvm?.benchmarks ?? {})) {
    const baselineBench = baseline.jvm?.benchmarks?.[id];
    pushMetric(metrics, {
      section: "jvm",
      scenario: id,
      label: currentBench.label ?? id,
      metric: "medianMs",
      before: baselineBench?.medianMs,
      after: currentBench.medianMs,
      unit: "ms",
      thresholdPct,
    });
  }

  for (const [name, currentSize] of Object.entries(current.sizes?.sizes ?? {})) {
    pushMetric(metrics, {
      section: "size",
      scenario: name,
      label: name,
      metric: "gzipBytes",
      before: baseline.sizes?.sizes?.[name]?.gzipBytes,
      after: currentSize.gzipBytes,
      unit: "bytes",
      thresholdPct,
    });
  }
  for (const field of ["cleanS", "incrementalS"]) {
    pushMetric(metrics, {
      section: "build",
      scenario: field,
      label: field === "cleanS" ? "clean browser-bench build" : "incremental browser-bench build",
      metric: field,
      before: baseline.sizes?.buildTimes?.[field],
      after: current.sizes?.buildTimes?.[field],
      unit: "s",
      thresholdPct,
    });
  }

  const extraExcluded = excluded("extra");
  for (const framework of Object.keys(current.extra?.results ?? {})) {
    if (extraExcluded.has(framework)) continue;
    const baselineFw = baseline.extra?.results?.[framework];
    if (!baselineFw) continue;
    for (const [op, currentOp] of Object.entries(current.extra.results[framework] ?? {})) {
      const baselineOp = baselineFw[op];
      pushMetric(metrics, {
        section: "extra",
        framework,
        scenario: op,
        label: currentOp.label ?? op,
        metric: "median",
        before: baselineOp?.duration?.median,
        after: currentOp.duration?.median,
        unit: "ms",
        thresholdPct,
      });
      pushMetric(metrics, {
        section: "extra",
        framework,
        scenario: `${op}:setup`,
        label: `${currentOp.label ?? op} setup`,
        metric: "setupMedian",
        before: baselineOp?.setupMs?.median,
        after: currentOp.setupMs?.median,
        unit: "ms",
        thresholdPct,
      });
    }
  }
  return metrics;
}

function geometricMean(values) {
  const positive = values.filter((value) => Number.isFinite(value) && value > 0);
  return positive.length
    ? Math.exp(positive.reduce((sum, value) => sum + Math.log(value), 0) / positive.length)
    : null;
}

function aggregateBrowser(metrics, thresholdPct) {
  const rows = [];
  const browser = metrics.filter((metric) => metric.section === "browser-ops");
  for (const framework of [...new Set(browser.map((metric) => metric.framework))]) {
    const frameworkMetrics = browser.filter((metric) => metric.framework === framework && metric.before > 0);
    const ratio = geometricMean(frameworkMetrics.map((metric) => metric.after / metric.before));
    if (ratio == null) continue;
    const deltaPct = (ratio - 1) * 100;
    rows.push({
      framework,
      scenarios: frameworkMetrics.length,
      ratio,
      deltaPct,
      status: Math.abs(deltaPct) < thresholdPct ? "stable" : deltaPct > 0 ? "regression" : "improvement",
    });
  }
  return rows;
}

function fmt(value, unit) {
  if (!Number.isFinite(value)) return "—";
  if (unit === "bytes") return `${(value / 1024).toFixed(1)} KB`;
  if (unit === "%") return `${value.toFixed(1)}%`;
  if (unit === "exponent") return value.toFixed(2);
  const digits = Math.abs(value) >= 100 ? 1 : Math.abs(value) >= 10 ? 2 : 3;
  return `${value.toFixed(digits)}${unit ? ` ${unit}` : ""}`;
}

function renderAggregateBars(aggregates) {
  if (aggregates.length === 0) return "<p>No common browser-operation metrics.</p>";
  const max = Math.max(5, ...aggregates.map((row) => Math.abs(row.deltaPct)));
  return `<div class="delta-chart" role="img" aria-label="Geometric mean browser performance change by framework">
    ${aggregates.map((row) => {
      const width = Math.min(50, Math.abs(row.deltaPct) / max * 48);
      const side = row.deltaPct >= 0 ? "right" : "left";
      return `<div class="delta-row">
        <span class="delta-name">${escapeHtml(row.framework)}</span>
        <span class="delta-track"><span class="zero"></span><span class="delta-bar ${side}" style="width:${width}%"></span></span>
        <span class="delta-value ${row.status}">${row.deltaPct >= 0 ? "+" : ""}${row.deltaPct.toFixed(1)}%</span>
      </div>`;
    }).join("")}
  </div>`;
}

function renderMetricTable(metrics) {
  return `<div class="table-scroll"><table>
    <thead><tr><th>Scope</th><th>Metric</th><th>Baseline</th><th>Current</th><th>Change</th><th>Status</th></tr></thead>
    <tbody>${metrics.map((metric) => `<tr>
      <td>${escapeHtml(metric.framework ? `${metric.framework} · ${metric.label}` : metric.label)}</td>
      <td><code>${escapeHtml(metric.metric)}</code></td>
      <td class="num">${fmt(metric.before, metric.unit)}</td>
      <td class="num">${fmt(metric.after, metric.unit)}</td>
      <td class="num ${metric.status}">${metric.deltaPct == null ? "—" : `${metric.deltaPct >= 0 ? "+" : ""}${metric.deltaPct.toFixed(1)}%`}</td>
      <td><span class="status ${metric.status}">${metric.status}</span></td>
    </tr>`).join("")}</tbody>
  </table></div>`;
}

function renderComparisonHtml(comparison) {
  const { meta, summary, compatibility, reuse, aggregates, metrics } = comparison;
  const grouped = metrics.reduce((sections, metric) => {
    (sections[metric.section] ??= []).push(metric);
    return sections;
  }, {});
  const incompatibility = compatibility.compatible
    ? `<div class="banner ok"><strong>Environment compatible.</strong> Browser/JVM identity checks match where metadata is available.</div>`
    : `<div class="banner warn"><strong>${meta.allowIncompatible ? "Environment mismatch explicitly overridden." : "Comparison blocked by environment mismatch."}</strong> ${compatibility.mismatches.map((item) => `${item.scope}.${item.field}: ${item.baseline} → ${item.current}`).join("; ")}</div>`;
  const reuseNotice = reuse.active
    ? `<div class="banner warn"><strong>Cached framework results excluded from current-run deltas.</strong> ${Object.entries(reuse.suites).map(([suite, entry]) => `${escapeHtml(SECTION_LABELS[suite] ?? suite)}: ${entry.reusedFrameworks.map((name) => escapeHtml(FRAMEWORK_LABELS[name] ?? name)).join(", ")}`).join("; ")}${reuse.sources.length ? `. Source: ${reuse.sources.map(escapeHtml).join(", ")}.` : "."}</div>`
    : "";
  const topRegressions = metrics.filter((metric) => metric.status === "regression")
    .sort((a, b) => b.impactPct - a.impactPct).slice(0, 8);
  const topImprovements = metrics.filter((metric) => metric.status === "improvement")
    .sort((a, b) => a.impactPct - b.impactPct).slice(0, 8);

  return `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark">
<title>Kinetica benchmark comparison</title><style>
:root{--bg:#f8f8f5;--surface:#fff;--ink:#171715;--muted:#65645e;--line:#deddd5;--blue:#2a78d6;--orange:#d56a22;--soft-blue:#dceafa;--soft-orange:#f9e5d5}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 system-ui,-apple-system,sans-serif}.page{max-width:1180px;margin:auto;padding:42px 24px 80px}h1{font-size:30px;margin:0 0 6px}h2{font-size:20px;margin:40px 0 8px}h3{font-size:16px;margin:28px 0 6px}.sub{color:var(--muted);margin:0}.banner{margin:20px 0;padding:13px 15px;border:1px solid;border-radius:8px}.banner.ok{border-color:#8fb7e5;background:var(--soft-blue)}.banner.warn{border-color:#dd9a68;background:var(--soft-orange)}.kpis{display:flex;flex-wrap:wrap;gap:10px;margin:18px 0}.kpi{min-width:150px;padding:12px 14px;background:var(--surface);border:1px solid var(--line);border-radius:8px}.kpi strong{display:block;font:700 23px ui-monospace,monospace}.kpi span{color:var(--muted)}.delta-chart{padding:18px;background:var(--surface);border:1px solid var(--line);border-radius:8px}.delta-row{display:grid;grid-template-columns:120px minmax(220px,1fr) 70px;gap:12px;align-items:center;margin:9px 0}.delta-name{text-align:right}.delta-track{height:14px;position:relative;background:linear-gradient(90deg,transparent 49.8%,#888 49.8%,#888 50.2%,transparent 50.2%)}.delta-bar{position:absolute;height:10px;top:2px}.delta-bar.left{right:50%;background:var(--blue)}.delta-bar.right{left:50%;background:var(--orange)}.delta-value{font-family:ui-monospace,monospace}.improvement{color:#1c65af}.regression{color:#a64b13}.stable{color:var(--muted)}.table-scroll{overflow:auto;border:1px solid var(--line);border-radius:8px;background:var(--surface)}table{border-collapse:collapse;width:100%;min-width:780px}th,td{padding:8px 10px;border-bottom:1px solid var(--line);text-align:left}th{font-size:12px;color:var(--muted);background:color-mix(in srgb,var(--surface),var(--line) 18%)}td.num{text-align:right;font-family:ui-monospace,monospace}.status{font-size:11px;text-transform:uppercase;letter-spacing:.04em}code{font:12px ui-monospace,monospace}details{margin:12px 0}summary{cursor:pointer;font-weight:650;padding:8px 0}.split{display:grid;grid-template-columns:1fr 1fr;gap:18px}.list{background:var(--surface);border:1px solid var(--line);border-radius:8px;padding:12px 16px}.list ol{padding-left:22px}.list li{margin:6px 0}.meta{display:grid;grid-template-columns:max-content 1fr;gap:4px 14px}.meta dt{font-weight:650}.meta dd{margin:0;color:var(--muted);overflow-wrap:anywhere}@media(max-width:760px){.split{grid-template-columns:1fr}.delta-row{grid-template-columns:82px minmax(130px,1fr) 58px}.page{padding:24px 14px}}
@media(prefers-color-scheme:dark){:root{--bg:#151514;--surface:#1d1d1b;--ink:#efeee9;--muted:#aaa9a1;--line:#3b3a36;--soft-blue:#182c42;--soft-orange:#3c281b;--blue:#5da2ed;--orange:#ef9254}}
</style></head><body><main class="page">
<header><h1>Kinetica benchmark comparison</h1><p class="sub">Current ${escapeHtml(meta.current.label)} against ${escapeHtml(meta.baseline.label)} · generated ${escapeHtml(meta.generatedAt)}</p></header>
${incompatibility}
${reuseNotice}
<section><h2>Technical summary</h2><p>${compatibility.compatible ? "The runs are environment-compatible for the metadata available." : meta.allowIncompatible ? "The runs differ in recorded environment metadata; the comparison was explicitly allowed and must be interpreted with that caveat." : "Performance deltas must not be interpreted until the environment mismatch is resolved or explicitly overridden."} A change is classified when its direction-adjusted magnitude is at least ${meta.thresholdPct}%.</p>
<div class="kpis"><div class="kpi"><strong>${summary.metrics}</strong><span>common metrics</span></div><div class="kpi"><strong class="regression">${summary.regressions}</strong><span>regressions</span></div><div class="kpi"><strong class="improvement">${summary.improvements}</strong><span>improvements</span></div><div class="kpi"><strong>${summary.stable}</strong><span>within threshold</span></div></div></section>
<section><h2>Browser aggregate change</h2><p>Each bar is the geometric mean of current/baseline median ratios over common browser operations. Left/blue is faster; right/orange is slower. This comparison is descriptive and does not estimate statistical significance.</p>${renderAggregateBars(aggregates.browser)}</section>
<section><h2>Largest movements</h2><div class="split"><div class="list"><h3>Regressions</h3><ol>${topRegressions.map((metric) => `<li><strong>+${metric.impactPct.toFixed(1)}%</strong> ${escapeHtml(metric.framework ? `${metric.framework} · ${metric.label}` : metric.label)}</li>`).join("") || "<li>None above threshold.</li>"}</ol></div><div class="list"><h3>Improvements</h3><ol>${topImprovements.map((metric) => `<li><strong>${metric.impactPct.toFixed(1)}%</strong> ${escapeHtml(metric.framework ? `${metric.framework} · ${metric.label}` : metric.label)}</li>`).join("") || "<li>None above threshold.</li>"}</ol></div></div></section>
<section><h2>Metric-level evidence</h2><p>Exact baseline/current values and raw percentage changes. For higher-is-better metrics such as FPS, status classification reverses the raw percentage direction.</p>${Object.entries(grouped).map(([section, rows]) => `<details ${section === "browser-ops" ? "open" : ""}><summary>${escapeHtml(SECTION_LABELS[section] ?? section)} (${rows.length})</summary>${renderMetricTable(rows)}</details>`).join("")}</section>
<section><h2>Scope and metric definitions</h2><dl class="meta"><dt>Current</dt><dd>${escapeHtml(meta.current.root)}</dd><dt>Baseline</dt><dd>${escapeHtml(meta.baseline.root)}</dd><dt>Threshold</dt><dd>${meta.thresholdPct}% direction-adjusted change</dd><dt>Browser aggregate</dt><dd>Geometric mean of per-operation current median / baseline median for common scenarios.</dd><dt>Status</dt><dd>Lower is better except FPS. Stable means absolute direction-adjusted change is below the threshold.</dd></dl></section>
<section><h2>Methodology and robustness</h2><p>Only freshly measured current-run metrics present in both runs are compared; cached framework values are retained in the combined artifacts for context but excluded from deltas. No missing value is imputed. Browser identity checks cover Chromium, CPU, architecture, platform and throttle where recorded; JVM checks cover runtime and OS. Sample distributions are retained in source JSON, while this report compares medians.</p></section>
<section><h2>Limitations</h2><ul><li>Percentage thresholds are practical noise guards, not confidence intervals.</li><li>Results from different machines, browser builds, throttle settings or JVMs are blocked by default.</li><li>A filtered run can compare only its common subset; aggregate changes state the number of included scenarios.</li><li>Bundle size comparisons are deterministic for identical build inputs; runtime measurements are not.</li></ul></section>
<section><h2>Recommended next steps</h2><ul><li>Inspect the largest same-environment regressions in the metric tables and raw samples.</li><li>Repeat surprising runtime changes with more iterations before accepting a baseline.</li><li>Promote and update documentation only from a complete all-framework run.</li></ul></section>
<section><h2>Further questions</h2><p>Do the largest deltas reproduce in a second run, and do CPU profiles or allocation traces explain them?</p></section>
</main></body></html>`;
}

export function generateComparison({
  currentDir,
  baselineDir,
  outDir = currentDir,
  currentLabel = basename(resolve(currentDir)),
  baselineLabel = basename(resolve(baselineDir)),
  thresholdPct = 5,
  allowIncompatible = false,
}) {
  const current = loadRunArtifacts(currentDir);
  const baseline = loadRunArtifacts(baselineDir);
  const compatibility = compatibilityCheck(current, baseline);
  const reuse = reuseProvenance(current);
  const metrics = collectMetrics(current, baseline, thresholdPct);
  if (metrics.length === 0) {
    throw new Error("current and baseline runs have no common benchmark metrics");
  }
  const summary = {
    metrics: metrics.length,
    regressions: metrics.filter((metric) => metric.status === "regression").length,
    improvements: metrics.filter((metric) => metric.status === "improvement").length,
    stable: metrics.filter((metric) => metric.status === "stable").length,
  };
  const comparison = {
    meta: {
      generatedAt: new Date().toISOString(),
      thresholdPct,
      allowIncompatible,
      current: { root: currentLabel, label: currentLabel },
      baseline: { root: baselineLabel, label: baselineLabel },
    },
    compatibility,
    reuse,
    summary,
    aggregates: { browser: aggregateBrowser(metrics, thresholdPct) },
    metrics,
  };
  mkdirSync(outDir, { recursive: true });
  const jsonPath = join(outDir, "comparison.json");
  const htmlPath = join(outDir, "comparison.html");
  writeFileSync(jsonPath, `${JSON.stringify(comparison, null, 2)}\n`);
  writeFileSync(htmlPath, renderComparisonHtml(comparison));
  return {
    comparison,
    jsonPath,
    htmlPath,
    allowed: compatibility.compatible || allowIncompatible,
  };
}

const DOC_START = "<!-- BENCHMARK_RESULTS:START -->";
const DOC_END = "<!-- BENCHMARK_RESULTS:END -->";

function displayMs(value) {
  return value >= 1000 ? `${(value / 1000).toFixed(2)} s` : value.toFixed(1);
}

function currentGeoMeans(main) {
  const frameworkOrder = frameworks.map((framework) => framework.name).filter((name) => main.results?.[name]);
  const benches = (main.benchmarks ?? []).filter((bench) => frameworkOrder.some((name) => main.results[name]?.[bench.id]));
  const best = Object.fromEntries(benches.map((bench) => [
    bench.id,
    Math.min(...frameworkOrder.map((name) => main.results[name]?.[bench.id]?.median ?? Infinity)),
  ]));
  const geomean = Object.fromEntries(frameworkOrder.map((name) => {
    const factors = benches.flatMap((bench) => {
      const value = main.results[name]?.[bench.id]?.median;
      return Number.isFinite(value) ? [value / best[bench.id]] : [];
    });
    return [name, geometricMean(factors)];
  }));
  return { frameworkOrder, benches, geomean };
}

export function updatePerformanceDocs({ currentDir, comparison = null, docsPath }) {
  const current = loadRunArtifacts(currentDir);
  if (!current.main) throw new Error("cannot update performance docs without results.json");
  const { frameworkOrder, benches, geomean } = currentGeoMeans(current.main);
  const labels = Object.fromEntries(frameworks.map((framework) => [framework.name, framework.label]));
  const header = `| Operation | ${frameworkOrder.map((name) => labels[name] ?? name).join(" | ")} |`;
  const separator = `|---|${frameworkOrder.map(() => "---:").join("|")}|`;
  const rows = benches.map((bench) => `| ${bench.label} | ${frameworkOrder.map((name) => {
    const value = current.main.results[name]?.[bench.id]?.median;
    return Number.isFinite(value) ? displayMs(value) : "—";
  }).join(" | ")} |`);
  const geoRow = `| **geometric mean vs per-operation fastest** | ${frameworkOrder.map((name) => `**${geomean[name]?.toFixed(2) ?? "—"}×**`).join(" | ")} |`;
  const meta = current.main.meta;
  const compareLine = comparison
    ? `\nCompared with **${comparison.meta.baseline.label}**: ${comparison.summary.regressions} regressions, ${comparison.summary.improvements} improvements, and ${comparison.summary.stable} metrics within the ${comparison.meta.thresholdPct}% threshold. [Open the comparison report](/bench/report/comparison.html).\n`
    : "";
  const block = `${DOC_START}
## Current benchmark results (${meta.date.slice(0, 10)})

_Generated by \`node bench/run.mjs --update-docs\`; do not edit this block manually._

Median click-to-paint duration in milliseconds unless shown as seconds. Environment:
${meta.machine?.cpu ?? "unknown CPU"}, ${meta.machine?.platform ?? "unknown platform"}/${meta.machine?.arch ?? "unknown arch"},
Chromium ${meta.chromium}, ${meta.warmup} warmups + ${meta.samples} measured samples.

${header}
${separator}
${rows.join("\n")}
${geoRow}
${compareLine}
Raw data: [results.json](/bench/results/results.json) · [tree.json](/bench/results/tree.json) · [scaling.json](/bench/results/scaling.json) · [JVM results](/bench/results/jvm/results.json) · [size/build metrics](/bench/results/sizes.json).
${DOC_END}`;

  const source = readFileSync(docsPath, "utf8");
  let updated;
  if (source.includes(DOC_START) && source.includes(DOC_END)) {
    updated = source.replace(new RegExp(`${DOC_START}[\\s\\S]*?${DOC_END}`), block);
  } else {
    const start = source.indexOf("## Where Kinetica stands");
    const end = source.indexOf("## Live demos");
    if (start < 0 || end < 0 || end <= start) {
      throw new Error("could not locate the benchmark-results section in performance.md");
    }
    updated = `${source.slice(0, start)}${block}\n\n${source.slice(end)}`;
  }
  writeFileSync(docsPath, updated);
  return { docsPath, block };
}
