// The wasm glue loads sibling files directly, so it is served without bundling. Kotlin Toolchain
// does not lay out skiko.mjs/skiko.wasm as Gradle's Compose plugin does; copy them from the
// resolved cache or fall back to Maven Central.

import { createWriteStream, existsSync, mkdirSync, readFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { gzipSync } from "node:zlib";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { pipeline } from "node:stream/promises";
import { Readable } from "node:stream";
import { fileURLToPath } from "node:url";
import { run } from "../scripts/lib/run.mjs";
import { JS_VARIANT_ARGS, jsOutputDir } from "../scripts/lib/js-output.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const MODULE = "browser-bench-compose-canvas";

const SKIKO_FILES = ["skiko.mjs", "skiko.wasm"];

const outputDir = jsOutputDir(repoRoot, MODULE, { platform: "wasmJs" });

const kotlin = process.platform === "win32" ? "kotlin.bat" : "./kotlin";
run(kotlin, ["build", ...JS_VARIANT_ARGS, "-m", MODULE], { cwd: repoRoot });

const entry = join(outputDir, `${MODULE}.mjs`);
if (!existsSync(entry)) {
  throw new Error(`Kotlin/Wasm link output not found: ${entry}`);
}

const skikoVersion = resolveSkikoVersion();
const jar = await resolveSkikoRuntimeJar(skikoVersion);
execFileSync("unzip", ["-o", "-q", jar.path, ...SKIKO_FILES, "-d", outputDir]);
for (const file of SKIKO_FILES) {
  if (!existsSync(join(outputDir, file))) {
    throw new Error(`${file} missing from ${jar.path} — skiko runtime layout changed`);
  }
}

const artifacts = [
  `${MODULE}.wasm`,
  `${MODULE}.mjs`,
  `${MODULE}.import-object.mjs`,
  `${MODULE}.js-builtins.mjs`,
  ...SKIKO_FILES,
];
let raw = 0;
let gzip = 0;
for (const file of artifacts) {
  const bytes = readFileSync(join(outputDir, file));
  raw += bytes.length;
  gzip += gzipSync(bytes).length;
}
console.log(
  `built ${MODULE} (skiko ${jar.version} from ${jar.source}): ` +
    `${(raw / 1048576).toFixed(2)}MB raw / ${(gzip / 1048576).toFixed(2)}MB gzip across ${artifacts.length} files`,
);

// Prefer the normal toolchain dependency cache so the copied runtime matches the linked build.
function resolveSkikoVersion() {
  const output = execFileSync(kotlin, [
    "show",
    "dependencies",
    "-m",
    MODULE,
    "-p",
    "wasmJs",
    "--filter=org.jetbrains.skiko:skiko-js-wasm-runtime",
    "--scope=runtime",
  ], { cwd: repoRoot, encoding: "utf8" });
  const versions = new Set(
    [...output.matchAll(/org\.jetbrains\.skiko:skiko-js-wasm-runtime:([^\s]+)/g)]
      .map((match) => match[1]),
  );
  if (versions.size !== 1) {
    throw new Error(`expected one resolved skiko runtime version, found: ${[...versions].join(", ") || "none"}`);
  }
  return [...versions][0];
}

async function resolveSkikoRuntimeJar(version) {
  const cached = findInToolchainCache(version);
  if (cached) return cached;

  const dir = join(repoRoot, "build", "tasks", `_${MODULE}_skiko`, version);
  const path = join(dir, `skiko-js-wasm-runtime-${version}.jar`);
  if (existsSync(path)) return { path, version, source: "repo cache" };

  mkdirSync(dir, { recursive: true });
  const url =
    "https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-js-wasm-runtime/" +
    `${version}/skiko-js-wasm-runtime-${version}.jar`;
  console.log(`downloading ${url}`);
  const response = await fetch(url);
  if (!response.ok) throw new Error(`skiko runtime download failed: ${response.status} ${url}`);
  await pipeline(Readable.fromWeb(response.body), createWriteStream(path));
  return { path, version, source: "maven central" };
}

function findInToolchainCache(version) {
  const roots = [
    process.env.KOTLIN_SHARED_CACHE_DIR,
    join(homedir(), "Library", "Caches", "JetBrains", "Kotlin"),
    join(homedir(), ".cache", "JetBrains", "Kotlin"),
    process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, "JetBrains", "Kotlin"),
  ].filter(Boolean);

  for (const root of roots) {
    const path = join(
      root,
      ".m2.cache",
      "org",
      "jetbrains",
      "skiko",
      "skiko-js-wasm-runtime",
      version,
      `skiko-js-wasm-runtime-${version}.jar`,
    );
    if (existsSync(path)) return { path, version, source: "toolchain cache" };
  }
  return null;
}
