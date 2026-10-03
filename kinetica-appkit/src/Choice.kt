package io.heapy.kinetica.appkit

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.Semantics
import io.heapy.kinetica.UiComponent
import io.heapy.kinetica.host
import io.heapy.kinetica.hostEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public data class ChoiceOption(val value: String, val label: String)

/** Controlled native popup button. Values remain stable even when labels change. */
@UiComponent
public fun ComponentScope.appKitChoice(
    value: String,
    options: List<ChoiceOption>,
    onChange: (String) -> Unit,
    semantics: Semantics? = null,
    key: String? = null,
    width: Double? = null,
) {
    require(options.isNotEmpty() && options.map { it.value }.distinct().size == options.size)
    require(options.any { it.value == value }) { "Selected choice is missing from options" }
    require(width == null || width.isFinite() && width > 0)
    val event = hostEvent<String>(onEvent = onChange)
    val props = mapOf("options" to Json.encodeToString(options), "value" to value, "event:onChoice" to event) +
        if (width == null) emptyMap() else mapOf("width" to width.toString())
    host(CHOICE_TAG, props = props,
        semantics = semantics, key = key)
}

internal const val CHOICE_TAG = "appkit:choice"
