package io.heapy.kinetica.render

import io.heapy.kinetica.HostNode

/** An externally rendered leaf. The widget owns its descendants, input, and drawing lifecycle. */
public interface HostWidget<V : Any> {
    public val view: V
    public fun update(node: HostNode)
    /** Let a compound host focus its internal editor instead of its nonfocusable root. */
    public fun requestFocus(): Boolean = false
    public fun dispose()
}

/** Registered by host tag on a renderer. Factories must return a fresh, unmounted widget. */
public fun interface HostWidgetFactory<V : Any> {
    public fun create(node: HostNode): HostWidget<V>
}
