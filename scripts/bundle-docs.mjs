// Build and minify the documentation site's browser-side Kotlin/JS modules.
//
// This mirrors bench/build-kinetica.mjs: Kotlin Toolchain still emits a linked
// ES-module graph, then esbuild turns each docs entrypoint into one production
// browser module.

import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { brotliCompressSync, constants as zlibConstants, gzipSync } from "node:zlib";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const requireFromBench = createRequire(join(repoRoot, "bench", "package.json"));

let build;
try {
  ({ build } = requireFromBench("esbuild"));
} catch (error) {
  console.error("Missing esbuild. Run `npm install` in ./bench before bundling docs.");
  throw error;
}

const targets = [
  {
    module: "docs-client",
    linkDir: "_docs-client_linkJs",
    bundleDir: "_docs-client_bundle",
    entry: "docs-client.mjs",
  },
  {
    module: "server-components-client",
    linkDir: "_server-components-client_linkJs",
    bundleDir: "_server-components-client_bundle",
    entry: "server-components-client.mjs",
  },
];

function run(cmd, args, cwd) {
  console.log(`$ ${cmd} ${args.join(" ")}`);
  const result = spawnSync(cmd, args, { cwd, stdio: "inherit" });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

function measureFile(file) {
  const content = readFileSync(file);
  const brotliFile = `${file}.br`;
  return {
    files: 1,
    rawBytes: content.length,
    gzipBytes: gzipSync(content).length,
    brotliBytes: existsSync(brotliFile) ? readFileSync(brotliFile).length : brotliBytes(content).length,
  };
}

function measureMjsGraph(dir) {
  let files = 0;
  let rawBytes = 0;
  let gzipBytes = 0;

  const walk = (current) => {
    for (const name of readdirSync(current)) {
      const path = join(current, name);
      if (statSync(path).isDirectory()) {
        walk(path);
      } else if (name.endsWith(".mjs")) {
        const content = readFileSync(path);
        files++;
        rawBytes += content.length;
        gzipBytes += gzipSync(content).length;
      }
    }
  };

  walk(dir);
  return { files, rawBytes, gzipBytes };
}

function kb(bytes) {
  return (bytes / 1024).toFixed(1);
}

function pct(before, after) {
  return (((after - before) / before) * 100).toFixed(1);
}

function printComparison(label, before, after) {
  console.log(
    `${label}: ${kb(before.rawBytes)}KB raw / ${kb(before.gzipBytes)}KB gzip ` +
      `(${before.files} files) -> ${kb(after.rawBytes)}KB raw / ${kb(after.gzipBytes)}KB gzip ` +
      `/ ${kb(after.brotliBytes)}KB br (${after.files} file, ${pct(before.gzipBytes, after.gzipBytes)}% gzip)`,
  );
}

function brotliBytes(content) {
  return brotliCompressSync(content, {
    params: {
      [zlibConstants.BROTLI_PARAM_QUALITY]: 11,
    },
  });
}

function writeBrotli(file) {
  writeFileSync(`${file}.br`, brotliBytes(readFileSync(file)));
}

const kotlin = process.platform === "win32" ? "kotlin.bat" : "./kotlin";
for (const target of targets) {
  run(kotlin, ["build", "-m", target.module], repoRoot);
}

for (const target of targets) {
  const linkDir = join(repoRoot, "build", "tasks", target.linkDir);
  const linkEntry = join(linkDir, target.entry);
  const bundleDir = join(repoRoot, "build", "tasks", target.bundleDir);
  const bundleFile = join(bundleDir, target.entry);

  if (!existsSync(linkEntry)) {
    throw new Error(`Kotlin JS link output not found: ${linkEntry}`);
  }

  const before = measureMjsGraph(linkDir);
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
  writeBrotli(bundleFile);

  printComparison(target.module, before, measureFile(bundleFile));
}

const docsAssetsDir = join(repoRoot, "build", "tasks", "_docs-site_assets");
const cssSource = join(repoRoot, "docs", "docs-site", "resources", "site.css");
const cssFile = join(docsAssetsDir, "site.css");
mkdirSync(docsAssetsDir, { recursive: true });
copyFileSync(cssSource, cssFile);
writeBrotli(cssFile);

const css = measureFile(cssFile);
console.log(`site.css: ${kb(css.rawBytes)}KB raw / ${kb(css.gzipBytes)}KB gzip / ${kb(css.brotliBytes)}KB br`);
