import { join } from "node:path";

// Web linking runs only for the release variant.
export const JS_VARIANT_ARGS = ["-v", "release"];

// Both toolchain web targets use CompiledWebArtifact; wasmJs still enters through .mjs glue.
export function jsOutputDir(repoRoot, module, { test = false, platform = "js" } = {}) {
  const suffix = test ? `${platform}Testrelease` : `${platform}release`;
  return join(repoRoot, "build", "artifacts", "CompiledWebArtifact", `${module}${suffix}`, "kotlin-output");
}

export function jsEntryPoint(repoRoot, module, { test = false, platform = "js" } = {}) {
  const file = test ? `${module}_test.mjs` : `${module}.mjs`;
  return join(jsOutputDir(repoRoot, module, { test, platform }), file);
}
