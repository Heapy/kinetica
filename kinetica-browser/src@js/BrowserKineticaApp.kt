package io.heapy.kinetica.browser

import io.heapy.kinetica.ClientRef
import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.FragmentNode
import io.heapy.kinetica.HostNode
import io.heapy.kinetica.KineticaJson
import io.heapy.kinetica.KineticaRuntime
import io.heapy.kinetica.Node
import io.heapy.kinetica.NodeFlags
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.toSafeHtml
import kotlinx.serialization.json.JsonObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Node as DomNode
import org.w3c.dom.events.Event

/**
 * Retained-mode DOM renderer: the first render mounts a shadow tree ([Mounted]) alongside the
 * DOM; every subsequent render diffs the fresh [Node] tree against it and applies the minimal
 * patch — prop updates in place, keyed child reconciliation with LIS-based moves, text updates
 * via nodeValue. Events are delegated from the root element, so nodes carry no listeners and
 * event rebinding is free. Focus survives patches naturally; restoration runs only when the
 * focused element's subtree was actually replaced.
 */
public class BrowserKineticaApp(
    private val rootElement: Element,
    private val runtime: KineticaRuntime = KineticaRuntime(debug = true),
    private val content: ComponentScope.() -> Unit,
) {
    private val scope = ComponentScope(runtime)
    private var currentTree: Node? = null
    private var mountedRoot: Mounted? = null
    private var clientRefCount = 0

    private val delegatedListener: (Event) -> Unit = { event -> handleDelegatedEvent(event) }

    init {
        DelegatedEventTypes.forEach { type -> rootElement.addEventListener(type, delegatedListener) }
    }

    public fun render() {
        render(restoredFocus = null)
    }

    public suspend fun awaitIdle() {
        while (true) {
            runtime.awaitIdle()
            if (runtime.hasPendingInvalidation) {
                render(restoredFocus = null)
                continue
            }
            return
        }
    }

    public fun tree(): Node =
        currentTree ?: error("Browser app has not rendered.")

    public fun innerHtml(): String =
        rootElement.innerHTML

    public fun snapshot(): BrowserUiSnapshot =
        BrowserUiSnapshot(
            innerHtml = innerHtml(),
            treeHtml = tree().toSafeHtml(),
            treeJson = BrowserSnapshotJson.encodeToString(Node.serializer(), tree()),
        )

    public fun assertInnerHtmlSnapshot(expected: String) {
        assertSnapshotEquals(expected, innerHtml(), label = "Browser DOM snapshot")
    }

    public fun elementByTestTag(testTag: String): Element =
        rootElement.querySelector("[data-testid=\"$testTag\"]")
            ?: error("No browser element found for data-testid=\"$testTag\".")

    public fun clickTestTag(testTag: String) {
        elementByTestTag(testTag).asDynamic().click()
    }

    public fun inputTestTag(testTag: String, value: String) {
        val input = elementByTestTag(testTag) as? HTMLInputElement
            ?: error("Element with data-testid=\"$testTag\" is not an input.")
        input.value = value
        input.dispatchEvent(browserEvent("input"))
    }

    public fun dispose() {
        DelegatedEventTypes.forEach { type -> rootElement.removeEventListener(type, delegatedListener) }
        clearRoot()
        mountedRoot = null
        clientRefCount = 0
        currentTree = null
        scope.dispose()
        runtime.dispose()
    }

    private fun render(restoredFocus: BrowserFocus?) {
        val focus = restoredFocus ?: BrowserFocus.capture()
        val previouslyActive = browserDocument.activeElement
        currentTree = runtime.render(scope, content).tree
        val nextTree = tree()
        val existing = mountedRoot
        mountedRoot = if (existing == null) {
            clearRoot()
            mount(nextTree, rootElement, anchor = null, path = "")
        } else {
            patch(existing, nextTree, rootElement, path = "")
        }
        if (runtime.debug || clientRefCount > 0) {
            refreshGeneratedAttributes()
        }
        if (focus != null &&
            previouslyActive != null &&
            previouslyActive != browserDocument.body &&
            previouslyActive.asDynamic().isConnected != true
        ) {
            focus.restore(rootElement)
        }
    }

    // --- event delegation ---

    private fun handleDelegatedEvent(event: Event) {
        if (event.type == "keydown" && event.asDynamic().key != "Enter") {
            return
        }
        var element: Element? = event.target as? Element
        while (element != null) {
            val candidate: Any? = element.asDynamic().__kinetica
            val mounted = candidate as? MountedHost
            if (mounted != null && dispatchTo(mounted.hostNode, element, event)) {
                return
            }
            if (element == rootElement) {
                return
            }
            element = element.parentElement
        }
    }

    private fun dispatchTo(node: HostNode, element: Element, event: Event): Boolean =
        when (event.type) {
            "click" -> {
                val eventId = node.props["event:onClick"]
                if (eventId == null) {
                    false
                } else {
                    val enabled = node.props["enabled"]?.toBooleanStrictOrNull() ?: true
                    if (enabled) {
                        dispatchAndRender(eventId, Unit)
                    }
                    true
                }
            }
            "input" -> {
                val eventId = node.props["event:onInput"]
                if (eventId == null) {
                    false
                } else {
                    dispatchAndRender(eventId, (element as HTMLInputElement).value)
                    true
                }
            }
            "change" -> {
                val eventId = node.props["event:onToggle"]
                if (eventId == null) {
                    false
                } else {
                    dispatchAndRender(eventId, Unit)
                    true
                }
            }
            "keydown" -> {
                val eventId = node.props["event:onSubmit"]
                if (eventId == null) {
                    false
                } else {
                    event.preventDefault()
                    dispatchAndRender(eventId, Unit)
                    true
                }
            }
            else -> false
        }

    private fun dispatchAndRender(eventId: String, payload: Any?) {
        runtime.dispatch(eventId, payload)
        render(restoredFocus = null)
    }

    // --- mounting ---

    private fun mount(node: Node, parent: Element, anchor: DomNode?, path: String): Mounted =
        when (node) {
            is FragmentNode -> {
                val children = mutableListOf<Mounted>()
                node.children.forEachIndexed { index, child ->
                    children.add(mount(child, parent, anchor, childPathOf(path, index)))
                }
                MountedFragment(node, children)
            }
            is HostNode -> mountHost(node, parent, anchor, path)
            is TextNode -> mountText(node, parent, anchor, path)
            is ClientRef -> mountClientRef(node, parent, anchor, path)
        }

    private fun mountHost(node: HostNode, parent: Element, anchor: DomNode?, path: String): MountedHost {
        val element = browserDocument.createElement(browserTagNameFor(node.tag))
        if (runtime.debug) {
            element.setAttribute(DATA_KINETICA_TAG, node.tag)
            element.setAttribute(DATA_KINETICA_PATH, path)
        }
        node.key?.let { key -> element.setAttribute(DATA_KINETICA_KEY, key) }
        node.semantics.applyTo(element, nativeTag = node.tag)

        when (node.tag) {
            "column" -> {
                element.applyPublicProps(node)
                element.applyFlex(direction = "column")
            }
            "row" -> {
                element.applyPublicProps(node)
                element.applyFlex(direction = "row")
                if (node.props["direction"] == "Rtl") {
                    element.setAttribute("dir", "rtl")
                }
            }
            "button" -> {
                element.applyPublicProps(node)
                if (!(node.props["enabled"]?.toBooleanStrictOrNull() ?: true)) {
                    element.setAttribute("disabled", "")
                }
            }
            "textInput" -> {
                element.setAttribute("type", "text")
                element.applyPublicProps(node)
                val input = element as HTMLInputElement
                input.value = node.props["value"].orEmpty()
                node.props["placeholder"]?.let { placeholder -> input.placeholder = placeholder }
            }
            "checkbox" -> {
                element.setAttribute("type", "checkbox")
                element.applyPublicProps(node)
                (element as HTMLInputElement).checked = node.props["checked"]?.toBooleanStrictOrNull() ?: false
            }
            else -> element.applyPublicProps(node)
        }

        val mounted = MountedHost(node, element, mutableListOf())
        element.asDynamic().__kinetica = mounted
        node.children.forEachIndexed { index, child ->
            mounted.children.add(mount(child, element, anchor = null, path = childPathOf(path, index)))
        }
        parent.insertBefore(element, anchor)
        return mounted
    }

    private fun mountText(node: TextNode, parent: Element, anchor: DomNode?, path: String): MountedText {
        if (!textNeedsElement(node)) {
            val dom = browserDocument.createTextNode(node.value)
            parent.insertBefore(dom, anchor)
            return MountedText(node, dom, wrapped = false)
        }
        val element = browserDocument.createElement("span")
        element.textContent = node.value
        if (runtime.debug) {
            element.setAttribute(DATA_KINETICA_PATH, path)
        }
        if (node.strikethrough) {
            element.setAttribute("style", "text-decoration: line-through;")
        }
        node.semantics.applyTo(element, nativeTag = "text")
        parent.insertBefore(element, anchor)
        return MountedText(node, element, wrapped = true)
    }

    private fun mountClientRef(node: ClientRef, parent: Element, anchor: DomNode?, path: String): MountedClientRef {
        val element = browserDocument.createElement("template")
        element.setAttribute(DATA_CLIENT_REF, node.componentId)
        element.setAttribute(DATA_KINETICA_PATH, path)
        element.setAttribute(DATA_CLIENT_PROPS, KineticaJson.encodeToString(JsonObject.serializer(), node.props))
        parent.insertBefore(element, anchor)
        clientRefCount++
        return MountedClientRef(node, element)
    }

    // --- patching ---

    private fun patch(mounted: Mounted, next: Node, parent: Element, path: String): Mounted {
        if (mounted.currentNode === next) {
            return mounted
        }
        return when {
            mounted is MountedHost && next is HostNode && mounted.hostNode.tag == next.tag -> {
                patchHost(mounted, next, path)
                mounted
            }
            mounted is MountedText && next is TextNode &&
                textNeedsElement(mounted.textNode) == textNeedsElement(next) &&
                mounted.textNode.semantics == next.semantics -> {
                patchText(mounted, next)
                mounted
            }
            mounted is MountedFragment && next is FragmentNode -> {
                patchFragment(mounted, next, parent, path)
                mounted
            }
            mounted is MountedClientRef && next is ClientRef && mounted.refNode.componentId == next.componentId -> {
                patchClientRef(mounted, next)
                mounted
            }
            else -> replace(mounted, next, parent, path)
        }
    }

    private fun replace(mounted: Mounted, next: Node, parent: Element, path: String): Mounted {
        val anchor = domAfter(mounted)
        unmount(mounted, parent)
        return mount(next, parent, anchor, path)
    }

    private fun unmount(mounted: Mounted, parent: Element) {
        when (mounted) {
            is MountedHost -> {
                mounted.element.asDynamic().__kinetica = null
                parent.removeChild(mounted.element)
            }
            is MountedText -> parent.removeChild(mounted.dom)
            is MountedFragment -> mounted.children.forEach { child -> unmount(child, parent) }
            is MountedClientRef -> {
                clientRefCount--
                parent.removeChild(mounted.element)
            }
        }
    }

    private fun patchHost(mounted: MountedHost, next: HostNode, path: String) {
        val previous = mounted.hostNode
        mounted.hostNode = next
        val element = mounted.element
        if (previous.semantics != next.semantics) {
            removeSemanticsAttributes(element, previous.semantics, next.tag)
            next.semantics.applyTo(element, nativeTag = next.tag)
        }
        if (previous.key != next.key) {
            val key = next.key
            if (key != null) {
                element.setAttribute(DATA_KINETICA_KEY, key)
            } else {
                element.removeAttribute(DATA_KINETICA_KEY)
            }
        }
        if (previous.props != next.props) {
            patchProps(element, next.tag, previous.props, next.props)
        }
        // Controlled inputs: the DOM value may have drifted via user input without a prop
        // change; every committed render syncs it back to the rendered value.
        when (next.tag) {
            "textInput" -> {
                val input = element as HTMLInputElement
                val expected = next.props["value"].orEmpty()
                if (input.value != expected) {
                    input.value = expected
                }
            }
            "checkbox" -> {
                val input = element as HTMLInputElement
                val expected = next.props["checked"]?.toBooleanStrictOrNull() ?: false
                if (input.checked != expected) {
                    input.checked = expected
                }
            }
        }
        patchChildren(
            element,
            mounted.children,
            next.children,
            appendAnchor = null,
            parentPath = path,
            prevFlags = previous.flags,
            nextFlags = next.flags,
        )
    }

    private fun patchProps(element: Element, tag: String, old: Map<String, String>, new: Map<String, String>) {
        new.forEach { (name, value) ->
            if (old[name] != value) {
                applyProp(element, tag, name, value)
            }
        }
        old.keys.forEach { name ->
            if (name !in new) {
                removeProp(element, tag, name)
            }
        }
    }

    private fun applyProp(element: Element, tag: String, name: String, value: String) {
        when {
            name.startsWith("event:") || name.startsWith("frame:") -> {}
            name == "value" && tag == "textInput" -> {
                val input = element as HTMLInputElement
                if (input.value != value) {
                    input.value = value
                }
            }
            name == "checked" && tag == "checkbox" ->
                (element as HTMLInputElement).checked = value.toBooleanStrictOrNull() ?: false
            name == "enabled" ->
                if (value.toBooleanStrictOrNull() ?: true) {
                    element.removeAttribute("disabled")
                } else {
                    element.setAttribute("disabled", "")
                }
            name == "placeholder" && tag == "textInput" ->
                (element as HTMLInputElement).placeholder = value
            name == "direction" ->
                if (value == "Rtl") {
                    element.setAttribute("dir", "rtl")
                } else {
                    element.removeAttribute("dir")
                }
            name == "style" && (tag == "row" || tag == "column") -> {}
            isPublicBrowserAttribute(name, value) -> element.setAttribute(name, value)
            isRemovablePublicBrowserAttribute(name) -> element.removeAttribute(name)
            else -> {}
        }
    }

    private fun removeProp(element: Element, tag: String, name: String) {
        when {
            name.startsWith("event:") || name.startsWith("frame:") -> {}
            name == "value" && tag == "textInput" -> (element as HTMLInputElement).value = ""
            name == "checked" && tag == "checkbox" -> (element as HTMLInputElement).checked = false
            name == "enabled" -> element.removeAttribute("disabled")
            name == "placeholder" && tag == "textInput" -> element.removeAttribute("placeholder")
            name == "direction" -> element.removeAttribute("dir")
            name == "style" && (tag == "row" || tag == "column") -> {}
            isRemovablePublicBrowserAttribute(name) -> element.removeAttribute(name)
            else -> {}
        }
    }

    private fun patchText(mounted: MountedText, next: TextNode) {
        val previous = mounted.textNode
        mounted.textNode = next
        if (mounted.wrapped) {
            val element = mounted.dom as Element
            if (previous.value != next.value) {
                element.textContent = next.value
            }
            if (previous.strikethrough != next.strikethrough) {
                if (next.strikethrough) {
                    element.setAttribute("style", "text-decoration: line-through;")
                } else {
                    element.removeAttribute("style")
                }
            }
        } else if (previous.value != next.value) {
            mounted.dom.textContent = next.value
        }
    }

    private fun patchFragment(mounted: MountedFragment, next: FragmentNode, parent: Element, path: String) {
        val anchor = domAfter(mounted)
        mounted.fragmentNode = next
        patchChildren(parent, mounted.children, next.children, appendAnchor = anchor, parentPath = path)
    }

    private fun patchClientRef(mounted: MountedClientRef, next: ClientRef) {
        val previous = mounted.refNode
        mounted.refNode = next
        if (previous.props != next.props) {
            mounted.element.setAttribute(
                DATA_CLIENT_PROPS,
                KineticaJson.encodeToString(JsonObject.serializer(), next.props),
            )
        }
    }

    // --- child reconciliation ---

    private fun patchChildren(
        parent: Element,
        mounted: MutableList<Mounted>,
        next: List<Node>,
        appendAnchor: DomNode?,
        parentPath: String,
        prevFlags: Int = 0,
        nextFlags: Int = 0,
    ) {
        // CHILDREN_KEYED on both sides is a construction-time proof that every child is a
        // uniquely-keyed HostNode, so the O(children) verification scan (two hash sets per
        // patch — the dominant bookkeeping cost of partial ops on big tables) is skipped.
        val certifiedKeyed = prevFlags and NodeFlags.CHILDREN_KEYED != 0 &&
            nextFlags and NodeFlags.CHILDREN_KEYED != 0 &&
            mounted.isNotEmpty() && next.isNotEmpty()
        if (certifiedKeyed || shouldReconcileKeyed(mounted, next)) {
            patchKeyedChildren(parent, mounted, next, appendAnchor, parentPath)
        } else {
            patchPositionalChildren(parent, mounted, next, appendAnchor, parentPath)
        }
    }

    private fun patchPositionalChildren(
        parent: Element,
        mounted: MutableList<Mounted>,
        next: List<Node>,
        appendAnchor: DomNode?,
        parentPath: String,
    ) {
        val common = minOf(mounted.size, next.size)
        for (index in 0 until common) {
            mounted[index] = patch(mounted[index], next[index], parent, childPathOf(parentPath, index))
        }
        if (mounted.size > next.size) {
            for (index in mounted.size - 1 downTo next.size) {
                unmount(mounted.removeAt(index), parent)
            }
        } else if (next.size > mounted.size) {
            for (index in mounted.size until next.size) {
                mounted.add(mount(next[index], parent, appendAnchor, childPathOf(parentPath, index)))
            }
        }
    }

    private fun shouldReconcileKeyed(mounted: List<Mounted>, next: List<Node>): Boolean {
        if (mounted.isEmpty() || next.isEmpty()) return false
        val oldKeys = HashSet<String>(mounted.size)
        for (child in mounted) {
            val key = (child as? MountedHost)?.hostNode?.key ?: return false
            if (!oldKeys.add(key)) return false
        }
        val newKeys = HashSet<String>(next.size)
        for (node in next) {
            val key = (node as? HostNode)?.key ?: return false
            if (!newKeys.add(key)) return false
        }
        return true
    }

    private fun patchKeyedChildren(
        parent: Element,
        mounted: MutableList<Mounted>,
        next: List<Node>,
        appendAnchor: DomNode?,
        parentPath: String,
    ) {
        var oldStart = 0
        var newStart = 0
        var oldEnd = mounted.size - 1
        var newEnd = next.size - 1
        val result = arrayOfNulls<Mounted>(next.size)

        while (oldStart <= oldEnd && newStart <= newEnd &&
            (mounted[oldStart] as MountedHost).hostNode.key == (next[newStart] as HostNode).key
        ) {
            result[newStart] = patch(mounted[oldStart], next[newStart], parent, childPathOf(parentPath, newStart))
            oldStart++
            newStart++
        }
        while (oldEnd >= oldStart && newEnd >= newStart &&
            (mounted[oldEnd] as MountedHost).hostNode.key == (next[newEnd] as HostNode).key
        ) {
            result[newEnd] = patch(mounted[oldEnd], next[newEnd], parent, childPathOf(parentPath, newEnd))
            oldEnd--
            newEnd--
        }

        val suffixAnchor: DomNode? =
            if (newEnd + 1 <= next.lastIndex) result[newEnd + 1]?.let(::firstDomOf) ?: appendAnchor else appendAnchor

        if (oldStart > oldEnd) {
            for (index in newEnd downTo newStart) {
                val anchor =
                    if (index + 1 <= next.lastIndex) result[index + 1]?.let(::firstDomOf) ?: suffixAnchor
                    else suffixAnchor
                result[index] = mount(next[index], parent, anchor, childPathOf(parentPath, index))
            }
        } else if (newStart > newEnd) {
            for (index in oldStart..oldEnd) {
                unmount(mounted[index], parent)
            }
        } else {
            val middleCount = newEnd - newStart + 1
            val keyToNewIndex = HashMap<String, Int>(middleCount)
            for (index in newStart..newEnd) {
                keyToNewIndex[(next[index] as HostNode).key!!] = index
            }
            val sourceOldIndex = IntArray(middleCount) { -1 }
            for (oldIndex in oldStart..oldEnd) {
                val child = mounted[oldIndex] as MountedHost
                val newIndex = keyToNewIndex[child.hostNode.key]
                if (newIndex == null) {
                    unmount(child, parent)
                } else {
                    sourceOldIndex[newIndex - newStart] = oldIndex
                    result[newIndex] = patch(child, next[newIndex], parent, childPathOf(parentPath, newIndex))
                }
            }
            val stable = longestIncreasingSubsequenceIndices(sourceOldIndex)
            val inStableRun = BooleanArray(middleCount)
            for (index in stable) {
                inStableRun[index] = true
            }
            for (middleIndex in middleCount - 1 downTo 0) {
                val newIndex = newStart + middleIndex
                val anchor =
                    if (newIndex + 1 <= next.lastIndex) result[newIndex + 1]?.let(::firstDomOf) ?: suffixAnchor
                    else suffixAnchor
                if (sourceOldIndex[middleIndex] < 0) {
                    result[newIndex] = mount(next[newIndex], parent, anchor, childPathOf(parentPath, newIndex))
                } else if (!inStableRun[middleIndex]) {
                    moveDom(result[newIndex]!!, parent, anchor)
                }
            }
        }

        mounted.clear()
        for (child in result) {
            mounted.add(child!!)
        }
    }

    // --- debug / hydration attributes ---

    private fun refreshGeneratedAttributes() {
        refreshAttributes(mountedRoot ?: return, "")
    }

    private fun refreshAttributes(mounted: Mounted, path: String) {
        when (mounted) {
            is MountedHost -> {
                if (runtime.debug) {
                    mounted.element.setAttribute(DATA_KINETICA_PATH, path)
                }
                mounted.children.forEachIndexed { index, child ->
                    refreshAttributes(child, refreshChildPath(path, index))
                }
            }
            is MountedText ->
                if (runtime.debug && mounted.wrapped) {
                    (mounted.dom as Element).setAttribute(DATA_KINETICA_PATH, path)
                }
            is MountedFragment ->
                mounted.children.forEachIndexed { index, child ->
                    refreshAttributes(child, refreshChildPath(path, index))
                }
            is MountedClientRef -> mounted.element.setAttribute(DATA_KINETICA_PATH, path)
        }
    }

    private fun childPathOf(parent: String, index: Int): String =
        if (!runtime.debug) "" else refreshChildPath(parent, index)

    private fun Element.applyPublicProps(node: HostNode) {
        node.props.forEach { (name, value) ->
            if (isPublicBrowserAttribute(name, value)) {
                setAttribute(name, value)
            }
        }
    }

    private fun Element.applyFlex(direction: String) {
        setAttribute(
            "style",
            listOf(
                "display: flex",
                "flex-direction: $direction",
                "gap: 0.5rem",
                "align-items: ${if (direction == "row") "center" else "stretch"}",
            ).joinToString(separator = "; ", postfix = ";"),
        )
    }

    private fun clearRoot() {
        while (rootElement.firstChild != null) {
            rootElement.removeChild(rootElement.firstChild!!)
        }
    }
}

