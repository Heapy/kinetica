# Compiler plugin

The compiler plugin (`io.heapy.kinetica.compiler`) is a standard part of the toolchain story:
it removes the ceremony that hand-written Kinetica code carries (explicit `key =` arguments)
and generates the cross-boundary artifacts for server components. It is a K2
`CompilerPluginRegistrar` that analyzes sources before compilation and emits generated Kotlin.

## Annotations

All annotations live in `kinetica-runtime`, so app code needs no extra dependency:

```kotlin
@UiComponent
fun Badge(label: String) {
    text("Static badge")
    text(label)
}

@Preview("Annotated app")
@UiComponent
fun AnnotatedApp() {
    var label by state { "Inbox" }     // no key: the compiler assigns a stable SlotId
    Badge(label)
}
```

| Annotation | Purpose |
|------------|---------|
| `@UiComponent` | stable `SlotId` generation, desugaring, render skipping, static hoisting |
| `@Preview(name)` | registers a preview entry for tooling |
| `@ServerComponent` / `@ClientComponent` | marks the server/client boundary for code splitting |
| `@ServerAction(invalidates = […])` | declares a typed server action and its invalidation keys |

## Enabling it

```yaml
# module.yaml
settings:
  kotlin:
    compilerPlugins:
      - id: io.heapy.kinetica.compiler
        dependency: io.heapy.kinetica:kinetica-compiler:0.1.0
        options:
          moduleId: my-app
          serverSourceSet: serverMain
          clientSourceSet: clientMain
```

See `samples/annotated` for the working wiring.

## What it generates

Generated symbols land in `io.heapy.kinetica.generated`: component transforms, preview entries,
server-action registrations + stubs + a dispatcher, the client-component manifest, and route
codecs. Declared responsibilities: slot-id generation, `@UiComponent` desugaring, skipping,
static hoisting, the server/client boundary, server-action stubs, diagnostics, hot-reload
protocol, preview entries.

## Current status — read before relying on it

The plugin infrastructure is real and wired (the `samples/annotated` module compiles through
it, and slot-id injection for `state { }` without keys is what that sample relies on). However,
the annotation-driven **generation output is early-stage**: the sample's checked-in tests
currently assert empty transform/preview/action lists. Treat the annotations and module wiring
as the stable contract, and populated codegen (previews, server-action dispatchers, manifests)
as in-progress — hand-written equivalents, as shown in
[Server components](/docs/server-components), are the supported path today.

Without the plugin, everything works with explicit keys: `state(key = "…")`, `each(key = { … })`,
hand-declared `SlotId`s for [persistence](/docs/persist), and hand-written manifests.
