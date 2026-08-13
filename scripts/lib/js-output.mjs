import { join } from "node:path";

// Since 0.12 the toolchain links JS into build/artifacts/CompiledWebArtifact instead of
// build/tasks/_<module>_linkJs, one directory per (module, platform, variant). Linking only
// runs for the release variant, so every JS build in this repo passes `-v release`.
export const JS_VARIANT_ARGS = ["-v", "release"];

export function jsOutputDir(repoRoot, module, { test = false } = {}) {
  const suffix = test ? "jsTestrelease" : "jsrelease";
  return join(repoRoot, "build", "artifacts", "CompiledWebArtifact", `${module}${suffix}`, "kotlin-output");
}

export function jsEntryPoint(repoRoot, module, { test = false } = {}) {
  const file = test ? `${module}_test.mjs` : `${module}.mjs`;
  return join(jsOutputDir(repoRoot, module, { test }), file);
}
