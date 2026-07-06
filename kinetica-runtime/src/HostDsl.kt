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
    val flags = if (consumeKeyedChildren(children)) NodeFlags.CHILDREN_KEYED else 0
    emit(HostNode(tag, mergedProps, children, key?.toString(), semantics, flags))
}

public fun ComponentScope.column(
    semantics: Semantics? = null,
    key: Any? = null,
    content: ComponentScope.() -> Unit,
) {
    emit(HostNode("column", children = collect(content), key = key?.toString(), semantics = semantics))
}

public fun ComponentScope.row(
    semantics: Semantics? = null,
    key: Any? = null,
    content: ComponentScope.() -> Unit,
) {
    emit(
        HostNode(
            tag = "row",
            props = propsOf("direction", currentLayoutDirection().name),
            children = collect(content),
            key = key?.toString(),
            semantics = semantics,
        ),
    )
}

public fun ComponentScope.text(
    value: String,
    strikethrough: Boolean = false,
    semantics: Semantics? = Semantics(role = Role.Text, label = value),
) {
    emit(TextNode(value, strikethrough, semantics))
}

public fun ComponentScope.button(
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    semantics: Semantics? = Semantics(role = Role.Button, focusable = true),
    key: Any? = null,
    content: ComponentScope.() -> Unit,
) {
    val enabledValue = if (enabled) "true" else "false"
    val props = if (onClick == null) {
        propsOf("enabled", enabledValue)
    } else {
        propsOf("enabled", enabledValue, "event:onClick", registerHostEvent { onClick() })
    }
    emit(HostNode("button", props, collect(content), key?.toString(), semantics))
}

public fun ComponentScope.textInput(
    value: String,
    onInput: ((String) -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
    placeholder: String? = null,
    semantics: Semantics? = Semantics(role = Role.TextInput, focusable = true),
    key: Any? = null,
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
        buffer[count++] = registerHostEvent { payload ->
            onInput(payload?.toString().orEmpty())
        }
    }
    if (onSubmit != null) {
        buffer[count++] = "event:onSubmit"
        buffer[count++] = registerHostEvent { onSubmit() }
    }
    emit(HostNode("textInput", propsFromBuffer(buffer, count), key = key?.toString(), semantics = semantics))
}

public fun ComponentScope.checkbox(
    checked: Boolean,
    onToggle: (() -> Unit)? = null,
    semantics: Semantics? = Semantics(role = Role.Checkbox, focusable = true),
    key: Any? = null,
) {
    val checkedValue = if (checked) "true" else "false"
    val props = if (onToggle == null) {
        propsOf("checked", checkedValue)
    } else {
        propsOf("checked", checkedValue, "event:onToggle", registerHostEvent { onToggle() })
    }
    emit(HostNode("checkbox", props, key = key?.toString(), semantics = semantics))
}

private fun ComponentScope.registerHostEvent(callback: (Any?) -> Unit): String =
    registerHostEvent(key = nextEventKey(), callback = callback)
