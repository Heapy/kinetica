package io.heapy.kinetica.compiler

public data class KineticaGeneratedSourceFile(
    val path: String,
    val text: String,
)

public class KineticaGeneratedSourceEmitter {
    public fun emit(plan: KineticaCompilerPlan): List<KineticaGeneratedSourceFile> =
        buildList {
            addAll(plan.routeCodecs.map(::routeCodecSource))
            add(serverActionsSource(plan.serverActions))
            add(clientManifestSource(plan.clientComponents))
            add(clientRefsSource(plan.clientComponents))
            add(previewsSource(plan.previews))
            add(componentTransformsSource(plan.desugaredComponents))
        }

    private fun routeCodecSource(codec: GeneratedRouteCodec): KineticaGeneratedSourceFile {
        val codecName = codec.codecFqName.simpleName()
        val codecPackage = codec.codecFqName.packageName()
        val routeType = codec.routeFqName.simpleName()
        val packageLine = codecPackage.packageLine()

        return KineticaGeneratedSourceFile(
            path = generatedPath(codec.codecFqName),
            text = """
                @file:Suppress("RedundantVisibilityModifier")

                $packageLine
                import io.heapy.kinetica.KineticaJson
                import io.heapy.kinetica.RouteCodec
                import kotlinx.serialization.decodeFromString
                import kotlinx.serialization.encodeToString

                public object $codecName : RouteCodec<$routeType> {
                    override fun encode(route: $routeType): String =
                        KineticaJson.encodeToString($routeType.serializer(), route)

                    override fun decode(value: String): $routeType =
                        KineticaJson.decodeFromString($routeType.serializer(), value)
                }
            """.trimGeneratedSource(),
        )
    }

    private fun clientManifestSource(
        components: List<ClientComponentManifestEntry>,
    ): KineticaGeneratedSourceFile =
        KineticaGeneratedSourceFile(
            path = "generated/io/heapy/kinetica/generated/KineticaClientManifest.kt",
            text = """
                @file:Suppress("RedundantVisibilityModifier")

                package io.heapy.kinetica.generated

                import io.heapy.kinetica.ClientComponentManifest
                import io.heapy.kinetica.ClientComponentRegistration

                public val KineticaGeneratedClientManifest: ClientComponentManifest = ClientComponentManifest(
                    components = listOf(
                ${components.joinToString(separator = ",\n") { it.toClientComponentRegistrationSource().prependIndent("        ") }}
                    ),
                    actions = KineticaGeneratedServerActions,
                )
            """.trimGeneratedSource(),
        )

    private fun serverActionsSource(
        actions: List<ServerActionDescriptor>,
    ): KineticaGeneratedSourceFile =
        KineticaGeneratedSourceFile(
            path = "generated/io/heapy/kinetica/generated/KineticaServerActions.kt",
            text = """
                @file:Suppress("RedundantVisibilityModifier")

                package io.heapy.kinetica.generated

                import io.heapy.kinetica.KineticaServerActionDispatcher
                import io.heapy.kinetica.ServerActionRegistration
                import io.heapy.kinetica.ServerActionStub
                import io.heapy.kinetica.serverActionStub
                import io.heapy.kinetica.serverActionPayloadSchema
                import kotlinx.serialization.serializer

                public val KineticaGeneratedServerActions: List<ServerActionRegistration> = listOf(
            ${actions.joinToString(separator = ",\n") { it.toServerActionRegistrationSource().prependIndent("    ") }}
                )

                public val KineticaGeneratedServerActionStubs: List<ServerActionStub> = listOf(
            ${actions.joinToString(separator = ",\n") { it.toServerActionStubSource().prependIndent("    ") }}
                )

                public val KineticaGeneratedServerActionDispatcher: KineticaServerActionDispatcher =
                    KineticaServerActionDispatcher(KineticaGeneratedServerActionStubs)
            """.trimGeneratedSource(),
        )

