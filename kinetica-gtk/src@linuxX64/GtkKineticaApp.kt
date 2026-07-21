package io.heapy.kinetica.gtk

import gtk4.GCallback
import gtk4.GTK_ORIENTATION_HORIZONTAL
import gtk4.GTK_ORIENTATION_VERTICAL
import gtk4.GtkBox
import gtk4.GtkButton
import gtk4.GtkCheckButton
import gtk4.GtkEditable
import gtk4.GtkEntry
import gtk4.GtkLabel
import gtk4.GtkWidget
import gtk4.g_idle_add
import gtk4.g_signal_connect_data
import gtk4.gtk_box_append
import gtk4.gtk_box_insert_child_after
import gtk4.gtk_box_new
import gtk4.gtk_box_remove
import gtk4.gtk_box_reorder_child_after
import gtk4.gtk_button_new_with_label
import gtk4.gtk_button_set_label
import gtk4.gtk_check_button_get_active
import gtk4.gtk_check_button_new
import gtk4.gtk_check_button_set_active
import gtk4.gtk_editable_get_text
import gtk4.gtk_editable_set_text
import gtk4.gtk_entry_new
import gtk4.gtk_entry_set_placeholder_text
import gtk4.gtk_label_new
import gtk4.gtk_label_set_text
import gtk4.gtk_widget_get_prev_sibling
import gtk4.gtk_widget_set_name
import gtk4.gtk_widget_set_sensitive
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.render.HostAdapter
import io.heapy.kinetica.render.MountedNode
import io.heapy.kinetica.render.Reconciler
import kotlin.concurrent.atomics.AtomicBoolean
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString

/** The widget handle type the reconciler tracks: a plain GTK4 widget pointer. */
public typealias GtkWidgetPtr = CPointer<GtkWidget>

/**
 * Mount [content] into [container] (a `GtkBox` the window owns) and render once. Mirrors
 * `renderAppKitApp`/`mountKineticaApp`: a top-level function so the Kinetica compiler plugin
 * recognises the `@UiComponent`-typed [content] parameter from the consuming module.
 */
public fun renderGtkApp(
    container: GtkWidgetPtr,
    runtime: KineticaRuntime = KineticaRuntime(debug = true),
    content: @UiComponent ComponentScope.() -> Unit,
): GtkKineticaApp = GtkKineticaApp(container, runtime, content).also { it.renderUntilSettled() }

/**
 * Retained-mode GTK4 renderer on the shared `kinetica-render-core` reconciler (KNT-0047): the
 * mirror of `AppKitKineticaApp`. Signals are wired per widget through [GtkEventDispatcher]
 * (`clicked`/`toggled`/`changed`/`activate` → `KineticaRuntime.dispatch`); programmatic state
 * pushes are guarded so controlled-state resyncs don't re-enter the dispatcher (GTK emits
 * `changed`/`toggled` for programmatic writes too, unlike AppKit). Off-main invalidations are
 * marshalled onto the GTK main loop via `g_idle_add`, coalesced to one hop.
 */
public class GtkKineticaApp(
    private val container: GtkWidgetPtr,
    private val runtime: KineticaRuntime = KineticaRuntime(debug = true),
    private val content: @UiComponent ComponentScope.() -> Unit,
) {
    private val scope = ComponentScope(runtime)
    private val dispatcher = GtkEventDispatcher(runtime, ::renderUntilSettled)
    private val reconciler = Reconciler(GtkHostAdapter(dispatcher))
    private var mountedRoot: MountedNode<GtkWidgetPtr>? = null
    private val mainHopScheduled = AtomicBoolean(false)
    private val selfRef = StableRef.create(this)
    private val invalidationRegistration = runtime.onInvalidation { scheduleMainThreadRender() }

    public fun render() {
        val tree = runtime.render(scope, content).tree
        val previous = mountedRoot
        mountedRoot = if (previous == null) {
            reconciler.mount(tree, container)
        } else {
            reconciler.patch(previous, tree, container)
        }
    }

    /** Drain pending invalidations; called from the dispatcher on the GTK main thread. */
    public fun renderUntilSettled() {
        render()
        while (runtime.hasPendingInvalidation) {
            render()
        }
    }

    public fun dispose() {
        invalidationRegistration.dispose()
        mountedRoot?.let { mounted -> reconciler.unmount(mounted, container) }
        mountedRoot = null
        dispatcher.reset()
        scope.dispose()
        runtime.dispose()
        selfRef.dispose()
    }

    /**
     * Marshal an off-main invalidation onto the GTK main loop. `g_idle_add` callbacks run on the
     * main context; returning 0 (`G_SOURCE_REMOVE`) makes the source one-shot. Coalesced by the
     * same hop guard as the AppKit marshal.
     */
    private fun scheduleMainThreadRender() {
        if (!mainHopScheduled.compareAndSet(expectedValue = false, newValue = true)) return
        g_idle_add(
            staticCFunction { data: COpaquePointer? ->
                val app = data!!.asStableRef<GtkKineticaApp>().get()
                app.mainHopScheduled.store(false)
                if (app.runtime.hasPendingInvalidation) {
                    app.renderUntilSettled()
                }
                0
            },
            selfRef.asCPointer(),
        )
    }
}

