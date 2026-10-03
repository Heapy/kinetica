package io.heapy.kinetica.appkit

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.render.HostAdapter
import io.heapy.kinetica.render.HostWidget
import io.heapy.kinetica.render.HostWidgetFactory
import io.heapy.kinetica.render.MountedNode
import io.heapy.kinetica.render.Reconciler
import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import kotlinx.serialization.json.Json
import platform.AppKit.NSBezelStyleRounded
import platform.AppKit.NSColor
import platform.AppKit.NSFont
import platform.AppKit.NSLayoutAttributeWidth
import platform.AppKit.NSLayoutConstraint
import platform.AppKit.NSButton
import platform.AppKit.NSControl
import platform.AppKit.NSStackView
import platform.AppKit.NSSecureTextField
import platform.AppKit.NSSwitchButton
import platform.AppKit.NSTextField
import platform.AppKit.NSTextFieldDelegateProtocol
import platform.AppKit.NSTextView
import platform.AppKit.NSUserInterfaceLayoutOrientationHorizontal
import platform.AppKit.NSUserInterfaceLayoutOrientationVertical
import platform.AppKit.NSView
import platform.AppKit.bottomAnchor
import platform.AppKit.currentEditor
import platform.AppKit.leadingAnchor
import platform.AppKit.topAnchor
import platform.AppKit.trailingAnchor
import platform.AppKit.translatesAutoresizingMaskIntoConstraints
import platform.AppKit.widthAnchor
import platform.AppKit.heightAnchor
import platform.AppKit.setContentHuggingPriority
import platform.Foundation.NSEdgeInsetsMake
import platform.Foundation.NSNotification
import platform.Foundation.NSMakeRange
import platform.Foundation.NSRange
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** The top-level wrapper lets the compiler number [content] into a region frame. */
@OptIn(ExperimentalForeignApi::class)
public fun renderAppKitApp(contentView: NSView, runtime: KineticaRuntime,
    content: @UiComponent ComponentScope.() -> Unit): AppKitKineticaApp =
    renderAppKitApp(contentView, runtime, emptyMap(), content)

@OptIn(ExperimentalForeignApi::class)
public fun renderAppKitApp(
    contentView: NSView,
    runtime: KineticaRuntime = KineticaRuntime(debug = true),
    hostWidgets: Map<String, HostWidgetFactory<NSView>> = emptyMap(),
    content: @UiComponent ComponentScope.() -> Unit,
): AppKitKineticaApp = AppKitKineticaApp(contentView, runtime, hostWidgets, content).also { it.renderUntilSettled() }

/**
 * Retained AppKit renderer with per-widget bindings and coalesced main-queue invalidations.
 * Kotlin/Native exposes AppKit's `BOOL` properties as `setX()` methods and `NS_ENUM` constants as
 * top-level values, not Kotlin properties or nested enum cases.
 */
