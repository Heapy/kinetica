import { createWriteStream, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { get } from "node:https";
import { basename, join, resolve } from "node:path";
import { execFileSync } from "node:child_process";
import { run } from "./lib/run.mjs";

const jacocoVersion = "0.8.14";
const root = process.cwd();
const toolsDir = resolve(root, ".tools/jacoco");
const reportsDir = resolve(root, "build/reports/coverage");
const execFile = join(reportsDir, "jacoco.exec");
const xmlFile = join(reportsDir, "jacoco.xml");
const htmlDir = join(reportsDir, "html");
const agentJar = join(toolsDir, "jacocoagent.jar");
const cliJar = join(toolsDir, `org.jacoco.cli-${jacocoVersion}-nodeps.jar`);

mkdirSync(toolsDir, { recursive: true });
mkdirSync(reportsDir, { recursive: true });

ensureAgentJar();
await ensureCliJar();

rmSync(execFile, { force: true });
rmSync(xmlFile, { force: true });
rmSync(htmlDir, { force: true, recursive: true });

const javaToolOptions = [
  process.env.JAVA_TOOL_OPTIONS,
  `-javaagent:${agentJar}=destfile=${execFile},append=true,dumponexit=true,includes=io.heapy.kinetica.*:app.*:bench.jvm.*:docs.site.*`,
].filter(Boolean).join(" ");

for (const moduleName of jvmTestModules()) {
  runKotlinWithCoverage(["test", "--platform=jvm", "-m", moduleName], javaToolOptions);
}
if (process.env.KINETICA_COVERAGE_RUN_SAMPLES !== "false") {
  runKotlinWithCoverage(["run", "-m", "annotated"], javaToolOptions);
  runKotlinWithCoverage(["run", "-m", "todo"], javaToolOptions);
  runKotlinWithCoverage(["run", "-m", "server-components", "--", "--print"], javaToolOptions);
}
if (!existsSync(execFile)) {
  throw new Error(`JaCoCo execution data was not written: ${execFile}`);
}

const classFiles = moduleJarFiles();
const sourceFiles = moduleSourceDirs();
if (classFiles.length === 0) {
  throw new Error("No JVM module jars found under build/tasks.");
}
if (sourceFiles.length === 0) {
  throw new Error("No module source directories found.");
}

execFileSync(
  "java",
  [
    "-jar",
    cliJar,
    "report",
    execFile,
    ...classFiles.flatMap((file) => ["--classfiles", file]),
    ...sourceFiles.flatMap((directory) => ["--sourcefiles", directory]),
    "--xml",
    xmlFile,
    "--html",
    htmlDir,
  ],
  { cwd: root, stdio: "inherit" },
);

const summary = summarizeJacocoXml(readFileSync(xmlFile, "utf8"));
const aggregateExcludedPackages = aggregateCoverageExcludedPackages();
const gatePackages = summary.packages.filter((packageSummary) =>
  !aggregateExcludedPackages.has(packageSummary.name),
);
if (gatePackages.length === 0) {
  throw new Error("No packages remain for the JVM aggregate coverage gate.");
}
const gateSummary = aggregatePackageCounters(gatePackages);
const minimumLinePercent = Number(process.env.KINETICA_JVM_LINE_MIN ?? "97.05");
const minimumBranchPercent = Number(process.env.KINETICA_JVM_BRANCH_MIN ?? "85.85");
const minimumPackageLinePercent = Number(process.env.KINETICA_JVM_PACKAGE_LINE_MIN ?? "93");
const minimumPackageBranchPercent = Number(process.env.KINETICA_JVM_PACKAGE_BRANCH_MIN ?? "50");
writeFileSync(
  join(reportsDir, "summary.json"),
  `${JSON.stringify({
    ...summary,
    gate: {
      excludedPackages: [...aggregateExcludedPackages].sort(),
      packages: gatePackages.map((packageSummary) => packageSummary.name).sort(),
      line: gateSummary.line,
      branch: gateSummary.branch,
    },
    minimums: {
      linePercent: minimumLinePercent,
      branchPercent: minimumBranchPercent,
      packageLinePercent: minimumPackageLinePercent,
      packageBranchPercent: minimumPackageBranchPercent,
    },
  }, null, 2)}\n`,
);
console.log(
  `JVM coverage: ${summary.line.covered}/${summary.line.total} lines (${summary.line.percent.toFixed(2)}%), ` +
    `${summary.branch.covered}/${summary.branch.total} branches (${summary.branch.percent.toFixed(2)}%).`,
);
if (aggregateExcludedPackages.size > 0) {
  console.log(
    `JVM aggregate gate: ${gateSummary.line.covered}/${gateSummary.line.total} lines ` +
      `(${gateSummary.line.percent.toFixed(2)}%), ${gateSummary.branch.covered}/${gateSummary.branch.total} ` +
      `branches (${gateSummary.branch.percent.toFixed(2)}%).`,
  );
  console.log(`JVM aggregate gate excludes: ${[...aggregateExcludedPackages].sort().join(", ")}`);
}
console.table(
  summary.packages.map((packageSummary) => ({
    package: packageSummary.name,
    line: packageSummary.line.percent.toFixed(2),
    branch: packageSummary.branch.percent.toFixed(2),
  })),
);

const thresholdFailures = [];
if (gateSummary.line.percent < minimumLinePercent) {
  thresholdFailures.push(`gate line ${gateSummary.line.percent.toFixed(2)}% < ${minimumLinePercent}%`);
}
if (gateSummary.branch.percent < minimumBranchPercent) {
  thresholdFailures.push(`gate branch ${gateSummary.branch.percent.toFixed(2)}% < ${minimumBranchPercent}%`);
}
for (const packageSummary of summary.packages) {
  if (packageSummary.line.percent < minimumPackageLinePercent) {
    thresholdFailures.push(
      `${packageSummary.name} line ${packageSummary.line.percent.toFixed(2)}% < ${minimumPackageLinePercent}%`,
    );
  }
  if (packageSummary.branch.percent < minimumPackageBranchPercent) {
    thresholdFailures.push(
      `${packageSummary.name} branch ${packageSummary.branch.percent.toFixed(2)}% < ${minimumPackageBranchPercent}%`,
    );
  }
}
if (thresholdFailures.length > 0) {
  throw new Error(
    `JVM coverage is below threshold:\n- ${thresholdFailures.join("\n- ")}`,
  );
}
console.log(`Coverage XML: ${xmlFile}`);
console.log(`Coverage HTML: ${htmlDir}/index.html`);

function runKotlinWithCoverage(args, javaToolOptions) {
  run("./kotlin", args, {
    cwd: root,
    env: {
      ...process.env,
      JAVA_TOOL_OPTIONS: javaToolOptions,
    },
    printCommand: false,
    stdio: "inherit",
  });
}

function ensureAgentJar() {
  if (existsSync(agentJar)) {
    return;
  }
  const bundle = findFirstExisting([
    join(root, ".kotlin/shared/.m2.cache/org.jacoco/org.jacoco.agent"),
    join(process.env.HOME ?? "", ".gradle/caches/modules-2/files-2.1/org.jacoco/org.jacoco.agent"),
  ], (file) => file.endsWith(`org.jacoco.agent-${jacocoVersion}.jar`));

  if (bundle == null) {
    throw new Error("Could not find org.jacoco.agent in local caches.");
  }
  const bytes = execFileSync("unzip", ["-p", bundle, "jacocoagent.jar"]);
  writeFileSync(agentJar, bytes);
}

async function ensureCliJar() {
  if (existsSync(cliJar)) {
    return;
  }
  const cached = findFirstExisting([
    join(process.env.HOME ?? "", ".gradle/caches/modules-2/files-2.1/org.jacoco/org.jacoco.cli"),
  ], (file) => file.endsWith(`org.jacoco.cli-${jacocoVersion}-nodeps.jar`));
  if (cached != null) {
    writeFileSync(cliJar, readFileSync(cached));
    return;
  }

  const url = `https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/${jacocoVersion}/org.jacoco.cli-${jacocoVersion}-nodeps.jar`;
  await download(url, cliJar);
}

function moduleJarFiles() {
  const taskRoot = join(root, "build/tasks");
  return walk(taskRoot)
    .filter((file) => /_jarJvm\/.*-jvm\.jar$/.test(file))
    .filter((file) => !file.includes("/_kinetica-compiler_javadocJarJvm/"))
    .sort();
}

function moduleSourceDirs() {
  return projectModules()
    .flatMap((modulePath) => ["src", "src@jvm"].map((sourceDir) => join(root, modulePath, sourceDir)))
    .filter((path) => existsSync(path) && statSync(path).isDirectory())
    .sort();
}

function jvmTestModules() {
  return projectModules()
    .filter((modulePath) =>
      hasJvmPlatform(modulePath) &&
      (existsSync(join(root, modulePath, "test")) || existsSync(join(root, modulePath, "test@jvm")))
    )
    .map((modulePath) => basename(modulePath));
}

function hasJvmPlatform(modulePath) {
  const manifest = readFileSync(join(root, modulePath, "module.yaml"), "utf8");
  return /^\s*product:\s*jvm\//m.test(manifest) ||
    /^\s*platforms:\s*\[[^\]]*\bjvm\b[^\]]*\]/m.test(manifest);
}

