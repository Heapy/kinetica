# Kinetica SSR + islands, built with Gradle

A standalone example of the architecture search engines actually reward: **one HTML response for
everybody, rendered on the server, with the interactive parts hydrated as islands**. No
user-agent sniffing, no bot-only rendering path.

It is also a consumer test of the release: nothing here is built from this repository's sources —
the `io.heapy.kinetica:*:0.4.0` artifacts come from Maven Central, and the build system is plain
Gradle 9.7.0, not the Kotlin Toolchain the rest of the repo uses.

## Run

```sh
./gradlew jvmRun          # builds the JS island, packs it into the server, starts Ktor on :8080
./gradlew jvmTest         # the SEO contract tests
```

Then look at the page the way a crawler does — raw bytes, no JavaScript:

```sh
curl -s http://localhost:8080/articles/islands-not-user-agents
```

The article text, the `<title>`, the meta description, the canonical link, Open Graph tags,
JSON-LD and even the feedback widget's markup are all in that response.

## What it demonstrates

| Concern | Where |
|---|---|
| Components shared by server and browser | `src/commonMain/kotlin/seo/site/` |
| `Node` tree → HTML, document head, JSON-LD | `src/jvmMain/kotlin/seo/server/Document.kt` |
| Ktor routing, 404 status, robots.txt, sitemap.xml, ETag/304, gzip | `src/jvmMain/kotlin/seo/server/Main.kt` |
| Island hydration in the browser | `src/jsMain/kotlin/seo/client/Client.kt` |
| Failing the build when SEO regresses | `src/jvmTest/kotlin/seo/server/SeoContractTest.kt` |

**How an island works here.** `ArticlePage` renders the feedback component inside a container
marked `data-kinetica-island` with its props serialised into the markup. The server evaluates that
component like any other, so the widget's text is in the initial HTML (`event:` props are dropped
by the HTML serialiser — handlers are meaningless server-side). In the browser, `Client.kt` finds
each container and mounts a live Kinetica app into it. This is a re-mount, not DOM adoption: the
client replaces the container's contents with its own render of the same component. For indexing
the distinction does not matter — the content is in the first response either way.

## Gradle wiring worth copying

Kinetica is compiler-plugin-only: without its K2 plugin on the compilation, `state`/`event` calls
throw `MissingKineticaPluginException` at runtime and the `@UiComponent` authoring rules stop
being enforced at compile time. From 0.4.0 that wiring is one plugin id:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.10"
    id("io.heapy.kinetica") version "0.4.0"
}
```

It applies the compiler plugin to every compilation of every target and adds `kinetica-runtime` to
`commonMain` and `kinetica-browser` to `jsMain` at its own version — which is why neither appears
in this build's dependency blocks. `kinetica { addRuntimeDependencies = false }` hands those back
to you, and the same block carries the compiler options (`moduleId`, `serverSourceSet`,
`clientSourceSet`, `sourcePipeline`, `transforms`, `checks`).

The plugin resolves through Maven Central, not the Gradle Plugin Portal, so `settings.gradle.kts`
lists `mavenCentral()` in `pluginManagement.repositories` — a fresh project has only the portal
there and would fail with `UnknownPluginException`.

Other notes on the build:

- Kotlin **2.4.10** matches the version Kinetica 0.4.0 was published with; klib metadata is not
  forward compatible, so do not bump one without the other. The plugin warns when they diverge.
- The Kotlin compile daemon needs JDK 17 or newer: the compiler plugin is loaded into it.
- `kotlinx-serialization-json` is declared explicitly because this example's own code builds
  island props and JSON-LD with it. Code that only uses Kinetica does not need the declaration —
  0.4.0 puts serialization and coroutines on the compile classpath.
- Repositories live in `build.gradle.kts`, not `settings.gradle.kts` — the Kotlin/JS plugin adds
  its own Node.js repository to the project, which a settings-only setup rejects or shadows.
- The configuration cache is off: Kotlin/JS compile tasks in KGP 2.4.10 are not yet compatible.
- `jvmProcessResources` pulls in `jsBrowserDistribution`, so one `./gradlew jvmRun` builds the
  client bundle and serves it from `/static/client.js` (~360 KiB minified, `defer`-loaded — it is
  never on the critical path for content).

## What "good SEO" means in this example

- Content in the first response, identical for every client.
- One `<h1>`, semantic `header`/`nav`/`main`/`article`/`footer`.
- Unique `<title>` + meta description; `rel=canonical`; Open Graph and Twitter card tags.
- JSON-LD: `BlogPosting` per article, `ItemList` on the index.
- Real status codes — missing pages answer **404** and carry `noindex`.
- `robots.txt` pointing at `sitemap.xml`; the sitemap lists canonical URLs with `lastmod`.
- Strong `ETag` + `must-revalidate`, so a re-crawl costs a `304`; gzip for everything else.
