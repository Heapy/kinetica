# Kinetica compiler plugin development

This guide describes how `kinetica-compiler` is loaded and executed in this repository,
and how to test a change without accidentally exercising an older plugin JAR. The
soundness invariants in [`AGENTS.md`](../AGENTS.md) remain the authoritative rules for
changing the FIR and IR implementations.

## Why the plugin is mandatory

Kinetica's runtime DSL is not complete on its own. The compiler plugin validates authoring
rules and rewrites component code so frame and slot identities are stable across renders.
Without the plugin, operations such as `state` and `event` can reach
`MissingKineticaPluginException`; code that needs a FIR rejection can also compile without
the diagnostic.

The plugin is identified by:

- compiler plugin ID: `io.heapy.kinetica.compiler`
- Maven coordinate: `io.heapy.kinetica:kinetica-compiler:<version>`
- current repository version: `0.4.0`, defined for publication in
  [`publish.module-template.yaml`](../publish.module-template.yaml)

[`common.module-template.yaml`](../common.module-template.yaml) attaches that Maven artifact
to Kinetica Toolchain modules. The `io.heapy.kinetica` Gradle plugin resolves the same
artifact and attaches it to every applicable Kotlin compilation. `kinetica-compiler` and
`kinetica-gradle-plugin` deliberately do not apply the common template: build tooling must
not compile itself as Kinetica UI code.

The version is necessarily duplicated in `common.module-template.yaml`,
`KineticaCompilerContract.pluginVersion`, and `KineticaCoordinates.version`. Contract tests
pin those copies to the publication template; change all of them together during a real
version bump.

## How the plugin runs

The high-level compilation path is:

```text
service loading -> CLI options -> optional PSI source processing -> FIR checks -> IR lowering -> runtime
```

1. The service files under `kinetica-compiler/resources/META-INF/services/` load
   `KineticaCommandLineProcessor` and `KineticaCompilerRegistrar` from the plugin JAR.
2. `KineticaCommandLineProcessor` reads `moduleId`, source-set names, `transforms`,
   `sourcePipeline`, and `checks` into the Kotlin compiler configuration. The default
   pipeline is `lightTree`; `sourcePipeline=psi` is JVM-only. PSI rewriting requires a
   full source set on each compilation: the Gradle plugin disables Kotlin incremental
   compilation for these tasks. Kotlin Toolchain consumers must set
   `settings.kotlin.compileIncrementally: false`, as the `annotated` sample does.
   Normal task up-to-date checks still apply; the default `lightTree` pipeline remains
   incremental.
3. With the PSI pipeline, `KineticaProcessSourcesExtension` extracts the Kinetica source
   model, reports source diagnostics, rewrites scope-free component sources, and adds
   generated registrations before normal compilation.
4. The registrar creates one `SingleRunOracle` per compilation and shares it between FIR
   and IR. FIR reports soundness violations, records contract-based single-run decisions,
   and applies the configurable component-style diagnostic. Soundness checks remain errors
   even when `checks=off`.
5. With transforms enabled, `KineticaIrGenerationExtension` performs backend-specific
   atomic lowering, component skipping, template and static-node optimization, and frame,
   region, slot, and entry-content transformation. The frame pass consumes the FIR
   oracle, so the two phases must classify every reachable construct identically.

The plugin is built for Kotlin `2.4.10` and Java 17 bytecode. Java 17 is intentional: the
JAR is loaded into the consumer's Kotlin compile daemon, which may run on JDK 17 even when
the repository toolchain itself uses a newer JDK.

## Two different plugin JAR paths

Compiler tests and consumer builds do not load the plugin from the same place.

| Test or build | Plugin JAR being exercised | Publish first? |
|---|---|---|
| `kinetica-compiler` unit and compile tests | Current compiler module output; `KineticaCompilationHarness` packages classes and service resources into a temporary JAR and passes it to the real `K2JVMCompiler` with `-Xplugin` | No, for the normal compiler-only red/green loop |
| Toolchain modules applying `common.module-template.yaml` | `~/.m2/repository/io/heapy/kinetica/kinetica-compiler/<version>/...jar`, installed by the `kinetica-compiler-local` build plugin during the same build | No |
| Gradle consumer fixtures and external Gradle builds | The Maven artifact selected by `KineticaGradlePlugin` | Yes, together with the other fixture artifacts |

