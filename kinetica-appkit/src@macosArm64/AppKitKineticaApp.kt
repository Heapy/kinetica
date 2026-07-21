package io.heapy.kinetica.appkit

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.render.HostAdapter
import io.heapy.kinetica.render.MountedNode
import io.heapy.kinetica.render.Reconciler
import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSBezelStyleRounded
import platform.AppKit.NSButton
import platform.AppKit.NSControl
import platform.AppKit.NSStackView
import platform.AppKit.NSSwitchButton
import platform.AppKit.NSTextField
import platform.AppKit.NSTextFieldDelegateProtocol
import platform.AppKit.NSTextView
import platform.AppKit.NSUserInterfaceLayoutOrientationHorizontal
import platform.AppKit.NSUserInterfaceLayoutOrientationVertical
import platform.AppKit.NSView
import platform.AppKit.bottomAnchor
import platform.AppKit.leadingAnchor
import platform.AppKit.topAnchor
import platform.AppKit.trailingAnchor
import platform.AppKit.translatesAutoresizingMaskIntoConstraints
import platform.Foundation.NSNotification
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Mount [content] into [contentView] and render once. Mirrors `mountKineticaApp` from the browser
 * renderer: a top-level function (rather than a direct constructor call at the call site) so the
 * Kinetica compiler plugin recognises the `@UiComponent`-typed [content] parameter from the
 * consuming module and numbers its body into a region frame.
 */
@OptIn(ExperimentalForeignApi::class)
public fun renderAppKitApp(
    contentView: NSView,
    runtime: KineticaRuntime = KineticaRuntime(debug = true),
    content: @UiComponent ComponentScope.() -> Unit,
): AppKitKineticaApp = AppKitKineticaApp(contentView, runtime, content).also { it.renderUntilSettled() }

/**
 * Retained-mode AppKit renderer on the shared `kinetica-render-core` reconciler (KNT-0046): the
 * first render mounts AppKit views inside [contentView]; every subsequent render diffs the fresh
 * `Node` tree against the [MountedNode] shadow tree and patches widgets in place (keyed-LIS child
 * moves, prop diffs, controlled-state resync). Views are retained across renders, so focus and
 * in-progress typing survive; [restoreFocus] covers the replace-the-focused-widget edge.
 *
 * Event wiring: Kinetica encodes host events as `event:<name>` props whose value is an opaque
 * event id resolvable via [KineticaRuntime.dispatch]. [AppKitEventDispatcher] (one shared
 * [NSObject]) is the target of every actionable [NSControl] (click/toggle/submit via
 * target-action) and the [NSTextFieldDelegateProtocol] delegate of every wired text field
 * (`controlTextDidChange` → `event:onInput` with the field text as payload). Bindings are
 * registered per widget and dropped in [HostAdapter.teardownHost] — no whole-map resets.
 *
 * Async invalidations (effects on `Dispatchers.Default` writing cells off the main thread) are
 * observed via [KineticaRuntime.onInvalidation] and marshalled onto the main queue by
 * [scheduleMainThreadRender]; N invalidations coalesce into one hop.
 *
 * Obj-C interop note: AppKit's `BOOL`-backed properties (`isEditable`, `isBordered`, `isEnabled`,
 * `drawsBackground`, ...) and the `NS_ENUM` constants (`NSBezelStyle`, `NSButtonType`) are exposed
 * by Kotlin/Native as explicit `setX()` setters and top-level `val`s respectively — not as Kotlin
 * properties or nested enum cases.
 */
