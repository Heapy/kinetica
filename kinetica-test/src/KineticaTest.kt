package io.heapy.kinetica.testing

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.JournalEntry
import io.heapy.kinetica.KineticaJson
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.Role
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.materialize
import io.heapy.kinetica.toSafeHtml
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public object KineticaTest {
    public fun render(content: ComponentScope.() -> Unit): TestRoot =
        HeadlessTestRoot(content).also { it.render() }

    public suspend fun renderSuspend(content: suspend ComponentScope.() -> Unit): SuspendTestRoot =
        HeadlessSuspendTestRoot(content).also { it.render() }
}

public interface TestRoot {
    public suspend fun awaitIdle()
    public fun advanceTimeBy(millis: Long)
    public fun node(matcher: SemanticsMatcher): TestNode
    public fun click(matcher: SemanticsMatcher)
    public fun input(matcher: SemanticsMatcher, value: String)
    public fun tree(): Node
    public fun journal(): List<JournalEntry>
    public fun dispose()
}

public interface SuspendTestRoot {
    public suspend fun awaitIdle()
    public suspend fun advanceTimeBy(millis: Long)
    public fun node(matcher: SemanticsMatcher): SuspendTestNode
    public suspend fun click(matcher: SemanticsMatcher)
    public suspend fun input(matcher: SemanticsMatcher, value: String)
    public fun tree(): Node
    public fun journal(): List<JournalEntry>
    public fun dispose()
}

public class TestNode internal constructor(
    private val root: HeadlessTestRoot,
    public val node: Node,
) {
    public fun click() {
        root.dispatch(node, "event:onClick", Unit)
        root.dispatch(node, "event:onToggle", Unit)
    }

    public fun input(value: String) {
        root.dispatch(node, "event:onInput", value)
    }

    public fun submit() {
        root.dispatch(node, "event:onSubmit", Unit)
    }
}

public class SuspendTestNode internal constructor(
    private val root: HeadlessSuspendTestRoot,
    public val node: Node,
) {
    public suspend fun click() {
        root.dispatch(node, "event:onClick", Unit)
        root.dispatch(node, "event:onToggle", Unit)
    }

    public suspend fun input(value: String) {
        root.dispatch(node, "event:onInput", value)
    }

    public suspend fun submit() {
        root.dispatch(node, "event:onSubmit", Unit)
    }
}

public class SemanticsMatcher internal constructor(
    internal val predicate: (Node) -> Boolean,
) {
    public infix fun and(other: SemanticsMatcher): SemanticsMatcher =
        SemanticsMatcher { node -> predicate(node) && other.predicate(node) }
}

public fun hasTestTag(tag: String): SemanticsMatcher =
    SemanticsMatcher { it.semantics?.testTag == tag }

public fun hasRole(role: Role): SemanticsMatcher =
    SemanticsMatcher { it.semantics?.role == role }

public fun hasLabel(label: String): SemanticsMatcher =
    SemanticsMatcher { it.semantics?.label == label }

public fun hasText(value: String): SemanticsMatcher =
    SemanticsMatcher { node -> node is TextNode && node.value == value }

public fun Node.toTreeSnapshot(): String =
    SnapshotJson.encodeToString(Node.serializer(), this)

public fun Node.toHtmlSnapshot(): String =
    toSafeHtml()

public fun TestRoot.treeSnapshot(): String =
    tree().toTreeSnapshot()

public fun TestRoot.htmlSnapshot(): String =
    tree().toHtmlSnapshot()

public fun SuspendTestRoot.treeSnapshot(): String =
    tree().toTreeSnapshot()

public fun SuspendTestRoot.htmlSnapshot(): String =
    tree().toHtmlSnapshot()

public fun TestRoot.assertTreeSnapshot(expected: String) {
    assertSnapshotEquals(expected, treeSnapshot(), label = "Tree snapshot")
}

public fun TestRoot.assertHtmlSnapshot(expected: String) {
    assertSnapshotEquals(expected, htmlSnapshot(), label = "HTML snapshot")
}

public fun SuspendTestRoot.assertTreeSnapshot(expected: String) {
    assertSnapshotEquals(expected, treeSnapshot(), label = "Tree snapshot")
}

