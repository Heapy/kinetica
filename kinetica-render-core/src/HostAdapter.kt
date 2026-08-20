package io.heapy.kinetica.render

import io.heapy.kinetica.HostNode
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode

/**
 * Toolkit operations used by [Reconciler]. The adapter owns widget and event lifecycle; the
 * reconciler owns tree matching. Focus orchestration remains the renderer's responsibility.
 */
public interface HostAdapter<V : Any> {
    public fun createHost(node: HostNode): V

    public fun createText(node: TextNode): V

    public fun setText(view: V, node: TextNode)

    /** Event props (`event:*`) must be rebound without triggering a render. */
    public fun setProp(view: V, name: String, value: String, node: HostNode)

    public fun removeProp(view: V, name: String, node: HostNode)

    /** Apply (or clear, when null) accessibility semantics. Must be idempotent. */
    public fun applySemantics(view: V, semantics: Semantics?, nativeTag: String)

    public fun insert(container: V, child: V, before: V?)

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

    public fun updateFoldedContent(view: V, node: HostNode) {}

    /**
     * Tags whose widget state can drift from the model. [syncControlledState] runs on every patch
     * visit, even when props compare equal.
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