function projectModules() {
  return readFileSync(join(root, "project.yaml"), "utf8")
    .split(/\r?\n/)
    .map((line) => line.match(/^\s+-\s+\.\/(\S+)\s*$/)?.[1])
    .filter(Boolean);
}

function summarizeJacocoXml(xml) {
  const totals = lastLineAndBranchCounters(xml);
  return {
    line: withPercent(totals.line),
    branch: withPercent(totals.branch),
    packages: packageSummaries(xml),
  };
}

function aggregateCoverageExcludedPackages() {
  const configured = process.env.KINETICA_JVM_AGGREGATE_EXCLUDE;
  const packageNames = configured == null
    ? [
      "app.annotated",
      "app.servercomponents",
      "app.servercomponents.shared",
      "app.todo",
      "bench.jvm",
      "docs.site",
      "io.heapy.kinetica.compiler",
    ]
    : configured
      .split(",")
      .map((value) => value.trim())
      .filter(Boolean);
  return new Set(packageNames);
}

function aggregatePackageCounters(packages) {
  const line = packages.reduce(
    (counter, packageSummary) => ({
      missed: counter.missed + packageSummary.line.missed,
      covered: counter.covered + packageSummary.line.covered,
    }),
    { missed: 0, covered: 0 },
  );
  const branch = packages.reduce(
    (counter, packageSummary) => ({
      missed: counter.missed + packageSummary.branch.missed,
      covered: counter.covered + packageSummary.branch.covered,
    }),
    { missed: 0, covered: 0 },
  );
  return {
    line: withPercent(line),
    branch: withPercent(branch),
  };
}