/**
 * [HostAdapter] over GTK4. All containers are `GtkBox`es (column/row and the app root), so
 * child management uses the box API; GTK's anchor primitives are "after a sibling", so the
 * reconciler's before-anchors are translated via `gtk_widget_get_prev_sibling`.
 */
internal class GtkHostAdapter(
    private val dispatcher: GtkEventDispatcher,
) : HostAdapter<GtkWidgetPtr> {

    override fun createHost(node: HostNode): GtkWidgetPtr {
        val widget: GtkWidgetPtr = when (node.tag) {
            "column" -> gtk_box_new(GTK_ORIENTATION_VERTICAL, 8)!!
            "row" -> gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 8)!!
            "button" -> makeButton(node)
            "checkbox" -> makeCheckbox(node)
            "textInput" -> makeEntry(node)
            // Unknown tag: a neutral vertical box keeps the tree navigable.
            else -> gtk_box_new(GTK_ORIENTATION_VERTICAL, 0)!!
        }
        return widget
    }

    override fun createText(node: TextNode): GtkWidgetPtr = gtk_label_new(node.value)!!

    override fun setText(view: GtkWidgetPtr, node: TextNode) {
        gtk_label_set_text(view.reinterpret<GtkLabel>(), node.value)
    }

    override fun setProp(view: GtkWidgetPtr, name: String, value: String, node: HostNode) {
        when (name) {
            "enabled" -> gtk_widget_set_sensitive(view, if (value != "false") 1 else 0)
            "checked" -> dispatcher.suppressed {
                gtk_check_button_set_active(view.reinterpret<GtkCheckButton>(), if (value == "true") 1 else 0)
            }
            "value" -> dispatcher.suppressed {
                val editable = view.reinterpret<GtkEditable>()
                if (gtk_editable_get_text(editable)?.toKString() != value) {
                    gtk_editable_set_text(editable, value)
                }
            }
            "placeholder" -> gtk_entry_set_placeholder_text(view.reinterpret<GtkEntry>(), value)
            "event:onClick" -> dispatcher.register(view, value, signal = "clicked")
            "event:onToggle" -> dispatcher.register(view, value, signal = "toggled")
            "event:onInput" -> dispatcher.register(view, value, signal = "changed")
            "event:onSubmit" -> dispatcher.register(view, value, signal = "activate")
        }
    }

    override fun removeProp(view: GtkWidgetPtr, name: String, node: HostNode) {
        when (name) {
            "enabled" -> gtk_widget_set_sensitive(view, 1)
            "checked" -> dispatcher.suppressed {
                gtk_check_button_set_active(view.reinterpret<GtkCheckButton>(), 0)
            }
            "value" -> dispatcher.suppressed {
                gtk_editable_set_text(view.reinterpret<GtkEditable>(), "")
            }
            "placeholder" -> gtk_entry_set_placeholder_text(view.reinterpret<GtkEntry>(), null)
            "event:onClick" -> dispatcher.unregister(view, signal = "clicked")
            "event:onToggle" -> dispatcher.unregister(view, signal = "toggled")
            "event:onInput" -> dispatcher.unregister(view, signal = "changed")
            "event:onSubmit" -> dispatcher.unregister(view, signal = "activate")
        }
    }

    override fun applySemantics(view: GtkWidgetPtr, semantics: Semantics?, nativeTag: String) {
        // POC scope: the testTag lands as the widget name (queryable via gtk inspector/AT-SPI
        // path); role/label mapping to full AT-SPI properties is a follow-up.
        gtk_widget_set_name(view, semantics?.testTag)
    }

    override fun insert(container: GtkWidgetPtr, child: GtkWidgetPtr, before: GtkWidgetPtr?) {
        val box = container.reinterpret<GtkBox>()
        if (before == null) {
            gtk_box_append(box, child)
        } else {
            // GTK anchors are "after"; insert after the anchor's previous sibling (null = prepend).
            gtk_box_insert_child_after(box, child, gtk_widget_get_prev_sibling(before))
        }
    }

    override fun remove(container: GtkWidgetPtr, child: GtkWidgetPtr) {
        gtk_box_remove(container.reinterpret<GtkBox>(), child)
    }

    override fun move(container: GtkWidgetPtr, child: GtkWidgetPtr, before: GtkWidgetPtr?) {
        val box = container.reinterpret<GtkBox>()
        val sibling = if (before == null) null else gtk_widget_get_prev_sibling(before)
        if (before == null) {
            // Move to the end: reorder after the current last child is awkward to compute; the
            // remove+append pair is equivalent for a widget already owned by this box.
            gtk_box_remove(box, child)
            gtk_box_append(box, child)
        } else {
            gtk_box_reorder_child_after(box, child, sibling)
        }
    }

    override fun foldsChildren(tag: String): Boolean = tag in LEAF_WIDGET_TAGS

    override fun updateFoldedContent(view: GtkWidgetPtr, node: HostNode) {
        if (node.tag == "button") {
            gtk_button_set_label(view.reinterpret<GtkButton>(), foldedCaption(node))
        }
    }

    override fun isControlledTag(tag: String): Boolean = tag == "textInput" || tag == "checkbox"

    override fun syncControlledState(view: GtkWidgetPtr, node: HostNode) {
        when (node.tag) {
            "textInput" -> dispatcher.suppressed {
                val editable = view.reinterpret<GtkEditable>()
                val value = node.props["value"].orEmpty()
                if (gtk_editable_get_text(editable)?.toKString() != value) {
                    gtk_editable_set_text(editable, value)
                }
            }
            "checkbox" -> dispatcher.suppressed {
                val check = view.reinterpret<GtkCheckButton>()
                val desired = if (node.props["checked"] == "true") 1 else 0
                if (gtk_check_button_get_active(check) != desired) {
                    gtk_check_button_set_active(check, desired)
                }
            }
        }
    }

    override fun teardownHost(view: GtkWidgetPtr, node: HostNode) {
        dispatcher.unregisterAll(view)
    }

    // --- tag → widget factories --------------------------------------------------------------

    private fun makeButton(node: HostNode): GtkWidgetPtr {
        val button = gtk_button_new_with_label(foldedCaption(node))!!
        if (node.props["enabled"] == "false") gtk_widget_set_sensitive(button, 0)
        node.props["event:onClick"]?.let { eventId -> dispatcher.register(button, eventId, signal = "clicked") }
        return button
    }

    private fun makeCheckbox(node: HostNode): GtkWidgetPtr {
        val check = gtk_check_button_new()!!
        if (node.props["checked"] == "true") {
            gtk_check_button_set_active(check.reinterpret<GtkCheckButton>(), 1)
        }
        if (node.props["enabled"] == "false") gtk_widget_set_sensitive(check, 0)
        node.props["event:onToggle"]?.let { eventId -> dispatcher.register(check, eventId, signal = "toggled") }
        return check
    }

    private fun makeEntry(node: HostNode): GtkWidgetPtr {
        val entry = gtk_entry_new()!!
        dispatcher.suppressed {
            gtk_editable_set_text(entry.reinterpret<GtkEditable>(), node.props["value"].orEmpty())
        }
        node.props["placeholder"]?.let { placeholder ->
            gtk_entry_set_placeholder_text(entry.reinterpret<GtkEntry>(), placeholder)
        }
        node.props["event:onInput"]?.let { eventId -> dispatcher.register(entry, eventId, signal = "changed") }
        node.props["event:onSubmit"]?.let { eventId -> dispatcher.register(entry, eventId, signal = "activate") }
        return entry
    }

    private fun foldedCaption(node: HostNode): String =
        (node.children.singleOrNull() as? TextNode)?.value.orEmpty()
}