@OptIn(ExperimentalForeignApi::class)
public class AppKitKineticaApp(
    private val contentView: NSView,
    private val runtime: KineticaRuntime = KineticaRuntime(debug = true),
    hostWidgets: Map<String, HostWidgetFactory<NSView>> = emptyMap(),
    private val content: @UiComponent ComponentScope.() -> Unit,
) {
    public constructor(contentView: NSView, runtime: KineticaRuntime,
        content: @UiComponent ComponentScope.() -> Unit) : this(contentView, runtime, emptyMap(), content)
    private val scope = ComponentScope(runtime)
    private val dispatcher = AppKitEventDispatcher(runtime, ::renderUntilSettled)
    private val adapter = AppKitHostAdapter(dispatcher, hostWidgets)
    private val reconciler = Reconciler(adapter)
    private var mountedRoot: MountedNode<NSView>? = null
    private var pinnedRoot: NSView? = null
    private var disposed = false
    private val mainHopScheduled = AtomicBoolean(false)
    private val invalidationRegistration = runtime.onInvalidation { scheduleMainThreadRender() }

    public fun render() {
        check(!disposed) { "AppKit renderer is disposed" }
        val tree = runtime.render(scope, content).tree
        val focus = captureFocus()
        val previous = mountedRoot
        mountedRoot = if (previous == null) {
            reconciler.mount(tree, contentView)
        } else {
            reconciler.patch(previous, tree, contentView)
        }
        pinRootIfChanged()
        restoreFocus(focus)
    }

    public fun renderUntilSettled() {
        render()
        while (runtime.hasPendingInvalidation) {
            render()
        }
    }

    /** Focus a retained control or custom host by its stable semantic test tag. */
    public fun focus(testTag: String): Boolean {
        if (disposed) return false
        val target = findViewByIdentifier(contentView, testTag) ?: return false
        return adapter.focus(target)
    }

    public fun dispose() {
        if (disposed) return
        disposed = true
        invalidationRegistration.dispose()
        mountedRoot?.let { mounted -> reconciler.unmount(mounted, contentView) }
        mountedRoot = null
        pinnedRoot = null
        dispatcher.reset()
        scope.dispose()
        runtime.dispose()
    }

    /** Coalesces off-main invalidations into one main-queue render hop. */
    private fun scheduleMainThreadRender() {
        if (!mainHopScheduled.compareAndSet(expectedValue = false, newValue = true)) return
        dispatch_async(dispatch_get_main_queue()) {
            mainHopScheduled.store(false)
            if (!disposed && runtime.hasPendingInvalidation) {
                renderUntilSettled()
            }
        }
    }

    /**
     * Pins only a single mounted root to [contentView]. Do not constrain [contentView] itself to
     * AppKit's private theme frame; doing so creates unsatisfiable constraints. Multi-child
     * fragments retain their natural size.
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

    private fun captureFocus(): FocusSnapshot? {
        val view = focusedView() ?: return null
        val field = view as? NSTextField
        return FocusSnapshot(view, view.identifier, field?.let(dispatcher::inputEventId), field?.currentEditor()?.selectedRange)
    }

    private fun restoreFocus(focus: FocusSnapshot?) {
        if (focus == null || focusedView() == focus.view) return
        val window = contentView.window ?: return
        val target = focus.identifier?.let { findViewByIdentifier(contentView, it) }
            ?: focus.inputEventId?.let(dispatcher::inputField)
            ?: return
        if (!window.makeFirstResponder(target)) return
        val field = target as? NSTextField ?: return
        focus.selection?.useContents {
            val start = location.coerceAtMost(field.stringValue.length.toULong())
            val selectionLength = length.coerceAtMost(field.stringValue.length.toULong() - start)
            field.currentEditor()?.setSelectedRange(NSMakeRange(start, selectionLength))
        }
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

@OptIn(ExperimentalForeignApi::class)
private data class FocusSnapshot(
    val view: NSView,
    val identifier: String?,
    val inputEventId: String?,
    val selection: CValue<NSRange>?,
)

@OptIn(ExperimentalForeignApi::class)
internal class AppKitHostAdapter(
    private val dispatcher: AppKitEventDispatcher,
    factories: Map<String, HostWidgetFactory<NSView>> = emptyMap(),
) : HostAdapter<NSView> {
    private val factories = factories.toMap()
    private val customWidgets = mutableMapOf<NSView, HostWidget<NSView>>()
    internal fun focus(view: NSView): Boolean = customWidgets[view]?.requestFocus() == true ||
        view.window?.makeFirstResponder(view) == true
    private val outlineEvents = mutableMapOf<AppKitOutlineTable, String>()
    private val outlineModels = mutableMapOf<AppKitOutlineTable, String>()
    private val sizeConstraints = mutableMapOf<Pair<NSView, String>, NSLayoutConstraint>()

    override fun canReuseHost(previous: HostNode, next: HostNode): Boolean =
        if (previous.tag in factories) previous.key == next.key
        else previous.tag != "textInput" || (previous.props["type"] == "password") == (next.props["type"] == "password")

    override fun createHost(node: HostNode): NSView {
        factories[node.tag]?.let { factory ->
            require(node.children.isEmpty()) { "Custom host ${node.tag} must be a leaf" }
            val widget = factory.create(node)
            try {
                widget.update(node)
                widget.view.translatesAutoresizingMaskIntoConstraints = false
                customWidgets[widget.view] = widget
                return widget.view
            } catch (failure: Throwable) { widget.dispose(); throw failure }
        }
        val view: NSView = when (node.tag) {
            "column", "row" -> NSStackView().apply {
                setOrientation(
                    if (node.tag == "column") NSUserInterfaceLayoutOrientationVertical
                    else NSUserInterfaceLayoutOrientationHorizontal,
                )
                setSpacing(8.0)
                setDetachesHiddenViews(false)
                if (node.tag == "column") setAlignment(NSLayoutAttributeWidth)
            }
            "button" -> makeButton(node)
            "checkbox" -> makeCheckbox(node)
            "textInput" -> makeTextField(node)
            "appkit:label" -> makeLabel(node.props["value"].orEmpty())
            "appkit:spacer" -> NSView().apply {
                setContentHuggingPriority(1f, NSUserInterfaceLayoutOrientationHorizontal)
            }
            OUTLINE_TABLE_TAG -> {
                lateinit var table: AppKitOutlineTable
                table = AppKitOutlineTable { event ->
                    outlineEvents[table]?.let { dispatcher.dispatch(it, event) }
                }
                bindOutline(table, node)
                table
            }
            else -> NSView()
        }
        view.translatesAutoresizingMaskIntoConstraints = false
        for ((name, value) in node.props) setProp(view, name, value, node)
        return view
    }

    override fun createText(node: TextNode): NSView = makeLabel(node.value)

    override fun setText(view: NSView, node: TextNode) {
        (view as? NSTextField)?.setStringValue(node.value)
    }

    override fun setProp(view: NSView, name: String, value: String, node: HostNode) {
        if (view in customWidgets) return
        when (name) {
            "enabled" -> (view as? NSControl)?.setEnabled(value != "false")
            "checked" -> (view as? NSButton)?.setState(if (value == "true") 1 else 0)
            "value" -> (view as? NSTextField)?.let { field ->
                if (field.stringValue != value) field.setStringValue(value)
            }
            "placeholder" -> (view as? NSTextField)?.setPlaceholderString(value)
            "spacing" -> (view as? NSStackView)?.setSpacing(value.toDouble())
            "padding" -> (view as? NSStackView)?.let { stack ->
                val inset = value.toDouble()
                stack.setEdgeInsets(NSEdgeInsetsMake(inset, inset, inset, inset))
            }
            "fontSize" -> (view as? NSTextField)?.setFont(NSFont.systemFontOfSize(value.toDouble()))
            "secondary" -> (view as? NSTextField)?.setTextColor(if (value == "true") NSColor.secondaryLabelColor else NSColor.labelColor)
            "width", "height", "minWidth", "minHeight" -> {
                sizeConstraints.remove(view to name)?.setActive(false)
                val anchor = if (name == "width" || name == "minWidth") view.widthAnchor else view.heightAnchor
                val constraint = if (name.startsWith("min")) anchor.constraintGreaterThanOrEqualToConstant(value.toDouble())
                    else anchor.constraintEqualToConstant(value.toDouble())
                constraint.setActive(true)
                sizeConstraints[view to name] = constraint
            }
            "event:onClick", "event:onToggle", "event:onSubmit" ->
                (view as? NSControl)?.let { control -> dispatcher.registerAction(control, value) }
            "event:onInput" ->
                (view as? NSTextField)?.let { field -> dispatcher.registerInput(field, value) }
        }
    }

    override fun removeProp(view: NSView, name: String, node: HostNode) {
        if (view in customWidgets) return
        when (name) {
            "enabled" -> (view as? NSControl)?.setEnabled(true)
            "checked" -> (view as? NSButton)?.setState(0)
            "value" -> (view as? NSTextField)?.setStringValue("")
            "placeholder" -> (view as? NSTextField)?.setPlaceholderString(null)
            "spacing" -> (view as? NSStackView)?.setSpacing(8.0)
            "padding" -> (view as? NSStackView)?.setEdgeInsets(NSEdgeInsetsMake(0.0, 0.0, 0.0, 0.0))
            "fontSize" -> (view as? NSTextField)?.setFont(NSFont.systemFontOfSize(13.0))
            "secondary" -> (view as? NSTextField)?.setTextColor(NSColor.labelColor)
            "width", "height", "minWidth", "minHeight" -> sizeConstraints.remove(view to name)?.setActive(false)
            "event:onClick", "event:onToggle", "event:onSubmit" ->
                (view as? NSControl)?.let(dispatcher::unregisterAction)
            "event:onInput" -> (view as? NSTextField)?.let(dispatcher::unregisterInput)
        }
    }

    override fun applySemantics(view: NSView, semantics: Semantics?, nativeTag: String) {
        view.setIdentifier(semantics?.testTag)
        view.setAccessibilityIdentifier(semantics?.testTag)
        view.setAccessibilityLabel(semantics?.label)
        if (view in customWidgets && semantics?.role == null) return
        if (view is AppKitOutlineTable) {
            view.outline.setAccessibilityIdentifier(semantics?.testTag)
            view.outline.setAccessibilityLabel(semantics?.label)
        } else {
            view.setAccessibilityRole(appKitAccessibilityRoleFor(semantics?.role, nativeTag))
        }
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

    override fun foldsChildren(tag: String): Boolean = tag in LEAF_WIDGET_TAGS || tag in factories

    override fun updateFoldedContent(view: NSView, node: HostNode) {
        customWidgets[view]?.let {
            require(node.children.isEmpty()) { "Custom host ${node.tag} must be a leaf" }
            it.update(node)
        }
        if (view is AppKitOutlineTable) bindOutline(view, node)
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
        customWidgets.remove(view)?.dispose()
        for (name in listOf("width", "height", "minWidth", "minHeight")) sizeConstraints.remove(view to name)?.setActive(false)
        (view as? NSControl)?.let(dispatcher::unregister)
        if (view is AppKitOutlineTable) {
            outlineEvents.remove(view)
            outlineModels.remove(view)
            view.dispose()
        }
    }

    private fun bindOutline(view: AppKitOutlineTable, node: HostNode) {
        val eventId = node.props["event:onOutline"]
        if (eventId == null) outlineEvents.remove(view) else outlineEvents[view] = eventId
        val encoded = requireNotNull(node.props["model"]) { "Outline table model is required" }
        if (outlineModels[view] != encoded) {
            view.update(Json.decodeFromString<OutlineTableModel>(encoded))
            outlineModels[view] = encoded
        }
    }

    private fun makeLabel(text: String): NSTextField {
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
        return (if (node.props["type"] == "password") NSSecureTextField() else NSTextField()).apply {
            setStringValue(node.props["value"].orEmpty())
            node.props["placeholder"]?.let { setPlaceholderString(it) }
            node.props["event:onInput"]?.let { eventId -> dispatcher.registerInput(this, eventId) }
            node.props["event:onSubmit"]?.let { eventId -> dispatcher.registerAction(this, eventId) }
        }
    }

    private fun makeButton(node: HostNode): NSButton {
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
        null -> when (nativeTag) {
            "button" -> "AXButton"
            "checkbox" -> "AXCheckBox"
            "textInput" -> "AXTextField"
            "text", "appkit:label" -> "AXStaticText"
            "column", "row" -> "AXGroup"
            else -> null
        }
    }
}

/** Tags whose text is represented by widget state rather than a child view. */
private val LEAF_WIDGET_TAGS: Set<String> = setOf("button", "checkbox", "textInput", "appkit:label", "appkit:spacer", OUTLINE_TABLE_TAG)

/** Shared action target and text-field delegate; each dispatch drains renders synchronously. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AppKitEventDispatcher(
    private val runtime: KineticaRuntime,
    private val renderUntilSettled: () -> Unit,
) : NSObject(), NSTextFieldDelegateProtocol {
    private var active = true

    @Suppress("unused")
    @ObjCAction
    fun clicked(sender: NSControl) {
        val eventId = actionBindings[sender] ?: return
        runtime.dispatch(eventId)
        if (active) renderUntilSettled()
    }

    override fun controlTextDidChange(obj: NSNotification) {
        val field = obj.`object` as? NSTextField ?: return
        val eventId = inputBindings[field] ?: return
        runtime.dispatch(eventId, field.stringValue)
        if (active) renderUntilSettled()
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

    fun inputEventId(field: NSTextField): String? = inputBindings[field]

    fun inputField(eventId: String): NSTextField? = inputBindings.entries.firstOrNull { it.value == eventId }?.key

    fun unregister(control: NSControl) {
        unregisterAction(control)
        if (control is NSTextField) unregisterInput(control)
    }

    fun unregisterAction(control: NSControl) {
        actionBindings.remove(control)
        if (control.target === this) {
            control.target = null
            control.action = null
        }
    }

    fun unregisterInput(control: NSTextField) {
        inputBindings.remove(control)
        if (control.delegate === this) control.delegate = null
    }

    fun dispatch(eventId: String, payload: Any?) {
        // Finish the native selection/expansion notification before reconciling its widget.
        dispatch_async(dispatch_get_main_queue()) {
            if (!active) return@dispatch_async
            runtime.dispatch(eventId, payload)
            if (active) renderUntilSettled()
        }
    }

    fun reset() {
        active = false
        actionBindings.clear()
        inputBindings.clear()
    }

    private val actionBindings: MutableMap<NSControl, String> = mutableMapOf()
    private val inputBindings: MutableMap<NSTextField, String> = mutableMapOf()
}

// Kotlin/Native forbids companion fields on Obj-C subclasses.
private const val ACTION_SELECTOR: String = "clicked:"