// --- mounted shadow tree ---

private sealed class Mounted {
    abstract val currentNode: Node
}

private class MountedHost(
    var hostNode: HostNode,
    val element: Element,
    val children: MutableList<Mounted>,
) : Mounted() {
    override val currentNode: Node get() = hostNode
}

private class MountedText(
    var textNode: TextNode,
    val dom: DomNode,
    val wrapped: Boolean,
) : Mounted() {
    override val currentNode: Node get() = textNode
}

private class MountedFragment(
    var fragmentNode: FragmentNode,
    val children: MutableList<Mounted>,
) : Mounted() {
    override val currentNode: Node get() = fragmentNode
}

private class MountedClientRef(
    var refNode: ClientRef,
    val element: Element,
) : Mounted() {
    override val currentNode: Node get() = refNode
}

private fun firstDomOf(mounted: Mounted): DomNode? = when (mounted) {
    is MountedHost -> mounted.element
    is MountedText -> mounted.dom
    is MountedClientRef -> mounted.element
    is MountedFragment -> mounted.children.firstNotNullOfOrNull(::firstDomOf)
}

private fun lastDomOf(mounted: Mounted): DomNode? = when (mounted) {
    is MountedHost -> mounted.element
    is MountedText -> mounted.dom
    is MountedClientRef -> mounted.element
    is MountedFragment -> mounted.children.asReversed().firstNotNullOfOrNull(::lastDomOf)
}

