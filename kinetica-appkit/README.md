# Kinetica AppKit renderer

`kinetica-appkit` targets `macosArm64`. Call `renderAppKitApp(contentView) { … }` on the main
thread, retain its returned renderer, and call `dispose()` when its window closes. Store
invalidations coalesce onto the main queue; native rendering and disposal belong on that queue.

Register `HostWidgetFactory<NSView>` instances through `hostWidgets` to embed an external
surface. Its host node must have no framework children: the widget owns its native subtree.
Matching tag/key pairs retain the widget across updates; a new key creates a new instance.
Unmounting disposes each instance once. `HostWidget.requestFocus()` can forward focus to an
internal editor, and the renderer's `focus(testTag)` locates the host by its semantic tag.

For a complete application, use `AppKitApplication.openWindow` to own the renderer,
window/tab lifecycle, menus, shortcuts and focus. See the
[application shell guide](../kinetica-application/README.md). `renderAppKitApp` remains
available for embedding components in an existing AppKit application.

## Choices

`appKitChoice(value, options, onChange)` renders a controlled native `NSPopUpButton`.
Each `ChoiceOption` has a stable value and display label. Reflect the delivered value
into state; changing labels or order preserves selection by value. Events are delivered
after the native action finishes. Disposal removes its callback and native target.

## Outline tables

`ComponentScope.outlineTable(model, onEvent = …)` renders a native `NSOutlineView` in a scroll
view. It accepts an `OutlineTableModel` containing columns, keyed tree rows, formatted cells,
expanded keys, a selected key and a sort descriptor. Reflect `SelectionChanged`,
`ExpansionChanged` and `SortChanged` events into application state.

- Row keys identify the entity across snapshots. A process needs PID plus start identity.
- The caller sorts siblings using the original values. The renderer never parses cell text.
- The first column holds the disclosure control. Column resizing is supported; reordering and
  multiple selection are not supported in this version.
- Stable keys reuse native item wrappers. Structural changes reload the outline; metric-only
  changes refresh visible rows. Cell views are reused by AppKit.
- Updates restore expansion, selection, column widths and the top visible row anchor when that
  row still exists. A selection hidden by filtering is visually cleared; retaining its key is
  the caller's choice.
- Native delegate events are delivered on the next main-queue turn so reconciliation happens
  after AppKit completes the delegate notification. Disposal drops pending events.
- Models must contain unique nonempty row keys, unique nonblank column IDs and valid column
  widths. Invalid models fail before changing the control.

The serializable model crosses Kinetica's existing string-property host boundary as JSON.
For high-frequency or substantially larger datasets, profile projection and encoding as well
as native cell reuse. The reusable runtime `hostEvent<A>` overload supplies typed payloads to
native hosts; the host must dispatch the declared payload type.

[`samples/harmon-native`](../samples/harmon-native/README.md) is the complete reference app.

## Layout support

Alongside `row`, `column`, `text`, `button`, `checkbox` and `textInput`, the renderer supports
`appkit:label` (`value`, `fontSize`, `secondary`) and `appkit:spacer`. Stack hosts accept
`spacing` and uniform `padding`; hosts accept `width`, `height`, `minWidth` and `minHeight` in
points. These are AppKit-specific host properties, not a portable styling API.

Semantic test tags become native accessibility identifiers. Native controls retain their
default accessibility roles when the component supplies no explicit role.

`textInput(type = TextInputType.Password)` uses `NSSecureTextField`. Switching between password
and other types replaces the native field and restores focus and selection through its test
tag or input-event binding. Untagged fields without an `onInput` binding have no focus-restoration
identity. Other text input types use ordinary `NSTextField`; HTML `autocomplete` hints are
browser-only.
