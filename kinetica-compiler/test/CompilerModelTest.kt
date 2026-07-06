package io.heapy.kinetica.compiler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerModelTest {
    @Test
    fun compilerContractListsResponsibilitiesAndDescriptorDefaults() {
        assertEquals("io.heapy.kinetica.compiler", KineticaCompilerContract.pluginId)
        assertEquals("0.2.0", KineticaCompilerContract.pluginVersion)
        assertEquals("moduleId", KineticaCompilerContract.optionModuleId)
        assertEquals("serverSourceSet", KineticaCompilerContract.optionServerSourceSet)
        assertEquals("clientSourceSet", KineticaCompilerContract.optionClientSourceSet)
        assertEquals(CompilerResponsibility.entries.toList(), KineticaCompilerContract.responsibilities)

        val slotId = CompilerSlotId(
            moduleId = "shop",
            functionFqName = "app.ShopScreen",
            declarationOrdinal = 2,
            disambiguator = "filter",
        )
        assertEquals("shop#app.ShopScreen#2#filter", slotId.stableKey())
        assertEquals(
            SlotDescriptor(
                id = slotId,
                variableName = "filter",
                persistent = true,
                transient = false,
            ),
            SlotDescriptor(slotId, "filter", persistent = true, transient = false),
        )
        assertEquals(
            PreviewEntry(
                componentFqName = "app.ShopScreen",
                displayName = "Shop",
                slotIds = listOf(slotId),
            ),
            PreviewEntry("app.ShopScreen", "Shop", listOf(slotId)),
        )
        assertEquals(
            ClientComponentManifestEntry(
                componentFqName = "app.AddToCartButton",
                clientId = "app/AddToCartButton",
                serializablePropsType = "kotlin.Unit",
            ),
            ClientComponentManifestEntry("app.AddToCartButton", "app/AddToCartButton", "kotlin.Unit"),
        )
        assertEquals(emptyList(), ServerActionDescriptor("app.refresh", "app/refresh", "kotlin.Unit", "kotlin.Unit").invalidates)
        assertEquals(null, ClientReferenceReplacement("app.A", "app.B", "app/B").location)
        assertEquals(null, StaticHoistDescriptor("app.A", "app/A#static#0", "TextNode(\"A\")").location)
        assertEquals(emptyList(), CompilerClientComponentManifest().components)
        assertEquals(null, CompilerClientComponentRegistration("id", "app.Client").serializablePropsType)
        assertEquals(emptyList(), CompilerServerActionRegistration("action", "app.action").invalidates)
    }

    @Test
    fun compilerSourceAndPlanModelsExposeStableDefaults() {
        val slot = SlotDeclaration(variableName = "count")
        assertFalse(slot.persistent)
        assertFalse(slot.transient)
        assertEquals("count", slot.disambiguator)

        assertEquals(null, StaticNodeDeclaration(nodeSource = "TextNode(\"Badge\")").location)
        assertEquals(null, ComponentCall(calleeFqName = "app.Badge").location)

        val component = ComponentDeclaration(fqName = "app.Counter")
        assertEquals(ComponentSourceSet.Common, component.sourceSet)
        assertEquals(emptySet(), component.annotations)
        assertEquals(emptyList(), component.parameters)
        assertEquals(emptyList(), component.slots)
        assertEquals(emptyList(), component.calls)
        assertEquals(emptyList(), component.staticNodes)
        assertEquals(null, component.previewName)
        assertEquals(null, component.serializablePropsType)
        assertEquals(null, component.location)

        assertEquals(null, RouteDeclaration(fqName = "app.Home", serializable = true).location)
        assertEquals(
            ServerActionDeclaration(
                functionFqName = "app.refresh",
                inputType = "kotlin.Unit",
                outputType = "kotlin.Unit",
            ),
            ServerActionDeclaration("app.refresh", "kotlin.Unit", "kotlin.Unit"),
        )
        assertEquals(
            CompilerDiagnostic(
                severity = DiagnosticSeverity.Warning,
                code = "KINETICA_WARNING",
                message = "warn",
            ),
            CompilerDiagnostic(DiagnosticSeverity.Warning, "KINETICA_WARNING", "warn"),
        )

        val sourceFile = KineticaSourceFile(path = "Counter.kt", packageName = "app", text = "fun plain() = Unit")
        assertEquals("Counter.kt", sourceFile.path)
        assertEquals(KineticaSourceModel(), KineticaSourceModel())
        assertEquals(KineticaSourceTransformation("Counter.kt", sourceFile.text, changed = false), KineticaSourceTransformation("Counter.kt", sourceFile.text, false))

        val desugared = DesugaredComponent(componentFqName = "app.Counter")
        assertEquals("\$scope", desugared.scopeParameterName)
        assertTrue(desugared.producesNode)
        assertEquals(emptyList(), desugared.slotIds)
        assertEquals(emptyList(), desugared.parameters)
        assertTrue(desugared.skippable)
        assertEquals(emptyList(), desugared.staticHoists)

        val plan = KineticaCompilerPlan(moduleId = "app")
        assertFalse(plan.hasErrors)
        assertEquals(emptyList(), plan.slots)
        assertEquals(emptyList(), plan.previews)
        assertEquals(emptyList(), plan.clientComponents)
        assertEquals(emptyList(), plan.clientReferences)
        assertEquals(emptyList(), plan.serverActions)
        assertEquals(emptyList(), plan.desugaredComponents)
        assertEquals(emptyList(), plan.staticHoists)
        assertEquals(emptyList(), plan.routeCodecs)
        assertEquals(emptyList(), plan.diagnostics)

        val errorPlan = plan.copy(
            diagnostics = listOf(
                CompilerDiagnostic(DiagnosticSeverity.Error, "KINETICA_ERROR", "broken"),
            ),
        )
        assertTrue(errorPlan.hasErrors)
        assertEquals(HotReloadPlan(), HotReloadPlan())
    }

    @Test
    fun analyzerGeneratesSlotsPreviewsClientManifestAndDesugarPlan() {
        val analyzer = KineticaCompilerAnalyzer(moduleId = "todo")

        val plan = analyzer.analyze(
            listOf(
                ComponentDeclaration(
                    fqName = "app.TodoScreen",
                    annotations = setOf(ComponentAnnotation.UiComponent, ComponentAnnotation.Preview),
                    previewName = "Todo",
                    slots = listOf(
                        SlotDeclaration("draft", persistent = true),
                        SlotDeclaration("filter"),
                    ),
                    calls = listOf(ComponentCall("app.AddTodoButton", location = "TodoScreen:12")),
                ),
                ComponentDeclaration(
                    fqName = "app.AddTodoButton",
                    sourceSet = ComponentSourceSet.Client,
                    annotations = setOf(ComponentAnnotation.ClientComponent),
                    serializablePropsType = "app.AddTodoProps",
                ),
            ),
        )

        assertFalse(plan.hasErrors)
        assertEquals(
            listOf(
                SlotDescriptor(
                    id = CompilerSlotId("todo", "app.TodoScreen", 0, "draft"),
                    variableName = "draft",
                    persistent = true,
                    transient = false,
                ),
                SlotDescriptor(
                    id = CompilerSlotId("todo", "app.TodoScreen", 1, "filter"),
                    variableName = "filter",
                    persistent = false,
                    transient = false,
                ),
            ),
            plan.slots,
        )
        assertEquals(
            listOf(
                PreviewEntry(
                    componentFqName = "app.TodoScreen",
                    displayName = "Todo",
                    slotIds = listOf(
                        CompilerSlotId("todo", "app.TodoScreen", 0, "draft"),
                        CompilerSlotId("todo", "app.TodoScreen", 1, "filter"),
                    ),
                ),
            ),
            plan.previews,
        )
        assertEquals(
            listOf(
                ClientComponentManifestEntry(
                    componentFqName = "app.AddTodoButton",
                    clientId = "app/AddTodoButton",
                    serializablePropsType = "app.AddTodoProps",
                ),
            ),
            plan.clientComponents,
        )
        assertEquals(
            listOf(
                ClientReferenceReplacement(
                    callerFqName = "app.TodoScreen",
                    calleeFqName = "app.AddTodoButton",
                    clientId = "app/AddTodoButton",
                    location = "TodoScreen:12",
                ),
            ),
            plan.clientReferences,
        )
        assertEquals(
            CompilerClientComponentManifest(
                components = listOf(
                    CompilerClientComponentRegistration(
                        componentId = "app/AddTodoButton",
                        componentFqName = "app.AddTodoButton",
                        serializablePropsType = "app.AddTodoProps",
                    ),
                ),
                actions = listOf(
                    CompilerServerActionRegistration(
                        actionId = "app/server/createTodo",
                        functionFqName = "app.server.createTodo",
                        invalidates = listOf("todos"),
                    ),
                ),
            ),
            plan.copy(
                serverActions = listOf(
                    ServerActionDescriptor(
                        functionFqName = "app.server.createTodo",
                        actionId = "app/server/createTodo",
                        inputType = "app.TodoDraft",
                        outputType = "app.Todo",
                        invalidates = listOf("todos"),
                    ),
                ),
            ).toClientComponentManifest(),
        )
        assertEquals(
            listOf(
                DesugaredComponent(
                    componentFqName = "app.TodoScreen",
                    slotIds = listOf(
                        CompilerSlotId("todo", "app.TodoScreen", 0, "draft"),
                        CompilerSlotId("todo", "app.TodoScreen", 1, "filter"),
                    ),
                ),
                DesugaredComponent(componentFqName = "app.AddTodoButton"),
            ),
            plan.desugaredComponents,
        )
    }

    @Test
    fun analyzerReportsPreviewAndServerClientBoundaryDiagnostics() {
        val plan = KineticaCompilerAnalyzer(moduleId = "shop").analyze(
            listOf(
                ComponentDeclaration(
                    fqName = "app.client.CartButton",
                    sourceSet = ComponentSourceSet.Client,
                    annotations = setOf(ComponentAnnotation.ClientComponent),
                    calls = listOf(
                        ComponentCall("app.server.SecretPrice", location = "CartButton:8"),
                        ComponentCall("app.Missing", location = "CartButton:9"),
                        ComponentCall("app.server.SourceSetOnly", location = "CartButton:10"),
                    ),
                ),
                ComponentDeclaration(
                    fqName = "app.server.SecretPrice",
                    sourceSet = ComponentSourceSet.Server,
                    annotations = setOf(ComponentAnnotation.ServerComponent),
                ),
                ComponentDeclaration(
                    fqName = "app.server.SourceSetOnly",
                    sourceSet = ComponentSourceSet.Server,
                ),
                ComponentDeclaration(
                    fqName = "app.NotAComponent",
                    annotations = setOf(ComponentAnnotation.Preview),
                ),
            ),
        )

        assertTrue(plan.hasErrors)
        assertEquals(
            setOf(
                "KINETICA_CLIENT_CALLS_SERVER",
                "KINETICA_UNKNOWN_COMPONENT_CALL",
                "KINETICA_PREVIEW_REQUIRES_UI_COMPONENT",
            ),
            plan.diagnostics.map { it.code }.toSet(),
        )
        assertEquals(
            mapOf(
                "KINETICA_CLIENT_CALLS_SERVER" to DiagnosticSeverity.Error,
                "KINETICA_UNKNOWN_COMPONENT_CALL" to DiagnosticSeverity.Warning,
                "KINETICA_PREVIEW_REQUIRES_UI_COMPONENT" to DiagnosticSeverity.Error,
            ),
            plan.diagnostics.associate { it.code to it.severity },
        )
    }

    @Test
    fun analyzerHonorsAnnotationOnlyClientAndServerBoundaries() {
        val plan = KineticaCompilerAnalyzer(moduleId = "shop").analyze(
            listOf(
                ComponentDeclaration(
                    fqName = "app.client.ClientShell",
                    sourceSet = ComponentSourceSet.Client,
                    annotations = setOf(ComponentAnnotation.ClientComponent),
                    calls = listOf(ComponentCall("app.server.AnnotatedServer", location = "ClientShell.kt:6:9")),
                ),
                ComponentDeclaration(
                    fqName = "app.server.AnnotatedServer",
                    annotations = setOf(ComponentAnnotation.ServerComponent),
                ),
                ComponentDeclaration(
                    fqName = "app.Storefront",
                    annotations = setOf(ComponentAnnotation.UiComponent),
                    calls = listOf(ComponentCall("app.islands.AnnotatedClient", location = "Storefront.kt:4:5")),
                ),
                ComponentDeclaration(
                    fqName = "app.islands.AnnotatedClient",
                    annotations = setOf(ComponentAnnotation.ClientComponent),
                    serializablePropsType = "app.islands.AnnotatedClientProps",
                ),
            ),
        )

        assertTrue(plan.hasErrors)
        assertEquals(
            CompilerDiagnostic(
                severity = DiagnosticSeverity.Error,
                code = "KINETICA_CLIENT_CALLS_SERVER",
                message = "Client component app.client.ClientShell cannot call server component app.server.AnnotatedServer.",
                declarationFqName = "app.client.ClientShell",
                location = "ClientShell.kt:6:9",
            ),
            plan.diagnostics.single(),
        )
        assertEquals(
            listOf(
                ClientReferenceReplacement(
                    callerFqName = "app.Storefront",
                    calleeFqName = "app.islands.AnnotatedClient",
                    clientId = "app/islands/AnnotatedClient",
                    location = "Storefront.kt:4:5",
                ),
            ),
            plan.clientReferences,
        )
        assertEquals(
            listOf(
                ClientComponentManifestEntry(
                    componentFqName = "app.client.ClientShell",
                    clientId = "app/client/ClientShell",
                    serializablePropsType = "kotlin.Unit",
                ),
                ClientComponentManifestEntry(
                    componentFqName = "app.islands.AnnotatedClient",
                    clientId = "app/islands/AnnotatedClient",
                    serializablePropsType = "app.islands.AnnotatedClientProps",
                ),
            ),
            plan.clientComponents,
        )
    }

    @Test
    fun analyzerCoversFallbacksDuplicatesSourceDiagnosticsAndHotReloadResets() {
        val blankModuleId = assertFailsWith<IllegalArgumentException> {
            KineticaCompilerAnalyzer(moduleId = " ")
        }
        assertEquals("moduleId must not be blank.", blankModuleId.message)

        val sourceDiagnostic = CompilerDiagnostic(
            severity = DiagnosticSeverity.Warning,
            code = "KINETICA_SOURCE_WARNING",
            message = "Source-level warning.",
            declarationFqName = "app.common.Shell",
            location = "Shell.kt:2:1",
        )
        val plan = KineticaCompilerAnalyzer(moduleId = "edge").analyze(
            declarations = listOf(
                ComponentDeclaration(
                    fqName = "app.common.Shell",
                    annotations = setOf(ComponentAnnotation.UiComponent, ComponentAnnotation.Preview),
                    previewName = " ",
                    calls = listOf(
                        ComponentCall("app.client.Button"),
                        ComponentCall("app.client.Button"),
                        ComponentCall("app.Missing"),
                    ),
                    location = "Shell.kt:3:1",
                ),
                ComponentDeclaration(
                    fqName = "app.client.Button",
                    sourceSet = ComponentSourceSet.Client,
                    serializablePropsType = null,
                    location = "Button.kt:1:1",
                ),
                ComponentDeclaration(
                    fqName = "app.server.Page",
                    sourceSet = ComponentSourceSet.Server,
                    calls = listOf(ComponentCall("app.client.Button")),
                    location = "Page.kt:5:1",
                ),
                ComponentDeclaration(
                    fqName = "app.Duplicate",
                    annotations = setOf(ComponentAnnotation.UiComponent),
                    location = "DuplicateA.kt:1:1",
                ),
                ComponentDeclaration(
                    fqName = "app.Duplicate",
                    annotations = setOf(ComponentAnnotation.UiComponent),
                    location = "DuplicateB.kt:1:1",
                ),
            ),
            sourceDiagnostics = listOf(sourceDiagnostic),
        )

        assertTrue(plan.hasErrors)
        assertEquals(
            listOf(
                PreviewEntry(
                    componentFqName = "app.common.Shell",
                    displayName = "Shell",
                    slotIds = emptyList(),
                ),
            ),
            plan.previews,
        )
        assertEquals(
            listOf(
                ClientComponentManifestEntry(
                    componentFqName = "app.client.Button",
                    clientId = "app/client/Button",
                    serializablePropsType = "kotlin.Unit",
                ),
            ),
            plan.clientComponents,
        )
        assertEquals(
            listOf(
                ClientReferenceReplacement(
                    callerFqName = "app.common.Shell",
                    calleeFqName = "app.client.Button",
                    clientId = "app/client/Button",
                    location = "Shell.kt:3:1",
                ),
                ClientReferenceReplacement(
                    callerFqName = "app.server.Page",
                    calleeFqName = "app.client.Button",
                    clientId = "app/client/Button",
                    location = "Page.kt:5:1",
                ),
            ),
            plan.clientReferences,
        )
        assertEquals(
            listOf(
                sourceDiagnostic,
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Error,
                    code = "KINETICA_DUPLICATE_COMPONENT",
                    message = "Duplicate component declaration: app.Duplicate",
                    declarationFqName = "app.Duplicate",
                    location = "DuplicateA.kt:1:1",
                ),
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_UNKNOWN_COMPONENT_CALL",
                    message = "Unable to resolve Kinetica component call: app.Missing",
                    declarationFqName = "app.common.Shell",
                    location = "Shell.kt:3:1",
                ),
            ),
            plan.diagnostics,
        )

        val resetSlot = CompilerSlotId(
            moduleId = "edge",
            functionFqName = "app.common.Shell",
            declarationOrdinal = 0,
            disambiguator = "draft",
        )
        val hotReload = KineticaCompilerAnalyzer(moduleId = "edge").planHotReload(
            previous = listOf(
                SlotDescriptor(
                    id = resetSlot,
                    variableName = "draft",
                    persistent = false,
                    transient = false,
                ),
            ),
            next = listOf(
                SlotDescriptor(
                    id = resetSlot,
                    variableName = "draft",
                    persistent = true,
                    transient = false,
                ),
            ),
        )

        assertEquals(emptyList(), hotReload.preserved)
        assertEquals(emptyList(), hotReload.added)
        assertEquals(emptyList(), hotReload.removed)
        assertEquals(listOf(resetSlot), hotReload.reset)
    }

    @Test
    fun analyzerPlansSkippingAndStaticHoists() {
        val plan = KineticaCompilerAnalyzer(moduleId = "shop").analyze(
            listOf(
                ComponentDeclaration(
                    fqName = "app.Header",
                    annotations = setOf(ComponentAnnotation.UiComponent),
                    parameters = listOf(
                        ComponentParameterDeclaration("title", "kotlin.String"),
                        ComponentParameterDeclaration("count", "kotlin.Int"),
                    ),
                    staticNodes = listOf(
                        StaticNodeDeclaration(
                            nodeSource = "TextNode(value = \"Static heading\")",
                            location = "Header.kt:5:5",
                        ),
                    ),
                ),
            ),
        )

        val desugared = plan.desugaredComponents.single()

        assertTrue(desugared.skippable)
        assertEquals(
            listOf(
                ComponentParameterDescriptor("title", "kotlin.String", stable = true),
                ComponentParameterDescriptor("count", "kotlin.Int", stable = true),
            ),
            desugared.parameters,
        )
        assertEquals(
            listOf(
                StaticHoistDescriptor(
                    componentFqName = "app.Header",
                    hoistId = "app/Header#static#0",
                    nodeSource = "TextNode(value = \"Static heading\")",
                    location = "Header.kt:5:5",
                ),
            ),
            plan.staticHoists,
        )
    }

    @Test
    fun analyzerGeneratesRouteCodecsAndReportsNonSerializableRoutes() {
        val plan = KineticaCompilerAnalyzer(moduleId = "shop").analyze(
            declarations = emptyList(),
            routes = listOf(
                RouteDeclaration(
                    fqName = "app.ShopRoute",
                    serializable = true,
                    location = "Routes.kt:3:1",
                ),
                RouteDeclaration(
                    fqName = "app.AdminRoute",
                    serializable = false,
                    location = "Routes.kt:10:1",
                ),
            ),
        )

        assertTrue(plan.hasErrors)
        assertEquals(
            listOf(
                GeneratedRouteCodec(
                    routeFqName = "app.ShopRoute",
                    codecFqName = "app.ShopRouteCodec",
                ),
            ),
            plan.routeCodecs,
        )
        assertEquals(
            CompilerDiagnostic(
                severity = DiagnosticSeverity.Error,
                code = "KINETICA_ROUTE_REQUIRES_SERIALIZABLE",
                message = "Route app.AdminRoute must be annotated with @Serializable for generated RouteCodec support.",
                declarationFqName = "app.AdminRoute",
                location = "Routes.kt:10:1",
            ),
            plan.diagnostics.single(),
        )
    }

    @Test
    fun analyzerGeneratesServerActionDescriptors() {
        val plan = KineticaCompilerAnalyzer(moduleId = "shop").analyze(
            declarations = emptyList(),
            serverActions = listOf(
                ServerActionDeclaration(
                    functionFqName = "app.server.addToCart",
                    inputType = "app.AddToCartInput",
                    outputType = "app.Cart",
                    invalidates = listOf("cart", "products"),
                    location = "Actions.kt:4:1",
                ),
            ),
        )

        assertEquals(
            listOf(
                ServerActionDescriptor(
                    functionFqName = "app.server.addToCart",
                    actionId = "app/server/addToCart",
                    inputType = "app.AddToCartInput",
                    outputType = "app.Cart",
                    invalidates = listOf("cart", "products"),
                    location = "Actions.kt:4:1",
                ),
            ),
            plan.serverActions,
        )
        assertEquals(
            listOf(
                CompilerServerActionRegistration(
                    actionId = "app/server/addToCart",
                    functionFqName = "app.server.addToCart",
                    invalidates = listOf("cart", "products"),
                ),
            ),
            plan.toClientComponentManifest().actions,
        )
    }

    @Test
    fun generatedSourceEmitterProducesRouteCodecsManifestAndClientRefFactories() {
        val plan = KineticaCompilerPlan(
            moduleId = "shop",
            previews = listOf(
                PreviewEntry(
                    componentFqName = "app.ShopScreen",
                    displayName = "Shop",
                    slotIds = listOf(CompilerSlotId("shop", "app.ShopScreen", 0, "filter")),
                ),
            ),
            routeCodecs = listOf(
                GeneratedRouteCodec(
                    routeFqName = "app.ShopRoute",
                    codecFqName = "app.ShopRouteCodec",
                ),
            ),
            clientComponents = listOf(
                ClientComponentManifestEntry(
                    componentFqName = "app.AddTodoButton",
                    clientId = "app/AddTodoButton",
                    serializablePropsType = "app.AddTodoProps",
                ),
            ),
            serverActions = listOf(
                ServerActionDescriptor(
                    functionFqName = "app.server.createTodo",
                    actionId = "app/server/createTodo",
                    inputType = "app.TodoDraft",
                    outputType = "app.Todo",
                    invalidates = listOf("todos"),
                ),
            ),
            desugaredComponents = listOf(
                DesugaredComponent(
                    componentFqName = "app.ShopScreen",
                    parameters = listOf(
                        ComponentParameterDescriptor("title", "kotlin.String", stable = true),
                        ComponentParameterDescriptor("onSelect", "() -> kotlin.Unit", stable = false),
                    ),
                    skippable = false,
                    staticHoists = listOf(
                        StaticHoistDescriptor(
                            componentFqName = "app.ShopScreen",
                            hoistId = "app/ShopScreen#static#0",
                            nodeSource = "TextNode(value = \"Static heading\")",
                            location = "ShopScreen.kt:5:5",
                        ),
                    ),
                ),
            ),
        )

        val generated = KineticaGeneratedSourceEmitter().emit(plan).associateBy { it.path }

        assertEquals(
            """
                @file:Suppress("RedundantVisibilityModifier")

                package app

                import io.heapy.kinetica.KineticaJson
                import io.heapy.kinetica.RouteCodec
                import kotlinx.serialization.decodeFromString
                import kotlinx.serialization.encodeToString

                public object ShopRouteCodec : RouteCodec<ShopRoute> {
                    override fun encode(route: ShopRoute): String =
                        KineticaJson.encodeToString(ShopRoute.serializer(), route)

                    override fun decode(value: String): ShopRoute =
                        KineticaJson.decodeFromString(ShopRoute.serializer(), value)
                }
            """.trimIndent() + "\n",
            generated.getValue("generated/app/ShopRouteCodec.kt").text,
        )

        val manifest = generated.getValue("generated/io/heapy/kinetica/generated/KineticaClientManifest.kt").text
        assertTrue("componentId = \"app/AddTodoButton\"" in manifest)
        assertTrue("componentFqName = \"app.AddTodoButton\"" in manifest)
        assertTrue("serializablePropsType = \"app.AddTodoProps\"" in manifest)
        assertTrue("actions = KineticaGeneratedServerActions" in manifest)

        val serverActions = generated.getValue("generated/io/heapy/kinetica/generated/KineticaServerActions.kt").text
        assertTrue("public val KineticaGeneratedServerActions: List<ServerActionRegistration>" in serverActions)
        assertTrue("actionId = \"app/server/createTodo\"" in serverActions)
        assertTrue("functionFqName = \"app.server.createTodo\"" in serverActions)
        assertTrue("invalidates = listOf(\"todos\")" in serverActions)
        assertTrue("inputSchema = serverActionPayloadSchema(serializer<app.TodoDraft>())" in serverActions)
        assertTrue("public val KineticaGeneratedServerActionStubs: List<ServerActionStub>" in serverActions)
        assertTrue("inputSerializer = serializer<app.TodoDraft>()" in serverActions)
        assertTrue("outputSerializer = serializer<app.Todo>()" in serverActions)
        assertTrue("handler = { input -> app.server.createTodo(input) }" in serverActions)
        assertTrue("public val KineticaGeneratedServerActionDispatcher: KineticaServerActionDispatcher" in serverActions)

        val clientRefs = generated.getValue("generated/io/heapy/kinetica/generated/KineticaClientRefs.kt").text
        assertTrue("public fun clientRef_app_AddTodoButton(" in clientRefs)
        assertTrue("ClientRef(" in clientRefs)
        assertTrue("componentId = \"app/AddTodoButton\"" in clientRefs)
        assertTrue("props: app.AddTodoProps" in clientRefs)
        assertTrue("KineticaJson.encodeToJsonElement(serializer<app.AddTodoProps>(), props).jsonObject" in clientRefs)

        val previews = generated.getValue("generated/io/heapy/kinetica/generated/KineticaPreviews.kt").text
        assertTrue("public val KineticaGeneratedPreviews: List<PreviewDescriptor>" in previews)
        assertTrue("componentFqName = \"app.ShopScreen\"" in previews)
        assertTrue("displayName = \"Shop\"" in previews)
        assertTrue("moduleId = \"shop\"" in previews)
        assertTrue("disambiguator = \"filter\"" in previews)

        val transforms = generated.getValue("generated/io/heapy/kinetica/generated/KineticaComponentTransforms.kt").text
        assertTrue("public const val KineticaGeneratedCompilerPluginId: String = \"io.heapy.kinetica.compiler\"" in transforms)
        assertTrue("public const val KineticaGeneratedCompilerPluginVersion: String = \"0.2.0\"" in transforms)
        assertTrue("public val KineticaGeneratedComponentTransforms: List<ComponentTransformRegistration>" in transforms)
        assertTrue("componentFqName = \"app.ShopScreen\"" in transforms)
        assertTrue("name = \"title\"" in transforms)
        assertTrue("type = \"kotlin.String\"" in transforms)
        assertTrue("stable = false" in transforms)
        assertTrue("skippable = false" in transforms)
        assertTrue("hoistId = \"app/ShopScreen#static#0\"" in transforms)
        assertTrue("node = TextNode(value = \"Static heading\")" in transforms)
    }

    @Test
    fun generatedSourceEmitterCoversUnitActionsRootCodecsEscapedLiteralsAndNullHoists() {
        val plan = KineticaCompilerPlan(
            moduleId = "root",
            previews = listOf(
                PreviewEntry(
                    componentFqName = "app.Escaped",
                    displayName = "Quote \" line\ncarriage\r tab\t slash\\ dollar \$sum",
                    slotIds = emptyList(),
                ),
            ),
            routeCodecs = listOf(
                GeneratedRouteCodec(
                    routeFqName = "RootRoute",
                    codecFqName = "RootRouteCodec",
                ),
            ),
            clientComponents = listOf(
                ClientComponentManifestEntry(
                    componentFqName = "1Client.Component",
                    clientId = "client\\\"\n\r\t",
                    serializablePropsType = "kotlin.Unit",
                ),
            ),
            serverActions = listOf(
                ServerActionDescriptor(
                    functionFqName = "app.server.refresh",
                    actionId = "app/server/refresh",
                    inputType = "kotlin.Unit",
                    outputType = "kotlin.Unit",
                    invalidates = listOf("cache\\key", "line\nbreak", "carriage\rreturn", "tab\tkey", "user_\$id"),
                ),
            ),
            desugaredComponents = listOf(
                DesugaredComponent(
                    componentFqName = "app.Escaped",
                    staticHoists = listOf(
                        StaticHoistDescriptor(
                            componentFqName = "app.Escaped",
                            hoistId = "app/Escaped#static#0",
                            nodeSource = "TextNode(value = \"Static\")",
                            location = null,
                        ),
                    ),
                ),
            ),
        )

        val generated = KineticaGeneratedSourceEmitter().emit(plan).associateBy { it.path }

        val rootCodec = generated.getValue("generated/RootRouteCodec.kt").text
        assertFalse("package " in rootCodec)
        assertTrue("public object RootRouteCodec : RouteCodec<RootRoute>" in rootCodec)

        val clientRefs = generated.getValue("generated/io/heapy/kinetica/generated/KineticaClientRefs.kt").text
        assertTrue("public fun clientRef__1Client_Component(" in clientRefs)
        assertTrue("props: JsonObject = JsonObject(emptyMap())" in clientRefs)
        assertFalse("props: kotlin.Unit" in clientRefs)

        val serverActions = generated.getValue("generated/io/heapy/kinetica/generated/KineticaServerActions.kt").text
        assertTrue("handler = { _: kotlin.Unit -> app.server.refresh() }" in serverActions)
        assertTrue("invalidates = listOf(" in serverActions)
        assertTrue("\"user_\\${'$'}id\"" in serverActions)

        val previews = generated.getValue("generated/io/heapy/kinetica/generated/KineticaPreviews.kt").text
        assertTrue("displayName = \"Quote \\\" line\\ncarriage\\r tab\\t slash\\\\ dollar \\${'$'}sum\"" in previews)

        val transforms = generated.getValue("generated/io/heapy/kinetica/generated/KineticaComponentTransforms.kt").text
        assertTrue("location = null" in transforms)
    }

    @Test
    fun hotReloadPlanPreservesStableSlotsAndResetsRenamedSlots() {
        val analyzer = KineticaCompilerAnalyzer(moduleId = "todo")
        val previous = analyzer.analyze(
            listOf(
                ComponentDeclaration(
                    fqName = "app.Counter",
                    annotations = setOf(ComponentAnnotation.UiComponent),
                    slots = listOf(
                        SlotDeclaration("count"),
                        SlotDeclaration("draft"),
                    ),
                ),
            ),
        )
        val next = analyzer.analyze(
            listOf(
                ComponentDeclaration(
                    fqName = "app.Counter",
                    annotations = setOf(ComponentAnnotation.UiComponent),
                    slots = listOf(
                        SlotDeclaration("count"),
                        SlotDeclaration("title"),
                        SlotDeclaration("hover", transient = true),
                    ),
                ),
            ),
        )

        val hotReload = analyzer.planHotReload(previous.slots, next.slots)

        assertEquals(listOf(CompilerSlotId("todo", "app.Counter", 0, "count")), hotReload.preserved)
        assertEquals(
            listOf(
                CompilerSlotId("todo", "app.Counter", 1, "title"),
                CompilerSlotId("todo", "app.Counter", 2, "hover"),
            ),
            hotReload.added,
        )
        assertEquals(listOf(CompilerSlotId("todo", "app.Counter", 1, "draft")), hotReload.removed)
        assertEquals(emptyList(), hotReload.reset)
    }

    @Test
    fun hotReloadPlanResetsStableIdsWhenMetadataChanges() {
        val analyzer = KineticaCompilerAnalyzer(moduleId = "todo")
        val renamed = CompilerSlotId("todo", "app.Counter", 0, "count")
        val transientChanged = CompilerSlotId("todo", "app.Counter", 1, "hover")

        val hotReload = analyzer.planHotReload(
            previous = listOf(
                SlotDescriptor(
                    id = renamed,
                    variableName = "count",
                    persistent = false,
                    transient = false,
                ),
                SlotDescriptor(
                    id = transientChanged,
                    variableName = "hover",
                    persistent = false,
                    transient = false,
                ),
            ),
            next = listOf(
                SlotDescriptor(
                    id = renamed,
                    variableName = "total",
                    persistent = false,
                    transient = false,
                ),
                SlotDescriptor(
                    id = transientChanged,
                    variableName = "hover",
                    persistent = false,
                    transient = true,
                ),
            ),
        )

        assertEquals(emptyList(), hotReload.preserved)
        assertEquals(emptyList(), hotReload.added)
        assertEquals(emptyList(), hotReload.removed)
        assertEquals(listOf(renamed, transientChanged), hotReload.reset)
    }
}
