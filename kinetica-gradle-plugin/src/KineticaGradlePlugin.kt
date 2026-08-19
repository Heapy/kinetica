package io.heapy.kinetica.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetContainer
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinTargetsContainer
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption

/**
 * Applies the Kinetica K2 compiler plugin to every Kotlin compilation of the project and, unless
 * turned off, the matching runtime dependencies.
 *
 * The compiler plugin is mandatory: without it `state`/`event` throw `MissingKineticaPluginException`
 * at runtime and the authoring-rule checkers stop reporting at compile time.
 */
public class KineticaGradlePlugin : KotlinCompilerPluginSupportPlugin {
    private lateinit var extension: KineticaExtension

    override fun apply(target: Project) {
        extension = target.extensions.create("kinetica", KineticaExtension::class.java).apply {
            enabled.convention(true)
            addRuntimeDependencies.convention(true)
            kineticaVersion.convention(KineticaCoordinates.version)
            // Moving kineticaVersion moves the compiler with it; setting compilerVersion pins
            // only the compiler, which is what a compiler-plugin bug hunt needs.
            compilerVersion.convention(kineticaVersion)
        }

        target.plugins.withType(KotlinBasePlugin::class.java) { kotlinPlugin ->
            warnOnKotlinVersionMismatch(target, kotlinPlugin.pluginVersion)
        }

        target.afterEvaluate { project ->
            if (!extension.enabled.get()) return@afterEvaluate

            if (project.extensions.findByName(KOTLIN_EXTENSION_NAME) == null) {
                throw GradleException(
                    "The io.heapy.kinetica plugin needs a Kotlin plugin in the same project. Apply " +
                        "org.jetbrains.kotlin.multiplatform (or .jvm) alongside it, or set " +
                        "kinetica { enabled = false }.",
                )
            }
            validateOptions()
            if (extension.addRuntimeDependencies.get()) {
                addRuntimeDependencies(project)
            }
            warnOnUnusablePsiPipeline(project)
        }
    }

    override fun getCompilerPluginId(): String =
        KineticaCoordinates.compilerPluginId

    override fun getPluginArtifact(): SubpluginArtifact =
        SubpluginArtifact(
            KineticaCoordinates.group,
            KineticaCoordinates.compilerArtifact,
            extension.compilerVersion.get(),
        )

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        extension.enabled.get()

    override fun applyToCompilation(
        kotlinCompilation: KotlinCompilation<*>,
    ): Provider<List<SubpluginOption>> {
        val project = kotlinCompilation.project
        // The PSI pipeline exists only in the JVM compiler pipeline; passing it to a JS, Native
        // or metadata compilation is a hard error there, so a multiplatform module that opts in
        // gets it on its JVM compilations and nowhere else.
        val acceptsPsi = kotlinCompilation.platformType.isJvmLike()
        if (!acceptsPsi && extension.sourcePipeline.orNull == KineticaCoordinates.sourcePipelinePsi) {
            project.logger.info(
                "Kinetica: sourcePipeline=psi not passed to ${kotlinCompilation.name} of target " +
                    "${kotlinCompilation.target.name} (${kotlinCompilation.platformType}); it is " +
                    "supported on JVM compilations only.",
            )
        }

        // Only the Property instances are captured, never the compilation, the project or the
        // logger: this provider is an input of the compile task and gets serialized by the
        // configuration cache.
        val moduleId = extension.moduleId
        val serverSourceSet = extension.serverSourceSet
        val clientSourceSet = extension.clientSourceSet
        val transforms = extension.transforms
        val checks = extension.checks
        val sourcePipeline = extension.sourcePipeline

        return project.provider {
            buildList {
                addOption(KineticaCoordinates.optionModuleId, moduleId.orNull)
                addOption(KineticaCoordinates.optionServerSourceSet, serverSourceSet.orNull)
                addOption(KineticaCoordinates.optionClientSourceSet, clientSourceSet.orNull)
                addOption(KineticaCoordinates.optionTransforms, transforms.orNull)
                addOption(KineticaCoordinates.optionChecks, checks.orNull)

                val pipeline = sourcePipeline.orNull
                if (pipeline != null && (acceptsPsi || pipeline != KineticaCoordinates.sourcePipelinePsi)) {
                    addOption(KineticaCoordinates.optionSourcePipeline, pipeline)
                }
            }
        }
    }

    private fun MutableList<SubpluginOption>.addOption(name: String, value: String?) {
        if (value != null) add(SubpluginOption(name, value))
    }

    /** The compiler stores unknown option values without complaining, so a typo would be silent. */
    private fun validateOptions() {
        checkOption(KineticaCoordinates.optionSourcePipeline, extension.sourcePipeline.orNull, SOURCE_PIPELINES)
        checkOption(KineticaCoordinates.optionTransforms, extension.transforms.orNull, TRANSFORMS)
        checkOption(KineticaCoordinates.optionChecks, extension.checks.orNull, CHECKS)
    }