    private fun clientRefsSource(
        components: List<ClientComponentManifestEntry>,
    ): KineticaGeneratedSourceFile =
        KineticaGeneratedSourceFile(
            path = "generated/io/heapy/kinetica/generated/KineticaClientRefs.kt",
            text = """
                @file:Suppress("RedundantVisibilityModifier")

                package io.heapy.kinetica.generated

                import io.heapy.kinetica.ClientRef
                import io.heapy.kinetica.KineticaJson
                import kotlinx.serialization.json.encodeToJsonElement
                import kotlinx.serialization.json.JsonObject
                import kotlinx.serialization.json.jsonObject
                import kotlinx.serialization.serializer

                ${components.joinToString(separator = "\n\n") { it.toClientRefFactorySource() }}
            """.trimGeneratedSource(),
        )

    private fun previewsSource(
        previews: List<PreviewEntry>,
    ): KineticaGeneratedSourceFile =
        KineticaGeneratedSourceFile(
            path = "generated/io/heapy/kinetica/generated/KineticaPreviews.kt",
            text = """
                @file:Suppress("RedundantVisibilityModifier")

                package io.heapy.kinetica.generated

                import io.heapy.kinetica.PreviewDescriptor
                import io.heapy.kinetica.SlotId

                public val KineticaGeneratedPreviews: List<PreviewDescriptor> = listOf(
            ${previews.joinToString(separator = ",\n") { it.toPreviewDescriptorSource().prependIndent("    ") }}
                )
            """.trimGeneratedSource(),
        )

    private fun componentTransformsSource(
        components: List<DesugaredComponent>,
    ): KineticaGeneratedSourceFile =
        KineticaGeneratedSourceFile(
            path = "generated/io/heapy/kinetica/generated/KineticaComponentTransforms.kt",
            text = """
                @file:Suppress("RedundantVisibilityModifier")

                package io.heapy.kinetica.generated

                import io.heapy.kinetica.ComponentParameterRegistration
                import io.heapy.kinetica.ComponentTransformRegistration
                import io.heapy.kinetica.StaticHoistRegistration
                import io.heapy.kinetica.TextNode

                public const val KineticaGeneratedCompilerPluginId: String = ${KineticaCompilerContract.pluginId.kotlinStringLiteral()}
                public const val KineticaGeneratedCompilerPluginVersion: String = ${KineticaCompilerContract.pluginVersion.kotlinStringLiteral()}

                public val KineticaGeneratedComponentTransforms: List<ComponentTransformRegistration> = listOf(
            ${components.joinToString(separator = ",\n") { it.toComponentTransformRegistrationSource().prependIndent("    ") }}
                )
            """.trimGeneratedSource(),
        )

    private fun ClientComponentManifestEntry.toClientComponentRegistrationSource(): String =
        """
            ClientComponentRegistration(
                componentId = ${clientId.kotlinStringLiteral()},
                componentFqName = ${componentFqName.kotlinStringLiteral()},
                serializablePropsType = ${serializablePropsType.kotlinStringLiteral()},
            )
        """.trimIndent()

    private fun ServerActionDescriptor.toServerActionRegistrationSource(): String =
        """
            ServerActionRegistration(
                actionId = ${actionId.kotlinStringLiteral()},
                functionFqName = ${functionFqName.kotlinStringLiteral()},
                invalidates = listOf(${invalidates.joinToString(separator = ", ") { it.kotlinStringLiteral() }}),
                inputSchema = serverActionPayloadSchema(serializer<$inputType>()),
            )
        """.trimIndent()

    private fun ServerActionDescriptor.toServerActionStubSource(): String {
        val handlerSource = if (inputType == "kotlin.Unit") {
            "handler = { _: kotlin.Unit -> $functionFqName() },"
        } else {
            "handler = { input -> $functionFqName(input) },"
        }
        return """
            serverActionStub(
                registration = ${toServerActionRegistrationSource().prependIndent("        ").trimStart()},
                inputSerializer = serializer<$inputType>(),
                outputSerializer = serializer<$outputType>(),
                $handlerSource
            )
        """.trimIndent()
    }

