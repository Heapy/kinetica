package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.extensions.ProcessSourcesBeforeCompilingExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * Test-only escape hatch for the compiler's OWN test suite: when this JVM system
 * property is `"true"`, the FIR checkers are not registered, making the IR frame pass's
 * located decline ERRORs (the defense-in-depth layer BEHIND the FIR rules) reachable and
 * testable. This does not weaken S1 for builds: it is not a plugin option — nothing a
 * consumer configures can set it — and even with the checkers off every unsound shape
 * still FAILS the compile via the IR decline errors, which is exactly what the tests pin.
 */
internal const val KINETICA_DISABLE_FIR_CHECKERS_PROPERTY: String =
    "io.heapy.kinetica.compiler.internal.disableFirCheckersForTesting"

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
    // The system-property gate exists ONLY for the compiler's own tests, so the IR
    // decline ERRORs behind these rules stay testable — see the property's KDoc.
    if (System.getProperty(KINETICA_DISABLE_FIR_CHECKERS_PROPERTY) != "true") {
        FirExtensionRegistrarAdapter.registerExtension(
            KineticaFirExtensionRegistrar(singleRunOracle, KineticaChecksMode.from(pluginConfiguration.checks)),
        )
    }
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
