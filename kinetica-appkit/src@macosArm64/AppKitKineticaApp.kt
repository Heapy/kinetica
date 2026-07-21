package io.heapy.kinetica.appkit

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.materializeDeep
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSBezelStyleRounded
import platform.AppKit.NSButton
import platform.AppKit.NSSwitchButton
import platform.AppKit.NSControl
import platform.AppKit.NSStackView
import platform.AppKit.NSTextField
import platform.AppKit.NSUserInterfaceLayoutOrientationHorizontal
import platform.AppKit.NSUserInterfaceLayoutOrientationVertical
import platform.AppKit.NSView
import platform.AppKit.bottomAnchor
import platform.AppKit.leadingAnchor
import platform.AppKit.topAnchor
import platform.AppKit.trailingAnchor
import platform.AppKit.translatesAutoresizingMaskIntoConstraints
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject

/**
 * Retained-mode AppKit renderer: the first render mounts a shadow tree ([Mounted]) of AppKit views
 * inside the supplied [contentView]; every subsequent render re-runs the Kinetica render loop and
 * patches the views by diffing the new [Node] tree against the previous one.
 *
 * Phase 1 simplification: [patch] does a full teardown/rebuild on each invalidation. State lives in
 * Kinetica cells, not in views, so this is correct — just cosmetically blunt. Incremental diffing
 * (keyed-LIS / regioned / positional reconciliation, mirroring the DOM renderer in `kinetica-browser`)
 * is a planned follow-up.
 *
 * Event wiring: Kinetica encodes host events as `event:<name>` props whose value is an opaque event
 * id resolvable via [KineticaRuntime.dispatch]. The renderer installs an [AppKitEventDispatcher]
 * (an [NSObject] target) as the target of every actionable [NSControl]; the dispatcher maps the
 * sender back to its event id and dispatches it, then the render loop re-renders synchronously while
 * the runtime reports a pending invalidation.
 *
 * Obj-C interop note: AppKit's `BOOL`-backed properties (`isEditable`, `isBordered`, `isEnabled`,
 * `drawsBackground`, ...) and the `NS_ENUM` constants (`NSBezelStyle`, `NSButtonType`) are exposed
 * by Kotlin/Native as explicit `setX()` setters and top-level `val`s respectively — not as Kotlin
 * properties or nested enum cases. The AppKit `NSBezelStyle`/`NSButtonType` classes themselves are
 * used only as types for the setters.
 */
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

