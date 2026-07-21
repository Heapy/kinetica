package io.heapy.kinetica.render

import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.Node
import io.heapy.kinetica.TextNode

/**
 * Shadow tree the [Reconciler] maintains alongside the toolkit's widget tree: each mounted node
 * remembers the [Node] it was last patched to (the diff baseline) and, for hosts/texts, the
 * widget it owns. Fragments flatten — their children live directly in the enclosing container.
 */
public sealed class MountedNode<V : Any> {
    /** First widget of this subtree in container order, or null for view-less subtrees. */
    public abstract fun firstView(): V?

    public class Host<V : Any>(
        public var node: HostNode,
        public val view: V,
        public val children: MutableList<MountedNode<V>>,
    ) : MountedNode<V>() {
        override fun firstView(): V = view
    }

    public class Text<V : Any>(
        public var node: TextNode,
        public val view: V,
    ) : MountedNode<V>() {
        override fun firstView(): V = view
    }

    public class Fragment<V : Any>(
        public var node: FragmentNode,
        public val children: MutableList<MountedNode<V>>,
    ) : MountedNode<V>() {
        override fun firstView(): V? = children.firstNotNullOfOrNull { child -> child.firstView() }
    }

    /** ClientRef and other node kinds with no native representation. */
    public class Empty<V : Any>(
        public var node: Node,
    ) : MountedNode<V>() {
        override fun firstView(): V? = null
    }
}
