import { join } from "node:path";

// Since 0.12 the toolchain links JS into build/artifacts/CompiledWebArtifact instead of
// build/tasks/_<module>_linkJs, one directory per (module, platform, variant). Linking only
// runs for the release variant, so every JS build in this repo passes `-v release`.
export const JS_VARIANT_ARGS = ["-v", "release"];

// `platform` is the toolchain's own name for the web target: "js" or "wasmJs". Both land in
// CompiledWebArtifact, and a wasmJs module's entry point is still a .mjs (the glue that
// instantiates the sibling .wasm).
export function jsOutputDir(repoRoot, module, { test = false, platform = "js" } = {}) {
  const suffix = test ? `${platform}Testrelease` : `${platform}release`;
  return join(repoRoot, "build", "artifacts", "CompiledWebArtifact", `${module}${suffix}`, "kotlin-output");
}

export function jsEntryPoint(repoRoot, module, { test = false, platform = "js" } = {}) {
  const file = test ? `${module}_test.mjs` : `${module}.mjs`;
  return join(jsOutputDir(repoRoot, module, { test, platform }), file);
}
