// Run after: ./kotlin task :kinetica-browser:linkJsTest
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { createRequire } from "node:module";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { jsOutputDir } from "./lib/js-output.mjs";

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), "..");
const { build } = createRequire(join(repoRoot, "bench/package.json"))("esbuild");
const testModule = join(jsOutputDir(repoRoot, "kinetica-browser", { test: true }),
  "kotlin_kinetica_browser_test/io/heapy/kinetica/browser/BrowserDebugDiagnosticsTest.mjs");
const outputDir = mkdtempSync(join(tmpdir(), "kinetica-browser-debug-"));
try {
  for (const flag of [undefined, true, false]) {
    const name = flag === undefined ? "default" : String(flag);
    const outfile = join(outputDir, `${name}.mjs`);
    await build({
      stdin: {
        contents: `
          import * as tests from ${JSON.stringify(testModule)};
          globalThis.KINETICA_TEST_EXPECT_DEBUG_ATTRIBUTES = ${flag !== false};
          let count = 0;
          globalThis.kotlinTest = { adapterTransformer: () => ({
            suite(name, ignored, body) { if (!ignored) body(); },
            test(name, ignored, body) { if (!ignored) { body(); count++; } },
          }) };
          const entry = Object.entries(tests).find(([name]) => name.startsWith("test_fun_"));
          if (!entry) throw new Error("Missing test registration");
          entry[1]();
          if (count !== 2) throw new Error("Expected two diagnostics tests, got " + count);
        `,
        resolveDir: repoRoot,
      },
      bundle: true,
      minify: true,
      format: "esm",
      platform: "node",
      define: flag === undefined ? {} : { KINETICA_DEBUG_DIAGNOSTICS: String(flag) },
      outfile,
    });
    const result = spawnSync(process.execPath, [outfile], { encoding: "utf8" });
    assert.equal(result.status, 0, `${name}: ${result.error ?? result.stderr}`);
    console.log(`PASS browser diagnostics=${name}: debug attributes and island metadata`);
  }
} finally {
  rmSync(outputDir, { recursive: true, force: true });
}
