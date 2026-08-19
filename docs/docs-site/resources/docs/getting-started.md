# Getting started

<!-- code: project.yaml, kotlin (wrapper CLI) -->

Kinetica builds with the JetBrains Kotlin Toolchain (the Amper successor): declarative YAML
modules, a `./kotlin` wrapper CLI, no Gradle.

## Project layout

<!-- code: samples/browser-counter/module.yaml, common.module-template.yaml (compiler plugin wiring), kinetica-runtime/src/HostDsl.kt -->

A project is a `project.yaml` listing modules; a module is a directory with a `module.yaml`.
A minimal browser app (the `apply` template wires the **mandatory**
[compiler plugin](/docs/compiler-plugin) — without it every `state`/`event` call throws
`MissingKineticaPluginException` at runtime):

```yaml
# module.yaml
product: js/app

apply:
  - ../../common.module-template.yaml   # registers io.heapy.kinetica:kinetica-compiler

dependencies:
  - ../../kinetica-browser
  - ../../kinetica-runtime
```

```kotlin
// src/main.kt
package app

import io.heapy.kinetica.*
import io.heapy.kinetica.browser.mountKineticaApp

@UiComponent
fun ComponentScope.App() {
    var name by state { "world" }
    column {
        textInput(value = name, onInput = event<String> { name = it })
        text("Hello, $name!")
    }
}

fun main() {
    mountKineticaApp("#app") { App() }
}
```

```html
<!-- web/index.html -->
<div id="app"></div>
<script type="module" src="../../../build/artifacts/CompiledWebArtifact/my-appjsrelease/kotlin-output/my-app.mjs"></script>
```

## Build and run

<!-- code: kotlin (wrapper CLI), scripts/verify-browser.mjs -->

```
./kotlin build -v release -m my-app   # links build/artifacts/CompiledWebArtifact/my-appjsrelease/kotlin-output/my-app.mjs
./kotlin test -m my-module            # run a module's tests
./kotlin show modules                 # inspect the project model
```

`js/app` output is an ES-module graph; serve the repository root with any static file server and
open the page. JVM apps (`product: jvm/app`) run with `./kotlin run -m my-server` and package to
an executable jar with `./kotlin package`.

## From Gradle

<!-- code: kinetica-gradle-plugin/src/KineticaGradlePlugin.kt, scripts/fixtures/gradle-plugin-consumer/build.gradle.kts, examples/gradle-ssr -->

Kinetica is built with the toolchain but consumed from any Kotlin build. For Gradle, the
`io.heapy.kinetica` plugin does the wiring:

```kotlin
// settings.gradle.kts — a fresh project resolves plugins from the portal only
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// build.gradle.kts
plugins {
    kotlin("multiplatform") version "2.4.10"
    id("io.heapy.kinetica") version "0.4.0"
}

repositories {
    mavenCentral()
}

kotlin {
    jvm()
    js { browser() }
}
```

That is the whole setup. The plugin applies the mandatory
[compiler plugin](/docs/compiler-plugin) to every compilation of every target and adds
`kinetica-runtime` to `commonMain` — plus `kinetica-browser` to a JS target's main source set — at
its own version. `kinetica { addRuntimeDependencies = false }` hands the dependencies back to you;
everything else the plugin exposes is on the compiler-plugin page.

Kotlin **2.4.10** is the version Kinetica is published with. klib metadata is not forward
compatible, so a mismatch fails the compilation — the plugin warns about it before that happens.
The plugin itself needs Gradle 8.11+ and a JDK 17+ Kotlin daemon; the compiled application still
targets whatever your toolchain says.

## Components are plain functions

<!-- code: kinetica-runtime/src/Annotations.kt (UiComponent), kinetica-runtime/src/ComponentScope.kt (state, each) -->

There is no component class and no special file type. A component is a function with
`ComponentScope` as receiver that *emits* nodes:

```kotlin
@UiComponent
fun ComponentScope.Badge(label: String) {
    host("span", props = mapOf("class" to "badge")) {
        text(label)
    }
}
```

Composition is a function call: `Badge("New")`. State lives in *slots* whose identity the
mandatory [compiler plugin](/docs/compiler-plugin) assigns at compile time — every component
is a `@UiComponent fun ComponentScope.X(...)`, and every `state`/`derived`/effect call site
gets its own slot automatically. Lists still key their rows explicitly:
`each(items, key = { … })`.

## Mounting choices

<!-- code: kinetica-browser/src@js/BrowserKineticaApp.kt (mountKineticaApp), kinetica-runtime/src/KineticaRuntime.kt (render), kinetica-test/src/KineticaTest.kt (KineticaTest.render) -->

| Entry point | Use for |
|-------------|---------|
| `mountKineticaApp(selector) { App() }` | Browser apps ([renderer details](/docs/browser-renderer)) |
| `KineticaRuntime().render { App() }.tree.toSafeHtml()` | Server-side HTML ([server components](/docs/server-components)) |
| `KineticaTest.render { App() }` | Headless tests ([testing](/docs/testing)) |

`mountKineticaApp` defaults to `KineticaRuntime(debug = true)` — journaling, duplicate-key
checks and debug DOM attributes. Pass `KineticaRuntime(debug = false)` for production mounts.
