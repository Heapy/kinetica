package io.heapy.kinetica.compiler

public enum class ComponentSourceSet {
    Common,
    Server,
    Client,
}

public enum class ComponentAnnotation {
    UiComponent,
    ServerComponent,
    ClientComponent,
    Preview,
}

public data class SlotDeclaration(
    val variableName: String,
    val persistent: Boolean = false,
    val transient: Boolean = false,
    val disambiguator: String = variableName,
)

public data class ComponentParameterDeclaration(
    val name: String,
    val type: String,
)

public data class StaticNodeDeclaration(
    val nodeSource: String,
    val location: String? = null,
)

public data class ComponentCall(
    val calleeFqName: String,
    val location: String? = null,
)

public data class ComponentDeclaration(
    val fqName: String,
    val sourceSet: ComponentSourceSet = ComponentSourceSet.Common,
    val annotations: Set<ComponentAnnotation> = emptySet(),
    val parameters: List<ComponentParameterDeclaration> = emptyList(),
    val slots: List<SlotDeclaration> = emptyList(),
    val calls: List<ComponentCall> = emptyList(),
    val staticNodes: List<StaticNodeDeclaration> = emptyList(),
    val previewName: String? = null,
    val serializablePropsType: String? = null,
    val location: String? = null,
)

public data class RouteDeclaration(
    val fqName: String,
    val serializable: Boolean,
    val location: String? = null,
)

public data class ServerActionDeclaration(
    val functionFqName: String,
    val inputType: String,
    val outputType: String,
    val invalidates: List<String> = emptyList(),
    val location: String? = null,
)

public enum class DiagnosticSeverity {
    Warning,
    Error,
}

public data class CompilerDiagnostic(
    val severity: DiagnosticSeverity,
    val code: String,
    val message: String,
    val declarationFqName: String? = null,
    val location: String? = null,
)

public data class DesugaredComponent(
    val componentFqName: String,
    val scopeParameterName: String = "\$scope",
    val producesNode: Boolean = true,
    val slotIds: List<CompilerSlotId> = emptyList(),
    val parameters: List<ComponentParameterDescriptor> = emptyList(),
    val skippable: Boolean = true,
    val staticHoists: List<StaticHoistDescriptor> = emptyList(),
)

public data class KineticaCompilerPlan(
    val moduleId: String,
    val slots: List<SlotDescriptor> = emptyList(),
    val previews: List<PreviewEntry> = emptyList(),
    val clientComponents: List<ClientComponentManifestEntry> = emptyList(),
    val clientReferences: List<ClientReferenceReplacement> = emptyList(),
    val serverActions: List<ServerActionDescriptor> = emptyList(),
    val desugaredComponents: List<DesugaredComponent> = emptyList(),
    val staticHoists: List<StaticHoistDescriptor> = emptyList(),
    val routeCodecs: List<GeneratedRouteCodec> = emptyList(),
    val diagnostics: List<CompilerDiagnostic> = emptyList(),
) {
    public val hasErrors: Boolean
        get() = diagnostics.any { it.severity == DiagnosticSeverity.Error }
}

public data class HotReloadPlan(
    val preserved: List<CompilerSlotId> = emptyList(),
    val added: List<CompilerSlotId> = emptyList(),
    val removed: List<CompilerSlotId> = emptyList(),
    val reset: List<CompilerSlotId> = emptyList(),
)

