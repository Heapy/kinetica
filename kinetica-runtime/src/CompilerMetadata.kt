package io.heapy.kinetica

import kotlinx.serialization.Serializable

@Serializable
public data class ComponentParameterRegistration(
    val name: String,
    val type: String,
    val stable: Boolean,
)

@Serializable
public data class StaticHoistRegistration(
    val hoistId: String,
    val componentFqName: String,
    val node: Node,
    val location: String? = null,
)

@Serializable
public data class ComponentTransformRegistration(
    val componentFqName: String,
    val parameters: List<ComponentParameterRegistration> = emptyList(),
    val skippable: Boolean,
    val staticHoists: List<StaticHoistRegistration> = emptyList(),
)
