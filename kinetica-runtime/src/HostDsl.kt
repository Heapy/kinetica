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
    val mergedProps = props.toMutableMap()
    frameProps.forEach { (property, value) ->
        mergedProps["frame:$property"] = value.id
        mergedProps["frame:$property:value"] = value.value.toString()
    }
    emit(HostNode(tag, mergedProps, collect(content), key?.toString(), semantics))
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
            props = mapOf("direction" to currentLayoutDirection().name),
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
    val props = mutableMapOf("enabled" to enabled.toString())
    if (onClick != null) {
        props["event:onClick"] = registerHostEvent { onClick() }
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
    val props = mutableMapOf(
        "value" to value,
    )
    if (placeholder != null) {
        props["placeholder"] = placeholder
    }
    if (onInput != null) {
        props["event:onInput"] = registerHostEvent { payload ->
            onInput(payload?.toString().orEmpty())
        }
    }
    if (onSubmit != null) {
        props["event:onSubmit"] = registerHostEvent { onSubmit() }
    }
    emit(HostNode("textInput", props, key = key?.toString(), semantics = semantics))
}

public fun ComponentScope.checkbox(
    checked: Boolean,
    onToggle: (() -> Unit)? = null,
    semantics: Semantics? = Semantics(role = Role.Checkbox, focusable = true),
    key: Any? = null,
) {
    val props = mutableMapOf("checked" to checked.toString())
    if (onToggle != null) {
        props["event:onToggle"] = registerHostEvent { onToggle() }
    }
    emit(HostNode("checkbox", props, key = key?.toString(), semantics = semantics))
}

private fun ComponentScope.registerHostEvent(callback: (Any?) -> Unit): String =
    runtime.registerEvent(identity = nextEventKey(), callback = callback)
