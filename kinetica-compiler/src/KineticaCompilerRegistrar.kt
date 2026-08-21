package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.extensions.ProcessSourcesBeforeCompilingExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * Test-only escape hatch for the compiler's OWN test suite: when this JVM system property
 * carries [KINETICA_DISABLE_FIR_CHECKERS_TOKEN], the FIR checkers are not registered,
 * making the IR frame pass's located decline ERRORs (the defense-in-depth layer BEHIND
 * the FIR rules) reachable and testable.
 *
 * What it actually costs when set — the honest version: the IR pass is NOT a complete
 * backstop. Rules A, B, D, F and LOCAL_COMPONENT_FUNCTION have no IR-side error at all;
 * the walker just declines to descend (`transformArgumentsSelectively`, `visitVariable`,
 * `visitSimpleFunction`) without reporting. With the checkers off, shapes such as
 * `forEach { state { } }`, a local `@UiComponent` function, or a val-stored lambda with
 * ordinal consumers compile CLEANLY and then crash or silently alias state at first
 * render. Only the handful of declines listed in [KineticaFrameTransformer] fail the
 * compile.
 *
 * Two things keep that from weakening S1 for real builds: the seam is not a plugin
 * option (nothing a consumer configures reaches it) and it demands an opaque token
 * instead of `"true"`, so a build cannot trip it by setting the property name to an
 * obvious value; and whenever it IS active the plugin announces it with a
 * [CompilerMessageSeverity.STRONG_WARNING] on every compilation.
 */
internal const val KINETICA_DISABLE_FIR_CHECKERS_PROPERTY: String =
    "io.heapy.kinetica.compiler.internal.disableFirCheckersForTesting"

/**
 * The only value of [KINETICA_DISABLE_FIR_CHECKERS_PROPERTY] that removes the checkers.
 * Deliberately not `"true"`: a build that sets the property by name alone keeps them.
 */
internal const val KINETICA_DISABLE_FIR_CHECKERS_TOKEN: String =
    "kinetica-compiler-own-tests-only"

internal const val KINETICA_FIR_CHECKERS_DISABLED_WARNING: String =
    "Kinetica: the FIR soundness checkers are DISABLED via " +
        "$KINETICA_DISABLE_FIR_CHECKERS_PROPERTY. This seam exists only for the compiler " +
        "plugin's own tests: without the checkers, ordinal consumers in multi-run " +
        "lambdas, local @UiComponent functions and stored content lambdas compile " +
        "cleanly and then fail at first render. Unset the property for any real build."

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
    // decline ERRORs behind these rules stay testable — see the property's KDoc for what
    // it really costs, and why it is loud and token-gated rather than value-gated.
    if (System.getProperty(KINETICA_DISABLE_FIR_CHECKERS_PROPERTY) == KINETICA_DISABLE_FIR_CHECKERS_TOKEN) {
        configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
            .report(CompilerMessageSeverity.STRONG_WARNING, KINETICA_FIR_CHECKERS_DISABLED_WARNING)
    } else {
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
