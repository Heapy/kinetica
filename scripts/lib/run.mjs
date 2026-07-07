import { spawnSync } from "node:child_process";

export function run(cmd, args, options = {}) {
  const {
    cwd = process.cwd(),
    env,
    printCommand = true,
    stdio = "inherit",
  } = options;
  if (printCommand) {
    console.log(`$ ${cmd} ${args.join(" ")}`);
  }
  const spawnOptions = { cwd, stdio };
  if (env !== undefined) {
    spawnOptions.env = env;
  }
  const result = spawnSync(cmd, args, spawnOptions);
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
  return result;
}