@OptIn(ExperimentalForeignApi::class)
public class AppKitKineticaApp(
    private val contentView: NSView,
    private val runtime: KineticaRuntime = KineticaRuntime(debug = true),
    private val content: @UiComponent ComponentScope.() -> Unit,
) {
    private val scope = ComponentScope(runtime)
    private val dispatcher = AppKitEventDispatcher(runtime, ::renderUntilSettled)
    private var mountedRoot: Mounted? = null

    /**
     * Render once. Use [renderUntilSettled] from event handlers and after the initial mount so the
     * UI reflects every invalidation produced synchronously by the render pass itself.
     */
    public fun render() {
        val tree = runtime.render(scope, content).tree
        mountedRoot = patch(mountedRoot, tree, contentView)
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
        mountedRoot?.teardown(contentView)
        mountedRoot = null
        dispatcher.reset()
        scope.dispose()
        runtime.dispose()
    }

    // --- patching ----------------------------------------------------------------------------------------------

    private fun patch(previous: Mounted?, node: Node, container: NSView): Mounted {
        if (previous != null) {
            previous.teardown(container)
        }
        // Full teardown/rebuild recreates every control, so the dispatcher's old sender→eventId
        // bindings are all dead. Drop them before remounting, otherwise the map (and the NSControls
        // it strongly keys on) leaks one entry per actionable widget on every invalidation.
        dispatcher.reset()
        val mounted = mount(node, container)
        pinRootToContainer(mounted, container)
        return mounted
    }

    /**
     * Fill [container] — the NSWindow's contentView, whose frame AppKit itself manages — with the
     * mounted Kinetica root by pinning the root view to the container's edges.
     *
     * We deliberately do NOT flip the contentView's own `translatesAutoresizingMaskIntoConstraints`
     * or constrain it to its superview (the private theme frame): AppKit owns the contentView's
     * layout, and adding four equality constraints against the frame view produces
     * unsatisfiable-constraint breaks and can wedge live window resizing. Pinning the child we own is
     * conflict-free. Re-pinned every render because the full-rebuild patch creates a fresh root view
     * each time (its constraints die with the old view on `removeFromSuperview`). Only a single-view
     * root (the usual case: one top-level container) has a well-defined "fill"; a multi-child fragment
     * root is left at its natural size.
     */
    private fun pinRootToContainer(root: Mounted, container: NSView) {
        val view = root.singleViewOrNull() ?: return
        view.leadingAnchor.constraintEqualToAnchor(container.leadingAnchor).setActive(true)
        view.trailingAnchor.constraintEqualToAnchor(container.trailingAnchor).setActive(true)
        view.topAnchor.constraintEqualToAnchor(container.topAnchor).setActive(true)
        view.bottomAnchor.constraintEqualToAnchor(container.bottomAnchor).setActive(true)
    }

    private fun mount(node: Node, container: NSView): Mounted {
        val normalized = node.materializeDeep()
        return mountChild(normalized, container)
    }

    private fun mountChild(node: Node, container: NSView): Mounted {
        // Fragments flatten into the parent; their children mount directly into the container.
        if (node is FragmentNode) {
            val children = node.children.map { mountChild(it, container) }
            return Mounted.Fragment(children)
        }
        if (node is TextNode) {
            val label = makeLabel(node.value)
            applySemantics(label, node.semantics, nativeTag = "text")
            addChild(container, label)
            return Mounted.Text(label, node)
        }
        if (node is HostNode) {
            val mounted = mountHost(node, container)
            applySemantics(mounted.view(), node.semantics, nativeTag = node.tag)
            return mounted
        }
        // TemplateNode / ClientRef — materializeDeep has already inlined templates; ClientRef has no
        // native representation in this renderer, so it is rendered as an empty fragment.
        return Mounted.Fragment(emptyList())
    }

    private fun mountHost(node: HostNode, container: NSView): Mounted.Host {
        val view: NSView = when (node.tag) {
            "column", "row" -> {
                NSStackView().apply {
                    setOrientation(
                        if (node.tag == "column") NSUserInterfaceLayoutOrientationVertical
                        else NSUserInterfaceLayoutOrientationHorizontal,
                    )
                    setSpacing(8.0)
                    translatesAutoresizingMaskIntoConstraints = false
                }
            }
            "button" -> makeButton(node)
            "checkbox" -> makeCheckbox(node)
            "textInput" -> makeTextField(node)
            else -> {
                // Unknown tag: fall back to a neutral container so the tree is still navigable.
                NSView().apply { translatesAutoresizingMaskIntoConstraints = false }
            }
        }
        addChild(container, view)
        // Leaf widgets fold their text children into the widget itself (button title, text field
        // value). Mounting those children as separate views would double-render the caption (once
        // as NSButton.title, once as an overlaid NSTextField), so they are skipped here.
        val children = if (node.tag in LEAF_WIDGET_TAGS) {
            emptyList()
        } else {
            node.children.map { mountChild(it, view) }
        }
        return Mounted.Host(view, node, children)
    }

    private fun addChild(container: NSView, child: NSView) {
        // NSStackView lays out its arranged subviews via Auto Layout constraints it owns; a plain
        // addSubview bypasses that and the child would float at (0,0). So stack containers receive
        // children via addArrangedSubview.
        if (container is NSStackView) {
            container.addArrangedSubview(child)
            return
        }
        container.addSubview(child)
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
            translatesAutoresizingMaskIntoConstraints = false
        }
    }

    private fun makeButton(node: HostNode): NSButton {
        // The button's title comes from its single text child (mirrors the DOM renderer, where a
        // <button> wraps a text node). We extract it here so the native bezel renders the caption.
        val caption = (node.children.singleOrNull() as? TextNode)?.value.orEmpty()
        return NSButton().apply {
            setTitle(caption)
            setBordered(true)
            setBezelStyle(NSBezelStyleRounded)
            translatesAutoresizingMaskIntoConstraints = false
            if (node.props["enabled"] == "false") setEnabled(false)
            wireEvent(node, "event:onClick", this)
        }
    }

    private fun makeCheckbox(node: HostNode): NSButton {
        return NSButton().apply {
            setTitle("")
            setButtonType(NSSwitchButton)
            setState(if (node.props["checked"] == "true") 1 else 0)
            translatesAutoresizingMaskIntoConstraints = false
            if (node.props["enabled"] == "false") setEnabled(false)
            wireEvent(node, "event:onToggle", this)
        }
    }

    /**
     * Register [propName]'s event id with the dispatcher and wire the [control] to call it. If the
     * prop is absent the control simply has no action.
     */
    private fun wireEvent(node: HostNode, propName: String, control: NSControl) {
        val eventId = node.props[propName] ?: return
        dispatcher.register(control, eventId)
        control.target = dispatcher
        control.action = NSSelectorFromString(ACTION_SELECTOR)
    }

    // --- semantics → accessibility -----------------------------------------------------------------------------

    private fun applySemantics(view: NSView, semantics: Semantics?, nativeTag: String) {
        val s = semantics ?: return
        s.testTag?.let { view.setIdentifier(it) }
        s.label?.let { view.setAccessibilityLabel(it) }
        // AppKit exposes roles via the accessibility API; for the POC we map only the role names we
        // actually emit from the DSL, mirroring BrowserMapping.browserRoleFor.
        view.setAccessibilityRole(appKitAccessibilityRoleFor(s.role, nativeTag))
    }

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

    // --- mounted shadow tree -----------------------------------------------------------------------------------

    // --- mounted shadow tree -----------------------------------------------------------------------------------

    private sealed interface Mounted {
        fun teardown(container: NSView)

        /** The single native view of this subtree, or null when it has zero or many top-level views. */
        fun singleViewOrNull(): NSView?

        class Host(
            private val view: NSView,
            @Suppress("unused") private val node: HostNode,
            private val children: List<Mounted>,
        ) : Mounted {
            fun view(): NSView = view
            override fun singleViewOrNull(): NSView = view
            override fun teardown(container: NSView) {
                children.forEach { it.teardown(view) }
                detachFrom(view, container)
            }
        }

        class Text(
            private val view: NSView,
            @Suppress("unused") private val node: TextNode,
        ) : Mounted {
            fun view(): NSView = view
            override fun singleViewOrNull(): NSView = view
            override fun teardown(container: NSView) {
                detachFrom(view, container)
            }
        }

        class Fragment(private val children: List<Mounted>) : Mounted {
            override fun singleViewOrNull(): NSView? = children.singleOrNull()?.singleViewOrNull()
            override fun teardown(container: NSView) {
                children.forEach { it.teardown(container) }
            }
        }
    }
}

