// End-to-end verification of the io.heapy.kinetica Gradle plugin: publishes the current sources
// to the local Maven repository, writes the plugin marker, and builds scripts/fixtures/
// gradle-plugin-consumer — a project whose only Kinetica wiring is `id("io.heapy.kinetica")`.
//
//   node scripts/verify-gradle-plugin.mjs             full run
//   node scripts/verify-gradle-plugin.mjs --no-publish   reuse what is already in ~/.m2
//
// Needs a JDK 17+ on JAVA_HOME for the Gradle wrapper (CI gets one from setup-kinetica; the
// `./kotlin` CLI provisions its own and does not export it).
//
// Three things are proven, in order: the plugin resolves through its marker and compiles a
// multiplatform project; the compiler plugin actually ran (state/event work at runtime, which
// is what MissingKineticaPluginException would otherwise report); and the FIR checkers still
// reject invalid code — the silent failure mode of hand-rolled -Xplugin wiring.
import { spawnSync } from "node:child_process";
import { copyFileSync, existsSync, readFileSync, rmSync } from "node:fs";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repoRoot = dirname(dirname(fileURLToPath(import.meta.url)));
// Two project shapes, because the plugin takes a different path through each: multiplatform has a
// targets container and a commonMain source set, single-target JVM has neither.
const fixture = join(repoRoot, "scripts", "fixtures", "gradle-plugin-consumer");
const jvmFixture = join(repoRoot, "scripts", "fixtures", "gradle-plugin-consumer-jvm");
// The example's wrapper is the only Gradle distribution in the repository; the fixtures borrow it.
const gradlew = join(repoRoot, "examples", "gradle-ssr", "gradlew");
const negativeSource = join(fixture, "negative", "SlotOutsideComponent.kt");
const negativeTarget = join(fixture, "src", "commonMain", "kotlin", "fixture", "SlotOutsideComponent.kt");
let negativeCopied = false;

const publish = !process.argv.includes("--no-publish");

function version() {
  const template = readFileSync(join(repoRoot, "publish.module-template.yaml"), "utf8");
  const match = template.match(/^\s*version:\s*(\S+)\s*$/m);
  if (!match) throw new Error("no `version:` line in publish.module-template.yaml");
  return match[1];
}

// What the fixture resolves, computed rather than listed: a hand-written list silently misses a
// transitive module (kinetica-browser needs kinetica-render-core) and a full release publish
// would drag in modules this fixture never touches.
function moduleClosure(roots) {
  const seen = new Set();
  const queue = [...roots];
  while (queue.length > 0) {
    const name = queue.shift();
    if (seen.has(name)) continue;
    seen.add(name);
    const manifest = readFileSync(join(repoRoot, name, "module.yaml"), "utf8");
    for (const match of manifest.matchAll(/^\s*-\s*(\.\.[^:\s]+)/gm)) {
      const dependency = basename(match[1]);
      if (existsSync(join(repoRoot, dependency, "module.yaml"))) queue.push(dependency);
    }
  }
  return [...seen];
}

function run(command, args, options = {}) {
  console.log(`\n> ${command} ${args.join(" ")}`);
  const result = spawnSync(command, args, {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: options.capture ? "pipe" : "inherit",
    ...options,
  });
  if (result.error) throw result.error;
  if (options.capture) process.stdout.write(result.stdout + result.stderr);
  return { status: result.status, output: options.capture ? result.stdout + result.stderr : "" };
}

function check(condition, message) {
  if (!condition) {
    console.error(`FAIL: ${message}`);
    process.exitCode = 1;
    throw new Error(message);
  }
  console.log(`ok: ${message}`);
}

const kineticaVersion = version();
console.log(`verifying the Gradle plugin at ${kineticaVersion}`);

try {
  if (publish) {
    const modules = moduleClosure([
      "kinetica-compiler",
      "kinetica-gradle-plugin",
      "kinetica-runtime",
      "kinetica-browser",
      "kinetica-test",
    ]).join(",");
    const published = run("./kotlin", ["publish", "mavenLocal", "-m", modules]);
    check(published.status === 0, `published ${modules.split(",").length} modules to mavenLocal`);
    const marker = run(join(repoRoot, "scripts", "gradle-plugin-marker.sh"), []);
    check(marker.status === 0, "wrote the io.heapy.kinetica.gradle.plugin marker");
  }

  const gradleArgs = [
    "-p", fixture,
    `-PkineticaVersion=${kineticaVersion}`,
    "--info",
  ];

  // --rerun belongs to the task before it: an up-to-date jvmTest or compileKotlinJs would
  // assert nothing about the compiler plugin.
  const positive = run(
    gradlew,
    [...gradleArgs, "jvmTest", "--rerun", "compileKotlinJs", "--rerun"],
    { capture: true },
  );
  check(positive.status === 0, "the multiplatform fixture builds and its jvmTest passes with no manual wiring");
  check(
    /sourcePipeline=psi not passed to .*(js|main of target js)/i.test(positive.output),
    "sourcePipeline=psi was withheld from the non-JVM compilations",
  );

  // The configuration cache runs here rather than in the multiplatform fixture, whose KGP 2.4.10
  // JS tasks are not cache-safe: this is the only shape that can catch the plugin capturing
  // something unserializable (a Project, a Logger) in the options provider.
  const jvmArgs = [
    "-p", jvmFixture,
    `-PkineticaVersion=${kineticaVersion}`,
    "--info",
    "--configuration-cache",
    "test", "--rerun",
  ];
  const jvmOnly = run(gradlew, jvmArgs, { capture: true });
  check(jvmOnly.status === 0, "the single-target JVM fixture builds and its test passes");
  check(
    !/sourcePipeline=psi has no effect here/.test(jvmOnly.output),
    "a single-target JVM project is not told its psi pipeline is useless",
  );
  check(
    /Configuration cache entry (stored|reused)/.test(jvmOnly.output),
    "the plugin's configuration is storable in the configuration cache",
  );

  const jvmCached = run(gradlew, jvmArgs, { capture: true });
  check(
    jvmCached.status === 0 && /Configuration cache entry reused/.test(jvmCached.output),
    "the second run reuses that entry instead of discarding it",
  );

  copyFileSync(negativeSource, negativeTarget);
  negativeCopied = true;
  // Once per backend: a plugin that silently stops running on one of them still compiles valid
  // code, so only the rejected file proves the checkers are live there.
  for (const task of ["compileKotlinJvm", "compileKotlinJs"]) {
    const negative = run(gradlew, [...gradleArgs, task], { capture: true });
    check(negative.status !== 0, `the authoring-rule violation fails ${task}`);
    check(
      negative.output.includes("can only be called inside a @UiComponent function"),
      `${task} failed with the Kinetica FIR diagnostic, not an unrelated error`,
    );
  }
} finally {
  // Only ever removes the file this run put there.
  if (negativeCopied) rmSync(negativeTarget, { force: true });
}

console.log("\ngradle plugin verification passed");
