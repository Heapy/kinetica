package io.heapy.kinetica

import kotlinx.serialization.Serializable

@Serializable
public data class Semantics(
    val role: Role? = null,
    val label: String? = null,
    val stateDescription: String? = null,
    val focusable: Boolean = false,
    val traversalIndex: Int? = null,
    val testTag: String? = null,
    val leaving: Boolean = false,
)

@Serializable
public enum class Role {
    Button,
    Checkbox,
    Text,
    TextInput,
    List,
    ListItem,
    Navigation,
    Dialog,
    Image,
    None,
}

@Serializable
public enum class LayoutDirection {
    Ltr,
    Rtl,
}

