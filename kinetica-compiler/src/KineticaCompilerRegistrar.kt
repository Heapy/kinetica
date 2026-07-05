package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
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
    ProcessSourcesBeforeCompilingExtension.Companion.registerExtension(
        KineticaProcessSourcesExtension(pluginConfiguration),
    )
}