This distinction is important. A passing `kinetica-compiler` compile test proves that the
freshly built code works. It does not prove that `kinetica-runtime`, `kinetica-test`,
`kinetica-persist`, a sample, or a Gradle consumer used that code.

## The `kinetica-compiler-local` build plugin

The Kotlin Toolchain cannot yet consume a compiler plugin from a module in the same project
([KTC-5839](https://youtrack.jetbrains.com/issue/KTC-5839)), so the coordinate must exist in
`~/.m2` before a consumer compiles. `plugins/kinetica-compiler-local` closes that gap inside the
build: it is registered in [`project.yaml`](../project.yaml) and enabled for every module that
applies [`common.module-template.yaml`](../common.module-template.yaml).

Its single task takes `kinetica-compiler`'s JAR as a build input, installs it over the `mavenLocal`
coordinate, and writes a comment-only Kotlin file carrying the JAR's SHA-256 into the consumer's
generated sources. That gives two properties:

- the consumer's compilation task depends on the install task, so a Kotlin Toolchain build always
  compiles against the compiler sources of the current checkout;
- the stamp changes whenever the JAR changes, which invalidates the consumer's compilation instead
  of leaving a false green behind.

The task installs only the JAR. `pom`, Gradle module metadata, and the sources JAR come from a real
`./kotlin publish mavenLocal -m kinetica-compiler`, and the task fails with an explicit message when
that POM is missing. The plugin module deliberately does not apply `common.module-template.yaml`:
that template is what attaches the artifact this plugin produces.

## Compiler-only red/green loop

For a change confined to the compiler implementation or its tests, run the compiler suite
directly:

```sh
./kotlin test --platform jvm -m kinetica-compiler
```

Use `--include-test <fully-qualified-test-name>` for a focused red/green iteration. The
compile-test harness constructs a fresh plugin JAR from the current test classpath once per
test JVM, includes both `META-INF/services` registrations, and invokes the production K2 CLI
loading path. Republishing between the red and green runs adds no coverage to this loop.

A completely fresh checkout still needs the repository's normal dependency bootstrap:
`kinetica-runtime` is a compiler-test dependency and is itself a plugin consumer. This is
why CI publishes before constructing the full project graph. Once that dependency is
buildable, the statement above concerns the JAR under test: normal compiler-only red/green
iterations do not need another publication.

## Testing any consumer

A Kotlin Toolchain consumer needs no manual step. `./kotlin build`, `./kotlin test`, and
`./kotlin run` install the current compiler JAR through the build plugin before they compile.

A Gradle consumer resolves the same coordinate from outside the Kotlin Toolchain task graph, and a
fresh checkout has no Kinetica POM in `~/.m2` at all. Both still need an explicit publication:

```sh
./kotlin publish mavenLocal -m kinetica-compiler
```

`publish` rebuilds and packages `kinetica-compiler` when its inputs changed; a separate
`build` command is not required. It then overwrites the current version under `~/.m2`.

Verify that the expected artifact was actually refreshed. This is especially useful after
a long compiler-only session or when several worktrees share the same local Maven repository:

```sh
kinetica_version="$(sed -n 's/^ *version: *//p' publish.module-template.yaml | head -1)"
kinetica_plugin_jar="$HOME/.m2/repository/io/heapy/kinetica/kinetica-compiler/$kinetica_version/kinetica-compiler-$kinetica_version.jar"
ls -l "$kinetica_plugin_jar"
shasum -a 256 "$kinetica_plugin_jar"
```

Check that the timestamp is from the publication just run. When investigating suspected
staleness, compare the size or checksum with the value from before publication as well.

### Invalidate consumer outputs

Republishing `0.4.0` changes the JAR bytes without changing its Maven coordinate, so a consumer
compilation can look up to date while its classes were transformed by the old plugin. Inside the
Kotlin Toolchain the build plugin's stamp file prevents that: every enabled consumer recompiles when
the JAR changes.

Manual invalidation is still required for Gradle consumers and
`scripts/verify-gradle-plugin.mjs`, and for a broad or final verification:

```sh
./kotlin publish mavenLocal -m kinetica-compiler
./kotlin clean
./kotlin test --platform jvm \
  -m kinetica-runtime \
  -m kinetica-test \
  -m kinetica-persist \
  -m kinetica-gradle-plugin
```

`./kotlin clean` removes repository build outputs, not the artifact just published to
`~/.m2`. For a narrow iteration, touching a real Kotlin source file in every consumer being
tested is sufficient and cheaper, but it is easier to miss a dependent or a target. Use a
full clean before claiming repository-wide or cross-backend verification.

The required order for that manual path is:

1. publish the compiler plugin;
2. verify the Maven-local JAR timestamp or checksum;
3. invalidate the relevant consumer outputs;
4. build or test the consumers.

Do not bump the version merely to defeat a local cache. Version changes are release-level
changes; for ordinary development, republish the current coordinate and invalidate outputs.

## Gradle plugin verification

The internal Gradle plugin tests check the duplicated compiler contract:

```sh
./kotlin test --platform jvm -m kinetica-gradle-plugin
```

The end-to-end fixture is stronger:

```sh
node scripts/verify-gradle-plugin.mjs
```

By default the script publishes the required module closure, creates the Gradle plugin
marker, and runs the consumer compilation tasks with `--rerun`. It verifies JVM and JS FIR
diagnostics, runtime transforms, PSI option routing, and Gradle configuration-cache reuse.
Use its `--no-publish` mode only when every required artifact has already been published
from the current checkout.

## When publication is required

The build plugin covers ordinary compiler changes for Kotlin Toolchain modules, so an explicit
publication is required only when:

- the verification includes a Gradle consumer or `scripts/verify-gradle-plugin.mjs`;
- `~/.m2` has no `kinetica-compiler` POM yet, as in a fresh checkout or a new worktree;
- `kinetica-compiler/module.yaml` changed its dependencies, Java target, packaging, or publication
  metadata, because the build plugin refreshes only the JAR and leaves the POM and Gradle module
  metadata from the last real publication.

Publication is never needed between compiler-only test iterations, because those tests use the
direct JAR path described above.

## Minimum verification by change type

- FIR rules or shared frame policy: run the focused FIR test, the full
  `kinetica-compiler` JVM suite, and the `firAndIrAgreeOn*` drift tests; then rebuild at least
  the affected consumer.
- IR framing, hoisting, templates, or skipping: run the relevant compiler compile tests;
  then test the runtime-facing consumers. Exercise every affected backend because the IR
  extension is multiplatform.
- service loading, registrar wiring, CLI options, or packaging: run
  `CompilerPluginWiringTest`, inspect the packaged service files if needed, and run a real
  consumer compilation; publish first when `kinetica-compiler/module.yaml` changed.
- Gradle option or coordinate changes: run both `kinetica-gradle-plugin` tests and
  `node scripts/verify-gradle-plugin.mjs`.
- Kotlin compiler or bytecode-target changes: clean, republish, and run the complete CI
  matrix in [`.github/workflows/ci.yml`](../.github/workflows/ci.yml).

## Diagnosing a suspicious green build

If compiler tests show the new behavior but a consumer does not, assume stale consumption
until disproved:

1. confirm the build log has an `installCompiler@kinetica-compiler-local` line for the consumer,
   and that its SHA-256 matches the JAR now in `~/.m2`;
2. confirm the version in `common.module-template.yaml` matches the published directory;
3. inspect the Maven-local JAR timestamp and checksum;
4. confirm the JAR contains both `META-INF/services` files;
5. clean or otherwise force the consumer compilation;
6. rerun a negative case that only succeeds when the Kinetica FIR checker or IR transform
   is active.

This last negative probe is more reliable than a valid source file: valid code can compile
successfully even when the plugin was never loaded.
