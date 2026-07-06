# Compiler plugin

<!-- code: kinetica-compiler/src/KineticaCompilerRegistrar.kt, kinetica-compiler/src/CompilerContract.kt -->

The compiler plugin (`io.heapy.kinetica.compiler`) is a standard part of the toolchain story:
it removes the ceremony that hand-written Kinetica code carries (explicit `key =` arguments)
and generates the cross-boundary artifacts for server components. It is a K2
`CompilerPluginRegistrar` whose core is a set of IR transforms plus FIR checkers; with the
opt-in `sourcePipeline: psi` it additionally analyzes sources before compilation and emits
generated Kotlin.

## Annotations

<!-- code: kinetica-runtime/src/Annotations.kt -->

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
| `@UiComponent` | stable `SlotId` generation, desugaring, render skipping (`skippable = false` opts out), static hoisting |
| `@Preview(name)` | registers a preview entry for tooling |
| `@ServerComponent` / `@ClientComponent` | marks the server/client boundary for code splitting |
| `@ServerAction(invalidates = […])` | declares a typed server action and its invalidation keys |

## Enabling it

<!-- code: common.module-template.yaml, samples/annotated/module.yaml, kinetica-compiler/src/KineticaCommandLineProcessor.kt (pluginOptions) -->

Every Kinetica module applies the shared template (`common.module-template.yaml`), which wires:

```yaml
# module.yaml
settings:
  kotlin:
    compilerPlugins:
      - id: io.heapy.kinetica.compiler
        dependency: io.heapy.kinetica:kinetica-compiler:0.3.0
        options:
          moduleId: my-app
          serverSourceSet: serverMain
          clientSourceSet: clientMain
```

Further options and their defaults: `sourcePipeline: lightTree` (`psi` turns on source
generation), `transforms: all` (`off` is the IR kill switch for debugging), and
`checks: error` (FIR authoring-rule diagnostics; `warning` downgrades them). See
`samples/annotated` for the working wiring.

## IR passes

<!-- code: kinetica-compiler/src/KineticaIrTransform.kt, kinetica-compiler/src/KineticaIrTemplate.kt, kinetica-compiler/src/KineticaIrHoist.kt, kinetica-compiler/src/KineticaIrFrames.kt -->

Four transforms run in order on every backend:

1. **Template extraction** — a `host` whose content is a single dynamic `text(…)` over
   constant props becomes a `templateNode` referencing a hoisted static skeleton; the browser
   renderer patches such nodes through a clone-and-fill fast path.
2. **Static hoisting** — constant `propsOf(…)` argument lists and static leaf hosts are
   interned as file-level fields, so re-renders reuse one instance instead of reallocating.
3. **Skippable components** — `@UiComponent` bodies get an inputs-equal fast path
   (`skippableNode`) that re-emits the previous subtree when arguments and read cells are
   unchanged.
4. **Frames** — the ordinal assignment described below.

## What it generates

<!-- code: kinetica-compiler/src/KineticaGeneratedSourceEmitter.kt, kinetica-compiler/src/CompilerContract.kt (responsibilities) -->

Generated symbols land in `io.heapy.kinetica.generated`: component transforms, preview entries,
server-action registrations + stubs + a dispatcher, the client-component manifest, and route
codecs. Declared responsibilities: slot-id generation, `@UiComponent` desugaring, skipping,
static hoisting, the server/client boundary, server-action stubs, diagnostics, hot-reload
protocol, preview entries.

## The plugin is mandatory

<!-- code: kinetica-compiler/src/KineticaIrFrames.kt (KineticaFrameTransformer), kinetica-compiler/src/KineticaFirExtension.kt (authoring-rule checkers), kinetica-runtime/src/Frames.kt (FrameTable) -->

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
