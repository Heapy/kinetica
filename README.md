# Kinetica

A Kotlin UI framework built around one idea: **the UI is a value**. Every render produces an
immutable, serializable `Node` tree — which yields headless testing, HTML server rendering,
streaming server components, a journaled runtime with replay, and a retained DOM renderer that
patches by diffing values. One UI loop, synchronous atomic commits, explicit effects, linear
causality.

```kotlin
fun ComponentScope.Counter() {
    var count by state(key = "count") { 0 }
    column {
        text("Clicked $count times")
        button(onClick = event { count += 1 }) { text("Increment") }
    }
}

fun main() = mountKineticaApp("#app") { Counter() }
```

## Documentation

The docs are written in Kinetica itself (markdown → `Node` trees → server-rendered by the
framework) and ship as a Docker image:

```sh
docs/docker-build.sh && docker run --rm -p 8080:8080 kinetica-docs
# or locally:
cd bench && npm install && cd ..
node scripts/bundle-docs.mjs
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
| `kinetica-compiler` | K2 compiler plugin (`@UiComponent`, slot ids, server/client boundary) |
| `samples/` | browser apps, server-components demo, annotated (compiler-plugin) sample |
| `docs/` | the documentation site + Docker packaging |
| `bench/` | js-framework-benchmark harness vs React/Preact/Vue/Svelte/vanilla — 13 keyed-table ops, GC accounting, scaling curves, sustained updates, deep-tree suite, memory/leak probes ([guide](bench/README.md)) |
| `bench-jvm/` | JVM microbenchmarks: reactive core, render pipeline, markdown SSR (`./kotlin run -m bench-jvm`) |
| `deep-research-report.md` | the design specification |
| `perf-rewrite-design.md` | renderer performance analysis & rewrite plan (P0–P3 benchmark packaging done — latest 13-op geomean 1.35×; create-10k ahead of React, 10k partial ops are the next target) |

## Building

Requires the Kotlin Toolchain CLI (`sdk install kotlintoolchain`); everything else is
provisioned by the `./kotlin` wrapper.

```sh
./kotlin test -m kinetica-runtime --platform jvm    # module tests
./kotlin build -m browser-todo                      # a JS sample
node scripts/verify-browser.mjs                     # Playwright verification (server on :4173)
```

CI runs JVM tests for all modules, builds the JS targets, and drives the browser + docs
verification suites (`.github/workflows/ci.yml`). Pushes to `main` publish the docs image to
`ghcr.io/heapy/kinetica-docs` (`.github/workflows/docs-image.yml`).
