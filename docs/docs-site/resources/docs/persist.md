# Persistence

State survives process death through **slot persistence**: state slots addressed by stable ids,
bridged to a storage backend explicitly. Also here: the theme battery, Kinetica's other ambient
state module.

## Stable slot ids

```kotlin
val DraftSlot = SlotId(
    moduleId = "todo",
    functionFqName = "app.TodoApp",
    declarationOrdinal = 2,
    disambiguator = "draft",
)

var draft by state(slotId = DraftSlot, persistent = true) { "" }
```

`SlotId` is the durable address of a piece of state. The [compiler
plugin](/docs/compiler-plugin) generates one for every keyless `state(persistent = true)`
call; declare one explicitly when you need an address that survives refactors verbatim.
`persistent = true` marks the slot as durable metadata — **it does not write to disk by
itself**; durability comes from the save/restore bridge below.

## Saving and restoring

```kotlin
val backend = JsonSlotPersistenceBackend()          // or your own SlotPersistenceBackend

// on save points (suspend):
scope.saveSlot(DraftSlot, backend, String.serializer())

// on startup, before rendering (suspend):
scope.restoreSlot(DraftSlot, backend, String.serializer())
scope.render {
    var draft by persistentState(slotId = DraftSlot, restoredValue = readSlot(DraftSlot)) { "" }
}
```

Writing `null` into a present slot removes the backend entry — cleared state does not resurrect
on the next launch. Batch variants: `persistentSlot(slotId, serializer)` bindings +
`restoreSlots` / `saveSlots`.

## Backends

`SlotPersistenceBackend` is three suspend functions (`read`, `write`, `remove`).
`StringSlotPersistenceBackend` handles JSON encoding over any `StringSlotPersistenceStore` —
implement the string store over your platform storage. The shipped
`DataStore…` / `LocalStorage…` / `NSUserDefaults…` backends are **namespaced wrappers over a
store you provide**, not built-in platform integrations.

## Theming (kinetica-theme)

Ambient design tokens ride the [context system](/docs/ui-dsl):

```kotlin
provideTheme(DarkTheme.copy(direction = LayoutDirection.Rtl)) {
    val t = theme()
    host("div", props = mapOf("style" to "background:${t.colors.background}")) {
        text(t.directional(ltr = "→", rtl = "←"))
        row { … }                       // rows pick up the RTL direction automatically
    }
}
```

- `Theme(colors: ColorTokens, breakpoints: Breakpoints, direction)` — serializable values;
  `LightTheme` / `DarkTheme` are provided.
- `ColorTokens(background, surface, text, accent)` — a minimal token set (not a full design
  system).
- Breakpoints: `theme.breakpointFor(width)` → `Compact / Medium / Expanded`.
- Direction helpers: `layoutDirection()`, `isRtl()`, `theme.directional(ltr, rtl)`.
