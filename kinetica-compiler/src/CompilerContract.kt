package io.heapy.kinetica.compiler

public object KineticaCompilerContract {
    public const val pluginId: String = "io.heapy.kinetica.compiler"
    public const val pluginVersion: String = "0.1.0"
    public const val optionModuleId: String = "moduleId"
    public const val optionServerSourceSet: String = "serverSourceSet"
    public const val optionClientSourceSet: String = "clientSourceSet"

    public val responsibilities: List<CompilerResponsibility> = listOf(
        CompilerResponsibility.SlotIdGeneration,
        CompilerResponsibility.UiComponentDesugaring,
        CompilerResponsibility.Skipping,
        CompilerResponsibility.StaticHoisting,
        CompilerResponsibility.ServerClientBoundary,
        CompilerResponsibility.ServerActionStubs,
        CompilerResponsibility.Diagnostics,
        CompilerResponsibility.HotReloadProtocol,
        CompilerResponsibility.PreviewEntries,
    )
}

public enum class CompilerResponsibility {
    SlotIdGeneration,
    UiComponentDesugaring,
    Skipping,
    StaticHoisting,
    ServerClientBoundary,
    ServerActionStubs,
    Diagnostics,
    HotReloadProtocol,
    PreviewEntries,
}

public data class CompilerSlotId(
    val moduleId: String,
    val functionFqName: String,
    val declarationOrdinal: Int,
    val disambiguator: String,
) {
    public fun stableKey(): String =
        listOf(moduleId, functionFqName, declarationOrdinal.toString(), disambiguator)
            .joinToString(separator = "#")
}

public data class SlotDescriptor(
    val id: CompilerSlotId,
    val variableName: String,
    val persistent: Boolean,
    val transient: Boolean,
)

public data class ComponentParameterDescriptor(
    val name: String,
    val type: String,
    val stable: Boolean,
)

public data class PreviewEntry(
    val componentFqName: String,
    val displayName: String,
    val slotIds: List<CompilerSlotId>,
)

public data class ClientComponentManifestEntry(
    val componentFqName: String,
    val clientId: String,
    val serializablePropsType: String,
)

public data class ClientReferenceReplacement(
    val callerFqName: String,
    val calleeFqName: String,
    val clientId: String,
    val location: String? = null,
)

public data class ServerActionDescriptor(
    val functionFqName: String,
    val actionId: String,
    val inputType: String,
    val outputType: String,
    val invalidates: List<String> = emptyList(),
    val location: String? = null,
)

public data class GeneratedRouteCodec(
    val routeFqName: String,
    val codecFqName: String,
)

public data class StaticHoistDescriptor(
    val componentFqName: String,
    val hoistId: String,
    val nodeSource: String,
    val location: String? = null,
)

public data class CompilerClientComponentManifest(
    val components: List<CompilerClientComponentRegistration> = emptyList(),
    val actions: List<CompilerServerActionRegistration> = emptyList(),
)

public data class CompilerClientComponentRegistration(
    val componentId: String,
    val componentFqName: String,
    val serializablePropsType: String? = null,
)

public data class CompilerServerActionRegistration(
    val actionId: String,
    val functionFqName: String,
    val invalidates: List<String> = emptyList(),
)