public class KineticaCompilerAnalyzer(
    private val moduleId: String,
) {
    init {
        require(moduleId.isNotBlank()) { "moduleId must not be blank." }
    }

    public fun analyze(
        declarations: List<ComponentDeclaration>,
        routes: List<RouteDeclaration> = emptyList(),
        serverActions: List<ServerActionDeclaration> = emptyList(),
        sourceDiagnostics: List<CompilerDiagnostic> = emptyList(),
    ): KineticaCompilerPlan {
        val diagnostics = sourceDiagnostics.toMutableList()
        val declarationByName = declarations.groupBy { it.fqName }
        declarationByName.filterValues { it.size > 1 }.forEach { (fqName, _) ->
            diagnostics += CompilerDiagnostic(
                severity = DiagnosticSeverity.Error,
                code = "KINETICA_DUPLICATE_COMPONENT",
                message = "Duplicate component declaration: $fqName",
                declarationFqName = fqName,
                location = declarations.firstOrNull { it.fqName == fqName }?.location,
            )
        }
        val uniqueDeclarations = declarationByName.values.map { it.first() }
        val byName = uniqueDeclarations.associateBy { it.fqName }

        val slots = uniqueDeclarations.flatMap { declaration ->
            declaration.slots.mapIndexed { ordinal, slot ->
                SlotDescriptor(
                    id = CompilerSlotId(
                        moduleId = moduleId,
                        functionFqName = declaration.fqName,
                        declarationOrdinal = ordinal,
                        disambiguator = slot.disambiguator,
                    ),
                    variableName = slot.variableName,
                    persistent = slot.persistent,
                    transient = slot.transient,
                )
            }
        }

        val slotsByFunction = slots.groupBy { it.id.functionFqName }
        val previews = uniqueDeclarations.mapNotNull { declaration ->
            if (ComponentAnnotation.Preview !in declaration.annotations) {
                return@mapNotNull null
            }
            if (!declaration.isUiComponentLike()) {
                diagnostics += CompilerDiagnostic(
                    severity = DiagnosticSeverity.Error,
                    code = "KINETICA_PREVIEW_REQUIRES_UI_COMPONENT",
                    message = "@Preview can only be used on a Kinetica UI component.",
                    declarationFqName = declaration.fqName,
                    location = declaration.location,
                )
            }
            PreviewEntry(
                componentFqName = declaration.fqName,
                displayName = declaration.previewName?.takeIf { it.isNotBlank() } ?: declaration.simpleName(),
                slotIds = slotsByFunction[declaration.fqName]?.map { it.id }.orEmpty(),
            )
        }

        val clientComponents = uniqueDeclarations
            .filter { ComponentAnnotation.ClientComponent in it.annotations || it.sourceSet == ComponentSourceSet.Client }
            .map { declaration ->
                ClientComponentManifestEntry(
                    componentFqName = declaration.fqName,
                    clientId = declaration.fqName.toClientId(),
                    serializablePropsType = declaration.serializablePropsType ?: "kotlin.Unit",
                )
            }

        val clientReferences = mutableListOf<ClientReferenceReplacement>()
        uniqueDeclarations.forEach { declaration ->
            declaration.calls.forEach { call ->
                val callee = byName[call.calleeFqName]
                if (callee == null) {
                    diagnostics += CompilerDiagnostic(
                        severity = DiagnosticSeverity.Warning,
                        code = "KINETICA_UNKNOWN_COMPONENT_CALL",
                        message = "Unable to resolve Kinetica component call: ${call.calleeFqName}",
                        declarationFqName = declaration.fqName,
                        location = call.location ?: declaration.location,
                    )
                    return@forEach
                }
                if (declaration.sourceSet == ComponentSourceSet.Client && callee.isServerOnly()) {
                    diagnostics += CompilerDiagnostic(
                        severity = DiagnosticSeverity.Error,
                        code = "KINETICA_CLIENT_CALLS_SERVER",
                        message = "Client component ${declaration.fqName} cannot call server component ${callee.fqName}.",
                        declarationFqName = declaration.fqName,
                        location = call.location ?: declaration.location,
                    )
                }
                if (declaration.sourceSet != ComponentSourceSet.Client && callee.isClientOnly()) {
                    clientReferences += ClientReferenceReplacement(
                        callerFqName = declaration.fqName,
                        calleeFqName = callee.fqName,
                        clientId = callee.fqName.toClientId(),
                        location = call.location ?: declaration.location,
                    )
                }
            }
        }

        val routeCodecs = routes
            .distinctBy { route -> route.fqName }
            .mapNotNull { route ->
                if (!route.serializable) {
                    diagnostics += CompilerDiagnostic(
                        severity = DiagnosticSeverity.Error,
                        code = "KINETICA_ROUTE_REQUIRES_SERIALIZABLE",
                        message = "Route ${route.fqName} must be annotated with @Serializable for generated RouteCodec support.",
                        declarationFqName = route.fqName,
                        location = route.location,
                    )
                    return@mapNotNull null
                }
                GeneratedRouteCodec(
                    routeFqName = route.fqName,
                    codecFqName = route.generatedCodecFqName(),
                )
            }

        val actionDescriptors = serverActions
            .distinctBy { action -> action.functionFqName }
            .map { action ->
                ServerActionDescriptor(
                    functionFqName = action.functionFqName,
                    actionId = action.functionFqName.toActionId(),
                    inputType = action.inputType,
                    outputType = action.outputType,
                    invalidates = action.invalidates,
                    location = action.location,
                )
            }

        val desugared = uniqueDeclarations
            .filter { it.isUiComponentLike() }
            .map { declaration ->
                val parameters = declaration.parameters.map { parameter ->
                    ComponentParameterDescriptor(
                        name = parameter.name,
                        type = parameter.type,
                        stable = parameter.type.isStableComponentInput(),
                    )
                }
                val staticHoists = declaration.staticNodes.mapIndexed { ordinal, staticNode ->
                    StaticHoistDescriptor(
                        componentFqName = declaration.fqName,
                        hoistId = "${declaration.fqName.toClientId()}#static#$ordinal",
                        nodeSource = staticNode.nodeSource,
                        location = staticNode.location,
                    )
                }
                DesugaredComponent(
                    componentFqName = declaration.fqName,
                    slotIds = slotsByFunction[declaration.fqName]?.map { it.id }.orEmpty(),
                    parameters = parameters,
                    skippable = parameters.all { parameter -> parameter.stable },
                    staticHoists = staticHoists,
                )
            }

        return KineticaCompilerPlan(
            moduleId = moduleId,
            slots = slots,
            previews = previews,
            clientComponents = clientComponents.distinctBy { it.componentFqName },
            clientReferences = clientReferences.distinctBy { reference ->
                "${reference.callerFqName}:${reference.calleeFqName}:${reference.location.orEmpty()}"
            },
            serverActions = actionDescriptors,
            desugaredComponents = desugared,
            staticHoists = desugared.flatMap { component -> component.staticHoists },
            routeCodecs = routeCodecs,
            diagnostics = diagnostics,
        )
    }

    public fun planHotReload(
        previous: List<SlotDescriptor>,
        next: List<SlotDescriptor>,
    ): HotReloadPlan {
        val previousById = previous.associateBy { it.id }
        val nextById = next.associateBy { it.id }
        val preserved = next.mapNotNull { slot ->
            previousById[slot.id]?.let { previousSlot ->
                slot.id.takeIf {
                    previousSlot.variableName == slot.variableName &&
                        previousSlot.persistent == slot.persistent &&
                        previousSlot.transient == slot.transient
                }
            }
        }
        val reset = next.mapNotNull { slot ->
            previousById[slot.id]?.let { previousSlot ->
                slot.id.takeIf {
                    previousSlot.variableName != slot.variableName ||
                        previousSlot.persistent != slot.persistent ||
                        previousSlot.transient != slot.transient
                }
            }
        }
        return HotReloadPlan(
            preserved = preserved,
            added = nextById.keys.filterNot { it in previousById.keys },
            removed = previousById.keys.filterNot { it in nextById.keys },
            reset = reset,
        )
    }
}

