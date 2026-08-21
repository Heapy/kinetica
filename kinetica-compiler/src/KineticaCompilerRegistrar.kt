package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.extensions.ProcessSourcesBeforeCompilingExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

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
    // One oracle per compilation, shared by the FIR checker (writer) and the IR frame
    // pass (reader): FIR's callsInPlace verdicts tell IR exactly which lambdas to number.
    // Never a global — one JVM (compile daemon, test suite) runs many compilations.
    val singleRunOracle = SingleRunOracle()
    // Always registered: the FIR soundness rules must survive every `checks` value —
    // removing them converts compile errors into runtime crashes or silent state
    // aliasing (S1). The mode only selects the style-rule severity (error/warning/off).
    FirExtensionRegistrarAdapter.registerExtension(
        KineticaFirExtensionRegistrar(singleRunOracle, KineticaChecksMode.from(pluginConfiguration.checks)),
    )
    if (pluginConfiguration.transforms) {
        IrGenerationExtension.registerExtension(
            KineticaIrGenerationExtension(
                configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE),
                pluginConfiguration,
                singleRunOracle,
            ),
        )
    }
}