private fun domAfter(mounted: Mounted): DomNode? = lastDomOf(mounted)?.nextSibling

private fun moveDom(mounted: Mounted, parent: Element, anchor: DomNode?) {
    when (mounted) {
        is MountedHost -> parent.insertBefore(mounted.element, anchor)
        is MountedText -> parent.insertBefore(mounted.dom, anchor)
        is MountedClientRef -> parent.insertBefore(mounted.element, anchor)
        is MountedFragment -> mounted.children.forEach { child -> moveDom(child, parent, anchor) }
    }
}

private fun textNeedsElement(node: TextNode): Boolean =
    node.strikethrough || node.semantics.hasElementAttributes()

private fun refreshChildPath(parent: String, index: Int): String =
    if (parent.isEmpty()) index.toString() else "$parent.$index"

private val DelegatedEventTypes = listOf("click", "input", "change", "keydown")

public data class BrowserUiSnapshot(
    val innerHtml: String,
    val treeHtml: String,
    val treeJson: String,
)

public fun mountKineticaApp(
    root: Element,
    runtime: KineticaRuntime = KineticaRuntime(debug = true),
    content: ComponentScope.() -> Unit,
): BrowserKineticaApp =
    BrowserKineticaApp(root, runtime, content).also { app -> app.render() }

