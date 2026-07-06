package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.extensions.ProcessSourcesBeforeCompilingExtension

public class KineticaCompilerRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = KineticaCompilerContract.pluginId

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        registerKineticaCompilerExtensions(configuration)
    }
}

public fun CompilerPluginRegistrar.ExtensionStorage.registerKineticaCompilerExtensions(
    configuration: CompilerConfiguration,
) {
    val pluginConfiguration = KineticaCompilerPluginConfiguration.from(configuration)
    // The source-processing extension is only safe in the JVM PSI pipeline. The default
    // lightTree mode keeps registration to IR-only transforms so every backend can load
    // the plugin without materializing replacement KtFile instances.
    if (pluginConfiguration.sourcePipeline == "psi") {
        ProcessSourcesBeforeCompilingExtension.Companion.registerExtension(
            KineticaProcessSourcesExtension(pluginConfiguration),
        )
    }
    if (pluginConfiguration.transforms) {
        IrGenerationExtension.registerExtension(
            KineticaIrGenerationExtension(
                configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE),
            ),
        )
    }
}
