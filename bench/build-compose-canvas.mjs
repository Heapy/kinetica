// Builds the Compose Multiplatform canvas app (Kotlin/Wasm + skiko) for the browser benchmark.
//
// Unlike the JS apps there is no bundling step: the toolchain's wasm glue fetches its .wasm by
// URL relative to its own module, so the linked output is served as-is. What the toolchain does
// NOT do is lay out the skiko runtime — the generated `<module>.import-object.mjs` opens with
// `import * as … from './skiko.mjs'`, but only the module's own four files are emitted (in a
// Gradle build the Compose plugin unpacks the runtime). So this script drops `skiko.mjs` and
// `skiko.wasm` next to the linked output, taking them from the toolchain's own dependency cache
// when it is there and falling back to Maven Central.

import { createWriteStream, existsSync, mkdirSync, readFileSync, readdirSync, statSync } from "node:fs";
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

// Pinned to what Compose 1.12.0-rc01 declares (org.jetbrains.compose.ui:ui -> skiko 0.150.1).
// Only used when the toolchain cache cannot answer; a compose bump that moves skiko will show
// up as a cache hit on a different version, which is printed on every build.
const FALLBACK_SKIKO_VERSION = "0.150.1";
const SKIKO_FILES = ["skiko.mjs", "skiko.wasm"];

const outputDir = jsOutputDir(repoRoot, MODULE, { platform: "wasmJs" });

const kotlin = process.platform === "win32" ? "kotlin.bat" : "./kotlin";
run(kotlin, ["build", ...JS_VARIANT_ARGS, "-m", MODULE], { cwd: repoRoot });

const entry = join(outputDir, `${MODULE}.mjs`);
if (!existsSync(entry)) {
  throw new Error(`Kotlin/Wasm link output not found: ${entry}`);
}

const jar = await resolveSkikoRuntimeJar();
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

// The toolchain resolves skiko-js-wasm-runtime as a normal dependency, so in a repository that
// has just built the module the jar is already on disk — no download, and guaranteed to be the
// version this build actually linked against.
async function resolveSkikoRuntimeJar() {
  const cached = findInToolchainCache();
  if (cached) return cached;

  const version = FALLBACK_SKIKO_VERSION;
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

function findInToolchainCache() {
  const roots = [
    process.env.KOTLIN_SHARED_CACHE_DIR,
    join(homedir(), "Library", "Caches", "JetBrains", "Kotlin"),
    join(homedir(), ".cache", "JetBrains", "Kotlin"),
    process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, "JetBrains", "Kotlin"),
  ].filter(Boolean);

  const found = [];
  for (const root of roots) {
    const base = join(root, ".m2.cache", "org", "jetbrains", "skiko", "skiko-js-wasm-runtime");
    if (!existsSync(base)) continue;
    for (const version of readdirSync(base)) {
      const path = join(base, version, `skiko-js-wasm-runtime-${version}.jar`);
      if (existsSync(path)) found.push({ path, version, source: "toolchain cache" });
    }
  }
  if (found.length === 0) return null;
  // Several versions can pile up in a cache shared by other projects; the newest one by mtime
  // is the one this build resolved.
  return found.sort((a, b) => statSync(b.path).mtimeMs - statSync(a.path).mtimeMs)[0];
}
