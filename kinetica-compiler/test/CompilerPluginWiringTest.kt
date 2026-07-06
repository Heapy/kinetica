package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.useLightTree
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.extensions.ProcessSourcesBeforeCompilingExtension
import org.jetbrains.kotlin.psi.KtPsiFactory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompilerPluginWiringTest {
    @Test
    @OptIn(CompilerConfiguration.Internals::class)
    fun commandLineProcessorSelectsPsiSourcePipeline() {
        val configuration = CompilerConfiguration().apply {
            useLightTree = true
        }
        val processor = KineticaCommandLineProcessor()

        processor.processOption(
            processor.pluginOptions.single { option -> option.optionName == KineticaCompilerContract.optionModuleId },
            "sample",
            configuration,
        )
        assertTrue(configuration.useLightTree, "moduleId alone must not switch the source pipeline")

        processor.processOption(
            processor.pluginOptions.single { option -> option.optionName == KineticaCompilerContract.optionSourcePipeline },
            "psi",
            configuration,
        )

        assertFalse(configuration.useLightTree)
        assertEquals("sample", configuration.get(KineticaConfigurationKeys.moduleId))
    }

    @Test
    @OptIn(CompilerConfiguration.Internals::class)
    fun commandLineProcessorProcessesAllOptionsAndRejectsUnknownNames() {
        val configuration = CompilerConfiguration().apply {
            useLightTree = true
        }
        val processor = KineticaCommandLineProcessor()

        assertEquals(KineticaCompilerContract.pluginId, processor.pluginId)
        processor.processOption(
            processor.pluginOptions.single { option -> option.optionName == KineticaCompilerContract.optionServerSourceSet },
            "serverMain",
            configuration,
        )
        processor.processOption(
            processor.pluginOptions.single { option -> option.optionName == KineticaCompilerContract.optionClientSourceSet },
            "clientMain",
            configuration,
        )

        assertTrue(configuration.useLightTree, "source-set options must not switch the source pipeline")
        assertEquals("serverMain", configuration.get(KineticaConfigurationKeys.serverSourceSet))
        assertEquals("clientMain", configuration.get(KineticaConfigurationKeys.clientSourceSet))

        val error = assertFailsWith<CliOptionProcessingException> {
            processor.processOption(
                CliOption(
                    optionName = "unknown",
                    valueDescription = "<value>",
                    description = "Unknown option",
                    required = false,
                    allowMultipleOccurrences = false,
                ),
                "value",
                configuration,
            )
        }
        assertEquals("Unknown Kinetica compiler option: unknown", error.message)
    }

    @Test
    @OptIn(CompilerConfiguration.Internals::class)
    fun compilerPluginConfigurationReadsConfiguredValuesAndDefaults() {
        assertEquals(
            KineticaCompilerPluginConfiguration(
                moduleId = "main",
                serverSourceSet = "server",
                clientSourceSet = "client",
            ),
            KineticaCompilerPluginConfiguration.from(CompilerConfiguration()),
        )

        val configuration = CompilerConfiguration().apply {
            put(KineticaConfigurationKeys.moduleId, "shop")
            put(KineticaConfigurationKeys.serverSourceSet, "serverMain")
            put(KineticaConfigurationKeys.clientSourceSet, "clientMain")
        }

        assertEquals(
            KineticaCompilerPluginConfiguration(
                moduleId = "shop",
                serverSourceSet = "serverMain",
                clientSourceSet = "clientMain",
            ),
            KineticaCompilerPluginConfiguration.from(configuration),
        )
    }

    @Test
    fun registrarExposesCompilerPluginMetadata() {
        val registrar = KineticaCompilerRegistrar()

        assertEquals(KineticaCompilerContract.pluginId, registrar.pluginId)
        assertTrue(registrar.supportsK2)
    }

    @Test
    @OptIn(CompilerConfiguration.Internals::class)
    fun registrarInstallsIrExtensionByDefault() {
        val configuration = CompilerConfiguration().apply {
            put(KineticaConfigurationKeys.moduleId, "sample")
            put(KineticaConfigurationKeys.serverSourceSet, "serverMain")
            put(KineticaConfigurationKeys.clientSourceSet, "clientMain")
        }
        val storage = CompilerPluginRegistrar.ExtensionStorage()

        storage.registerKineticaCompilerExtensions(configuration)

        assertTrue(storage.get(ProcessSourcesBeforeCompilingExtension.Companion).isEmpty())
        val irExtension = storage.get(IrGenerationExtension.Companion).single()
        assertIs<KineticaIrGenerationExtension>(irExtension)
    }

    @Test
    @OptIn(CompilerConfiguration.Internals::class)
    fun registrarInstallsSourceProcessingExtensionForPsiPipeline() {
        val configuration = CompilerConfiguration().apply {
            put(KineticaConfigurationKeys.moduleId, "sample")
            put(KineticaConfigurationKeys.serverSourceSet, "serverMain")
            put(KineticaConfigurationKeys.clientSourceSet, "clientMain")
            put(KineticaConfigurationKeys.sourcePipeline, "psi")
        }
        val storage = CompilerPluginRegistrar.ExtensionStorage()

        storage.registerKineticaCompilerExtensions(configuration)

        val extension = storage.get(ProcessSourcesBeforeCompilingExtension.Companion).single()
        assertIs<KineticaProcessSourcesExtension>(extension)
        val irExtension = storage.get(IrGenerationExtension.Companion).single()
        assertIs<KineticaIrGenerationExtension>(irExtension)
    }

    @Test
    @OptIn(CompilerConfiguration.Internals::class)
    fun transformsKillSwitchDisablesIrExtensionButKeepsPsiSourcePipeline() {
        val configuration = CompilerConfiguration().apply {
            put(KineticaConfigurationKeys.moduleId, "sample")
            put(KineticaConfigurationKeys.transforms, "off")
            put(KineticaConfigurationKeys.sourcePipeline, "psi")
        }
        val storage = CompilerPluginRegistrar.ExtensionStorage()

        storage.registerKineticaCompilerExtensions(configuration)

        assertTrue(storage.get(IrGenerationExtension.Companion).isEmpty())
        assertIs<KineticaProcessSourcesExtension>(
            storage.get(ProcessSourcesBeforeCompilingExtension.Companion).single(),
        )
    }

    @Test
    @OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
    fun processSourcesHandlesEmptySourceCollections() {
        val configuration = CompilerConfiguration()
        val extension = KineticaProcessSourcesExtension(
            KineticaCompilerPluginConfiguration(
                moduleId = "empty",
                serverSourceSet = "serverMain",
                clientSourceSet = "clientMain",
            ),
        )

        val processed = extension.processSources(emptyList(), configuration)

        assertEquals(emptyList(), processed.toList())
        assertEquals("empty", assertNotNull(configuration.get(KineticaConfigurationKeys.compilerPlan)).moduleId)
        assertTrue(assertNotNull(configuration.get(KineticaConfigurationKeys.generatedSources)).isNotEmpty())
    }

    @Test
    @OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
    fun processSourcesTransformsAnnotatedPsiAndRegistersGeneratedFiles() {
        val disposable = Disposer.newDisposable()
        val ideaHome = createTempDirectory(prefix = "kinetica-compiler-test")
        val previousIdeaHome = System.setProperty("idea.home.path", ideaHome.toString())
        val previousIdeaConfig = System.setProperty("idea.config.path", ideaHome.resolve("config").toString())
        val previousIdeaSystem = System.setProperty("idea.system.path", ideaHome.resolve("system").toString())
        try {
            val configuration = CompilerConfiguration()
            val environment = KotlinCoreEnvironment.createForTests(
                disposable,
                configuration,
                EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
            val source = KtPsiFactory(environment.project).createPhysicalFile(
                "Screen.kt",
                """
                    package app

                    @Preview("Screen")
                    @UiComponent
                    fun Screen() {
                        var title by state { "Inbox" }
                        text("Static")
                    }
                """.trimIndent(),
            )
            val plainSource = KtPsiFactory(environment.project).createPhysicalFile(
                "Plain.kt",
                """
                    package app

                    fun Plain() = Unit
                """.trimIndent(),
            )
            val extension = KineticaProcessSourcesExtension(
                KineticaCompilerPluginConfiguration(
                    moduleId = "app",
                    serverSourceSet = "serverMain",
                    clientSourceSet = "clientMain",
                ),
            )

            val processed = extension.processSources(listOf(source, plainSource), configuration).toList()

            assertTrue(processed.first().text.contains("fun io.heapy.kinetica.ComponentScope.Screen()"))
            assertTrue(processed.any { file -> file === plainSource })
            assertTrue(processed.first().text.contains("skippableNode("))
            assertTrue(processed.first().text.contains("emit(staticNode(\"app/Screen#static#0\")"))
            assertTrue(processed.any { file -> file.name == "KineticaPreviews.kt" })
            assertTrue(processed.any { file -> file.name == "KineticaComponentTransforms.kt" })

            val plan = assertNotNull(configuration.get(KineticaConfigurationKeys.compilerPlan))
            assertEquals("app.Screen", plan.previews.single().componentFqName)
            assertEquals("Screen", plan.previews.single().displayName)
            assertEquals("app/Screen#static#0", plan.staticHoists.single().hoistId)
            assertTrue(assertNotNull(configuration.get(KineticaConfigurationKeys.generatedSources)).isNotEmpty())
        } finally {
            restoreSystemProperty("idea.system.path", previousIdeaSystem)
            restoreSystemProperty("idea.config.path", previousIdeaConfig)
            restoreSystemProperty("idea.home.path", previousIdeaHome)
            ApplicationManager.getApplication()?.runWriteAction {
                Disposer.dispose(disposable)
            } ?: Disposer.dispose(disposable)
        }
    }

    @Test
    fun sourceExtractorBuildsAnalyzerInputFromKotlinText() {
        val extractor = KineticaSourceModelExtractor(
            KineticaCompilerPluginConfiguration(
                moduleId = "todo",
                serverSourceSet = "serverMain",
                clientSourceSet = "clientMain",
            ),
        )
        val declarations = extractor.extract(
            listOf(
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/app/Todo.kt",
                    packageName = "app",
                    text = """
                        package app

                        @Preview("Todo")
                        @UiComponent
                        fun TodoScreen() {
                            var draft by state(persistent = true) { "" }
                            val hover = state(transient = true) { false }
                            AddTodoButton()
                        }
                    """.trimIndent(),
                ),
                KineticaSourceFile(
                    path = "/repo/src/clientMain/kotlin/app/AddTodoButton.kt",
                    packageName = "app",
                    text = """
                        package app

                        @ClientComponent
                        fun AddTodoButton(onClick: () -> Unit, metadata: Map<String, Int>) {
                        }
                    """.trimIndent(),
                ),
            ),
        )

        val plan = KineticaCompilerAnalyzer("todo").analyze(declarations)

        assertEquals(
            listOf(
                SlotDescriptor(
                    id = CompilerSlotId("todo", "app.TodoScreen", 0, "draft"),
                    variableName = "draft",
                    persistent = true,
                    transient = false,
                ),
                SlotDescriptor(
                    id = CompilerSlotId("todo", "app.TodoScreen", 1, "hover"),
                    variableName = "hover",
                    persistent = false,
                    transient = true,
                ),
            ),
            plan.slots,
        )
        assertEquals(listOf("app.AddTodoButton"), declarations.single { it.fqName == "app.TodoScreen" }.calls.map { it.calleeFqName })
        assertEquals("/repo/src/commonMain/kotlin/app/Todo.kt:3:1", declarations.single { it.fqName == "app.TodoScreen" }.location)
        assertEquals(
            "/repo/src/commonMain/kotlin/app/Todo.kt:8:5",
            declarations.single { it.fqName == "app.TodoScreen" }.calls.single().location,
        )
        assertEquals(
            listOf(
                ClientReferenceReplacement(
                    callerFqName = "app.TodoScreen",
                    calleeFqName = "app.AddTodoButton",
                    clientId = "app/AddTodoButton",
                    location = "/repo/src/commonMain/kotlin/app/Todo.kt:8:5",
                ),
            ),
            plan.clientReferences,
        )
        val addTodoButton = declarations.single { it.fqName == "app.AddTodoButton" }
        assertEquals(ComponentSourceSet.Client, addTodoButton.sourceSet)
        assertEquals(
            listOf(
                ComponentParameterDeclaration("onClick", "() -> kotlin.Unit"),
                ComponentParameterDeclaration("metadata", "kotlin.collections.Map<kotlin.String, kotlin.Int>"),
            ),
            addTodoButton.parameters,
        )
        assertEquals("Todo", plan.previews.single().displayName)
        assertEquals("app.AddTodoButton.Props", plan.clientComponents.single().serializablePropsType)
    }

    @Test
    fun sourceExtractorFindsRouteDeclarationsForGeneratedCodecs() {
        val extractor = KineticaSourceModelExtractor(
            KineticaCompilerPluginConfiguration(
                moduleId = "shop",
                serverSourceSet = "serverMain",
                clientSourceSet = "clientMain",
            ),
        )
        val sourceModel = extractor.extractModel(
            listOf(
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/app/Routes.kt",
                    packageName = "app",
                    text = """
                        package app

                        import io.heapy.kinetica.Route
                        import kotlinx.serialization.Serializable

                        @Serializable
                        sealed interface ShopRoute : Route {
                            @Serializable
                            data object Home : ShopRoute
                        }

                        sealed interface AdminRoute : Route
                    """.trimIndent(),
                ),
            ),
        )

        val plan = KineticaCompilerAnalyzer("shop").analyze(
            declarations = sourceModel.components,
            routes = sourceModel.routes,
        )

        assertEquals(
            listOf(
                RouteDeclaration(
                    fqName = "app.ShopRoute",
                    serializable = true,
                    location = "/repo/src/commonMain/kotlin/app/Routes.kt:6:1",
                ),
                RouteDeclaration(
                    fqName = "app.AdminRoute",
                    serializable = false,
                    location = "/repo/src/commonMain/kotlin/app/Routes.kt:12:1",
                ),
            ),
            sourceModel.routes,
        )
        assertEquals(
            listOf(GeneratedRouteCodec("app.ShopRoute", "app.ShopRouteCodec")),
            plan.routeCodecs,
        )
        assertEquals("KINETICA_ROUTE_REQUIRES_SERIALIZABLE", plan.diagnostics.single().code)
    }

    @Test
    fun sourceExtractorFindsServerActionDeclarations() {
        val extractor = KineticaSourceModelExtractor(
            KineticaCompilerPluginConfiguration(
                moduleId = "shop",
                serverSourceSet = "serverMain",
                clientSourceSet = "clientMain",
            ),
        )
        val sourceModel = extractor.extractModel(
            listOf(
                KineticaSourceFile(
                    path = "/repo/src/serverMain/kotlin/app/server/Actions.kt",
                    packageName = "app.server",
                    text = """
                        package app.server

                        @ServerAction(invalidates = ["cart", "products"])
                        suspend fun addToCart(input: AddToCartInput): Cart {
                            return TODO()
                        }

                        @ServerAction
                        fun summarize(): Summary

                        @ServerAction(invalidates = ["inventory"])
                        fun listProducts(limit: Int = 10): List<Product> = TODO()
                    """.trimIndent(),
                ),
            ),
        )
        val plan = KineticaCompilerAnalyzer("shop").analyze(
            declarations = sourceModel.components,
            serverActions = sourceModel.serverActions,
        )

        assertEquals(
            listOf(
                ServerActionDeclaration(
                    functionFqName = "app.server.addToCart",
                    inputType = "app.server.AddToCartInput",
                    outputType = "app.server.Cart",
                    invalidates = listOf("cart", "products"),
                    location = "/repo/src/serverMain/kotlin/app/server/Actions.kt:3:1",
                ),
                ServerActionDeclaration(
                    functionFqName = "app.server.summarize",
                    inputType = "kotlin.Unit",
                    outputType = "app.server.Summary",
                    invalidates = emptyList(),
                    location = "/repo/src/serverMain/kotlin/app/server/Actions.kt:8:1",
                ),
                ServerActionDeclaration(
                    functionFqName = "app.server.listProducts",
                    inputType = "kotlin.Int",
                    outputType = "kotlin.collections.List<app.server.Product>",
                    invalidates = listOf("inventory"),
                    location = "/repo/src/serverMain/kotlin/app/server/Actions.kt:11:1",
                ),
            ),
            sourceModel.serverActions,
        )
        assertEquals(
            listOf("app/server/addToCart", "app/server/summarize", "app/server/listProducts"),
            plan.serverActions.map { it.actionId },
        )
    }

    @Test
    fun sourceExtractorFindsComponentTransformMetadata() {
        val extractor = KineticaSourceModelExtractor(
            KineticaCompilerPluginConfiguration(
                moduleId = "shop",
                serverSourceSet = "serverMain",
                clientSourceSet = "clientMain",
            ),
        )
        val sourceModel = extractor.extractModel(
            listOf(
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/app/Header.kt",
                    packageName = "app",
                    text = """
                        package app

                        @UiComponent
                        fun Header(title: String, onClick: () -> Unit) {
                            text("Static heading")
                            text(title)
                        }
                    """.trimIndent(),
                ),
            ),
        )
        val declaration = sourceModel.components.single()
        val plan = KineticaCompilerAnalyzer("shop").analyze(sourceModel.components)
        val desugared = plan.desugaredComponents.single()

        assertEquals(
            listOf(
                ComponentParameterDeclaration("title", "kotlin.String"),
                ComponentParameterDeclaration("onClick", "() -> kotlin.Unit"),
            ),
            declaration.parameters,
        )
        assertEquals(
            listOf(
                StaticNodeDeclaration(
                    nodeSource = "TextNode(value = \"Static heading\")",
                    location = "/repo/src/commonMain/kotlin/app/Header.kt:5:5",
                ),
            ),
            declaration.staticNodes,
        )
        assertFalse(desugared.skippable)
        assertEquals(
            listOf(
                ComponentParameterDescriptor("title", "kotlin.String", stable = true),
                ComponentParameterDescriptor("onClick", "() -> kotlin.Unit", stable = false),
            ),
            desugared.parameters,
        )
        assertEquals("app/Header#static#0", desugared.staticHoists.single().hoistId)
    }

    @Test
    fun sourceExtractorHandlesRootPackagesAmbiguousCallsRoutesAndActionDefaults() {
        val configuration = KineticaCompilerPluginConfiguration(
            moduleId = "edge",
            serverSourceSet = "serverMain",
            clientSourceSet = "clientMain",
        )
        val sourceModel = KineticaSourceModelExtractor(configuration).extractModel(
            listOf(
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/Root.kt",
                    packageName = "",
                    text = """
                        @Preview(name = "Root")
                        @ServerComponent
                        fun RootScreen(
                            title: String = "Inbox",
                            items: List<Item>,
                            qualified: app.Model,
                            producer: Consumer<out Item>,
                            handler: kotlin.Function0<Unit>,
                        ) {
                            var count by persistentState { 0 }
                            val draft = state(persistent = true, transient = true) { "" }
                            JsonObject(emptyMap())
                            Shared()
                            Shared()
                            Duplicated()
                            text("Static heading", strikethrough = false)
                            text("Hello ${'$'}title")
                        }
                    """.trimIndent(),
                ),
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/feature/Shared.kt",
                    packageName = "feature",
                    text = """
                        @UiComponent
                        fun Shared() {
                        }
                    """.trimIndent(),
                ),
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/left/Duplicated.kt",
                    packageName = "left",
                    text = """
                        @UiComponent
                        fun Duplicated() {
                        }
                    """.trimIndent(),
                ),
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/right/Duplicated.kt",
                    packageName = "right",
                    text = """
                        @UiComponent
                        fun Duplicated() {
                        }
                    """.trimIndent(),
                ),
                KineticaSourceFile(
                    path = "/repo/src/serverMain/kotlin/app/server/Server.kt",
                    packageName = "app.server",
                    text = """
                        @ServerComponent
                        fun ServerOnly() {
                        }

                        @ServerAction
                        fun refresh()

                        @ServerAction(invalidates = ["one"])
                        fun merge(left: Cart, right: Cart = Cart()): Result<Cart> = TODO()
                    """.trimIndent(),
                ),
                KineticaSourceFile(
                    path = "/repo/src/clientMain/kotlin/app/client/Client.kt",
                    packageName = "app.client",
                    text = """
                        @io.heapy.kinetica.ClientComponent
                        fun ClientOnly() {
                        }
                    """.trimIndent(),
                ),
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/app/Routes.kt",
                    packageName = "app",
                    text = """
                        @kotlinx.serialization.Serializable
                        public data class ProductRoute(val id: String) : NavigationRoute, Route<Product>

                        internal object SettingsRoute : Route

                        private class PlainThing
                    """.trimIndent(),
                ),
            ),
        )

        val root = sourceModel.components.single { component -> component.fqName == "RootScreen" }
        assertEquals(ComponentSourceSet.Common, root.sourceSet)
        assertEquals("Root", root.previewName)
        assertEquals(
            listOf(
                ComponentParameterDeclaration("title", "kotlin.String"),
                ComponentParameterDeclaration("items", "kotlin.collections.List<Item>"),
                ComponentParameterDeclaration("qualified", "app.Model"),
                ComponentParameterDeclaration("producer", "Consumer<out Item>"),
                ComponentParameterDeclaration("handler", "kotlin.Function0<kotlin.Unit>"),
            ),
            root.parameters,
        )
        assertEquals(
            listOf(
                SlotDeclaration("count", persistent = true, transient = false, disambiguator = "count"),
                SlotDeclaration("draft", persistent = true, transient = true, disambiguator = "draft"),
            ),
            root.slots,
        )
        assertEquals(listOf("feature.Shared"), root.calls.map { call -> call.calleeFqName })
        assertEquals(
            listOf(
                StaticNodeDeclaration(
                    nodeSource = "TextNode(value = \"Static heading\")",
                    location = "/repo/src/commonMain/kotlin/Root.kt:16:5",
                ),
            ),
            root.staticNodes,
        )

        assertEquals(ComponentSourceSet.Server, sourceModel.components.single { it.fqName == "app.server.ServerOnly" }.sourceSet)
        assertEquals(ComponentSourceSet.Client, sourceModel.components.single { it.fqName == "app.client.ClientOnly" }.sourceSet)
        assertEquals("kotlin.Unit", sourceModel.components.single { it.fqName == "feature.Shared" }.serializablePropsType)

        assertEquals(
            listOf(
                RouteDeclaration(
                    fqName = "app.ProductRoute",
                    serializable = true,
                    location = "/repo/src/commonMain/kotlin/app/Routes.kt:1:1",
                ),
                RouteDeclaration(
                    fqName = "app.SettingsRoute",
                    serializable = false,
                    location = "/repo/src/commonMain/kotlin/app/Routes.kt:4:1",
                ),
            ),
            sourceModel.routes,
        )
        assertEquals(
            listOf(
                ServerActionDeclaration(
                    functionFqName = "app.server.refresh",
                    inputType = "kotlin.Unit",
                    outputType = "kotlin.Unit",
                    invalidates = emptyList(),
                    location = "/repo/src/serverMain/kotlin/app/server/Server.kt:5:1",
                ),
                ServerActionDeclaration(
                    functionFqName = "app.server.merge",
                    inputType = "app.server.merge.Input",
                    outputType = "app.server.Result<app.server.Cart>",
                    invalidates = listOf("one"),
                    location = "/repo/src/serverMain/kotlin/app/server/Server.kt:8:1",
                ),
            ),
            sourceModel.serverActions,
        )
    }

    @Test
    fun sourceExtractorCoversTypeQualificationInputFallbacksAndDefaultModels() {
        assertEquals(KineticaSourceModel(), KineticaSourceModel())

        val configuration = KineticaCompilerPluginConfiguration(
            moduleId = "edge",
            serverSourceSet = "serverMain",
            clientSourceSet = "clientMain",
        )
        val sourceModel = KineticaSourceModelExtractor(configuration).extractModel(
            listOf(
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/app/edge/Edges.kt",
                    packageName = "app.edge",
                    text = """
                        package app.edge

                        @UiComponent
                        fun ComponentEdges(
                            qualified: External.Model,
                            lower: custom,
                            any: Any,
                            set: Set<Item>,
                            values: Map<String, List<Nested>>,
                            consumer: Consumer<in Event>,
                            defaulted: List<Item> = listOf(cache[0], cache[1]),
                            bad,
                        ) {
                            Helper()
                            text("Cost ${'$'}42")
                            text("Hello ${'$'}name")
                        }

                        @UiComponent
                        fun Helper() {
                        }

                        @ServerAction
                        fun expression(input: External.Result) = TODO()

                        @ServerAction
                        fun multi(
                            first: String = pick(names[0], names[1]),
                            second: Result<Cart>,
                        ): Outcome = TODO()

                        @ServerAction
                        fun fallback(missingColon): Done = TODO()

                        @ServerAction
                        fun blankType(input: ): Done = TODO()
                    """.trimIndent(),
                ),
            ),
        )

        val component = sourceModel.components.single { declaration -> declaration.fqName == "app.edge.ComponentEdges" }
        assertEquals("app.edge.ComponentEdges.Props", component.serializablePropsType)
        assertEquals(
            listOf(
                ComponentParameterDeclaration("qualified", "External.Model"),
                ComponentParameterDeclaration("lower", "custom"),
                ComponentParameterDeclaration("any", "kotlin.Any"),
                ComponentParameterDeclaration("set", "kotlin.collections.Set<app.edge.Item>"),
                ComponentParameterDeclaration(
                    "values",
                    "kotlin.collections.Map<kotlin.String, kotlin.collections.List<app.edge.Nested>>",
                ),
                ComponentParameterDeclaration("consumer", "app.edge.Consumer<in app.edge.Event>"),
                ComponentParameterDeclaration("defaulted", "kotlin.collections.List<app.edge.Item>"),
            ),
            component.parameters,
        )
        assertEquals(listOf("app.edge.Helper"), component.calls.map { call -> call.calleeFqName })
        assertEquals(emptyList(), component.staticNodes)
        assertEquals(
            listOf(
                "app.edge.expression:External.Result:kotlin.Unit",
                "app.edge.multi:app.edge.multi.Input:app.edge.Outcome",
                "app.edge.fallback:app.edge.fallback.Input:app.edge.Done",
                "app.edge.blankType:kotlin.Unit:app.edge.Done",
            ),
            sourceModel.serverActions.map { action ->
                "${action.functionFqName}:${action.inputType}:${action.outputType}"
            },
        )
    }

    @Test
    fun sourceExtractorHandlesEscapedStaticTextExpressionBodiesAndUnmatchedBlocks() {
        val configuration = KineticaCompilerPluginConfiguration(
            moduleId = "edge",
            serverSourceSet = "serverMain",
            clientSourceSet = "clientMain",
        )
        val sourceModel = KineticaSourceModelExtractor(configuration).extractModel(
            listOf(
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/app/Escaped.kt",
                    packageName = "app",
                    text = "package app\n" +
                        "\n" +
                        "@UiComponent\n" +
                        "fun Escaped() {\n" +
                        "    text(\"Quote \\\" slash \\\\\")\n" +
                        "    text(\"Line\nbreak\")\n" +
                        "    text(\"Escaped newline\\nvalue\")\n" +
                        "    text(\"Escaped tab\\there\")\n" +
                        "    text(\"Escaped backspace\\bvalue\")\n" +
                        "    text(\"Escaped carriage\\rvalue\")\n" +
                        "    text(\"Escaped single\\'quote\")\n" +
                        "    text(\"Escaped dollar \\${'$'}42\")\n" +
                        "    text(\"Unicode \\u0041\")\n" +
                        "    text(\"Invalid unicode \\u00zz\")\n" +
                        "    text(\"Short unicode \\u0\")\n" +
                        "    text(\"Unknown escape \\q\")\n" +
                        "    text(\"Carriage\rreturn\")\n" +
                        "    text(\"Tab\there\")\n" +
                        "}\n",
                ),
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/app/Expression.kt",
                    packageName = "app",
                    text = """
                        package app

                        @UiComponent
                        fun Expression(): Node = text("Expression")
                    """.trimIndent(),
                ),
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/app/Unclosed.kt",
                    packageName = "app",
                    text = """
                        package app

                        @UiComponent
                        fun Unclosed(ref: Ref<String>) {
                            layoutEffect { ref.current
                            text("Unclosed")
                    """.trimIndent(),
                ),
            ),
        )

        val escapedStaticSources = sourceModel.components
            .single { component -> component.fqName == "app.Escaped" }
            .staticNodes
            .map { node -> node.nodeSource }
        assertEquals(14, escapedStaticSources.size)
        assertTrue("""TextNode(value = "Quote \" slash \\")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Line\nbreak")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Escaped newline\nvalue")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Escaped tab\there")""" in escapedStaticSources)
        assertTrue("TextNode(value = \"Escaped backspace\bvalue\")" in escapedStaticSources)
        assertTrue("""TextNode(value = "Escaped carriage\rvalue")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Escaped single'quote")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Escaped dollar \$42")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Unicode A")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Invalid unicode \\u00zz")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Short unicode \\u0")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Unknown escape \\q")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Carriage\rreturn")""" in escapedStaticSources)
        assertTrue("""TextNode(value = "Tab\there")""" in escapedStaticSources)

        assertEquals(
            listOf("""TextNode(value = "Expression")"""),
            sourceModel.components
                .single { component -> component.fqName == "app.Expression" }
                .staticNodes
                .map { node -> node.nodeSource },
        )
        assertEquals(
            listOf("""TextNode(value = "Unclosed")"""),
            sourceModel.components
                .single { component -> component.fqName == "app.Unclosed" }
                .staticNodes
                .map { node -> node.nodeSource },
        )
        assertEquals(
            CompilerDiagnostic(
                severity = DiagnosticSeverity.Error,
                code = "KINETICA_REF_READ_IN_RENDER",
                message = "Ref.current can only be read from layoutEffect or effect/event phases.",
                declarationFqName = "app.Unclosed",
                location = "/repo/src/commonMain/kotlin/app/Unclosed.kt:5:20",
            ),
            sourceModel.diagnostics.single(),
        )
    }

    @Test
    fun sourceTransformerDesugarsAnnotatedComponentsToNodeProducers() {
        val configuration = KineticaCompilerPluginConfiguration(
            moduleId = "todo",
            serverSourceSet = "serverMain",
            clientSourceSet = "clientMain",
        )
        val source = KineticaSourceFile(
            path = "/repo/src/commonMain/kotlin/app/Todo.kt",
            packageName = "app",
            text = """
                package app

                import io.heapy.kinetica.ClientComponent
                import io.heapy.kinetica.UiComponent
                import io.heapy.kinetica.state
                import io.heapy.kinetica.text

                @UiComponent
                fun TodoScreen() {
                    var draft by state { "" }
                    AddTodoButton()
                    AddTodoButton()
                }

                @UiComponent
                suspend fun AsyncTodo() {
                    var loaded by state { "ready" }
                    text(loaded)
                }

                @UiComponent
                suspend fun AsyncShell() {
                    AsyncTodo()
                }

                @ClientComponent
                fun AddTodoButton() {
                    text("Add")
                }

                @ServerComponent
                fun ServerPanel() {
                    text("Server")
                }

                @io.heapy.kinetica.UiComponent
                fun QualifiedPanel(label: String) {
                    text("Qualified")
                    app.AddTodoButton()
                    AddTodoButton()
                }
            """.trimIndent(),
        )
        val sourceModel = KineticaSourceModelExtractor(configuration).extractModel(listOf(source))

        val transformation = KineticaComponentSourceTransformer("todo").transform(
            file = source,
            components = sourceModel.components,
        )

        assertTrue(transformation.changed)
        assertTrue("fun io.heapy.kinetica.ComponentScope.TodoScreen(): io.heapy.kinetica.Node =" in transformation.text)
        assertTrue("skippableNode(" in transformation.text)
        assertTrue("componentId = \"app.TodoScreen\"" in transformation.text)
        assertTrue("inputs = emptyList()" in transformation.text)
        assertTrue("renderNode {" in transformation.text)
        assertTrue(
            "var draft by state(slotId = io.heapy.kinetica.SlotId(moduleId = \"todo\", functionFqName = \"app.TodoScreen\", declarationOrdinal = 0, disambiguator = \"draft\")) { \"\" }" in transformation.text,
        )
        assertTrue("keyed(\"app/TodoScreen#call#0\") { emit(AddTodoButton()) }" in transformation.text)
        assertTrue("keyed(\"app/TodoScreen#call#1\") { emit(AddTodoButton()) }" in transformation.text)
        assertTrue("suspend fun io.heapy.kinetica.ComponentScope.AsyncTodo(): io.heapy.kinetica.Node =" in transformation.text)
        assertTrue("skippableSuspendNode(" in transformation.text)
        assertTrue("componentId = \"app.AsyncTodo\"" in transformation.text)
        assertTrue("renderSuspendNode {" in transformation.text)
        assertTrue("suspend fun io.heapy.kinetica.ComponentScope.AsyncShell(): io.heapy.kinetica.Node =" in transformation.text)
        assertTrue("componentId = \"app.AsyncShell\"" in transformation.text)
        assertTrue("suspendKeyed(\"app/AsyncShell#call#0\") { emit(AsyncTodo()) }" in transformation.text)
        assertTrue("fun io.heapy.kinetica.ComponentScope.AddTodoButton(): io.heapy.kinetica.Node =" in transformation.text)
        assertTrue(
            "emit(staticNode(\"app/AddTodoButton#static#0\") { io.heapy.kinetica.TextNode(value = \"Add\") })" in transformation.text,
        )
        assertTrue("fun io.heapy.kinetica.ComponentScope.ServerPanel(): io.heapy.kinetica.Node =" in transformation.text)
        assertTrue("componentId = \"app.ServerPanel\"" in transformation.text)
        assertTrue("fun io.heapy.kinetica.ComponentScope.QualifiedPanel(label: String): io.heapy.kinetica.Node =" in transformation.text)
        assertTrue("componentId = \"app.QualifiedPanel\"" in transformation.text)
        assertTrue("inputs = listOf(label)" in transformation.text)
        assertTrue(
            "emit(staticNode(\"app/QualifiedPanel#static#0\") { io.heapy.kinetica.TextNode(value = \"Qualified\") })" in transformation.text,
        )
        assertTrue("app.AddTodoButton()" in transformation.text)
        assertTrue("keyed(\"app/QualifiedPanel#call#0\") { emit(AddTodoButton()) }" in transformation.text)
    }

    @Test
    fun sourceTransformerHandlesSlotArgumentsCommentsNonSkippableInputsAndUnchangedSources() {
        val configuration = KineticaCompilerPluginConfiguration(
            moduleId = "module\"\\line\ncarriage\rtab\t\$",
            serverSourceSet = "serverMain",
            clientSourceSet = "clientMain",
        )
        val source = KineticaSourceFile(
            path = "/repo/src/commonMain/kotlin/app/Edge.kt",
            packageName = "app",
            text = """
                package app

                @UiComponent
                fun Search(title: String, onClick: kotlin.Function0<Unit>) {

                    var query by state(persistent = true) { "" }
                    val existing = state(slotId = SlotId("existing")) { "kept" }
                    val existingSlot = SlotId("existing-variable")
                    val existingVariable = state(slotId = existingSlot) { "kept" }
                    var blankArgs by state() { 0 }
                    var block by state { 1 }
                    var saved by persistentState { "cached" }
                    var ignored by state { 2 }
                    text("Static", strikethrough = false) // keep comment
                    text("Price \${'$'}42")
                    text("Hello ${'$'}title")
                    MissingComponent()
                    Child(title) // child comment
                }

                @UiComponent
                fun Title(title: String, count: Int) {
                    text(title)
                }

                @UiComponent
                fun Child(title: String) {
                    text("Child")
                }

                @UiComponent
                fun ArrowInput(onDone: () -> Unit) {
                    var restored by persistentState(restoredValue = "warm") { "cold" }
                    text("Arrow")
                }

                @NotKinetica
                fun Helper() {
                }
            """.trimIndent(),
        )
        val sourceModel = KineticaSourceModelExtractor(configuration).extractModel(listOf(source))
        val components = sourceModel.components.map { component ->
            if (component.fqName == "app.Search") {
                component.copy(slots = component.slots.filterNot { slot -> slot.variableName == "ignored" })
            } else {
                component
            }
        }

        val transformation = KineticaComponentSourceTransformer(configuration.moduleId).transform(
            file = source,
            components = components,
        )
        val unchanged = KineticaComponentSourceTransformer("edge").transform(
            file = KineticaSourceFile(
                path = "/repo/src/commonMain/kotlin/app/Plain.kt",
                packageName = "app",
                text = "fun Plain() = Unit",
            ),
            components = emptyList(),
        )

        assertTrue(transformation.changed)
        assertFalse("skippableNode(\n        componentId = \"app.Search\"" in transformation.text)
        assertTrue("fun io.heapy.kinetica.ComponentScope.Search(title: String, onClick: kotlin.Function0<Unit>): io.heapy.kinetica.Node =" in transformation.text)
        assertTrue("inputs = listOf(title, count)" in transformation.text)
        assertTrue(
            "moduleId = \"module\\\"\\\\line\\ncarriage\\rtab\\t\\$\"" in transformation.text,
        )
        assertTrue(
            "var query by state(slotId = io.heapy.kinetica.SlotId(moduleId = \"module\\\"\\\\line\\ncarriage\\rtab\\t\\$\", functionFqName = \"app.Search\", declarationOrdinal = 0, disambiguator = \"query\"), persistent = true) { \"\" }" in transformation.text,
        )
        assertTrue("val existing = state(slotId = SlotId(\"existing\")) { \"kept\" }" in transformation.text)
        assertTrue("val existingVariable = state(slotId = existingSlot) { \"kept\" }" in transformation.text)
        assertTrue(
            "var blankArgs by state(slotId = io.heapy.kinetica.SlotId(moduleId = \"module\\\"\\\\line\\ncarriage\\rtab\\t\\$\", functionFqName = \"app.Search\", declarationOrdinal = 2, disambiguator = \"blankArgs\")) { 0 }" in transformation.text,
        )
        assertTrue(
            "var block by state(slotId = io.heapy.kinetica.SlotId(moduleId = \"module\\\"\\\\line\\ncarriage\\rtab\\t\\$\", functionFqName = \"app.Search\", declarationOrdinal = 3, disambiguator = \"block\")) { 1 }" in transformation.text,
        )
        assertTrue(
            "var saved by persistentState(slotId = io.heapy.kinetica.SlotId(moduleId = \"module\\\"\\\\line\\ncarriage\\rtab\\t\\$\", functionFqName = \"app.Search\", declarationOrdinal = 4, disambiguator = \"saved\"), restoredValue = null) { \"cached\" }" in transformation.text,
        )
        assertTrue(
            "fun io.heapy.kinetica.ComponentScope.ArrowInput(onDone: () -> Unit): io.heapy.kinetica.Node =" in transformation.text,
        )
        assertFalse("componentId = \"app.ArrowInput\"" in transformation.text)
        assertTrue(
            "var restored by persistentState(slotId = io.heapy.kinetica.SlotId(moduleId = \"module\\\"\\\\line\\ncarriage\\rtab\\t\\$\", functionFqName = \"app.ArrowInput\", declarationOrdinal = 0, disambiguator = \"restored\"), restoredValue = \"warm\") { \"cold\" }" in transformation.text,
        )
        assertTrue("var ignored by state { 2 }" in transformation.text)
        assertTrue(
            "emit(staticNode(\"app/Search#static#0\") { io.heapy.kinetica.TextNode(value = \"Static\") }) // keep comment" in transformation.text,
        )
        assertTrue(
            "emit(staticNode(\"app/Search#static#1\") { io.heapy.kinetica.TextNode(value = \"Price \\${'$'}42\") })" in transformation.text,
        )
        assertTrue("text(\"Hello ${'$'}title\")" in transformation.text)
        assertTrue("MissingComponent()" in transformation.text)
        assertTrue("keyed(\"app/Search#call#0\") { emit(Child(title)) } // child comment" in transformation.text)
        assertEquals(KineticaSourceTransformation("/repo/src/commonMain/kotlin/app/Plain.kt", "fun Plain() = Unit", changed = false), unchanged)
    }

    @Test
    fun sourceTransformerLeavesMalformedUnplannedAndUnhoistedSourcesStable() {
        val transformer = KineticaComponentSourceTransformer("edge")
        val expressionSource = KineticaSourceFile(
            path = "/repo/src/commonMain/kotlin/app/Expression.kt",
            packageName = "app",
            text = """
                package app

                @UiComponent
                fun Expression(): Node = text("Expression")
            """.trimIndent(),
        )
        val unclosedSource = KineticaSourceFile(
            path = "/repo/src/commonMain/kotlin/app/Unclosed.kt",
            packageName = "app",
            text = """
                package app

                @UiComponent
                fun Unclosed() {
                    text("Unclosed")
            """.trimIndent(),
        )
        val unplannedSource = KineticaSourceFile(
            path = "/repo/src/commonMain/kotlin/app/Unplanned.kt",
            packageName = "app",
            text = """
                package app

                @UiComponent
                fun Unplanned() {
                    text("No declaration")
                }
            """.trimIndent(),
        )
        val malformedStateSource = KineticaSourceFile(
            path = "/repo/src/commonMain/kotlin/app/MalformedState.kt",
            packageName = "app",
            text = """
                package app

                @UiComponent
                fun MalformedState() {
                    var broken by state(
                    text("No hoist descriptor")
                    Child()
                }
            """.trimIndent(),
        )
        val missingParametersSource = KineticaSourceFile(
            path = "/repo/src/commonMain/kotlin/app/MissingParameters.kt",
            packageName = "app",
            text = """
                package app

                @UiComponent
                fun MissingParameters(
            """.trimIndent(),
        )

        assertEquals(
            KineticaSourceTransformation(expressionSource.path, expressionSource.text, changed = false),
            transformer.transform(
                file = expressionSource,
                components = listOf(
                    ComponentDeclaration(
                        fqName = "app.Expression",
                        annotations = setOf(ComponentAnnotation.UiComponent),
                    ),
                ),
            ),
        )
        assertEquals(
            KineticaSourceTransformation(unclosedSource.path, unclosedSource.text, changed = false),
            transformer.transform(
                file = unclosedSource,
                components = listOf(
                    ComponentDeclaration(
                        fqName = "app.Unclosed",
                        annotations = setOf(ComponentAnnotation.UiComponent),
                    ),
                ),
            ),
        )
        assertEquals(
            KineticaSourceTransformation(unplannedSource.path, unplannedSource.text, changed = false),
            transformer.transform(file = unplannedSource, components = emptyList()),
        )
        assertEquals(
            KineticaSourceTransformation(missingParametersSource.path, missingParametersSource.text, changed = false),
            transformer.transform(
                file = missingParametersSource,
                components = listOf(
                    ComponentDeclaration(
                        fqName = "app.MissingParameters",
                        annotations = setOf(ComponentAnnotation.UiComponent),
                    ),
                ),
            ),
        )

        val transformed = transformer.transform(
            file = malformedStateSource,
            components = listOf(
                ComponentDeclaration(
                    fqName = "app.MalformedState",
                    annotations = setOf(ComponentAnnotation.UiComponent),
                    slots = listOf(SlotDeclaration("broken")),
                    staticNodes = emptyList(),
                ),
                ComponentDeclaration(
                    fqName = "app.Child",
                    annotations = setOf(ComponentAnnotation.UiComponent),
                ),
            ),
        )

        assertTrue(transformed.changed)
        assertTrue("var broken by state(" in transformed.text)
        assertTrue("text(\"No hoist descriptor\")" in transformed.text)
        assertTrue("keyed(\"app/MalformedState#call#0\") { emit(Child()) }" in transformed.text)
    }

    @Test
    fun sourceExtractorReportsRenderPhaseDiagnostics() {
        val extractor = KineticaSourceModelExtractor(
            KineticaCompilerPluginConfiguration(
                moduleId = "debug",
                serverSourceSet = "serverMain",
                clientSourceSet = "clientMain",
            ),
        )
        val sourceModel = extractor.extractModel(
            listOf(
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/app/Bad.kt",
                    packageName = "app",
                    text = """
                        package app

                        @UiComponent
                        fun Bad(ref: Ref<String>) {
                            println("render")
                            val value = ref.current
                            layoutEffect { ref.current }
                            val onClick = event { println("event") }
                            watch({ peek { Unit } }) { println("watch") }
                        }
                    """.trimIndent(),
                ),
            ),
        )
        val plan = KineticaCompilerAnalyzer("debug").analyze(
            declarations = sourceModel.components,
            sourceDiagnostics = sourceModel.diagnostics,
        )

        assertEquals(
            listOf(
                "KINETICA_REF_READ_IN_RENDER",
                "KINETICA_RENDER_SIDE_EFFECT",
                "KINETICA_WATCH_SOURCE_UNTRACKED_READ",
            ),
            plan.diagnostics.map { it.code }.sorted(),
        )
        assertEquals(
            "/repo/src/commonMain/kotlin/app/Bad.kt:6:17",
            plan.diagnostics.single { it.code == "KINETICA_REF_READ_IN_RENDER" }.location,
        )
    }

    @Test
    fun sourceExtractorMasksAllowedPhasesAndSkipsMalformedDeclarations() {
        val extractor = KineticaSourceModelExtractor(
            KineticaCompilerPluginConfiguration(
                moduleId = "debug",
                serverSourceSet = "serverMain",
                clientSourceSet = "clientMain",
            ),
        )
        val sourceModel = extractor.extractModel(
            listOf(
                KineticaSourceFile(
                    path = "/repo/src/commonMain/kotlin/app/Masked.kt",
                    packageName = "app",
                    text = """
                        package app

                        @UiComponent
                        fun Masked(ref: Ref<String>, key: Any) {
                            val onClick = event { println("event"); ref.current }
                            launchEffect<Unit> { println("launch"); ref.current }
                            layoutEffect(key) { println("layout"); ref.current }
                            watch({ peek { Unit } }) { println("watch"); ref.current }
                            println("render")
                            val value = ref.current
                        }

                        @UiComponent
                        fun MissingDelimiter(ref: Ref<String> {
                            println("ignored")
                        }

                        @ServerAction
                        fun BrokenAction(input: Broken {
                    """.trimIndent(),
                ),
            ),
        )

        assertEquals(listOf("app.Masked"), sourceModel.components.map { component -> component.fqName })
        assertEquals(emptyList(), sourceModel.serverActions)
        assertEquals(
            listOf(
                "KINETICA_REF_READ_IN_RENDER",
                "KINETICA_RENDER_SIDE_EFFECT",
                "KINETICA_WATCH_SOURCE_UNTRACKED_READ",
            ),
            sourceModel.diagnostics.map { diagnostic -> diagnostic.code }.sorted(),
        )
        assertEquals(
            "/repo/src/commonMain/kotlin/app/Masked.kt:10:17",
            sourceModel.diagnostics.single { it.code == "KINETICA_REF_READ_IN_RENDER" }.location,
        )
    }

    @Test
    @OptIn(CompilerConfiguration.Internals::class)
    fun diagnosticReporterMapsAnalyzerDiagnosticsToCompilerMessages() {
        val collector = RecordingMessageCollector()
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, collector)
        }

        KineticaDiagnosticReporter.report(
            configuration,
            listOf(
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_UNKNOWN_COMPONENT_CALL",
                    message = "Unable to resolve Kinetica component call: app.Missing",
                    location = "/repo/src/App.kt:12:9",
                ),
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Error,
                    code = "KINETICA_CLIENT_CALLS_SERVER",
                    message = "Client component app.Client cannot call server component app.Server.",
                    location = "App.kt",
                ),
            ),
        )

        val warning = collector.messages[0]
        assertEquals(CompilerMessageSeverity.WARNING, warning.severity)
        assertEquals(
            "[KINETICA_UNKNOWN_COMPONENT_CALL] Unable to resolve Kinetica component call: app.Missing",
            warning.message,
        )
        assertEquals("/repo/src/App.kt", warning.location?.path)
        assertEquals(12, warning.location?.line)
        assertEquals(9, warning.location?.column)

        val error = collector.messages[1]
        assertEquals(CompilerMessageSeverity.ERROR, error.severity)
        assertEquals("App.kt", error.location?.path)
        assertTrue(collector.hasErrors())

        collector.clear()
        assertFalse(collector.hasErrors())
    }

    @Test
    @OptIn(CompilerConfiguration.Internals::class)
    fun diagnosticReporterHandlesMissingCollectorAndUnparseableLocations() {
        KineticaDiagnosticReporter.report(
            CompilerConfiguration(),
            listOf(
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Error,
                    code = "KINETICA_NO_COLLECTOR",
                    message = "No collector is configured.",
                    location = "/repo/src/App.kt:1:1",
                ),
            ),
        )

        val collector = RecordingMessageCollector()
        val configuration = CompilerConfiguration().apply {
            put(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, collector)
        }

        KineticaDiagnosticReporter.report(
            configuration,
            listOf(
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_NULL_LOCATION",
                    message = "Location is null.",
                    location = null,
                ),
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_BLANK_LOCATION",
                    message = "Location is blank.",
                    location = " ",
                ),
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_MISSING_COLUMN",
                    message = "Location has no column.",
                    location = "OnlyLine.kt:12",
                ),
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_BAD_LINE",
                    message = "Location line is not numeric.",
                    location = "/repo/src/App.kt:notLine:9",
                ),
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_BAD_COLUMN",
                    message = "Location column is not numeric.",
                    location = "/repo/src/App.kt:12:notColumn",
                ),
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_ZERO_LINE",
                    message = "Location line is not positive.",
                    location = "/repo/src/App.kt:0:9",
                ),
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_BLANK_PATH",
                    message = "Location path is blank.",
                    location = ":12:9",
                ),
                CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_ZERO_COLUMN",
                    message = "Location column is not positive.",
                    location = "/repo/src/App.kt:12:0",
                ),
            ),
        )

        assertEquals(null, collector.messages[0].location)
        assertEquals(null, collector.messages[1].location)
        assertEquals("OnlyLine.kt:12", collector.messages[2].location?.path)
        assertEquals("/repo/src/App.kt:notLine:9", collector.messages[3].location?.path)
        assertEquals("/repo/src/App.kt:12:notColumn", collector.messages[4].location?.path)
        assertEquals("/repo/src/App.kt:0:9", collector.messages[5].location?.path)
        assertEquals(":12:9", collector.messages[6].location?.path)
        assertEquals("/repo/src/App.kt:12:0", collector.messages[7].location?.path)
        assertFalse(collector.hasErrors())
    }

    private class RecordingMessageCollector : MessageCollector {
        val messages = mutableListOf<CollectedMessage>()

        override fun clear() {
            messages.clear()
        }

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            messages += CollectedMessage(severity, message, location)
        }

        override fun hasErrors(): Boolean =
            messages.any { it.severity.isError }
    }

    private data class CollectedMessage(
        val severity: CompilerMessageSeverity,
        val message: String,
        val location: CompilerMessageSourceLocation?,
    )

    private fun restoreSystemProperty(
        key: String,
        previousValue: String?,
    ) {
        if (previousValue == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, previousValue)
        }
    }
}
