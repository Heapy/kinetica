package io.heapy.kinetica.render

import io.heapy.kinetica.HostNode
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode

/**
 * The leaf-operation surface a native widget toolkit implements to plug into the shared
 * [Reconciler]. `V` is the toolkit's view/widget type (NSView, GtkWidget wrapper, a test stub).
 *
 * The adapter owns everything toolkit-specific: widget construction (including reading the
 * initial props/caption from the node), prop application, child insertion order, event wiring
 * and teardown. The reconciler owns everything tree-shaped: which child matches which, what
 * moves, what mounts, what unmounts.
 *
 * Deliberately NOT part of this contract (see KNT-0046): template cloning and event delegation
 * (DOM-only idioms — native input is `materializeDeep`-normalized and events are wired
 * per-widget), and focus capture/restore (the renderer orchestrates it around [Reconciler.patch];
 * retained views mean focus survives any patch that doesn't replace the focused widget).
 */
public interface HostAdapter<V : Any> {
    /** Create the widget for [node], applying its props (and folded caption, if any). */
    public fun createHost(node: HostNode): V

    /** Create the widget for a text node (a label in most toolkits). */
    public fun createText(node: TextNode): V

    /** Update an existing text widget to [node]'s value (and decorations). */
    public fun setText(view: V, node: TextNode)

    /** Apply a changed prop. Event props (`event:*`) arrive here too — rebind, don't render. */
    public fun setProp(view: V, name: String, value: String, node: HostNode)

    /** Remove a prop that disappeared from the node. */
    public fun removeProp(view: V, name: String, node: HostNode)

    /** Apply (or clear, when null) accessibility semantics. Must be idempotent. */
    public fun applySemantics(view: V, semantics: Semantics?, nativeTag: String)

    /** Insert [child] into [container] before [before]; null anchor appends. */
    public fun insert(container: V, child: V, before: V?)

    /** Physically remove [child] from [container]. */
    public fun remove(container: V, child: V)

    /**
     * Move an already-mounted [child] before [before]. Default is remove+insert; toolkits with
     * a native reorder primitive (NSStackView insertArrangedSubview:atIndex:,
     * gtk_box_reorder_child_after) should override to preserve widget state.
     */
    public fun move(container: V, child: V, before: V?) {
        remove(container, child)
        insert(container, child, before)
    }

    /**
     * Tags whose text children fold into the widget itself (button caption, field value).
     * The reconciler mounts no child views for them and calls [updateFoldedContent] on patch.
     */
    public fun foldsChildren(tag: String): Boolean = false

    /** Re-apply folded content (e.g. button caption) from the fresh node. */
    public fun updateFoldedContent(view: V, node: HostNode) {}

    /**
     * Tags whose widget holds user-mutable state that can drift from the model (text fields,
     * checkboxes). The reconciler calls [syncControlledState] on EVERY patch visit of such a
     * host — even when props compare equal — mirroring the browser renderer's controlled-input
     * resync (KNT-0034).
     */
    public fun isControlledTag(tag: String): Boolean = false

    /** Push model state back into a controlled widget. Must be cheap when already in sync. */
    public fun syncControlledState(view: V, node: HostNode) {}

    /**
     * Per-host cleanup on unmount (event unbinding, dispatcher deregistration). Called for
     * EVERY host in a removed subtree, innermost first; physical removal happens only at the
     * subtree root.
     */
    public fun teardownHost(view: V, node: HostNode) {}
}