@OptIn(ExperimentalForeignApi::class)
public class AppKitKineticaApp(
    private val contentView: NSView,
    private val runtime: KineticaRuntime = KineticaRuntime(debug = true),
    private val content: @UiComponent ComponentScope.() -> Unit,
) {
    private val scope = ComponentScope(runtime)
    private val dispatcher = AppKitEventDispatcher(runtime, ::renderUntilSettled)
    private val reconciler = Reconciler(AppKitHostAdapter(dispatcher))
    private var mountedRoot: MountedNode<NSView>? = null
    private var pinnedRoot: NSView? = null
    private val mainHopScheduled = AtomicBoolean(false)
    private val invalidationRegistration = runtime.onInvalidation { scheduleMainThreadRender() }

    /**
     * Render once. Use [renderUntilSettled] from event handlers and after the initial mount so the
     * UI reflects every invalidation produced synchronously by the render pass itself.
     */
    public fun render() {
        val tree = runtime.render(scope, content).tree
        val focusedIdentifier = captureFocusIdentifier()
        val previous = mountedRoot
        mountedRoot = if (previous == null) {
            reconciler.mount(tree, contentView)
        } else {
            reconciler.patch(previous, tree, contentView)
        }
        pinRootIfChanged()
        restoreFocus(focusedIdentifier)
    }

    /**
     * Drain pending invalidations. Called from [AppKitEventDispatcher] on the main thread after an
     * event dispatch, and from the application's bootstrap after the initial mount.
     */
    public fun renderUntilSettled() {
        render()
        while (runtime.hasPendingInvalidation) {
            render()
        }
    }

    public fun dispose() {
        invalidationRegistration.dispose()
        mountedRoot?.let { mounted -> reconciler.unmount(mounted, contentView) }
        mountedRoot = null
        pinnedRoot = null
        dispatcher.reset()
        scope.dispose()
        runtime.dispose()
    }

    /**
     * Marshal an off-main invalidation (an effect on `Dispatchers.Default` writing a cell) onto
     * the main queue and drain it there. Coalescing: invalidations arriving before the hop runs
     * fold into that hop (single [AtomicBoolean] guard). Synchronous click-path invalidations
     * fire this too, but their hop lands after [AppKitEventDispatcher] already drained —
     * [KineticaRuntime.hasPendingInvalidation] is false by then and the hop no-ops.
     */
    private fun scheduleMainThreadRender() {
        if (!mainHopScheduled.compareAndSet(expectedValue = false, newValue = true)) return
        dispatch_async(dispatch_get_main_queue()) {
            mainHopScheduled.store(false)
            if (runtime.hasPendingInvalidation) {
                renderUntilSettled()
            }
        }
    }

    // --- root pinning ------------------------------------------------------------------------------------------

    /**
     * Fill [contentView] — whose frame AppKit itself manages — with the mounted Kinetica root by
     * pinning the root view to the container's edges. We deliberately do NOT touch the
     * contentView's own `translatesAutoresizingMaskIntoConstraints` or constrain it to its
     * superview (the private theme frame) — that produces unsatisfiable-constraint breaks.
     * Views are retained across patches now, so re-pinning happens only when the root view was
     * actually replaced (the old view's constraints died with it). Only a single-view root has a
     * well-defined "fill"; a multi-child fragment root keeps its natural size.
     */
    private fun pinRootIfChanged() {
        val root = mountedRoot?.let(::singleViewOf) ?: return
        if (root === pinnedRoot) return
        root.leadingAnchor.constraintEqualToAnchor(contentView.leadingAnchor).setActive(true)
        root.trailingAnchor.constraintEqualToAnchor(contentView.trailingAnchor).setActive(true)
        root.topAnchor.constraintEqualToAnchor(contentView.topAnchor).setActive(true)
        root.bottomAnchor.constraintEqualToAnchor(contentView.bottomAnchor).setActive(true)
        pinnedRoot = root
    }

    private fun singleViewOf(mounted: MountedNode<NSView>): NSView? = when (mounted) {
        is MountedNode.Host<NSView> -> mounted.view
        is MountedNode.Text<NSView> -> mounted.view
        is MountedNode.Fragment<NSView> -> mounted.children.singleOrNull()?.let(::singleViewOf)
        is MountedNode.Empty<NSView> -> null
    }

    // --- focus preservation ------------------------------------------------------------------------------------

    /**
     * Identifier (the `testTag` semantics) of the currently focused widget. Text fields focus
     * through their window-shared field editor (an [NSTextView] whose delegate is the field), so
     * the responder is unwrapped first.
     */
    private fun captureFocusIdentifier(): String? =
        focusedView()?.identifier

    private fun restoreFocus(identifier: String?) {
        if (identifier == null) return
        val window = contentView.window ?: return
        if (focusedView()?.identifier == identifier) return
        val target = findViewByIdentifier(contentView, identifier) ?: return
        window.makeFirstResponder(target)
    }

    private fun focusedView(): NSView? =
        when (val responder = contentView.window?.firstResponder) {
            is NSTextView -> responder.delegate as? NSTextField
            is NSView -> responder
            else -> null
        }

    private fun findViewByIdentifier(root: NSView, identifier: String): NSView? {
        if (root.identifier == identifier) return root
        for (subview in root.subviews) {
            val view = subview as? NSView ?: continue
            findViewByIdentifier(view, identifier)?.let { return it }
        }
        return null
    }
}

