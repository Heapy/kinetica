package io.heapy.kinetica

import kotlinx.serialization.Serializable

@Serializable
public data class PreviewDescriptor(
    val componentFqName: String,
    val displayName: String,
    val slotIds: List<SlotId> = emptyList(),
)