private fun ComponentDeclaration.isUiComponentLike(): Boolean =
    ComponentAnnotation.UiComponent in annotations ||
        ComponentAnnotation.ServerComponent in annotations ||
        ComponentAnnotation.ClientComponent in annotations

private fun ComponentDeclaration.isServerOnly(): Boolean =
    ComponentAnnotation.ServerComponent in annotations || sourceSet == ComponentSourceSet.Server

private fun ComponentDeclaration.isClientOnly(): Boolean =
    ComponentAnnotation.ClientComponent in annotations || sourceSet == ComponentSourceSet.Client

private fun ComponentDeclaration.simpleName(): String =
    fqName.substringAfterLast('.')

private fun String.toClientId(): String =
    replace('.', '/')

private fun String.toActionId(): String =
    replace('.', '/')

private fun String.isStableComponentInput(): Boolean =
    "->" !in this && !contains("kotlin.Function")

private fun RouteDeclaration.generatedCodecFqName(): String =
    "${fqName}Codec"

public fun KineticaCompilerPlan.toClientComponentManifest(
    actions: List<CompilerServerActionRegistration> = emptyList(),
): CompilerClientComponentManifest =
    CompilerClientComponentManifest(
        components = clientComponents.map { entry ->
            CompilerClientComponentRegistration(
                componentId = entry.clientId,
                componentFqName = entry.componentFqName,
                serializablePropsType = entry.serializablePropsType,
            )
        },
        actions = actions + serverActions.map { action ->
            CompilerServerActionRegistration(
                actionId = action.actionId,
                functionFqName = action.functionFqName,
                invalidates = action.invalidates,
            )
        },
    )