/**
 * [HostAdapter] over AppKit: tag → widget factories, prop application, NSStackView-aware child
 * management, semantics → accessibility, and per-widget event (un)binding via the shared
 * [AppKitEventDispatcher].
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppKitHostAdapter(
    private val dispatcher: AppKitEventDispatcher,
) : HostAdapter<NSView> {

    override fun createHost(node: HostNode): NSView {
        val view: NSView = when (node.tag) {
            "column", "row" -> NSStackView().apply {
                setOrientation(
                    if (node.tag == "column") NSUserInterfaceLayoutOrientationVertical
                    else NSUserInterfaceLayoutOrientationHorizontal,
                )
                setSpacing(8.0)
            }
            "button" -> makeButton(node)
            "checkbox" -> makeCheckbox(node)
            "textInput" -> makeTextField(node)
            // Unknown tag: a neutral container keeps the tree navigable.
            else -> NSView()
        }
        view.translatesAutoresizingMaskIntoConstraints = false
        return view
    }

    override fun createText(node: TextNode): NSView = makeLabel(node.value)

    override fun setText(view: NSView, node: TextNode) {
        (view as? NSTextField)?.setStringValue(node.value)
    }

    override fun setProp(view: NSView, name: String, value: String, node: HostNode) {
        when (name) {
            "enabled" -> (view as? NSControl)?.setEnabled(value != "false")
            "checked" -> (view as? NSButton)?.setState(if (value == "true") 1 else 0)
            "value" -> (view as? NSTextField)?.let { field ->
                if (field.stringValue != value) field.setStringValue(value)
            }
            "placeholder" -> (view as? NSTextField)?.setPlaceholderString(value)
            "event:onClick", "event:onToggle", "event:onSubmit" ->
                (view as? NSControl)?.let { control -> dispatcher.registerAction(control, value) }
            "event:onInput" ->
                (view as? NSTextField)?.let { field -> dispatcher.registerInput(field, value) }
        }
    }

    override fun removeProp(view: NSView, name: String, node: HostNode) {
        when (name) {
            "enabled" -> (view as? NSControl)?.setEnabled(true)
            "checked" -> (view as? NSButton)?.setState(0)
            "value" -> (view as? NSTextField)?.setStringValue("")
            "placeholder" -> (view as? NSTextField)?.setPlaceholderString(null)
            "event:onClick", "event:onToggle", "event:onSubmit", "event:onInput" ->
                (view as? NSControl)?.let(dispatcher::unregister)
        }
    }

    override fun applySemantics(view: NSView, semantics: Semantics?, nativeTag: String) {
        view.setIdentifier(semantics?.testTag)
        view.setAccessibilityLabel(semantics?.label)
        view.setAccessibilityRole(appKitAccessibilityRoleFor(semantics?.role, nativeTag))
    }

    override fun insert(container: NSView, child: NSView, before: NSView?) {
        // NSStackView lays out its arranged subviews via Auto Layout constraints it owns; a plain
        // addSubview bypasses that and the child would float at (0,0).
        if (container is NSStackView) {
            val index = before?.let { anchor -> container.arrangedSubviews.indexOf(anchor) } ?: -1
            if (index >= 0) {
                container.insertArrangedSubview(child, index.toLong())
            } else {
                container.addArrangedSubview(child)
            }
            return
        }
        container.addSubview(child)
    }

    override fun remove(container: NSView, child: NSView) {
        if (container is NSStackView) container.removeArrangedSubview(child)
        child.removeFromSuperview()
    }

    override fun foldsChildren(tag: String): Boolean = tag in LEAF_WIDGET_TAGS

    override fun updateFoldedContent(view: NSView, node: HostNode) {
        if (node.tag == "button") {
            (view as? NSButton)?.setTitle(foldedCaption(node))
        }
    }

    override fun isControlledTag(tag: String): Boolean = tag == "textInput" || tag == "checkbox"

    override fun syncControlledState(view: NSView, node: HostNode) {
        when (node.tag) {
            "textInput" -> {
                val field = view as? NSTextField ?: return
                val value = node.props["value"].orEmpty()
                // Skip-if-equal keeps the caret stable while typing: the model was just updated
                // from onInput, so the common case compares equal.
                if (field.stringValue != value) field.setStringValue(value)
            }
            "checkbox" -> {
                val button = view as? NSButton ?: return
                val state = if (node.props["checked"] == "true") 1L else 0L
                if (button.state != state) button.setState(state)
            }
        }
    }

    override fun teardownHost(view: NSView, node: HostNode) {
        (view as? NSControl)?.let(dispatcher::unregister)
    }

    // --- tag → widget factories --------------------------------------------------------------------------------

    private fun makeLabel(text: String): NSTextField {
        // A non-editable, non-bordered text field is AppKit's idiomatic label.
        return NSTextField().apply {
            setStringValue(text)
            setBordered(false)
            setDrawsBackground(false)
            setEditable(false)
            setSelectable(false)
            setBezelStyle(0u)
            translatesAutoresizingMaskIntoConstraints = false
        }
    }

    private fun makeTextField(node: HostNode): NSTextField {
        return NSTextField().apply {
            setStringValue(node.props["value"].orEmpty())
            node.props["placeholder"]?.let { setPlaceholderString(it) }
            node.props["event:onInput"]?.let { eventId -> dispatcher.registerInput(this, eventId) }
            node.props["event:onSubmit"]?.let { eventId -> dispatcher.registerAction(this, eventId) }
        }
    }

    private fun makeButton(node: HostNode): NSButton {
        // The button's title comes from its single text child (mirrors the DOM renderer, where a
        // <button> wraps a text node); the reconciler folds the child instead of mounting it.
        return NSButton().apply {
            setTitle(foldedCaption(node))
            setBordered(true)
            setBezelStyle(NSBezelStyleRounded)
            if (node.props["enabled"] == "false") setEnabled(false)
            node.props["event:onClick"]?.let { eventId -> dispatcher.registerAction(this, eventId) }
        }
    }

    private fun makeCheckbox(node: HostNode): NSButton {
        return NSButton().apply {
            setTitle("")
            setButtonType(NSSwitchButton)
            setState(if (node.props["checked"] == "true") 1 else 0)
            if (node.props["enabled"] == "false") setEnabled(false)
            node.props["event:onToggle"]?.let { eventId -> dispatcher.registerAction(this, eventId) }
        }
    }

    private fun foldedCaption(node: HostNode): String =
        (node.children.singleOrNull() as? TextNode)?.value.orEmpty()

    // --- semantics → accessibility -----------------------------------------------------------------------------

    private fun appKitAccessibilityRoleFor(role: Role?, nativeTag: String): String? = when (role) {
        Role.Button -> "AXButton"
        Role.Checkbox -> "AXCheckBox"
        Role.TextInput -> "AXTextField"
        Role.Text -> "AXStaticText"
        Role.List -> "AXList"
        Role.ListItem -> "AXRow"
        Role.Navigation -> "AXGroup"
        Role.Image -> "AXImage"
        Role.Dialog -> "AXSheet"
        Role.None -> null
        null -> null
    }
}

/**
 * Host tags whose text children are absorbed into the widget's own caption/value rather than
 * mounted as separate views (`NSButton.title` is the whole caption; mounting the child too would
 * double-render it).
 */
