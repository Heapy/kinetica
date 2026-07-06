# Migrating to the plugin-only slot model (frame ordinals)

Kinetica no longer has a plugin-less mode. The K2 compiler plugin assigns every
slot- and event-consuming call site a static ordinal inside a per-component *frame*;
the runtime's string-keyed slot storage, positional cursors, and `SlotKind`
discrimination are gone. This page lists everything a codebase must change.

## Authoring rules

1. **Every stateful function is a component.** `state`, `derived`, `launchEffect`,
   `watch`, `event`, `hostRef`, `imperativeHandle`, `resource`, `frameValue`,
   boundaries, `exitGroup`, `keyed`, `each`, `lazyEach`, and the event-registering
   host DSL (`button`, `textInput`, `checkbox`, `hostEvent`) may be called only
   lexically inside a `@UiComponent fun ComponentScope.X(...)` declaration. A call
   site the plugin never rewrote throws `MissingKineticaPluginException` at runtime.
2. **`state(key = "…")` is removed.** Slot identity is the call site. For dynamic
   identity — several logical instances of the same call site — wrap the subtree in
   `keyed(key) { … }` or use `each(items, key = { … })`.
3. **No slot calls in loops or multi-run lambdas.** `for` bodies, `List(n) { … }`,
   `repeat`, `map`/`forEach` lambdas run many times per render; a static ordinal
   cannot tell iterations apart, so such calls fail fast. Use `each`/`keyed` (their
   user key disambiguates), or build cell graphs with the scope-free factories
   `store(initial)` / `derive { … }`.
4. **Content parameters are typed.** A parameter that accepts renderable content and
   invokes it once per render should be declared
   `content: @UiComponent ComponentScope.() -> Unit` — the plugin then wraps caller
   lambdas into frame regions (this is how `render { App() }` works).
5. **Tests render components.** Inline stateful lambdas in `render { … }` are
   compile-/run-time errors; define private top-level `@UiComponent` components in
   the test file and parameterize them via captured `store(...)` cells or top-level
   vars.

## Behavior changes

- **Removed `each` rows are disposed.** When a key leaves the list its frame (state,
  effects, events) is disposed immediately; if the key returns the row starts fresh.
  The old string-keyed storage silently resurrected such state — that was a leak.
- **Branch state retention is structural.** A boundary's hidden branch (or an
  untaken `if` branch) keeps its state cells but has its effects, refs, resources
  and event registrations disposed on the shown→hidden transition; they restart when
  the branch comes back.
- **Persistence addresses are `SlotId`s.** Keyless `state(persistent = true)` gets a
  compiler-generated `SlotId` (same recipe as the old PSI injection, so stored data
  keeps its address). `writeSlot` before the owning component's first render parks
  the value in a restore buffer that seeds the cell on creation;
  `migrateRestoredSlot` is gone.
- **Slot-collision diagnostics are gone** because the bug class is gone: two call
  sites can never share a slot, and the `slot-class-mismatch` warning,
  debug-mode failure, and production self-heal were deleted with it.

## Build

The plugin block lives in `common.module-template.yaml` and applies to every module;
`moduleId` defaults to the compilation's module name. The plugin must be published to
the toolchain-local repo before anything else compiles:
`./kotlin publish mavenLocal -m kinetica-compiler`. When republishing the same
version, touch a consumer source file — the toolchain does not invalidate on a
same-version jar swap.

The `checks` plugin option (`error|off`) gates the FIR authoring rules (slot DSL
containment, loop bans, `ComponentScope` receivers). It defaults to `off` during
migration and flips to `error` once a codebase conforms.
