# Getting started

Kinetica builds with the JetBrains Kotlin Toolchain (the Amper successor): declarative YAML
modules, a `./kotlin` wrapper CLI, no Gradle.

## Project layout

A project is a `project.yaml` listing modules; a module is a directory with a `module.yaml`.
A minimal browser app:

```yaml
# module.yaml
product: js/app

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
<script type="module" src="../../../build/tasks/_my-app_linkJs/my-app.mjs"></script>
```

## Build and run

```
./kotlin build -m my-app        # emits build/tasks/_my-app_linkJs/my-app.mjs
./kotlin test -m my-module      # run a module's tests
./kotlin show modules           # inspect the project model
```

`js/app` output is an ES-module graph; serve the repository root with any static file server and
open the page. JVM apps (`product: jvm/app`) run with `./kotlin run -m my-server` and package to
an executable jar with `./kotlin package`.

## Components are plain functions

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

| Entry point | Use for |
|-------------|---------|
| `mountKineticaApp(selector) { App() }` | Browser apps ([renderer details](/docs/browser-renderer)) |
| `KineticaRuntime().render { App() }.tree.toSafeHtml()` | Server-side HTML ([server components](/docs/server-components)) |
| `KineticaTest.render { App() }` | Headless tests ([testing](/docs/testing)) |

`mountKineticaApp` defaults to `KineticaRuntime(debug = true)` — journaling, duplicate-key
checks and debug DOM attributes. Pass `KineticaRuntime(debug = false)` for production mounts.