public fun mountKineticaApp(
    selector: String,
    runtime: KineticaRuntime = KineticaRuntime(debug = true),
    content: ComponentScope.() -> Unit,
): BrowserKineticaApp {
    val root = browserDocument.querySelector(selector)
        ?: error("No element matches selector: $selector")
    return mountKineticaApp(root, runtime, content)
}

public fun assertBrowserSnapshotEquals(
    expected: String,
    actual: String,
    label: String = "Browser snapshot",
) {
    assertSnapshotEquals(expected, actual, label)
}

private fun Semantics?.applyTo(element: Element, nativeTag: String) {
    val semantics = this ?: return
    semantics.testTag?.let { testTag ->
        element.setAttribute("data-testid", testTag)
        element.setAttribute("data-kinetica-test-tag", testTag)
    }
    semantics.role?.let { role ->
        val roleAttribute = browserRoleFor(role)
        if (roleAttribute != null && nativeTag !in NativeRoleTags) {
            element.setAttribute("role", roleAttribute)
        }
    }
    semantics.label?.let { label -> element.setAttribute("aria-label", label) }
    semantics.stateDescription?.let { description -> element.setAttribute("aria-description", description) }
    if (semantics.focusable && nativeTag !in NativeFocusableTags) {
        element.setAttribute("tabindex", "0")
    }
    if (semantics.traversalIndex != null) {
        element.setAttribute("data-kinetica-traversal-index", semantics.traversalIndex.toString())
    }
    if (semantics.leaving) {
        element.setAttribute("data-kinetica-leaving", "true")
    }
}

