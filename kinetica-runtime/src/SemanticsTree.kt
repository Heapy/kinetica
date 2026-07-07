package io.heapy.kinetica

import kotlinx.serialization.Serializable

@Serializable
public data class SemanticsTree(
    val nodes: List<SemanticsNode>,
) {
    public fun byTestTag(testTag: String): SemanticsNode? =
        nodes.firstOrNull { it.semantics.testTag == testTag }

    public fun byRole(role: Role): List<SemanticsNode> =
        nodes.filter { it.semantics.role == role }

    public fun byLabel(label: String): List<SemanticsNode> =
        nodes.filter { it.semantics.label == label }

    public fun focusOrder(): List<SemanticsNode> =
        nodes
            .filter { it.semantics.focusable }
            .sortedWith(compareBy<SemanticsNode> { it.semantics.traversalIndex ?: Int.MAX_VALUE }
                .thenBy { it.pathKey })
}

@Serializable
public data class SemanticsNode(
    val path: List<Int>,
    val semantics: Semantics,
    val hostTag: String? = null,
    val text: String? = null,
) {
    internal val pathKey: String
        get() = path.joinToString(separator = ".") { it.toString().padStart(8, '0') }
}

public fun Node.semanticsTree(): SemanticsTree {
    val nodes = mutableListOf<SemanticsNode>()

    fun visit(node: Node, path: List<Int>) {
        if (node is TemplateNode) {
            visit(node.materialize(), path)
            return
        }
        val semantics = node.effectiveSemantics()
        if (semantics != null) {
            nodes += SemanticsNode(
                path = path,
                semantics = semantics,
                hostTag = (node as? HostNode)?.tag,
                text = (node as? TextNode)?.value,
            )
        }
        when (node) {
            is FragmentNode -> node.children.forEachIndexed { index, child -> visit(child, path + index) }
            is HostNode -> node.children.forEachIndexed { index, child -> visit(child, path + index) }
            is TextNode -> Unit
            is ClientRef -> Unit
            is TemplateNode -> Unit
        }
    }

    visit(this, emptyList())
    return SemanticsTree(nodes)
}

public fun Node.effectiveSemantics(): Semantics? {
    val current = this.semantics ?: return null
    return if (this is TextNode && current === DefaultTextSemantics) {
        current.copy(label = value)
    } else {
        current
    }
}

public fun Node.nodeAt(path: List<Int>): Node? {
    var current: Node = this
    for (index in path) {
        current = when (current) {
            is FragmentNode -> current.children.getOrNull(index)
            is HostNode -> current.children.getOrNull(index)
            is TemplateNode -> current.materialize().children.getOrNull(index)
            is TextNode -> null
            is ClientRef -> null
        } ?: return null
    }
    return current
}

public class FocusManager(
    initialTree: SemanticsTree = SemanticsTree(emptyList()),
) {
    private var tree: SemanticsTree = initialTree

    public var focusedPath: List<Int>? = null
        private set

    public val focused: SemanticsNode?
        get() = focusedPath?.let { path -> tree.nodes.firstOrNull { it.path == path } }

    public fun update(tree: SemanticsTree) {
        this.tree = tree
        if (focusedPath != null && tree.nodes.none { it.path == focusedPath && it.semantics.focusable }) {
            focusedPath = null
        }
    }

    public fun requestFocus(path: List<Int>): Boolean {
        val target = tree.nodes.firstOrNull { it.path == path && it.semantics.focusable } ?: return false
        focusedPath = target.path
        return true
    }

    public fun requestFocusByTestTag(testTag: String): Boolean {
        val target = tree.byTestTag(testTag)?.takeIf { it.semantics.focusable } ?: return false
        focusedPath = target.path
        return true
    }

    public fun moveNext(): SemanticsNode? =
        moveBy(delta = 1)

    public fun movePrevious(): SemanticsNode? =
        moveBy(delta = -1)

    private fun moveBy(delta: Int): SemanticsNode? {
        val order = tree.focusOrder()
        if (order.isEmpty()) {
            focusedPath = null
            return null
        }
        val currentIndex = focusedPath?.let { path -> order.indexOfFirst { it.path == path } } ?: -1
        val nextIndex = when {
            currentIndex == -1 && delta > 0 -> 0
            currentIndex == -1 && delta < 0 -> order.lastIndex
            else -> (currentIndex + delta).floorMod(order.size)
        }
        val next = order[nextIndex]
        focusedPath = next.path
        return next
    }
}

private fun Int.floorMod(divisor: Int): Int {
    val value = this % divisor
    return if (value < 0) value + divisor else value
}
