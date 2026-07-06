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

## The plugin is mandatory

Kinetica does not have a plugin-less mode. The IR pass (which runs on every backend — JVM,
JS, wasm, native) assigns each slot- and event-consuming call site a static ordinal inside a
per-component *frame*, wraps content lambdas into frame regions, and stages child ordinals
for component calls. State identity is decided at compile time: there are no string keys, no
positional cursors, and the collision bug class (two branches landing on one slot) is
impossible by construction. A call site the plugin never rewrote fails fast at runtime with
`MissingKineticaPluginException`.

Consequences for authoring:

- every function that calls `state`/`derived`/effects/events/boundaries must be a
  `@UiComponent fun ComponentScope.X(...)`;
- slot calls directly inside loops or multi-run lambdas (`List(n) { … }`, `repeat`, `map`)
  are not supported — use `each(items, key = { … })` or `keyed(key) { … }`, which
  disambiguate iterations by user key;
- `render { }` content should call components; the lambda itself is wrapped into a frame
  region by the plugin (its parameter type carries `@UiComponent`).

The annotation-driven **generation output** (previews, server-action dispatchers, manifests)
remains early-stage and JVM-only via the `sourcePipeline: psi` option; hand-written
equivalents, as shown in [Server components](/docs/server-components), are the supported path
for those features today.