function withPercent(counter) {
  const total = counter.missed + counter.covered;
  return {
    covered: counter.covered,
    missed: counter.missed,
    total,
    percent: total === 0 ? 100 : (counter.covered / total) * 100,
  };
}

function packageSummaries(xml) {
  return [...xml.matchAll(/<package name="([^"]*)">([\s\S]*?)<\/package>/g)]
    .map(([, rawName, body]) => {
      const counters = lastLineAndBranchCounters(body);
      return {
        name: rawName.replaceAll("/", ".") || "<default>",
        line: withPercent(counters.line),
        branch: withPercent(counters.branch ?? { missed: 0, covered: 0 }),
      };
    })
    .filter((summary) => summary.line.total > 0)
    .sort((left, right) =>
      left.line.percent - right.line.percent ||
        left.branch.percent - right.branch.percent ||
        left.name.localeCompare(right.name),
    );
}

function lastLineAndBranchCounters(xml) {
  const counters = [...xml.matchAll(/<counter type="(LINE|BRANCH)" missed="(\d+)" covered="(\d+)"\/>/g)];
  const totals = { line: null, branch: null };
  for (const [, type, missed, covered] of counters) {
    const key = type === "LINE" ? "line" : "branch";
    totals[key] = {
      missed: Number(missed),
      covered: Number(covered),
    };
  }
  if (totals.line == null) {
    throw new Error("JaCoCo XML did not contain a line counter.");
  }
  return totals;
}

function findFirstExisting(roots, predicate) {
  for (const candidateRoot of roots) {
    if (!candidateRoot || !existsSync(candidateRoot)) {
      continue;
    }
    const match = walk(candidateRoot).find(predicate);
    if (match != null) {
      return match;
    }
  }
  return null;
}

function walk(path) {
  const stat = statSync(path);
  if (stat.isFile()) {
    return [path];
  }
  if (!stat.isDirectory()) {
    return [];
  }
  return readdirSync(path).flatMap((entry) => walk(join(path, entry)));
}

function download(url, target) {
  return new Promise((resolvePromise, reject) => {
    const file = createWriteStream(target);
    get(url, (response) => {
      if (response.statusCode !== 200) {
        file.close();
        rmSync(target, { force: true });
        reject(new Error(`Failed to download ${url}: HTTP ${response.statusCode}`));
        return;
      }
      response.pipe(file);
      file.on("finish", () => {
        file.close(resolvePromise);
      });
    }).on("error", (error) => {
      file.close();
      rmSync(target, { force: true });
      reject(error);
    });
  });
}
