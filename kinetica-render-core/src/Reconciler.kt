package io.heapy.kinetica.render

import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.Node
import io.heapy.kinetica.NodeFlags
import io.heapy.kinetica.TemplateNode
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.materialize
import io.heapy.kinetica.materializeDeep
import io.heapy.kinetica.reconcileKey

/**
 * Backend-agnostic retained reconciler for native renderers (KNT-0046): diffs a fresh
 * [Node] tree against the [MountedNode] shadow tree and applies the minimal widget mutations
 * through a [HostAdapter]. Child reconciliation follows the browser renderer's strategy
 * (head/tail converge scan, keyed middle with LIS-stable moves, positional fallback) minus the
 * DOM-only idioms — no template cloning (input is [materializeDeep]-normalized), no event
 * delegation, no regioned pass (deferred to the KNT-0025 unified op-model).
 *
 * Perf posture: correctness-first, small-tree-honest — no scratch pooling, no identity
 * short-circuit (every subtree is visited, which also makes controlled-state resync trivial).
 * Revisit if native trees grow bench-relevant; the browser keeps its own tuned path by design.
 */
public class Reconciler<V : Any>(
    private val adapter: HostAdapter<V>,
) {
    /** Mount [node] (normalized first) into [container], appending at the end. */
    public fun mount(node: Node, container: V): MountedNode<V> =
        mountChild(node.materializeDeep(), container, before = null)

    /**
     * Patch [previous] to render [next] (normalized first). Returns the mounted root — the same
     * instance when the root kind matched, a fresh one when the root was replaced.
     */
    public fun patch(previous: MountedNode<V>, next: Node, container: V): MountedNode<V> =
        patchChild(previous, next.materializeDeep(), container, endAnchor = null)

    /** Tear down the whole mounted subtree: adapter cleanup + physical removal. */
    public fun unmount(mounted: MountedNode<V>, container: V) {
        unmountChild(mounted, container, removePhysically = true)
    }

    // --- mounting ----------------------------------------------------------------------------

    private fun mountChild(node: Node, container: V, before: V?): MountedNode<V> = when (node) {
        is HostNode -> {
            val view = adapter.createHost(node)
            adapter.applySemantics(view, node.semantics, node.tag)
            val children: MutableList<MountedNode<V>> = if (adapter.foldsChildren(node.tag)) {
                mutableListOf()
            } else {
                node.children.mapTo(mutableListOf()) { child -> mountChild(child, view, before = null) }
            }
            adapter.insert(container, view, before)
            MountedNode.Host(node, view, children)
        }
        is TextNode -> {
            val view = adapter.createText(node)
            adapter.applySemantics(view, node.semantics, nativeTag = "text")
            adapter.insert(container, view, before)
            MountedNode.Text(node, view)
        }
        is FragmentNode -> {
            // Children flatten into the parent container; inserting each before the same anchor
            // preserves their relative order.
            val children = node.children.mapTo(mutableListOf()) { child ->
                mountChild(child, container, before)
            }
            MountedNode.Fragment(node, children)
        }
        is ClientRef -> MountedNode.Empty(node)
        // Unreachable after materializeDeep; kept defensive rather than throwing mid-render.
        is TemplateNode -> mountChild(node.materialize().materializeDeep(), container, before)
    }

    // --- patching ----------------------------------------------------------------------------

    private fun patchChild(
        previous: MountedNode<V>,
        next: Node,
        container: V,
        endAnchor: V?,
    ): MountedNode<V> = when {
        previous is MountedNode.Host<V> && next is HostNode && previous.node.tag == next.tag ->
            patchHost(previous, next)
        previous is MountedNode.Text<V> && next is TextNode ->
            patchText(previous, next)
        previous is MountedNode.Fragment<V> && next is FragmentNode -> {
            patchChildren(
                mounted = previous.children,
                nextNodes = next.children,
                container = container,
                certifiedKeyed = false,
                endAnchor = endAnchor,
            )
            previous.node = next
            previous
        }
        previous is MountedNode.Empty<V> && next is ClientRef -> {
            previous.node = next
            previous
        }
        else -> replaceChild(previous, next, container, endAnchor)
    }

    private fun patchHost(previous: MountedNode.Host<V>, next: HostNode): MountedNode.Host<V> {
        val view = previous.view
        val old = previous.node
        patchProps(view, old, next)
        if (old.semantics != next.semantics) {
            adapter.applySemantics(view, next.semantics, next.tag)
        }
        if (adapter.foldsChildren(next.tag)) {
            adapter.updateFoldedContent(view, next)
        } else {
            val certifiedKeyed =
                old.flags and NodeFlags.CHILDREN_KEYED != 0 &&
                    next.flags and NodeFlags.CHILDREN_KEYED != 0
            patchChildren(
                mounted = previous.children,
                nextNodes = next.children,
                container = view,
                certifiedKeyed = certifiedKeyed,
                endAnchor = null,
            )
        }
        if (adapter.isControlledTag(next.tag)) {
            // Always resync — the widget may have drifted even when props compare equal
            // (mirrors the browser's controlled-input resync, KNT-0034).
            adapter.syncControlledState(view, next)
        }
        previous.node = next
        return previous
    }

    private fun patchProps(view: V, old: HostNode, next: HostNode) {
        if (old.props === next.props) return
        for ((name, value) in next.props) {
            if (old.props[name] != value) {
                adapter.setProp(view, name, value, next)
            }
        }
        for (name in old.props.keys) {
            if (name !in next.props) {
                adapter.removeProp(view, name, next)
            }
        }
    }

    private fun patchText(previous: MountedNode.Text<V>, next: TextNode): MountedNode.Text<V> {
        val old = previous.node
        if (old.value != next.value || old.strikethrough != next.strikethrough) {
            adapter.setText(previous.view, next)
        }
        if (old.semantics != next.semantics) {
            adapter.applySemantics(previous.view, next.semantics, nativeTag = "text")
        }
        previous.node = next
        return previous
    }

    private fun replaceChild(
        previous: MountedNode<V>,
        next: Node,
        container: V,
        endAnchor: V?,
    ): MountedNode<V> {
        // Mount the replacement before the old subtree's first view so it lands in the old
        // position, then tear the old one down. View-less old subtrees fall back to the block's
        // end anchor.
        val anchor = previous.firstView() ?: endAnchor
        val mounted = mountChild(next, container, anchor)
        unmountChild(previous, container, removePhysically = true)
        return mounted
    }

    // --- child lists -------------------------------------------------------------------------

    /**
     * Reconcile [mounted] (mutated in place) against [nextNodes] inside [container].
     * [endAnchor] is the widget that follows this child block in the container — non-null only
     * when the block belongs to a flattened fragment sitting between siblings.
     */
    private fun patchChildren(
        mounted: MutableList<MountedNode<V>>,
        nextNodes: List<Node>,
        container: V,
        certifiedKeyed: Boolean,
        endAnchor: V?,
    ) {
        val oldSize = mounted.size
        val newSize = nextNodes.size
        if (oldSize == 0 && newSize == 0) return

        // Head converge scan.
        var head = 0
        val maxScan = minOf(oldSize, newSize)
        while (head < maxScan && hasSamePatchTarget(mounted[head], nextNodes[head])) {
            head++
        }
        // Tail converge scan (never overlapping the head region).
        var tail = 0
        while (
            tail < maxScan - head &&
            hasSamePatchTarget(mounted[oldSize - 1 - tail], nextNodes[newSize - 1 - tail])
        ) {
            tail++
        }

        val oldMidCount = oldSize - head - tail
        val newMidCount = newSize - head - tail

        // Slot plan for the new list: a matched old child (patched, maybe moved) or null (mount).
        val pairs = arrayOfNulls<MountedNode<V>>(newSize)
        val stable = BooleanArray(newSize) { true }
        for (index in 0 until head) {
            pairs[index] = mounted[index]
        }
        for (offset in 0 until tail) {
            pairs[newSize - 1 - offset] = mounted[oldSize - 1 - offset]
        }

        if (oldMidCount > 0 && newMidCount > 0 &&
            (certifiedKeyed || shouldReconcileKeyed(mounted, nextNodes, head, oldMidCount, newMidCount))
        ) {
            // Keyed middle: match by key, then keep the LIS of matched old positions in place
            // and move everything else.
            val keyToNewIndex = HashMap<String, Int>(newMidCount)
            for (offset in 0 until newMidCount) {
                val key = nextNodes[head + offset].reconcileKey ?: continue
                keyToNewIndex[key] = offset
            }
            val sourceOldIndex = IntArray(newMidCount) { -1 }
            for (offset in 0 until oldMidCount) {
                val old = mounted[head + offset]
                val key = (old as? MountedNode.Host<V>)?.node?.key
                val newOffset = key?.let(keyToNewIndex::get)
                if (newOffset != null && hasSamePatchTarget(old, nextNodes[head + newOffset])) {
                    pairs[head + newOffset] = old
                    sourceOldIndex[newOffset] = offset
                } else {
                    unmountChild(old, container, removePhysically = true)
                }
            }
            val lis = longestIncreasingSubsequenceIndices(sourceOldIndex)
            for (offset in 0 until newMidCount) {
                stable[head + offset] = false
            }
            for (index in lis) {
                stable[head + index] = true
            }
        } else {
            // Positional middle: patch index-aligned (mismatches replace in place via
            // patchChild), mount extras, unmount leftovers.
            val overlap = minOf(oldMidCount, newMidCount)
            for (offset in 0 until overlap) {
                pairs[head + offset] = mounted[head + offset]
            }
            for (offset in overlap until oldMidCount) {
                unmountChild(mounted[head + offset], container, removePhysically = true)
            }
        }

        // Apply right-to-left so every slot knows the widget that follows it.
        val result = arrayOfNulls<MountedNode<V>>(newSize)
        var anchor = endAnchor
        for (index in newSize - 1 downTo 0) {
            val nextNode = nextNodes[index]
            val pair = pairs[index]
            val patched = if (pair == null) {
                mountChild(nextNode, container, anchor)
            } else {
                if (!stable[index]) {
                    moveMounted(pair, container, anchor)
                }
                patchChild(pair, nextNode, container, anchor)
            }
            result[index] = patched
            anchor = patched.firstView() ?: anchor
        }

        mounted.clear()
        for (index in 0 until newSize) {
            mounted += result[index]!!
        }
    }

    /**
     * Keyed reconciliation is safe when every new middle child carries a unique key and every
     * old middle child is a keyed host. Mirrors the browser's `shouldReconcileKeyed`; the
     * [NodeFlags.CHILDREN_KEYED] certification (checked by the caller) skips these scans.
     */
    private fun shouldReconcileKeyed(
        mounted: List<MountedNode<V>>,
        nextNodes: List<Node>,
        head: Int,
        oldMidCount: Int,
        newMidCount: Int,
    ): Boolean {
        val seen = HashSet<String>(newMidCount)
        for (offset in 0 until newMidCount) {
            val key = nextNodes[head + offset].reconcileKey ?: return false
            if (!seen.add(key)) return false
        }
        for (offset in 0 until oldMidCount) {
            val old = mounted[head + offset] as? MountedNode.Host<V> ?: return false
            if (old.node.key == null) return false
        }
        return true
    }

    private fun hasSamePatchTarget(mounted: MountedNode<V>, next: Node): Boolean = when (mounted) {
        is MountedNode.Host<V> ->
            next is HostNode && mounted.node.tag == next.tag && mounted.node.key == next.reconcileKey
        is MountedNode.Text<V> -> next is TextNode
        is MountedNode.Fragment<V> -> next is FragmentNode
        is MountedNode.Empty<V> -> next is ClientRef
    }

    // --- unmount / move ----------------------------------------------------------------------

    private fun unmountChild(mounted: MountedNode<V>, container: V, removePhysically: Boolean) {
        when (mounted) {
            is MountedNode.Host<V> -> {
                // Innermost-first adapter cleanup; physical removal only at the subtree root —
                // removing the root widget drops its descendants with it.
                for (child in mounted.children) {
                    unmountChild(child, mounted.view, removePhysically = false)
                }
                adapter.teardownHost(mounted.view, mounted.node)
                if (removePhysically) {
                    adapter.remove(container, mounted.view)
                }
            }
            is MountedNode.Text<V> -> {
                if (removePhysically) {
                    adapter.remove(container, mounted.view)
                }
            }
            is MountedNode.Fragment<V> -> {
                for (child in mounted.children) {
                    unmountChild(child, container, removePhysically)
                }
            }
            is MountedNode.Empty<V> -> Unit
        }
    }

    private fun moveMounted(mounted: MountedNode<V>, container: V, before: V?) {
        when (mounted) {
            is MountedNode.Host<V> -> adapter.move(container, mounted.view, before)
            is MountedNode.Text<V> -> adapter.move(container, mounted.view, before)
            is MountedNode.Fragment<V> ->
                // Moving each child before the same anchor preserves their relative order.
                for (child in mounted.children) {
                    moveMounted(child, container, before)
                }
            is MountedNode.Empty<V> -> Unit
        }
    }
}
