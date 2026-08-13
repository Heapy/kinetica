import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { run } from "./lib/run.mjs";
import { JS_VARIANT_ARGS, jsEntryPoint } from "./lib/js-output.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const kotlin = process.platform === "win32" ? "kotlin.bat" : "./kotlin";

run(kotlin, ["publish", "mavenLocal", "-m", "kinetica-compiler"], { cwd: repoRoot });
run(kotlin, ["build", ...JS_VARIANT_ARGS, "-m", "annotated-js"], { cwd: repoRoot });
run("node", [jsEntryPoint(repoRoot, "annotated-js")], { cwd: repoRoot });

console.log("JS sample verification passed");
