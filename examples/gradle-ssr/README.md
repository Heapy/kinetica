# Kinetica SSR + islands, built with Gradle

A standalone example of the architecture search engines actually reward: **one HTML response for
everybody, rendered on the server, with the interactive parts hydrated as islands**. No
user-agent sniffing, no bot-only rendering path.

It is also a consumer test of the release: nothing here is built from this repository's sources —
the `io.heapy.kinetica:*:0.3.0` artifacts come from Maven Central, and the build system is plain
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

**This section is on its way out.** From 0.4.0 Kinetica publishes the `io.heapy.kinetica` Gradle
plugin, which does all of the wiring below plus the runtime dependencies; this example keeps the
manual form until that release is on Central, since it consumes only published artifacts.

Kinetica 0.3.0 is compiler-plugin-only and publishes no Gradle subplugin, so the plugin jar is
resolved through its own configuration and passed to every Kotlin compilation:

```kotlin
val kineticaCompiler = configurations.resolvable("kineticaCompiler") { isTransitive = false }
dependencies { add(kineticaCompiler.name, libs.kinetica.compiler) }

val kineticaPluginArgument = kineticaCompiler.flatMap { configuration ->
    configuration.elements.map { jars -> "-Xplugin=${jars.single().asFile.absolutePath}" }
}
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions.freeCompilerArgs.add(kineticaPluginArgument)
}
```

If the wiring is ever lost, the failure is loud rather than silent: the plugin's FIR checkers stop
running, and `state`/`event` calls throw `MissingKineticaPluginException` — but note the plugin
also *is* the thing that enforces `@UiComponent` call rules at compile time, so treat a build that
suddenly stops reporting those errors as suspicious.

Other notes on the build:

- Kotlin **2.4.10** matches the version Kinetica 0.3.0 was published with; klib metadata is not
  forward compatible, so do not bump one without the other.
- `kotlinx-serialization-json` is declared explicitly: Kinetica exposes it as a runtime-scoped
  transitive dependency, which is not on the compile classpath.
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
