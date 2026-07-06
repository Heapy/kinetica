package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.config.useLightTree

public object KineticaConfigurationKeys {
    public val moduleId: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create(KineticaCompilerContract.optionModuleId)

    public val serverSourceSet: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create(KineticaCompilerContract.optionServerSourceSet)

    public val clientSourceSet: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create(KineticaCompilerContract.optionClientSourceSet)

    public val transforms: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create(KineticaCompilerContract.optionTransforms)

    public val sourcePipeline: CompilerConfigurationKey<String> =
        CompilerConfigurationKey.create(KineticaCompilerContract.optionSourcePipeline)

    public val compilerPlan: CompilerConfigurationKey<KineticaCompilerPlan> =
        CompilerConfigurationKey.create("kinetica compiler plan")

    public val generatedSources: CompilerConfigurationKey<List<KineticaGeneratedSourceFile>> =
        CompilerConfigurationKey.create("kinetica generated sources")
}

public class KineticaCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = KineticaCompilerContract.pluginId

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = KineticaCompilerContract.optionModuleId,
            valueDescription = "<id>",
            description = "Stable Kinetica module id used as the first segment of generated SlotId values.",
            required = true,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = KineticaCompilerContract.optionServerSourceSet,
            valueDescription = "<name>",
            description = "Source set treated as the server side of the Kinetica server/client boundary.",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = KineticaCompilerContract.optionClientSourceSet,
            valueDescription = "<name>",
            description = "Source set treated as the client side of the Kinetica server/client boundary.",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = KineticaCompilerContract.optionSourcePipeline,
            valueDescription = "<psi|lightTree>",
            description = "psi enables the JVM-only source-processing pipeline (scope-free components, generated registrations); default lightTree runs only the IR transforms and works on every backend.",
            required = false,
            allowMultipleOccurrences = false,
        ),
        CliOption(
            optionName = KineticaCompilerContract.optionTransforms,
            valueDescription = "<all|off>",
            description = "Kill switch for the IR perf transforms (skipping/hoisting); metadata generation is unaffected.",
            required = false,
            allowMultipleOccurrences = false,
        ),
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            KineticaCompilerContract.optionModuleId -> configuration.put(KineticaConfigurationKeys.moduleId, value)
            KineticaCompilerContract.optionServerSourceSet -> {
                configuration.put(KineticaConfigurationKeys.serverSourceSet, value)
            }
            KineticaCompilerContract.optionClientSourceSet -> {
                configuration.put(KineticaConfigurationKeys.clientSourceSet, value)
            }
            KineticaCompilerContract.optionTransforms -> {
                configuration.put(KineticaConfigurationKeys.transforms, value)
            }
            KineticaCompilerContract.optionSourcePipeline -> {
                configuration.put(KineticaConfigurationKeys.sourcePipeline, value)
                if (value == "psi") {
                    // Must happen at option-processing time: the phased pipelines decide how
                    // to materialize source files before plugin registrars run. Only the JVM
                    // pipeline supports PSI sources; on JS this option must stay unset.
                    configuration.useLightTree = false
                }
            }
            else -> throw CliOptionProcessingException("Unknown Kinetica compiler option: ${option.optionName}")
        }
    }
}
