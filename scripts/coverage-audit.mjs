import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const projectYaml = readFileSync("project.yaml", "utf8");
const verifierCache = new Map();

const modules = projectYaml
  .split(/\r?\n/)
  .map((line) => line.match(/^\s+-\s+(\.\/\S+)\s*$/)?.[1])
  .filter(Boolean);

const externalCoverage = new Map([
  ["./docs/docs-client", verifier("docs/verify-docs.mjs", "live counter example mounts")],
  ["./samples/annotated-js", verifier("scripts/verify-js-samples.mjs", "annotated-js")],
  ["./samples/browser-bench", verifier("bench/driver/bench.mjs", "01_run1k")],
  ["./samples/browser-counter", verifier("scripts/verify-browser.mjs", "function verifyCounter(")],
  ["./samples/browser-game-of-life", verifier("scripts/verify-browser.mjs", "function verifyGameOfLife(")],
  ["./samples/browser-tests", verifier("scripts/verify-browser.mjs", "function verifyBrowserTests(")],
  ["./samples/browser-todo", verifier("scripts/verify-browser.mjs", "function verifyTodo(")],
  ["./samples/server-components-client", verifier("scripts/verify-browser.mjs", "function verifyServerComponents(")],
]);

const rows = [];
const failures = [];

for (const modulePath of modules) {
  const testFiles = existingFilesUnder(modulePath, (relativePath) =>
    relativePath.startsWith("test/") || /^test@[^/]+\//.test(relativePath),
  ).filter((file) => file.endsWith(".kt"));
  const sourceFiles = existingFilesUnder(modulePath, (relativePath) =>
    relativePath.startsWith("src/") || /^src@[^/]+\//.test(relativePath),
  ).filter((file) => file.endsWith(".kt"));
  const verifier = externalCoverage.get(modulePath);
  const verifierPresent = verifier == null || verifierText(verifier.file).includes(verifier.needle);

  if (sourceFiles.length > 0 && testFiles.length === 0 && verifier == null) {
    failures.push(`${modulePath} has source files but no Kotlin tests or external verifier coverage.`);
  }
  if (verifier != null && !verifierPresent) {
    failures.push(`${modulePath} declares external coverage through ${verifier.name}, but ${verifier.file} does not contain its verifier marker.`);
  }

  rows.push({
    module: modulePath,
    sources: sourceFiles.length,
    tests: testFiles.length,
    verifier: verifier?.name ?? "",
  });
}

const allTestText = modules
  .flatMap((modulePath) => existingFilesUnder(modulePath, (relativePath) =>
    relativePath.startsWith("test/") || /^test@[^/]+\//.test(relativePath),
  ))
  .filter((file) => file.endsWith(".kt"))
  .map((file) => readFileSync(file, "utf8"))
  .join("\n");

const styleChecks = [
  {
    name: "headless integration tests",
    ok: allTestText.includes("KineticaTest.render"),
  },
  {
    name: "snapshot tests",
    ok: allTestText.includes("assertHtmlSnapshot") || allTestText.includes("toTreeSnapshot"),
  },
  {
    name: "HTTP/server action tests",
    ok: allTestText.includes("HttpClient") && allTestText.includes("ServerActionRequest"),
  },
  {
    name: "browser end-to-end tests",
    ok:
      verifierText("scripts/verify-browser.mjs").includes("verifyCounter") &&
      verifierText("scripts/verify-browser.mjs").includes("verifyTodo") &&
      verifierText("scripts/verify-browser.mjs").includes("verifyServerComponents") &&
      verifierText("scripts/verify-browser.mjs").includes("KINETICA_SERVER_COMPONENTS_URL"),
  },
];

for (const check of styleChecks) {
  if (!check.ok) {
    failures.push(`Missing required test style: ${check.name}.`);
  }
}

console.table(rows);
console.log("Test styles:");
for (const check of styleChecks) {
  console.log(`- ${check.ok ? "ok" : "missing"} ${check.name}`);
}

if (failures.length > 0) {
  console.error("Coverage audit failed:");
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exit(1);
}

console.log("Coverage audit passed");

function verifier(file, needle) {
  return {
    name: `${file}:${needle}`,
    file,
    needle,
  };
}

function verifierText(file) {
  if (!verifierCache.has(file)) {
    verifierCache.set(file, readFileSync(file, "utf8"));
  }
  return verifierCache.get(file);
}

function existingFilesUnder(root, includeRelativePath) {
  const result = [];
  visit(root, "");
  return result;

  function visit(absoluteRoot, relativeRoot) {
    let entries;
    try {
      entries = readdirSync(absoluteRoot);
    } catch {
      return;
    }

    for (const entry of entries) {
      const absolutePath = join(absoluteRoot, entry);
      const relativePath = relativeRoot === "" ? entry : `${relativeRoot}/${entry}`;
      const stat = statSync(absolutePath);
      if (stat.isDirectory()) {
        if (entry === "build") {
          continue;
        }
        visit(absolutePath, relativePath);
      } else if (includeRelativePath(relativePath)) {
        result.push(absolutePath);
      }
    }
  }
}