    private fun checkOption(name: String, value: String?, allowed: Set<String>) {
        if (value != null && value !in allowed) {
            throw GradleException(
                "kinetica { $name = \"$value\" } is not a value the Kinetica compiler plugin " +
                    "accepts. Allowed: ${allowed.joinToString()}.",
            )
        }
    }

    private fun warnOnKotlinVersionMismatch(project: Project, kotlinVersion: String) {
        if (kotlinVersion != KineticaCoordinates.kotlinVersion) {
            project.logger.warn(
                "Kinetica ${KineticaCoordinates.version} is published for Kotlin " +
                    "${KineticaCoordinates.kotlinVersion}, this build uses Kotlin $kotlinVersion. " +
                    "klib metadata is not forward compatible — expect compilation failures until " +
                    "both versions match.",
            )
        }
    }

    private fun warnOnUnusablePsiPipeline(project: Project) {
        if (extension.sourcePipeline.orNull != KineticaCoordinates.sourcePipelinePsi) return
        if (!hasJvmTarget(project)) {
            project.logger.warn(
                "Kinetica: sourcePipeline=psi has no effect here — the project has no JVM target.",
            )
        }
    }

    private fun addRuntimeDependencies(project: Project) {
        val version = extension.kineticaVersion.get()
        val runtime = "${KineticaCoordinates.group}:${KineticaCoordinates.runtimeArtifact}:$version"
        val browser = "${KineticaCoordinates.group}:${KineticaCoordinates.browserArtifact}:$version"
        val sourceSets = sourceSetsOf(project)

        val common = sourceSets.findByName(KotlinSourceSet.COMMON_MAIN_SOURCE_SET_NAME)
        if (common != null) {
            // Multiplatform: commonMain carries the runtime for every target at once.
            common.addImplementation(runtime)
        } else {
            sourceSets.findByName(SINGLE_TARGET_MAIN_SOURCE_SET_NAME)?.addImplementation(runtime)
        }

        jsMainSourceSetNames(project).forEach { name ->
            sourceSets.findByName(name)?.addImplementation(browser)
        }
    }

    private fun KotlinSourceSet.addImplementation(notation: String) {
        dependencies { handler -> handler.implementation(notation) }
    }

    // Single-target JS is not a shape Kinetica can be consumed from: the `kotlin-js` plugin is a
    // hard error in Kotlin 2.4.10 ("use kotlin(\"multiplatform\") with a js() target"), so a JS
    // target always comes from the multiplatform extension and its targets container.
    private fun jsMainSourceSetNames(project: Project): List<String> =
        targetsOf(project)
            .filter { target -> target.platformType == KotlinPlatformType.js }
            .map { target ->
                target.compilations
                    .findByName(KotlinCompilation.MAIN_COMPILATION_NAME)
                    ?.defaultSourceSet
                    ?.name
                    ?: "${target.name}$MAIN_SOURCE_SET_SUFFIX"
            }

    private fun hasJvmTarget(project: Project): Boolean {
        val targets = targetsOf(project)
        if (targets.isNotEmpty()) return targets.any { target -> target.platformType.isJvmLike() }
        // Single-target projects have no targets container (KotlinJvmProjectExtension is a
        // KotlinSingleTargetExtension), so the applied plugin is the only signal.
        return project.pluginManager.hasPlugin(KOTLIN_JVM_PLUGIN_ID) ||
            project.pluginManager.hasPlugin(KOTLIN_ANDROID_PLUGIN_ID)
    }

    private fun sourceSetsOf(project: Project) =
        (project.extensions.getByName(KOTLIN_EXTENSION_NAME) as KotlinSourceSetContainer).sourceSets

    private fun targetsOf(project: Project): List<KotlinTarget> =
        (project.extensions.getByName(KOTLIN_EXTENSION_NAME) as? KotlinTargetsContainer)
            ?.targets
            ?.toList()
            .orEmpty()

    private fun KotlinPlatformType.isJvmLike(): Boolean =
        this == KotlinPlatformType.jvm || this == KotlinPlatformType.androidJvm

    private companion object {
        const val KOTLIN_EXTENSION_NAME = "kotlin"
        const val KOTLIN_JVM_PLUGIN_ID = "org.jetbrains.kotlin.jvm"
        const val KOTLIN_ANDROID_PLUGIN_ID = "org.jetbrains.kotlin.android"

        // Single-target projects (kotlin("jvm"), kotlin("js")) name their source sets main/test.
        const val SINGLE_TARGET_MAIN_SOURCE_SET_NAME = "main"
        const val MAIN_SOURCE_SET_SUFFIX = "Main"

        val SOURCE_PIPELINES = setOf("psi", "lightTree")
        val TRANSFORMS = setOf("all", "off")
        val CHECKS = setOf("error", "off")
    }
}
