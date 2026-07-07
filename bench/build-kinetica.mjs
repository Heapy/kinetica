import { existsSync, mkdirSync, readFileSync } from "node:fs";
import { gzipSync } from "node:zlib";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { run } from "../scripts/lib/run.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const linkEntry = join(repoRoot, "build", "tasks", "_browser-bench_linkJs", "browser-bench.mjs");
const bundleDir = join(repoRoot, "build", "tasks", "_browser-bench_bundle");
const bundleFile = join(bundleDir, "browser-bench.bundle.mjs");

const kotlin = process.platform === "win32" ? "kotlin.bat" : "./kotlin";
// browser-bench compiles through the Kinetica compiler plugin, resolved from the
// toolchain-local repo — publish it first so the bundle always uses the current plugin.
run(kotlin, ["publish", "mavenLocal", "-m", "kinetica-compiler"], { cwd: repoRoot });
run(kotlin, ["build", "-m", "browser-bench"], { cwd: repoRoot });

if (!existsSync(linkEntry)) {
  throw new Error(`Kotlin JS link output not found: ${linkEntry}`);
}

mkdirSync(bundleDir, { recursive: true });
await build({
  entryPoints: [linkEntry],
  bundle: true,
  minify: true,
  treeShaking: true,
  legalComments: "none",
  format: "esm",
  platform: "browser",
  target: "es2020",
  outfile: bundleFile,
  logLevel: "warning",
});

const bytes = readFileSync(bundleFile);
console.log(
  `built kinetica bundle: ${(bytes.length / 1024).toFixed(1)}KB raw / ${(gzipSync(bytes).length / 1024).toFixed(1)}KB gzip`,
);
