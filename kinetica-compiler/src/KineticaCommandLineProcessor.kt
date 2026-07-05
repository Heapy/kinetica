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
    )

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        configuration.useLightTree = false
        when (option.optionName) {
            KineticaCompilerContract.optionModuleId -> configuration.put(KineticaConfigurationKeys.moduleId, value)
            KineticaCompilerContract.optionServerSourceSet -> {
                configuration.put(KineticaConfigurationKeys.serverSourceSet, value)
            }
            KineticaCompilerContract.optionClientSourceSet -> {
                configuration.put(KineticaConfigurationKeys.clientSourceSet, value)
            }
            else -> throw CliOptionProcessingException("Unknown Kinetica compiler option: ${option.optionName}")
        }
    }
}
