package io.heapy.kinetica

public fun ComponentScope.fragment(content: ComponentScope.() -> Unit) {
    emit(FragmentNode(collect(content)))
}

public data class FrameBinding(
    val property: String,
    val frameId: String,
    val initialValue: Float,
)

public fun HostNode.frameBindings(): List<FrameBinding> =
    props
        .filterKeys { key -> key.startsWith("frame:") && !key.endsWith(":value") }
        .map { (key, frameId) ->
            val property = key.removePrefix("frame:")
            FrameBinding(
                property = property,
                frameId = frameId,
                initialValue = props["frame:$property:value"]?.toFloatOrNull() ?: 0f,
            )
        }

public val LayoutDirectionContext: Context<LayoutDirection> =
    context(LayoutDirection.Ltr, name = "LayoutDirection")

public fun ComponentScope.provideLayoutDirection(
    direction: LayoutDirection,
    content: ComponentScope.() -> Unit,
) {
    provide(LayoutDirectionContext, direction, content)
}

public fun ComponentScope.currentLayoutDirection(): LayoutDirection =
    read(LayoutDirectionContext)

public fun ComponentScope.host(
    tag: String,
    props: Map<String, String> = emptyMap(),
    frameProps: Map<String, FrameValue> = emptyMap(),
    semantics: Semantics? = null,
    key: Any? = null,
    content: ComponentScope.() -> Unit = {},
) {
    // Without frame bindings the caller's map is emitted as-is: HostNode treats props as
    // an immutable value, and copying every node's props dominated the create profile.
    val mergedProps = if (frameProps.isEmpty()) {
        props
    } else {
        props.toMutableMap().also { merged ->
            frameProps.forEach { (property, value) ->
                merged["frame:$property"] = value.id
                merged["frame:$property:value"] = value.value.toString()
            }
        }
    }
    val children = collect(content)
    val flags = childShapeFlags(children)
    emit(HostNode(tag, mergedProps, children, key?.toString(), semantics, flags))
}

public fun ComponentScope.column(
    semantics: Semantics? = null,
    key: Any? = null,
    content: ComponentScope.() -> Unit,
) {
    val children = collect(content)
    emit(HostNode("column", children = children, key = key?.toString(), semantics = semantics, flags = childShapeFlags(children)))
}

public fun ComponentScope.row(
    semantics: Semantics? = null,
    key: Any? = null,
    content: ComponentScope.() -> Unit,
) {
    val children = collect(content)
    emit(
        HostNode(
            tag = "row",
            props = propsOf("direction", currentLayoutDirection().name),
            children = children,
            key = key?.toString(),
            semantics = semantics,
            flags = childShapeFlags(children),
        ),
    )
}

public fun ComponentScope.text(
    value: String,
    strikethrough: Boolean = false,
    semantics: Semantics? = DefaultTextSemantics,
) {
    emit(TextNode(value, strikethrough, semantics))
}

private fun ComponentScope.childShapeFlags(children: List<Node>): Int {
    var flags = if (consumeKeyedChildren(children) || children.areKeyedTemplateRows()) {
        NodeFlags.CHILDREN_KEYED
    } else {
        0
    }
    if ((children.singleOrNull() as? TextNode)?.isPlainDomText() == true) {
        flags = flags or NodeFlags.CHILDREN_SINGLE_TEXT
    }
    return flags
}

private fun List<Node>.areKeyedTemplateRows(): Boolean {
    if (isEmpty()) return false
    val keys = HashSet<String>(size)
    for (child in this) {
        val key = (child as? TemplateNode)?.key ?: return false
        if (!keys.add(key)) return false
    }
    return true
}

private fun TextNode.isPlainDomText(): Boolean =
    !strikethrough && !semantics.hasTextElementAttributes()

private fun Semantics?.hasTextElementAttributes(): Boolean =
    this != null &&
        (
            testTag != null ||
                role != null && role != Role.Text ||
                label != null ||
                stateDescription != null ||
                focusable ||
                traversalIndex != null ||
                leaving
            )

public fun ComponentScope.button(
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    semantics: Semantics? = Semantics(role = Role.Button, focusable = true),
    key: Any? = null,
    ordinal: Int = -1,
    content: ComponentScope.() -> Unit,
) {
    val enabledValue = if (enabled) "true" else "false"
    val props = if (onClick == null) {
        propsOf("enabled", enabledValue)
    } else {
        propsOf("enabled", enabledValue, "event:onClick", registerHostEvent(ordinal) { onClick() })
    }
    emit(HostNode("button", props, collect(content), key?.toString(), semantics))
}

public fun ComponentScope.hostEvent(ordinal: Int = -1, onEvent: () -> Unit): String =
    registerHostEvent(ordinal) { onEvent() }

public fun ComponentScope.textInput(
    value: String,
    onInput: ((String) -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    placeholder: String? = null,
    semantics: Semantics? = Semantics(role = Role.TextInput, focusable = true),
    key: Any? = null,
    ordinal: Int = -1,
) {
    val buffer = arrayOfNulls<String>(8)
    var count = 0
    buffer[count++] = "value"
    buffer[count++] = value
    if (placeholder != null) {
        buffer[count++] = "placeholder"
        buffer[count++] = placeholder
    }
    if (onInput != null) {
        buffer[count++] = "event:onInput"
        buffer[count++] = registerHostEvent(ordinal) { payload ->
            onInput(payload?.toString().orEmpty())
        }
    }
    if (onSubmit != null) {
        buffer[count++] = "event:onSubmit"
        buffer[count++] = registerHostEvent(ordinal, role = EVENT_ROLE_SECONDARY) { onSubmit() }
    }
    emit(HostNode("textInput", propsFromBuffer(buffer, count), key = key?.toString(), semantics = semantics))
}

public fun ComponentScope.checkbox(
    checked: Boolean,
    onToggle: (() -> Unit)? = null,
    semantics: Semantics? = Semantics(role = Role.Checkbox, focusable = true),
    key: Any? = null,
    ordinal: Int = -1,
) {
    val checkedValue = if (checked) "true" else "false"
    val props = if (onToggle == null) {
        propsOf("checked", checkedValue)
    } else {
        propsOf("checked", checkedValue, "event:onToggle", registerHostEvent(ordinal) { onToggle() })
    }
    emit(HostNode("checkbox", props, key = key?.toString(), semantics = semantics))
}

private fun ComponentScope.registerHostEvent(
    ordinal: Int,
    role: Int = EVENT_ROLE_PRIMARY,
    callback: (Any?) -> Unit,
): String {
    if (ordinal < 0) throw MissingKineticaPluginException("host event")
    return frameEvent(ordinal, role, callback)
}
