import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const kotlin = process.platform === "win32" ? "kotlin.bat" : "./kotlin";

run(kotlin, ["publish", "mavenLocal", "-m", "kinetica-compiler"]);
run(kotlin, ["build", "-m", "annotated-js"]);
run("node", [join(repoRoot, "build", "tasks", "_annotated-js_linkJs", "annotated-js.mjs")]);

console.log("JS sample verification passed");

function run(cmd, args) {
  console.log(`$ ${cmd} ${args.join(" ")}`);
  const result = spawnSync(cmd, args, { cwd: repoRoot, stdio: "inherit" });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}
