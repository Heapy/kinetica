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
    // The source-processing extension is only ever invoked by the JVM pipeline in PSI mode,
    // which modules opt into via the sourcePipeline=psi plugin option (processed early, in
    // KineticaCommandLineProcessor). Registering it unconditionally is harmless elsewhere.
    ProcessSourcesBeforeCompilingExtension.Companion.registerExtension(
        KineticaProcessSourcesExtension(pluginConfiguration),
    )
    if (pluginConfiguration.transforms) {
        IrGenerationExtension.registerExtension(
            KineticaIrGenerationExtension(
                configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE),
            ),
        )
    }
}