private val LEAF_WIDGET_TAGS: Set<String> = setOf("button", "checkbox", "textInput")

/**
 * Signal hub: widget+signal → event id. One [StableRef]'d instance is the `user_data` of every
 * `g_signal_connect_data`; the per-signal static handlers (they cannot capture — see
 * [staticCFunction]) look the binding up and dispatch. [suppressed] guards programmatic
 * writes — GTK emits `changed`/`toggled` for those too, and echoing them into
 * [KineticaRuntime.dispatch] would loop the render.
 */
internal class GtkEventDispatcher(
    private val runtime: KineticaRuntime,
    private val renderUntilSettled: () -> Unit,
) {
    private val bindings = mutableMapOf<Pair<GtkWidgetPtr, String>, String>()
    private val connected = mutableSetOf<Pair<GtkWidgetPtr, String>>()
    private val ref = StableRef.create(this)
    private var suppressDepth = 0

    fun suppressed(block: () -> Unit) {
        suppressDepth++
        try {
            block()
        } finally {
            suppressDepth--
        }
    }

    fun register(widget: GtkWidgetPtr, eventId: String, signal: String) {
        val slot = widget to signal
        bindings[slot] = eventId
        if (slot in connected) return
        connected += slot
        val handler: GCallback = when (signal) {
            "clicked" -> ClickedHandler
            "toggled" -> ToggledHandler
            "activate" -> ActivateHandler
            else -> ChangedHandler
        }
        g_signal_connect_data(widget, signal, handler, ref.asCPointer(), null, 0u)
    }

    fun unregister(widget: GtkWidgetPtr, signal: String) {
        bindings.remove(widget to signal)
    }

    fun unregisterAll(widget: GtkWidgetPtr) {
        bindings.keys.filter { (boundWidget, _) -> boundWidget == widget }.forEach(bindings::remove)
        connected.removeAll { (boundWidget, _) -> boundWidget == widget }
    }

    fun reset() {
        bindings.clear()
        connected.clear()
    }

    internal fun fire(widget: GtkWidgetPtr?, signal: String) {
        if (suppressDepth > 0 || widget == null) return
        val eventId = bindings[widget to signal] ?: return
        runtime.dispatch(eventId)
        renderUntilSettled()
    }

    internal fun fireInput(widget: GtkWidgetPtr?) {
        if (suppressDepth > 0 || widget == null) return
        val eventId = bindings[widget to "changed"] ?: return
        val text = gtk_editable_get_text(widget.reinterpret<GtkEditable>())?.toKString().orEmpty()
        runtime.dispatch(eventId, text)
        renderUntilSettled()
    }
}

// staticCFunction lambdas cannot capture, so each signal gets its own top-level handler that
// knows the signal name as a literal; the dispatcher instance travels through user_data.

private val ClickedHandler: GCallback = staticCFunction { widget: GtkWidgetPtr?, data: COpaquePointer? ->
    data?.asStableRef<GtkEventDispatcher>()?.get()?.fire(widget, "clicked")
}.reinterpret()

private val ToggledHandler: GCallback = staticCFunction { widget: GtkWidgetPtr?, data: COpaquePointer? ->
    data?.asStableRef<GtkEventDispatcher>()?.get()?.fire(widget, "toggled")
}.reinterpret()

private val ActivateHandler: GCallback = staticCFunction { widget: GtkWidgetPtr?, data: COpaquePointer? ->
    data?.asStableRef<GtkEventDispatcher>()?.get()?.fire(widget, "activate")
}.reinterpret()

private val ChangedHandler: GCallback = staticCFunction { widget: GtkWidgetPtr?, data: COpaquePointer? ->
    data?.asStableRef<GtkEventDispatcher>()?.get()?.fireInput(widget)
}.reinterpret()