public fun SuspendTestRoot.assertHtmlSnapshot(expected: String) {
    assertSnapshotEquals(expected, htmlSnapshot(), label = "HTML snapshot")
}

private val SnapshotJson: Json = Json(KineticaJson) {
    prettyPrint = true
}

private fun assertSnapshotEquals(
    expected: String,
    actual: String,
    label: String,
) {
    val normalizedExpected = expected.trimIndent().trim()
    val normalizedActual = actual.trim()
    if (normalizedExpected != normalizedActual) {
        throw AssertionError(
            "$label mismatch.\nExpected:\n$normalizedExpected\nActual:\n$normalizedActual",
        )
    }
}

private fun traverseNodes(root: Node): Sequence<Node> = sequence {
    yield(root)
    when (root) {
        is io.heapy.kinetica.FragmentNode -> root.children.forEach { yieldAll(traverseNodes(it)) }
        is HostNode -> root.children.forEach { yieldAll(traverseNodes(it)) }
        is TextNode -> Unit
        is io.heapy.kinetica.ClientRef -> Unit
        is io.heapy.kinetica.TemplateNode -> yieldAll(traverseNodes(root.materialize()))
    }
}

internal class HeadlessSuspendTestRoot(
    private val content: suspend ComponentScope.() -> Unit,
) : SuspendTestRoot {
    private val runtime = KineticaRuntime(debug = true)
    private val scope = ComponentScope(runtime)
    private var currentTree: Node? = null

    suspend fun render() {
        currentTree = runtime.renderSuspend(scope, content).tree
    }

    override suspend fun awaitIdle() {
        while (true) {
            runtime.awaitIdle()
            if (runtime.hasPendingInvalidation) {
                render()
                continue
            }
            return
        }
    }

    override suspend fun advanceTimeBy(millis: Long) {
        runtime.advanceVirtualTimeBy(millis)
        render()
    }

    override fun node(matcher: SemanticsMatcher): SuspendTestNode {
        val match = traverseNodes(tree()).firstOrNull(matcher.predicate)
            ?: error("No node matched the requested semantics")
        return SuspendTestNode(this, match)
    }

    override suspend fun click(matcher: SemanticsMatcher) {
        node(matcher).click()
    }

    override suspend fun input(matcher: SemanticsMatcher, value: String) {
        node(matcher).input(value)
    }

    override fun tree(): Node = currentTree ?: error("Root has not rendered")

    override fun journal(): List<JournalEntry> = runtime.journal()

    override fun dispose() {
        currentTree = null
        scope.dispose()
        runtime.dispose()
    }

    internal suspend fun dispatch(node: Node, eventName: String, payload: Any?) {
        val eventId = (node as? HostNode)?.props?.get(eventName) ?: return
        runtime.dispatch(eventId, payload)
        render()
    }
}

internal class HeadlessTestRoot(
    private val content: ComponentScope.() -> Unit,
) : TestRoot {
    private val runtime = KineticaRuntime(debug = true)
    private val scope = ComponentScope(runtime)
    private var currentTree: Node? = null

    fun render() {
        currentTree = runtime.render(scope, content).tree
    }

    override suspend fun awaitIdle() {
        while (true) {
            runtime.awaitIdle()
            if (runtime.hasPendingInvalidation) {
                render()
                continue
            }
            return
        }
    }

    override fun advanceTimeBy(millis: Long) {
        runtime.advanceVirtualTimeBy(millis)
        render()
    }

    override fun node(matcher: SemanticsMatcher): TestNode {
        val match = traverseNodes(tree()).firstOrNull(matcher.predicate)
            ?: error("No node matched the requested semantics")
        return TestNode(this, match)
    }

    override fun click(matcher: SemanticsMatcher) {
        node(matcher).click()
    }

    override fun input(matcher: SemanticsMatcher, value: String) {
        node(matcher).input(value)
    }

    override fun tree(): Node = currentTree ?: error("Root has not rendered")

    override fun journal(): List<JournalEntry> = runtime.journal()

    override fun dispose() {
        currentTree = null
        scope.dispose()
        runtime.dispose()
    }

    internal fun dispatch(node: Node, eventName: String, payload: Any?) {
        val eventId = (node as? HostNode)?.props?.get(eventName) ?: return
        runtime.dispatch(eventId, payload)
        render()
    }
}