private val LEAF_WIDGET_TAGS: Set<String> = setOf("button", "checkbox", "textInput")

/**
 * The single [NSObject] shared by every actionable widget: target of the `clicked:` action
 * (buttons, checkboxes, text-field submit) and [NSTextFieldDelegateProtocol] delegate of wired
 * text fields (`controlTextDidChange` → `event:onInput` with the field text as payload). Each
 * dispatch drives [renderUntilSettled] so the UI settles synchronously on the main thread.
 *
 * One target (rather than one per control) keeps the Obj-C method-dispatch surface tiny.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AppKitEventDispatcher(
    private val runtime: KineticaRuntime,
    private val renderUntilSettled: () -> Unit,
) : NSObject(), NSTextFieldDelegateProtocol {

    @Suppress("unused")
    @ObjCAction
    fun clicked(sender: NSControl) {
        val eventId = actionBindings[sender] ?: return
        runtime.dispatch(eventId)
        renderUntilSettled()
    }

    override fun controlTextDidChange(obj: NSNotification) {
        val field = obj.`object` as? NSTextField ?: return
        val eventId = inputBindings[field] ?: return
        runtime.dispatch(eventId, field.stringValue)
        renderUntilSettled()
    }

    fun registerAction(control: NSControl, eventId: String) {
        actionBindings[control] = eventId
        control.target = this
        control.action = NSSelectorFromString(ACTION_SELECTOR)
    }

    fun registerInput(field: NSTextField, eventId: String) {
        inputBindings[field] = eventId
        field.delegate = this
    }

    /** Drop a widget's bindings on unmount so dead controls don't accumulate. */
    fun unregister(control: NSControl) {
        actionBindings.remove(control)
        if (control is NSTextField) {
            inputBindings.remove(control)
            field(control)
        }
    }

    private fun field(control: NSTextField) {
        if (control.delegate === this) control.delegate = null
    }

    fun reset() {
        actionBindings.clear()
        inputBindings.clear()
    }

    // Kotlin/Native forbids companion-object fields on Obj-C subclasses, so the action selector name
    // is a top-level constant instead.
    private val actionBindings: MutableMap<NSControl, String> = mutableMapOf()
    private val inputBindings: MutableMap<NSTextField, String> = mutableMapOf()
}

/** The Obj-C selector AppKit invokes on [AppKitEventDispatcher]; mirrors the `clicked(sender:)` method. */
private const val ACTION_SELECTOR: String = "clicked:"
