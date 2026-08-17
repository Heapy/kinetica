# Kinetica

A Kotlin UI framework built around one idea: **the UI is a value**. Every render produces an
immutable, serializable `Node` tree — which yields headless testing, HTML server rendering,
streaming server components, a journaled runtime with replay, and a retained DOM renderer that
patches by diffing values. One UI loop, synchronous atomic commits, explicit effects, linear
causality.

```kotlin
@UiComponent
fun ComponentScope.Counter() {
    var count by state { 0 }
    column {
        text("Clicked $count times")
        button(onClick = event { count += 1 }) { text("Increment") }
    }
}

fun main() = mountKineticaApp("#app") { Counter() }
```

Kinetica is **compiler-plugin-only**: the K2 plugin assigns every `state`/`derived`/effect
call site a static slot ordinal in a per-component frame, so state identity is decided at
compile time — no keys to pass, no positional-collision bug class, and the render hot path
reads slots by array index.

## Documentation

The docs are written in Kinetica itself (markdown → `Node` trees → server-rendered by the
framework) and ship as a Docker image:

```sh
docs/docker-build.sh && docker run --rm -p 8080:8080 kinetica-docs
# or locally:
cd bench && npm install && cd ..
node scripts/bundle-docs.mjs
node scripts/build-game-of-life.mjs
PORT=8080 ./kotlin run -m docs-site
```

See [`docs/README.md`](docs/README.md).

## Repository map

| Path | Contents |
|------|----------|
| `kinetica-runtime` | core: cells, node tree, effects, resources, boundaries, journal, server components |
| `kinetica-browser` | retained-mode DOM renderer (keyed LIS diffing, event delegation) |
| `kinetica-router` / `-forms` / `-motion` / `-data` / `-persist` / `-theme` / `-markdown` | first-party batteries |
| `kinetica-test` | headless component test harness |
| `kinetica-compiler` | K2 compiler plugin — mandatory: frame/slot ordinals, skip transform, FIR authoring rules, server/client boundary |
| `samples/` | browser apps, four-way Game of Life comparison, server-components demo, annotated (compiler-plugin) sample |
| `docs/` | the documentation site + Docker packaging |
| `examples/gradle-ssr` | standalone Gradle 9.7 consumer of the released artifacts: SSR + island hydration + the SEO metadata that goes with it |
| `bench/` | Unified benchmark runner (`node bench/run.mjs`) plus js-framework-benchmark harness vs React/Preact/Vue/Svelte/vanilla/Compose HTML — 13 keyed-table ops, GC accounting, scaling curves, sustained updates, deep-tree suite, memory/leak probes ([guide](bench/README.md)) |
| `bench-jvm/` | JVM microbenchmarks: reactive core, render pipeline, markdown SSR (`./kotlin run -m bench-jvm`) |

## Building

No global install needed: the `./kotlin` wrapper provisions the Kotlin Toolchain itself
(and with it the JDK and Kotlin/Native), caching them outside the repo like any other tool.

```sh
./kotlin publish mavenLocal -m kinetica-compiler    # first: every module compiles with the plugin
./kotlin test -m kinetica-runtime --platform jvm    # module tests
./kotlin build -v release -m browser-todo           # a JS sample (JS links only in release)
node scripts/verify-browser.mjs                     # Playwright verification (server on :4173)
```

When working on `kinetica-compiler`: republishing the **same version** to the local Maven
repository (`~/.m2`) does not invalidate consumers' compilation caches — after
`publish mavenLocal`, touch a source file in the module you are rebuilding (or bump the
plugin version).

CI runs JVM tests for all modules, builds the JS targets, and drives the browser + docs
verification suites (`.github/workflows/ci.yml`). Pushes to `main` publish the docs image to
`ghcr.io/heapy/kinetica-docs` (`.github/workflows/docs-image.yml`).

## Releasing

`GPG_KEY_ID=... scripts/release.sh` builds a signed Maven Central bundle at
`build/kinetica-<version>.zip`; `--upload` also POSTs it as a deployment that Central validates
and holds until you release it by hand. Keep the signing key and the Central token in a
git-ignored `./publish.sh` that exports them and calls the script. Coordinates live in
`publish.module-template.yaml`. `kinetica-gtk` is excluded by default — it needs GTK dev headers,
so publish it from Linux with `PUBLISH_MODULES=kinetica-gtk`.
