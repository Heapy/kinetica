package io.heapy.kinetica

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
public sealed interface Node {
    public val semantics: Semantics?
}

@Serializable
@SerialName("fragment")
public data class FragmentNode(
    val children: List<Node> = emptyList(),
    override val semantics: Semantics? = null,
) : Node

@Serializable
@SerialName("host")
public data class HostNode(
    val tag: String,
    val props: Map<String, String> = emptyMap(),
    val children: List<Node> = emptyList(),
    val key: String? = null,
    override val semantics: Semantics? = null,
) : Node

@Serializable
@SerialName("text")
public data class TextNode(
    val value: String,
    val strikethrough: Boolean = false,
    override val semantics: Semantics? = Semantics(role = Role.Text),
) : Node

@Serializable
@SerialName("clientRef")
public data class ClientRef(
    val componentId: String,
    val props: JsonObject = JsonObject(emptyMap()),
    override val semantics: Semantics? = null,
) : Node

@Serializable
public data class NodeDiff(
    val path: List<Int>,
    val kind: Kind,
    val before: Node? = null,
    val after: Node? = null,
) {
    @Serializable
    public enum class Kind {
        Inserted,
        Removed,
        Replaced,
    }
}

public fun diffNodes(before: Node?, after: Node?): List<NodeDiff> {
    val diffs = mutableListOf<NodeDiff>()
    fun visit(path: List<Int>, left: Node?, right: Node?) {
        when {
            left == null && right != null -> diffs += NodeDiff(path, NodeDiff.Kind.Inserted, after = right)
            left != null && right == null -> diffs += NodeDiff(path, NodeDiff.Kind.Removed, before = left)
            left != null && right != null && left::class != right::class -> {
                diffs += NodeDiff(path, NodeDiff.Kind.Replaced, before = left, after = right)
            }
            left != right -> {
                val leftChildren = left!!.childrenForDiff()
                val rightChildren = right!!.childrenForDiff()
                if (leftChildren == null || rightChildren == null) {
                    diffs += NodeDiff(path, NodeDiff.Kind.Replaced, before = left, after = right)
                } else if (!left.hasSameDiffContainerAs(right)) {
                    diffs += NodeDiff(path, NodeDiff.Kind.Replaced, before = left, after = right)
                } else {
                    val max = maxOf(leftChildren.size, rightChildren.size)
                    for (index in 0 until max) {
                        visit(path + index, leftChildren.getOrNull(index), rightChildren.getOrNull(index))
                    }
                }
            }
        }
    }
    visit(emptyList(), before, after)
    return diffs
}

private fun Node.childrenForDiff(): List<Node>? = when (this) {
    is FragmentNode -> children
    is HostNode -> children
    is TextNode -> null
    is ClientRef -> null
}

private fun Node.hasSameDiffContainerAs(other: Node): Boolean =
    when {
        this is FragmentNode && other is FragmentNode -> semantics == other.semantics
        this is HostNode && other is HostNode ->
            tag == other.tag &&
                props == other.props &&
                key == other.key &&
                semantics == other.semantics
        else -> false
    }

public fun JsonObject.Companion.of(vararg values: Pair<String, JsonElement>): JsonObject =
    JsonObject(mapOf(*values))
