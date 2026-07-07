import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { run } from "./lib/run.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const kotlin = process.platform === "win32" ? "kotlin.bat" : "./kotlin";

run(kotlin, ["publish", "mavenLocal", "-m", "kinetica-compiler"], { cwd: repoRoot });
run(kotlin, ["build", "-m", "annotated-js"], { cwd: repoRoot });
run("node", [join(repoRoot, "build", "tasks", "_annotated-js_linkJs", "annotated-js.mjs")], { cwd: repoRoot });

console.log("JS sample verification passed");