private fun removeSemanticsAttributes(element: Element, semantics: Semantics?, nativeTag: String) {
    if (semantics == null) return
    if (semantics.testTag != null) {
        element.removeAttribute("data-testid")
        element.removeAttribute("data-kinetica-test-tag")
    }
    if (semantics.role?.let(::browserRoleFor) != null && nativeTag !in NativeRoleTags) {
        element.removeAttribute("role")
    }
    if (semantics.label != null) {
        element.removeAttribute("aria-label")
    }
    if (semantics.stateDescription != null) {
        element.removeAttribute("aria-description")
    }
    if (semantics.focusable && nativeTag !in NativeFocusableTags) {
        element.removeAttribute("tabindex")
    }
    if (semantics.traversalIndex != null) {
        element.removeAttribute("data-kinetica-traversal-index")
    }
    if (semantics.leaving) {
        element.removeAttribute("data-kinetica-leaving")
    }
}

private fun Semantics?.hasElementAttributes(): Boolean =
    this != null &&
        (
            testTag != null ||
                role?.let(::browserRoleFor) != null ||
                label != null ||
                stateDescription != null ||
                focusable ||
                traversalIndex != null ||
                leaving
            )

private class BrowserFocus(
    private val stableSelector: String?,
    private val path: String?,
    private val selectionStart: Int?,
    private val selectionEnd: Int?,
) {
    fun restore(root: Element) {
        val element = stableSelector?.let(root::querySelector)
            ?: path?.let { root.querySelector(attributeSelector(DATA_KINETICA_PATH, it)) }
            ?: return
        if (browserDocument.activeElement != element) {
            element.asDynamic().focus(js("({ preventScroll: true })"))
        }
        val input = (element as? HTMLInputElement)
            ?.takeIf { browserInputTypeSupportsTextSelection(it.type) }
            ?: return
        if (selectionStart != null && selectionEnd != null) {
            input.setSelectionRange(selectionStart, selectionEnd)
        }
    }

    companion object {
        fun capture(): BrowserFocus? {
            val element = browserDocument.activeElement ?: return null
            if (element == browserDocument.body) {
                return null
            }
            val stableSelector = element.getAttribute("data-testid")?.let { testTag ->
                attributeSelector("data-testid", testTag)
            } ?: element.getAttribute(DATA_KINETICA_KEY)?.let { key ->
                attributeSelector(DATA_KINETICA_KEY, key)
            }
            val path = element.getAttribute(DATA_KINETICA_PATH)
            if (stableSelector == null && path == null) {
                return null
            }
            val input = element as? HTMLInputElement
            val selectionInput = input?.takeIf { browserInputTypeSupportsTextSelection(it.type) }
            return BrowserFocus(
                stableSelector = stableSelector,
                path = path,
                selectionStart = selectionInput?.selectionStart,
                selectionEnd = selectionInput?.selectionEnd,
            )
        }
    }
}

private fun attributeSelector(name: String, value: String): String =
    "[$name=\"${value.escapeCssAttributeValue()}\"]"

private fun String.escapeCssAttributeValue(): String =
    buildString(length) {
        this@escapeCssAttributeValue.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\a ")
                '\r' -> append("\\d ")
                '\t' -> append("\\9 ")
                else -> append(character)
            }
        }
    }

private fun browserEvent(type: String): Event =
    js("new Event(type, { bubbles: true, cancelable: true })").unsafeCast<Event>()

private val browserDocument: Document
    get() = js("document").unsafeCast<Document>()

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

private val BrowserSnapshotJson = kotlinx.serialization.json.Json(KineticaJson) {
    prettyPrint = true
}

private const val DATA_KINETICA_TAG = "data-kinetica-tag"
private const val DATA_KINETICA_KEY = "data-kinetica-key"
private const val DATA_KINETICA_PATH = "data-kinetica-path"
private const val DATA_CLIENT_REF = "data-kinetica-client-ref"
private const val DATA_CLIENT_PROPS = "data-kinetica-props"

private val NativeRoleTags = setOf("button", "textInput", "checkbox")
private val NativeFocusableTags = setOf("button", "textInput", "checkbox")
