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

/**
 * Structural facts about a [HostNode] proven at construction time, so renderers can skip
 * re-deriving them on every patch. `0` (the default, and the value for any hand-built node)
 * means "unknown" — renderers must fall back to their existing checks.
 */
public object NodeFlags {
    /**
     * Every child is a [HostNode] carrying a non-null key, and the keys are unique. Set only
     * by the `each` -> `host` cooperation in the render DSL: `each` certifies that every row
     * emitted exactly one host keyed by that row's (deduplicated, hence unique) row key, and
     * `host` stamps the flag when those rows are its entire child list. A renderer seeing the
     * flag on both the previous and next node may run keyed reconciliation directly instead
     * of scanning all children (two hash sets per patch — the 10k-table partial-op tax).
     */
    public const val CHILDREN_KEYED: Int = 1

    /**
     * The host has exactly one child, that child is an unwrapped [TextNode], and the text
     * can be updated by writing directly to the existing DOM text node.
     */
    public const val CHILDREN_SINGLE_TEXT: Int = 1 shl 1
}

@Serializable
@SerialName("host")
public data class HostNode(
    val tag: String,
    val props: Map<String, String> = emptyMap(),
    val children: List<Node> = emptyList(),
    val key: String? = null,
    override val semantics: Semantics? = null,
    val flags: Int = 0,
) : Node

public object TemplateHoleKinds {
    public const val Text: String = "TEXT"
    public const val Prop: String = "PROP"
    public const val EventProp: String = "EVENT_PROP"
}

@Serializable
public data class TemplateHole(
    val path: String,
    val kind: String,
    val propName: String? = null,
)

@Serializable
public data class TemplateDefinition(
    val id: String,
    val skeleton: HostNode,
    val holes: List<TemplateHole>,
)

@Serializable
@SerialName("template")
public data class TemplateNode(
    val definition: TemplateDefinition,
    val values: List<String?> = emptyList(),
    val key: String? = null,
) : Node {
    override val semantics: Semantics?
        get() = definition.skeleton.semantics
}

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

public fun templateNode(
    definition: TemplateDefinition,
    values: List<String?>,
    key: Any? = null,
): TemplateNode =
    TemplateNode(
        definition = definition,
        values = values,
        key = key?.toString(),
    )

public fun singleTextTemplateDefinition(
    id: String,
    tag: String,
    props: Map<String, String> = emptyMap(),
): TemplateDefinition =
    TemplateDefinition(
        id = id,
        skeleton = HostNode(
            tag = tag,
            props = props,
            children = listOf(TextNode("", semantics = null)),
            flags = NodeFlags.CHILDREN_SINGLE_TEXT,
        ),
        holes = listOf(TemplateHole(path = "0", kind = TemplateHoleKinds.Text)),
    )

public fun TemplateNode.materialize(): HostNode =
    materializeTemplateHost(definition.skeleton, this, path = "")

private fun materializeTemplateHost(
    node: HostNode,
    template: TemplateNode,
    path: String,
): HostNode {
    var props = node.props
    template.definition.holes.forEachIndexed { index, hole ->
        if (hole.path == path && (hole.kind == TemplateHoleKinds.Prop || hole.kind == TemplateHoleKinds.EventProp)) {
            val propName = hole.propName ?: return@forEachIndexed
            props = if (index < template.values.size) {
                val value = template.values[index]
                if (value == null) props - propName else props + (propName to value)
            } else {
                props - propName
            }
        }
    }
    return node.copy(
        props = props,
        children = node.children.mapIndexed { index, child ->
            materializeTemplateNode(child, template, refreshTemplatePath(path, index))
        },
        key = if (path.isEmpty()) template.key ?: node.key else node.key,
    )
}

private fun materializeTemplateNode(
    node: Node,
    template: TemplateNode,
    path: String,
): Node {
    val textHole = template.definition.holes.withIndex().firstOrNull { (_, hole) ->
        hole.path == path && hole.kind == TemplateHoleKinds.Text
    }
    if (node is TextNode && textHole != null) {
        return node.copy(value = template.values.getOrNull(textHole.index).orEmpty())
    }
    return when (node) {
        is HostNode -> materializeTemplateHost(node, template, path)
        is FragmentNode -> node.copy(
            children = node.children.mapIndexed { index, child ->
                materializeTemplateNode(child, template, refreshTemplatePath(path, index))
            },
        )
        is TextNode -> node
        is ClientRef -> node
        is TemplateNode -> node.materialize()
    }
}

private fun refreshTemplatePath(parent: String, index: Int): String =
    if (parent.isEmpty()) index.toString() else "$parent.$index"

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
    is TemplateNode -> materialize().children
}

private fun Node.hasSameDiffContainerAs(other: Node): Boolean =
    when {
        this is FragmentNode && other is FragmentNode -> semantics == other.semantics
        this is HostNode && other is HostNode ->
            tag == other.tag &&
                props == other.props &&
                key == other.key &&
                semantics == other.semantics
        this is TemplateNode && other is TemplateNode ->
            materialize().hasSameDiffContainerAs(other.materialize())
        this is TemplateNode && other is HostNode ->
            materialize().hasSameDiffContainerAs(other)
        this is HostNode && other is TemplateNode ->
            hasSameDiffContainerAs(other.materialize())
        else -> false
    }

public fun JsonObject.Companion.of(vararg values: Pair<String, JsonElement>): JsonObject =
    JsonObject(mapOf(*values))