    private fun ClientComponentManifestEntry.toClientRefFactorySource(): String {
        val functionName = "clientRef_${componentFqName.toIdentifier()}"
        val rawFactory = """
            public fun $functionName(
                props: JsonObject = JsonObject(emptyMap()),
            ): ClientRef =
                ClientRef(
                    componentId = ${clientId.kotlinStringLiteral()},
                    props = props,
                )
        """.trimIndent()

        if (serializablePropsType == "kotlin.Unit") {
            return rawFactory
        }

        val typedFactory = """
            public fun $functionName(
                props: $serializablePropsType,
            ): ClientRef =
                ClientRef(
                    componentId = ${clientId.kotlinStringLiteral()},
                    props = KineticaJson.encodeToJsonElement(serializer<$serializablePropsType>(), props).jsonObject,
                )
        """.trimIndent()

        return "$rawFactory\n\n$typedFactory"
    }

    private fun PreviewEntry.toPreviewDescriptorSource(): String =
        """
            PreviewDescriptor(
                componentFqName = ${componentFqName.kotlinStringLiteral()},
                displayName = ${displayName.kotlinStringLiteral()},
                slotIds = listOf(
            ${slotIds.joinToString(separator = ",\n") { it.toSlotIdSource().prependIndent("        ") }}
                ),
            )
        """.trimIndent()

    private fun DesugaredComponent.toComponentTransformRegistrationSource(): String =
        """
            ComponentTransformRegistration(
                componentFqName = ${componentFqName.kotlinStringLiteral()},
                parameters = listOf(
        ${parameters.joinToString(separator = ",\n") { it.toComponentParameterRegistrationSource().prependIndent("        ") }}
                ),
                skippable = $skippable,
                staticHoists = listOf(
        ${staticHoists.joinToString(separator = ",\n") { it.toStaticHoistRegistrationSource().prependIndent("        ") }}
                ),
            )
        """.trimIndent()

    private fun ComponentParameterDescriptor.toComponentParameterRegistrationSource(): String =
        """
            ComponentParameterRegistration(
                name = ${name.kotlinStringLiteral()},
                type = ${type.kotlinStringLiteral()},
                stable = $stable,
            )
        """.trimIndent()

    private fun StaticHoistDescriptor.toStaticHoistRegistrationSource(): String =
        """
            StaticHoistRegistration(
                hoistId = ${hoistId.kotlinStringLiteral()},
                componentFqName = ${componentFqName.kotlinStringLiteral()},
                node = $nodeSource,
                location = ${location?.kotlinStringLiteral() ?: "null"},
            )
        """.trimIndent()

    private fun CompilerSlotId.toSlotIdSource(): String =
        """
            SlotId(
                moduleId = ${moduleId.kotlinStringLiteral()},
                functionFqName = ${functionFqName.kotlinStringLiteral()},
                declarationOrdinal = $declarationOrdinal,
                disambiguator = ${disambiguator.kotlinStringLiteral()},
            )
        """.trimIndent()

    private fun generatedPath(fqName: String): String =
        "generated/${fqName.replace('.', '/')}.kt"

    private fun String.packageName(): String =
        substringBeforeLast('.', missingDelimiterValue = "")

    private fun String.simpleName(): String =
        substringAfterLast('.')

    private fun String.packageLine(): String =
        if (isBlank()) "" else "package $this\n"

    private fun String.toIdentifier(): String {
        val sanitized = map { character ->
            if (character.isLetterOrDigit()) character else '_'
        }.joinToString(separator = "")
        return if (sanitized.firstOrNull()?.isDigit() == true) "_$sanitized" else sanitized
    }

    private fun String.kotlinStringLiteral(): String =
        buildString {
            append('"')
            this@kotlinStringLiteral.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '$' -> {
                        append('\\')
                        append('$')
                    }
                    else -> append(character)
                }
            }
            append('"')
        }

    private fun String.trimGeneratedSource(): String =
        trimIndent()
            .lines()
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
            .joinToString(separator = "\n", postfix = "\n")
}
