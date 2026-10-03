# Application shell

`kinetica-application` supplies platform-independent commands, menu descriptions,
window geometry and asynchronous resource ownership. The first window-system backend
is `AppKitApplication` in `kinetica-appkit` (macOS ARM64). Browser and other native
window-system backends are not implemented by this module yet.

## Ownership

| Kinetica | Application or embedded surface |
| --- | --- |
| Native application/window delegates, tab groups and menus | Domain sessions and application policy |
| Dispatch to the currently focused owned window | PTY, VT, network transports and application data |
| Per-window component renderer and focus | Specialized Metal/WebGL rendering |
| Visibility, window closing and quit coordination | Async release operation and its completion |

Each tab is an independently owned window with its own renderer. Switching tabs retains
the view, component state and first responder. A custom `HostWidget` keeps specialized
rendering outside reconciliation: ordinary terminal output never rebuilds the component
tree. Implement `HostWidget.requestFocus()` when the host's focus target is a child view.

## AppKit example

```kotlin
@UiComponent
fun ComponentScope.Document(query: MutableCell<String>) {
    textInput(query.value, onInput = { query.value = it }, semantics = Semantics(testTag = "search"))
}

val query = store("")
val app = AppKitApplication("Example")
app.commands.register(ApplicationCommand("close", "Close", listOf(KeyShortcut("w")),
    windowRequired = true) { app.activeWindow?.close() })
app.setMenus(listOf(ApplicationMenu("Window", listOf(MenuItem.Command("close")))))
app.install()
app.openWindow(ApplicationWindow("document-1", "Example", tabGroup = "documents",
    initialFocus = "search")) { Document(query) }
app.run()
```

Declare the content as a named `@UiComponent` function; call that component from
`openWindow`. Slot DSL declarations directly inside the entry lambda are intentionally
rejected by the compiler. To add a tab, open another window with a unique ID, matching
`tabGroup`, and `tabOf = existing.id`. `selectTab` and `nextTab` operate on the active group.

`WindowCallbacks.visibilityChanged` accounts for hidden/minimized windows, app hiding
and native tab selection. Use it to suspend background work. `closed` removes the
application's visible session; `release(done)` closes resources and calls `done` once
they have actually stopped. The framework disposes the renderer first and delays native
quit until all releases, including already-closed tabs, finish. A `canClose` veto leaves
all windows open when quitting. Call these APIs on the AppKit event thread.

Commands recheck `CommandState` when invoked. Window commands never fall back to an
arbitrary background document. `PRIMARY` means Command on macOS. Native menus handle
Command shortcuts; enabled non-Command shortcuts are monitored only within the active
owned window. Scope Enter/Escape commands with an explicit state predicate so they do
not consume text or terminal input. Marked native text composition takes precedence.
`editingCommand` uses the native responder chain for copy/paste/undo, including embedded
surfaces and text fields. Shortcut registration rejects duplicate logical bindings.

`AppKitWindow.focus(testTag)` addresses a semantic tag; `initialFocus` is used only on
first show. `restoreBounds` clamps saved geometry to an available screen. Persistence
format and settings policy remain application concerns.

## Applications and verification

[`HarmonApplication`](../samples/harmon-native/src/HarmonApplication.kt) uses this shell
for independent monitor tabs, menus, filtering focus and cancellation of hidden polling.
The separate [Kinetica Terminal repository](https://github.com/Heapy/kinetica-terminal)
uses it for native terminal windows; its `kinetica-terminal-ui` module embeds the independent
terminal renderer in Kinetica on both macOS and the browser. Framework modules have no
dependency on the terminal engine.

```sh
./kotlin test -m kinetica-application -p jvm
./kotlin test -m kinetica-appkit -m harmon-native -p macosArm64
python3 scripts/verify-harmon-live.py
# Local consumer artifacts, until these APIs have a framework release:
./kotlin publish mavenLocal -m kinetica-appkit -m kinetica-browser --transitive
```
