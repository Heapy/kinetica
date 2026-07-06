# UI DSL & semantics

Components emit nodes with a small DSL. Four node kinds exist: `HostNode` (an element),
`TextNode`, `FragmentNode`, and `ClientRef` (a [server-component island](/docs/server-components)).

## Primitives

```kotlin
host("section", props = mapOf("class" to "card", "id" to "intro")) { … }
column { … }                     // <div> with vertical flex layout
row { … }                        // <div> with horizontal flex layout, direction-aware (RTL)
text("Hello")                    // text node
button(onClick = event { … }, enabled = isReady) { text("Save") }
textInput(value = draft, onInput = event<String> { draft = it },
          onSubmit = event { commit() }, placeholder = "Type…")
checkbox(checked = done, onToggle = event { done = !done })
fragment { … }                   // group without an element
```

`host(tag, props, frameProps, semantics, key, content)` is the escape hatch to any element:
`table`, `tr`, `a`, `svg` containers — the browser renderer creates the tag verbatim if its name
is safe, and applies `props` as attributes through a sanitizing allowlist (`class`, `style`,
`id`, `href`, `title`, `data-*`, `aria-*` pass; event-handler attributes and unsafe URL schemes
like `javascript:` never reach the DOM, on the server serializer and the browser renderer alike).

`key` gives a node a stable identity for [keyed reconciliation](/docs/lists-and-keys).
`frameProps` binds [motion frame values](/docs/motion) to a node.

## Styling

There is no styling system in core — set `class`/`style` props and ship CSS. `row`/`column` own
their inline `style` (flex layout); use `class` on them, or plain `host("div")` when you want
full control.

## Semantics

Accessibility and testability are one model, carried on the node:

```kotlin
button(
    onClick = save,
    semantics = Semantics(
        role = Role.Button,        // role attribute (skipped when the native tag covers it)
        label = "Save document",   // aria-label
        testTag = "save",          // data-testid, used by tests and drivers
        focusable = true,          // tabindex when the element isn't natively focusable
    ),
) { text("Save") }
```

`Role` covers `Button, Checkbox, Text, TextInput, List, ListItem, Navigation, Dialog, Image,
None`. `Semantics` also carries `stateDescription` (aria-description), `traversalIndex`, and
`leaving` (exit-animation marker).

`text()` defaults to `Semantics(role = Role.Text)` and renders as a bare text node. The
semantics tree derives its label from the text value for queries; pass an explicit
`Semantics(label = ...)` only when the accessible label differs from the visible text.

## Contexts

Ambient values flow through the tree without prop drilling:

```kotlin
val Density = context(1.0f, name = "Density")

provide(Density, 2.0f) {
    val density = read(Density)     // 2.0f anywhere in this subtree
}
```

The [theme battery](/docs/persist) is built on exactly this: `provideTheme(DarkTheme) { … }` +
`theme()`.

## Boundaries in the tree

Two structural components manage failure and loading around any subtree — they compose with
everything above and are covered in [Resources & boundaries](/docs/resources):

```kotlin
errorBoundary(fallback = { error, info, retry -> … }) { RiskyPanel() }
loadingBoundary(fallback = { text("Loading…") }) { ProfileCard() }
```

## Conditional subtrees

Plain `if/else` just works: slot identity is the call site, so the two arms can never share
or corrupt each other's state — each branch's `state`/effects live in their own slots, and
boundaries isolate their branches structurally. A branch that stops rendering keeps its state
cells (it picks up where it left off when it comes back) while its effects, refs and event
registrations are disposed on the way out.

`keyed` is for *dynamic* identity — one call site rendering different logical instances, where
switching the key should deliberately reset state:

```kotlin
keyed(selectedNoteId) { NoteDetail(selectedNoteId) }
```

`keyed` gives every slot, event and effect inside it a frame per key, exactly like an `each`
row — see
[State & reactivity](/docs/state) for how slots are addressed.