/**
 * Detach [view] from [container]. For an [NSStackView] parent this must clear the arranged list
 * (via `removeArrangedSubview`) in addition to `removeFromSuperview` — otherwise a re-mount after
 * a teardown/rebuild leaves stale entries in the arranged list and the stack renders duplicates.
 */
@OptIn(ExperimentalForeignApi::class)
private fun detachFrom(view: NSView, container: NSView) {
    if (container is NSStackView) container.removeArrangedSubview(view)
    view.removeFromSuperview()
}

/**
 * Host tags whose text children are absorbed into the widget's own caption/value rather than
 * mounted as separate views. Mirrors how the DOM renderer treats `<button>+</button>` (the "+" is
 * the button's text content); on AppKit the `NSButton.title` is the whole caption, so the child
 * text node must NOT also be mounted as an NSTextField on top of it.
 */
private val LEAF_WIDGET_TAGS: Set<String> = setOf("button", "checkbox", "textInput")

/**
 * The single [NSObject] target shared by every actionable [NSControl]. AppKit's target-action
 * machinery invokes [clicked] with the sender; the dispatcher maps the sender back to the event id
 * it was registered with, hands it to the runtime, and then drives the render loop until settled.
 *
 * One target (rather than one per control) keeps the Obj-C method-dispatch surface tiny: Kinetica
 * emits a high event churn, and registering many tiny NSObject subclasses on Native is wasteful.
 */
@OptIn(ExperimentalForeignApi::class)
private class AppKitEventDispatcher(
    private val runtime: KineticaRuntime,
    private val renderUntilSettled: () -> Unit,
) : NSObject() {

    @Suppress("unused")
    @ObjCAction
    fun clicked(sender: NSControl) {
        val eventId = bindings[sender] ?: return
        runtime.dispatch(eventId)
        renderUntilSettled()
    }

    fun register(control: NSControl, eventId: String) {
        bindings[control] = eventId
    }

    /** Drop all sender→eventId bindings so dead NSControls (map keys) don't leak across rebuilds. */
    fun reset() {
        bindings.clear()
    }

    // Kotlin/Native forbids companion-object fields on Obj-C subclasses, so the action selector name
    // is a top-level constant instead.
    private val bindings: MutableMap<NSControl, String> = mutableMapOf()
}

/** The Obj-C selector AppKit invokes on [AppKitEventDispatcher]; mirrors the `clicked(sender:)` method. */
private const val ACTION_SELECTOR: String = "clicked:"
