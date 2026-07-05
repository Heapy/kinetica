import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const projectYaml = readFileSync("project.yaml", "utf8");
const verifyBrowser = readFileSync("scripts/verify-browser.mjs", "utf8");

const modules = projectYaml
  .split(/\r?\n/)
  .map((line) => line.match(/^\s+-\s+(\.\/\S+)\s*$/)?.[1])
  .filter(Boolean);

const externalCoverage = new Map([
  ["./samples/browser-counter", "verifyCounter"],
  ["./samples/browser-tests", "verifyBrowserTests"],
  ["./samples/browser-todo", "verifyTodo"],
  ["./samples/server-components-client", "verifyServerComponents"],
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
  const verifierPresent = verifier == null || verifyBrowser.includes(`function ${verifier}(`);

  if (sourceFiles.length > 0 && testFiles.length === 0 && verifier == null) {
    failures.push(`${modulePath} has source files but no Kotlin tests or external verifier coverage.`);
  }
  if (verifier != null && !verifierPresent) {
    failures.push(`${modulePath} declares external coverage through ${verifier}, but scripts/verify-browser.mjs does not define it.`);
  }

  rows.push({
    module: modulePath,
    sources: sourceFiles.length,
    tests: testFiles.length,
    verifier: verifier ?? "",
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
      verifyBrowser.includes("verifyCounter") &&
      verifyBrowser.includes("verifyTodo") &&
      verifyBrowser.includes("verifyServerComponents") &&
      verifyBrowser.includes("KINETICA_SERVER_COMPONENTS_URL"),
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
