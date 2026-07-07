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
import io.heapy.kinetica.TemplateDefinition
import io.heapy.kinetica.TemplateHoleKinds
import io.heapy.kinetica.TemplateNode
import io.heapy.kinetica.TextNode
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.materializeDeep
import io.heapy.kinetica.reconcileKey
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
    private val content: @UiComponent ComponentScope.() -> Unit,
) {
    private val scope = ComponentScope(runtime)
    private var currentTree: Node? = null
    private var mountedRoot: Mounted? = null
    private var clientRefCount = 0
    private val keyedPatchScratchPool = ArrayList<KeyedPatchScratchFrame>(KEYED_PATCH_SCRATCH_DEPTH)
    private val templatePrototypes = HashMap<String, TemplatePrototype>()

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

    internal val clientRefCountForTests: Int
        get() = clientRefCount

    public fun snapshot(): BrowserUiSnapshot =
        BrowserUiSnapshot(
            innerHtml = innerHtml(),
            treeHtml = tree().toSafeHtml(),
            treeJson = BrowserSnapshotJson.encodeToString(Node.serializer(), tree().materializeDeep()),
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
            val templateEvents = candidate as? MountedTemplateEvents
            if (templateEvents != null) {
                val propName = DelegatedEventPropNames[event.type]
                val templateEvent = if (propName == null) null else templateEvents.byPropName[propName]
                if (templateEvent != null && dispatchTo(templateEvent, element, event)) {
                    return
                }
            }
            if (element == rootElement) {
                return
            }
            element = element.parentElement
        }
    }

    private fun dispatchTo(node: HostNode, element: Element, event: Event): Boolean {
        val propName = DelegatedEventPropNames[event.type] ?: return false
        val eventId = node.props[propName] ?: return false
        return when (event.type) {
            "click" -> {
                val enabled = node.props["enabled"]?.toBooleanStrictOrNull() ?: true
                if (enabled) {
                    dispatchAndRender(eventId, Unit)
                }
                true
            }
            "input" -> {
                dispatchAndRender(eventId, (element as HTMLInputElement).value)
                true
            }
            "change" -> {
                dispatchAndRender(eventId, Unit)
                true
            }
            "keydown" -> {
                event.preventDefault()
                dispatchAndRender(eventId, Unit)
                true
            }
            else -> false
        }
    }

    private fun dispatchTo(binding: MountedTemplateEvent, element: Element, event: Event): Boolean {
        val propName = DelegatedEventPropNames[event.type] ?: return false
        val eventId = binding.eventId ?: return false
        if (binding.propName != propName) {
            return false
        }
        return when (event.type) {
            "click" -> {
                if (element.getAttribute("disabled") == null) {
                    dispatchAndRender(eventId, Unit)
                }
                true
            }
            "input" -> {
                dispatchAndRender(eventId, (element as HTMLInputElement).value)
                true
            }
            "change" -> {
                dispatchAndRender(eventId, Unit)
                true
            }
            "keydown" -> {
                event.preventDefault()
                dispatchAndRender(eventId, Unit)
                true
            }
            else -> false
        }
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
            is TemplateNode -> mountTemplate(node, parent, anchor, path)
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

    private fun mountTemplate(node: TemplateNode, parent: Element, anchor: DomNode?, path: String): MountedTemplate {
        val prototype = templatePrototype(node.definition)
        val root = prototype.root.cloneNode(true) as Element
        val key = node.reconcileKey
        if (key != null) {
            root.setAttribute(DATA_KINETICA_KEY, key)
        } else {
            root.removeAttribute(DATA_KINETICA_KEY)
        }
        if (runtime.debug) {
            root.setAttribute(DATA_KINETICA_PATH, path)
        }
        val mounted = MountedTemplate(
            templateNode = node,
            root = root,
            holeDoms = arrayOfNulls(node.definition.holes.size),
            eventBindings = arrayOfNulls(node.definition.holes.size),
        )
        patchTemplateValues(mounted, previousValues = emptyList(), nextValues = node.values)
        parent.insertBefore(root, anchor)
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
        if (mounted.currentNode === next && !mounted.isControlledInputHost()) {
            return mounted
        }
        return when {
            mounted is MountedHost && next is HostNode && mounted.hostNode.tag == next.tag -> {
                patchHost(mounted, next, path)
                mounted
            }
            mounted is MountedTemplate && next is TemplateNode &&
                mounted.templateNode.definition.id == next.definition.id -> {
                patchTemplate(mounted, next, path)
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
        detach(mounted)
        removeMountedDom(mounted, parent)
    }

    private fun removeMountedDom(mounted: Mounted, parent: Element) {
        when (mounted) {
            is MountedHost -> parent.removeChild(mounted.element)
            is MountedTemplate -> parent.removeChild(mounted.root)
            is MountedText -> parent.removeChild(mounted.dom)
            is MountedFragment -> mounted.children.forEach { child -> removeMountedDom(child, parent) }
            is MountedClientRef -> parent.removeChild(mounted.element)
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
        if (previous.flags and NodeFlags.CHILDREN_SINGLE_TEXT != 0 &&
            next.flags and NodeFlags.CHILDREN_SINGLE_TEXT != 0 &&
            patchSingleTextChild(mounted, next)
        ) {
            return
        }
        patchChildren(
            element,
            mounted.children,
            next.children,
            appendAnchor = null,
            parentPath = path,
            prevFlags = previous.flags,
            nextFlags = next.flags,
            ownsParent = true,
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

    private fun patchTemplate(mounted: MountedTemplate, next: TemplateNode, path: String) {
        val previous = mounted.templateNode
        mounted.templateNode = next
        if (previous.reconcileKey != next.reconcileKey) {
            val key = next.reconcileKey
            if (key != null) {
                mounted.root.setAttribute(DATA_KINETICA_KEY, key)
            } else {
                mounted.root.removeAttribute(DATA_KINETICA_KEY)
            }
        }
        if (runtime.debug) {
            mounted.root.setAttribute(DATA_KINETICA_PATH, path)
        }
        patchTemplateValues(mounted, previous.values, next.values)
    }

    private fun templatePrototype(definition: TemplateDefinition): TemplatePrototype =
        templatePrototypes.getOrPut(definition.id) {
            val parent = browserDocument.createElement("div")
            val mounted = mountHost(definition.skeleton, parent, anchor = null, path = "")
            stripTemplatePrototypeDebugAttributes(mounted.element)
            TemplatePrototype(mounted.element)
        }

    private fun stripTemplatePrototypeDebugAttributes(element: Element) {
        element.removeAttribute(DATA_KINETICA_PATH)
        element.removeAttribute(DATA_KINETICA_TAG)
        for (index in 0 until element.children.length) {
            stripTemplatePrototypeDebugAttributes(element.children.item(index) ?: continue)
        }
    }

    private fun patchTemplateValues(
        mounted: MountedTemplate,
        previousValues: List<String?>,
        nextValues: List<String?>,
    ) {
        mounted.templateNode.definition.holes.forEachIndexed { index, hole ->
            val nextValue = nextValues.getOrNull(index)
            val wasBound = mounted.holeDoms[index] != null
            val dom = mounted.holeDoms[index]
                ?: templateDomAt(mounted.root, hole.path).also { mounted.holeDoms[index] = it }
            if (wasBound && previousValues.getOrNull(index) == nextValue) {
                return@forEachIndexed
            }
            when (hole.kind) {
                TemplateHoleKinds.Text -> dom.nodeValue = nextValue.orEmpty()
                TemplateHoleKinds.Prop -> {
                    val element = dom as Element
                    val propName = hole.propName ?: return@forEachIndexed
                    when {
                        propName == "enabled" -> {
                            if (nextValue?.toBooleanStrictOrNull() ?: true) {
                                element.removeAttribute("disabled")
                            } else {
                                element.setAttribute("disabled", "")
                            }
                        }
                        nextValue == null -> {
                            if (isRemovablePublicBrowserAttribute(propName)) {
                                element.removeAttribute(propName)
                            }
                        }
                        isPublicBrowserAttribute(propName, nextValue) ->
                            element.setAttribute(propName, nextValue)
                    }
                }
                TemplateHoleKinds.EventProp -> {
                    val element = dom as Element
                    val propName = hole.propName ?: return@forEachIndexed
                    val binding = mounted.eventBindings[index] ?: MountedTemplateEvent(
                        element = element,
                        propName = propName,
                        eventId = nextValue,
                    ).also { created ->
                        mounted.eventBindings[index] = created
                        templateEventContainer(element).byPropName[propName] = created
                    }
                    binding.eventId = nextValue
                }
            }
        }
    }

    private fun templateDomAt(root: Element, path: String): DomNode {
        var current: DomNode = root
        if (path.isEmpty()) {
            return current
        }
        path.split('.').forEach { segment ->
            val index = segment.toInt()
            current = current.childNodes.item(index)
                ?: error("Template hole path '$path' does not exist.")
        }
        return current
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
        ownsParent: Boolean = false,
    ) {
        if (next.isEmpty() && ownsParent && appendAnchor == null && mounted.isNotEmpty()) {
            clearOwnedChildren(parent, mounted)
            return
        }
        // CHILDREN_KEYED on both sides is a construction-time proof that every child is a
        // uniquely-keyed HostNode, so the O(children) verification scan (two hash sets per
        // patch — the dominant bookkeeping cost of partial ops on big tables) is skipped.
        val certifiedKeyed = prevFlags and NodeFlags.CHILDREN_KEYED != 0 &&
            nextFlags and NodeFlags.CHILDREN_KEYED != 0 &&
            mounted.isNotEmpty() && next.isNotEmpty()
        if (certifiedKeyed || shouldReconcileKeyed(mounted, next)) {
            patchKeyedChildren(parent, mounted, next, appendAnchor, parentPath, ownsParent)
        } else {
            patchSegmentedChildren(parent, mounted, next, appendAnchor, parentPath)
        }
    }

    private fun patchSingleTextChild(mounted: MountedHost, next: HostNode): Boolean {
        val mountedText = mounted.children.singleOrNull() as? MountedText ?: return false
        val nextText = next.children.singleOrNull() as? TextNode ?: return false
        if (mountedText.wrapped || textNeedsElement(nextText)) return false
        if (mountedText.textNode.value != nextText.value) {
            mountedText.dom.nodeValue = nextText.value
        }
        mountedText.textNode = nextText
        return true
    }

    private data class ChildPivot(
        val oldIndex: Int,
        val newIndex: Int,
    )

    private fun patchSegmentedChildren(
        parent: Element,
        mounted: MutableList<Mounted>,
        next: List<Node>,
        appendAnchor: DomNode?,
        parentPath: String,
    ) {
        val result = arrayOfNulls<Mounted>(next.size)
        patchSegmentedRange(
            parent = parent,
            mounted = mounted,
            next = next,
            result = result,
            oldStart = 0,
            oldEnd = mounted.lastIndex,
            newStart = 0,
            newEnd = next.lastIndex,
            endAnchor = appendAnchor,
            parentPath = parentPath,
        )
        mounted.clear()
        for (index in next.indices) {
            mounted.add(result[index]!!)
        }
    }

    private fun patchSegmentedRange(
        parent: Element,
        mounted: List<Mounted>,
        next: List<Node>,
        result: Array<Mounted?>,
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        endAnchor: DomNode?,
        parentPath: String,
    ) {
        var leftOld = oldStart
        var rightOld = oldEnd
        var leftNew = newStart
        var rightNew = newEnd

        while (leftOld <= rightOld && leftNew <= rightNew && canAlignChild(mounted[leftOld], next[leftNew])) {
            result[leftNew] = patch(mounted[leftOld], next[leftNew], parent, childPathOf(parentPath, leftNew))
            leftOld++
            leftNew++
        }
        while (leftOld <= rightOld && leftNew <= rightNew && canAlignChild(mounted[rightOld], next[rightNew])) {
            result[rightNew] = patch(mounted[rightOld], next[rightNew], parent, childPathOf(parentPath, rightNew))
            rightOld--
            rightNew--
        }
        val rangeEndAnchor = firstDomInResult(result, rightNew + 1, newEnd, endAnchor)

        when {
            leftOld > rightOld -> {
                mountRange(parent, next, result, leftNew, rightNew, rangeEndAnchor, parentPath)
            }
            leftNew > rightNew -> {
                unmountRange(parent, mounted, leftOld, rightOld)
            }
            shouldReconcileKeyedRange(mounted, next, leftOld, rightOld, leftNew, rightNew) -> {
                patchKeyedRange(
                    parent,
                    mounted,
                    next,
                    result,
                    leftOld,
                    rightOld,
                    leftNew,
                    rightNew,
                    rangeEndAnchor,
                    parentPath,
                )
            }
            else -> {
                val pivot = findUniqueUnkeyedPivot(mounted, next, leftOld, rightOld, leftNew, rightNew)
                if (pivot == null) {
                    if (shouldReconcileKeyedSubsetRange(mounted, next, leftOld, rightOld, leftNew, rightNew)) {
                        patchKeyedSubsetRange(
                            parent,
                            mounted,
                            next,
                            result,
                            leftOld,
                            rightOld,
                            leftNew,
                            rightNew,
                            rangeEndAnchor,
                            parentPath,
                        )
                    } else {
                        replaceRange(
                            parent,
                            mounted,
                            next,
                            result,
                            leftOld,
                            rightOld,
                            leftNew,
                            rightNew,
                            rangeEndAnchor,
                            parentPath,
                        )
                    }
                } else {
                    result[pivot.newIndex] = patch(
                        mounted[pivot.oldIndex],
                        next[pivot.newIndex],
                        parent,
                        childPathOf(parentPath, pivot.newIndex),
                    )
                    val pivotAnchor = firstDomOf(result[pivot.newIndex]!!)
                    patchSegmentedRange(
                        parent = parent,
                        mounted = mounted,
                        next = next,
                        result = result,
                        oldStart = leftOld,
                        oldEnd = pivot.oldIndex - 1,
                        newStart = leftNew,
                        newEnd = pivot.newIndex - 1,
                        endAnchor = pivotAnchor,
                        parentPath = parentPath,
                    )
                    patchSegmentedRange(
                        parent = parent,
                        mounted = mounted,
                        next = next,
                        result = result,
                        oldStart = pivot.oldIndex + 1,
                        oldEnd = rightOld,
                        newStart = pivot.newIndex + 1,
                        newEnd = rightNew,
                        endAnchor = rangeEndAnchor,
                        parentPath = parentPath,
                    )
                }
            }
        }
    }

    private fun patchKeyedSubsetRange(
        parent: Element,
        mounted: List<Mounted>,
        next: List<Node>,
        result: Array<Mounted?>,
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        endAnchor: DomNode?,
        parentPath: String,
    ) {
        val middleCount = newEnd - newStart + 1
        val keyToNewIndex = HashMap<String, Int>(middleCount)
        for (index in newStart..newEnd) {
            val key = next[index].reconcileKey ?: continue
            keyToNewIndex[key] = index
        }
        val sourceOldIndex = IntArray(middleCount) { -1 }
        for (oldIndex in oldStart..oldEnd) {
            val child = mounted[oldIndex]
            val key = child.reconcileKey()
            val newIndex = if (key == null) null else keyToNewIndex[key]
            if (newIndex == null) {
                unmount(child, parent)
            } else {
                sourceOldIndex[newIndex - newStart] = oldIndex
                result[newIndex] = patch(child, next[newIndex], parent, childPathOf(parentPath, newIndex))
            }
        }
        val lis = LongestIncreasingSubsequenceScratch()
        val stableSize = longestIncreasingSubsequenceIndices(sourceOldIndex, middleCount, lis)
        val stable = lis.result
        val inStableRun = BooleanArray(middleCount)
        for (position in 0 until stableSize) {
            inStableRun[stable[position]] = true
        }
        for (middleIndex in middleCount - 1 downTo 0) {
            val newIndex = newStart + middleIndex
            val anchor = firstDomInResult(result, newIndex + 1, newEnd, endAnchor)
            if (result[newIndex] == null) {
                result[newIndex] = mount(next[newIndex], parent, anchor, childPathOf(parentPath, newIndex))
            } else if (!inStableRun[middleIndex]) {
                moveDom(result[newIndex]!!, parent, anchor)
            }
        }
    }

    private fun mountRange(
        parent: Element,
        next: List<Node>,
        result: Array<Mounted?>,
        newStart: Int,
        newEnd: Int,
        endAnchor: DomNode?,
        parentPath: String,
    ) {
        for (index in newEnd downTo newStart) {
            val anchor = firstDomInResult(result, index + 1, newEnd, endAnchor)
            result[index] = mount(next[index], parent, anchor, childPathOf(parentPath, index))
        }
    }

    private fun unmountRange(
        parent: Element,
        mounted: List<Mounted>,
        oldStart: Int,
        oldEnd: Int,
    ) {
        for (index in oldStart..oldEnd) {
            unmount(mounted[index], parent)
        }
    }

    private fun replaceRange(
        parent: Element,
        mounted: List<Mounted>,
        next: List<Node>,
        result: Array<Mounted?>,
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        endAnchor: DomNode?,
        parentPath: String,
    ) {
        unmountRange(parent, mounted, oldStart, oldEnd)
        mountRange(parent, next, result, newStart, newEnd, endAnchor, parentPath)
    }

    private fun patchKeyedRange(
        parent: Element,
        mounted: List<Mounted>,
        next: List<Node>,
        result: Array<Mounted?>,
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
        endAnchor: DomNode?,
        parentPath: String,
    ) {
        val middleCount = newEnd - newStart + 1
        val keyToNewIndex = HashMap<String, Int>(middleCount)
        for (index in newStart..newEnd) {
            keyToNewIndex[next[index].reconcileKey!!] = index
        }
        val sourceOldIndex = IntArray(middleCount) { -1 }
        for (oldIndex in oldStart..oldEnd) {
            val child = mounted[oldIndex]
            val newIndex = keyToNewIndex[child.reconcileKey()]
            if (newIndex == null) {
                unmount(child, parent)
            } else {
                sourceOldIndex[newIndex - newStart] = oldIndex
                result[newIndex] = patch(child, next[newIndex], parent, childPathOf(parentPath, newIndex))
            }
        }
        val lis = LongestIncreasingSubsequenceScratch()
        val stableSize = longestIncreasingSubsequenceIndices(sourceOldIndex, middleCount, lis)
        val stable = lis.result
        val inStableRun = BooleanArray(middleCount)
        for (position in 0 until stableSize) {
            inStableRun[stable[position]] = true
        }
        for (middleIndex in middleCount - 1 downTo 0) {
            val newIndex = newStart + middleIndex
            val anchor = firstDomInResult(result, newIndex + 1, newEnd, endAnchor)
            if (sourceOldIndex[middleIndex] < 0) {
                result[newIndex] = mount(next[newIndex], parent, anchor, childPathOf(parentPath, newIndex))
            } else if (!inStableRun[middleIndex]) {
                moveDom(result[newIndex]!!, parent, anchor)
            }
        }
    }

    private fun firstDomInResult(
        result: Array<Mounted?>,
        start: Int,
        end: Int,
        fallback: DomNode?,
    ): DomNode? {
        var index = start
        while (index <= end) {
            val dom = result[index]?.let(::firstDomOf)
            if (dom != null) {
                return dom
            }
            index++
        }
        return fallback
    }

    private fun shouldReconcileKeyedRange(
        mounted: List<Mounted>,
        next: List<Node>,
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
    ): Boolean {
        val oldKeys = HashSet<String>(oldEnd - oldStart + 1)
        for (index in oldStart..oldEnd) {
            val key = mounted[index].reconcileKey() ?: return false
            if (!oldKeys.add(key)) return false
        }
        val newKeys = HashSet<String>(newEnd - newStart + 1)
        for (index in newStart..newEnd) {
            val key = next[index].reconcileKey ?: return false
            if (!newKeys.add(key)) return false
        }
        return true
    }

    private fun shouldReconcileKeyedSubsetRange(
        mounted: List<Mounted>,
        next: List<Node>,
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
    ): Boolean {
        val oldKeys = HashSet<String>()
        for (index in oldStart..oldEnd) {
            val key = mounted[index].reconcileKey() ?: continue
            if (!oldKeys.add(key)) return false
        }
        if (oldKeys.isEmpty()) {
            return false
        }
        var hasOverlap = false
        val newKeys = HashSet<String>()
        for (index in newStart..newEnd) {
            val key = next[index].reconcileKey ?: continue
            if (!newKeys.add(key)) return false
            hasOverlap = hasOverlap || key in oldKeys
        }
        return hasOverlap
    }

    private fun findUniqueUnkeyedPivot(
        mounted: List<Mounted>,
        next: List<Node>,
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
    ): ChildPivot? {
        val oldCounts = HashMap<String, Int>()
        val oldIndexBySignature = HashMap<String, Int>()
        for (index in oldStart..oldEnd) {
            val signature = mounted[index].currentNode.unkeyedAlignmentSignature() ?: continue
            oldCounts[signature] = (oldCounts[signature] ?: 0) + 1
            if (signature !in oldIndexBySignature) {
                oldIndexBySignature[signature] = index
            }
        }

        val newCounts = HashMap<String, Int>()
        for (index in newStart..newEnd) {
            val signature = next[index].unkeyedAlignmentSignature() ?: continue
            newCounts[signature] = (newCounts[signature] ?: 0) + 1
        }

        for (newIndex in newStart..newEnd) {
            val signature = next[newIndex].unkeyedAlignmentSignature() ?: continue
            if (newCounts[signature] == 1 && oldCounts[signature] == 1) {
                return ChildPivot(oldIndexBySignature[signature]!!, newIndex)
            }
        }
        return null
    }

    private fun canAlignChild(mounted: Mounted, next: Node): Boolean {
        val oldKey = mounted.reconcileKey()
        val newKey = next.reconcileKey
        if (oldKey != null || newKey != null) {
            return oldKey != null && oldKey == newKey
        }
        return canPatchUnkeyed(mounted, next)
    }

    private fun canPatchUnkeyed(mounted: Mounted, next: Node): Boolean =
        when {
            mounted is MountedHost && next is HostNode -> mounted.hostNode.tag == next.tag
            mounted is MountedTemplate && next is TemplateNode ->
                mounted.templateNode.definition.id == next.definition.id
            mounted is MountedText && next is TextNode ->
                textNeedsElement(mounted.textNode) == textNeedsElement(next) &&
                    mounted.textNode.semantics == next.semantics
            mounted is MountedFragment && next is FragmentNode -> true
            mounted is MountedClientRef && next is ClientRef -> mounted.refNode.componentId == next.componentId
            else -> false
        }

    private fun Node.unkeyedAlignmentSignature(): String? {
        if (reconcileKey != null) {
            return null
        }
        return when (this) {
            is HostNode -> {
                val builder = StringBuilder()
                builder.append("host:").append(tag).append('(')
                children.forEach { child ->
                    val childSignature = child.unkeyedAlignmentSignature() ?: return null
                    builder.append(childSignature).append(',')
                }
                builder.append(')').toString()
            }
            is TemplateNode -> "template:${definition.id}:${values.joinToString(separator = "\u0000")}"
            is TextNode -> "text:${textNeedsElement(this)}:$strikethrough:$semantics:$value"
            is ClientRef -> "client:$componentId"
            is FragmentNode -> null
        }
    }

    private fun shouldReconcileKeyed(mounted: List<Mounted>, next: List<Node>): Boolean {
        if (mounted.isEmpty() || next.isEmpty()) return false
        val oldKeys = HashSet<String>(mounted.size)
        for (child in mounted) {
            val key = child.reconcileKey() ?: return false
            if (!oldKeys.add(key)) return false
        }
        val newKeys = HashSet<String>(next.size)
        for (node in next) {
            val key = node.reconcileKey ?: return false
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
        ownsParent: Boolean,
    ) {
        val scratch = acquireKeyedPatchScratch()
        try {
            patchKeyedChildren(parent, mounted, next, appendAnchor, parentPath, ownsParent, scratch)
        } finally {
            releaseKeyedPatchScratch(scratch)
        }
    }

    private fun patchKeyedChildren(
        parent: Element,
        mounted: MutableList<Mounted>,
        next: List<Node>,
        appendAnchor: DomNode?,
        parentPath: String,
        ownsParent: Boolean,
        scratch: KeyedPatchScratchFrame,
    ) {
        var oldStart = 0
        var newStart = 0
        var oldEnd = mounted.size - 1
        var newEnd = next.size - 1
        val result = scratch.prepareResult(next.size)

        while (oldStart <= oldEnd && newStart <= newEnd &&
            mounted[oldStart].reconcileKey() == next[newStart].reconcileKey
        ) {
            result[newStart] = patch(mounted[oldStart], next[newStart], parent, childPathOf(parentPath, newStart))
            oldStart++
            newStart++
        }
        while (oldEnd >= oldStart && newEnd >= newStart &&
            mounted[oldEnd].reconcileKey() == next[newEnd].reconcileKey
        ) {
            result[newEnd] = patch(mounted[oldEnd], next[newEnd], parent, childPathOf(parentPath, newEnd))
            oldEnd--
            newEnd--
        }

        if (ownsParent && appendAnchor == null &&
            oldStart == 0 && oldEnd == mounted.lastIndex &&
            newStart == 0 && newEnd == next.lastIndex &&
            hasNoKeyOverlap(mounted, oldStart, oldEnd, next, newStart, newEnd, scratch)
        ) {
            clearOwnedChildren(parent, mounted)
            next.forEachIndexed { index, node ->
                mounted.add(mount(node, parent, anchor = null, path = childPathOf(parentPath, index)))
            }
            return
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
            val keyToNewIndex = scratch.keyToNewIndex
            keyToNewIndex.clear()
            for (index in newStart..newEnd) {
                keyToNewIndex[next[index].reconcileKey!!] = index
            }
            val sourceOldIndex = scratch.prepareSourceOldIndex(middleCount)
            for (oldIndex in oldStart..oldEnd) {
                val child = mounted[oldIndex]
                val newIndex = keyToNewIndex[child.reconcileKey()]
                if (newIndex == null) {
                    unmount(child, parent)
                } else {
                    sourceOldIndex[newIndex - newStart] = oldIndex
                    result[newIndex] = patch(child, next[newIndex], parent, childPathOf(parentPath, newIndex))
                }
            }
            val stableSize = longestIncreasingSubsequenceIndices(sourceOldIndex, middleCount, scratch.lis)
            val stable = scratch.lis.result
            val inStableRun = scratch.prepareStableRun(middleCount)
            for (position in 0 until stableSize) {
                inStableRun[stable[position]] = true
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
        for (index in 0 until next.size) {
            mounted.add(result[index]!!)
            result[index] = null
        }
    }

    private fun acquireKeyedPatchScratch(): KeyedPatchScratchFrame {
        return keyedPatchScratchPool.removeLastOrNull() ?: KeyedPatchScratchFrame()
    }

    private fun releaseKeyedPatchScratch(scratch: KeyedPatchScratchFrame) {
        if (keyedPatchScratchPool.size < KEYED_PATCH_SCRATCH_DEPTH) {
            keyedPatchScratchPool.add(scratch)
        }
    }

    private fun hasNoKeyOverlap(
        mounted: List<Mounted>,
        oldStart: Int,
        oldEnd: Int,
        next: List<Node>,
        newStart: Int,
        newEnd: Int,
        scratch: KeyedPatchScratchFrame,
    ): Boolean {
        if (oldStart > oldEnd || newStart > newEnd) {
            return false
        }
        val keyToNewIndex = scratch.keyToNewIndex
        keyToNewIndex.clear()
        for (index in newStart..newEnd) {
            keyToNewIndex[next[index].reconcileKey ?: return false] = index
        }
        for (index in oldStart..oldEnd) {
            if (keyToNewIndex.containsKey(mounted[index].reconcileKey() ?: return false)) {
                return false
            }
        }
        return true
    }

    private fun clearOwnedChildren(parent: Element, mounted: MutableList<Mounted>) {
        if (clientRefCount > 0) {
            mounted.forEach(::detach)
        }
        mounted.clear()
        // The DOM subtree is discarded wholesale, so __kinetica/template-event expandos
        // are GC-collectable with it; clientRefCount is the only app-lifetime bookkeeping.
        parent.textContent = ""
    }

    private fun detach(mounted: Mounted) {
        when (mounted) {
            is MountedHost -> {
                mounted.children.forEach(::detach)
                mounted.element.asDynamic().__kinetica = null
            }
            is MountedTemplate -> {
                mounted.eventBindings.forEach { binding ->
                    if (binding != null) {
                        removeTemplateEventBinding(binding)
                    }
                }
            }
            is MountedText -> {}
            is MountedFragment -> mounted.children.forEach(::detach)
            is MountedClientRef -> clientRefCount--
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
            is MountedTemplate ->
                if (runtime.debug) {
                    mounted.root.setAttribute(DATA_KINETICA_PATH, path)
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

private fun Mounted.isControlledInputHost(): Boolean =
    this is MountedHost && (hostNode.tag == "textInput" || hostNode.tag == "checkbox")

private class MountedTemplate(
    var templateNode: TemplateNode,
    val root: Element,
    val holeDoms: Array<DomNode?>,
    val eventBindings: Array<MountedTemplateEvent?>,
) : Mounted() {
    override val currentNode: Node get() = templateNode
}

private class MountedTemplateEvents(
    val element: Element,
    val byPropName: MutableMap<String, MountedTemplateEvent>,
)

private class MountedTemplateEvent(
    val element: Element,
    val propName: String,
    var eventId: String?,
)

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

private class TemplatePrototype(
    val root: Element,
)

private class KeyedPatchScratchFrame {
    var result: Array<Mounted?> = emptyArray()
    val keyToNewIndex: HashMap<String, Int> = HashMap()
    var sourceOldIndex: IntArray = IntArray(0)
    var inStableRun: BooleanArray = BooleanArray(0)
    val lis: LongestIncreasingSubsequenceScratch = LongestIncreasingSubsequenceScratch()

    fun prepareResult(size: Int): Array<Mounted?> {
        if (result.size < size) {
            result = arrayOfNulls(size)
        } else {
            for (index in 0 until size) {
                result[index] = null
            }
        }
        return result
    }

    fun prepareSourceOldIndex(size: Int): IntArray {
        if (sourceOldIndex.size < size) {
            sourceOldIndex = IntArray(size)
        }
        sourceOldIndex.fill(-1, 0, size)
        return sourceOldIndex
    }

    fun prepareStableRun(size: Int): BooleanArray {
        if (inStableRun.size < size) {
            inStableRun = BooleanArray(size)
        } else {
            inStableRun.fill(false, 0, size)
        }
        return inStableRun
    }
}

private fun firstDomOf(mounted: Mounted): DomNode? = when (mounted) {
    is MountedHost -> mounted.element
    is MountedTemplate -> mounted.root
    is MountedText -> mounted.dom
    is MountedClientRef -> mounted.element
    is MountedFragment -> mounted.children.firstNotNullOfOrNull(::firstDomOf)
}

private fun lastDomOf(mounted: Mounted): DomNode? = when (mounted) {
    is MountedHost -> mounted.element
    is MountedTemplate -> mounted.root
    is MountedText -> mounted.dom
    is MountedClientRef -> mounted.element
    is MountedFragment -> mounted.children.asReversed().firstNotNullOfOrNull(::lastDomOf)
}

private fun domAfter(mounted: Mounted): DomNode? = lastDomOf(mounted)?.nextSibling

private fun moveDom(mounted: Mounted, parent: Element, anchor: DomNode?) {
    when (mounted) {
        is MountedHost -> parent.insertBefore(mounted.element, anchor)
        is MountedTemplate -> parent.insertBefore(mounted.root, anchor)
        is MountedText -> parent.insertBefore(mounted.dom, anchor)
        is MountedClientRef -> parent.insertBefore(mounted.element, anchor)
        is MountedFragment -> mounted.children.forEach { child -> moveDom(child, parent, anchor) }
    }
}

private fun templateEventContainer(element: Element): MountedTemplateEvents {
    val candidate: Any? = element.asDynamic().__kinetica
    val existing = candidate as? MountedTemplateEvents
    if (existing != null) {
        return existing
    }
    return MountedTemplateEvents(element, mutableMapOf()).also { created ->
        element.asDynamic().__kinetica = created
    }
}

private fun removeTemplateEventBinding(binding: MountedTemplateEvent) {
    val candidate: Any? = binding.element.asDynamic().__kinetica
    val container = candidate as? MountedTemplateEvents ?: return
    if (container.byPropName[binding.propName] === binding) {
        container.byPropName.remove(binding.propName)
    }
    if (container.byPropName.isEmpty()) {
        container.element.asDynamic().__kinetica = null
    }
}

private fun Mounted.reconcileKey(): String? =
    when (this) {
        is MountedHost -> hostNode.reconcileKey
        is MountedTemplate -> templateNode.reconcileKey
        is MountedText,
        is MountedClientRef,
        is MountedFragment,
        -> null
    }

private fun textNeedsElement(node: TextNode): Boolean =
    node.strikethrough || node.semantics.hasElementAttributes()

private fun refreshChildPath(parent: String, index: Int): String =
    if (parent.isEmpty()) index.toString() else "$parent.$index"

private val DelegatedEventPropNames = mapOf(
    "click" to "event:onClick",
    "input" to "event:onInput",
    "change" to "event:onToggle",
    "keydown" to "event:onSubmit",
)
private val DelegatedEventTypes: Set<String> = DelegatedEventPropNames.keys
private const val KEYED_PATCH_SCRATCH_DEPTH = 4

public data class BrowserUiSnapshot(
    val innerHtml: String,
    val treeHtml: String,
    val treeJson: String,
)

public fun mountKineticaApp(
    root: Element,
    runtime: KineticaRuntime = KineticaRuntime(debug = true),
    content: @UiComponent ComponentScope.() -> Unit,
): BrowserKineticaApp =
    BrowserKineticaApp(root, runtime, content).also { app -> app.render() }

public fun mountKineticaApp(
    selector: String,
    runtime: KineticaRuntime = KineticaRuntime(debug = true),
    content: @UiComponent ComponentScope.() -> Unit,
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
