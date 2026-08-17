package io.heapy.kinetica.gradle

/**
 * Coordinates and option names baked into the plugin at build time.
 *
 * The module has no compile dependency on `kinetica-compiler` (it must not compile with the
 * Kinetica compiler plugin), so the compiler contract is duplicated here as string constants.
 * `KineticaPluginContractTest` asserts every one of them against `KineticaCompilerContract`, and
 * [version] against `publish.module-template.yaml` — the same line `scripts/release.sh` parses.
 */
public object KineticaCoordinates {
    public const val group: String = "io.heapy.kinetica"
    public const val version: String = "0.4.0"

    public const val compilerPluginId: String = "io.heapy.kinetica.compiler"
    public const val compilerArtifact: String = "kinetica-compiler"
    public const val runtimeArtifact: String = "kinetica-runtime"
    public const val browserArtifact: String = "kinetica-browser"

    /** The Kotlin version Kinetica is published with; klib metadata is not forward compatible. */
    public const val kotlinVersion: String = "2.4.10"

    public const val optionModuleId: String = "moduleId"
    public const val optionServerSourceSet: String = "serverSourceSet"
    public const val optionClientSourceSet: String = "clientSourceSet"
    public const val optionTransforms: String = "transforms"
    public const val optionSourcePipeline: String = "sourcePipeline"
    public const val optionChecks: String = "checks"

    /** The only [optionSourcePipeline] value that is JVM-only. */
    public const val sourcePipelinePsi: String = "psi"
}
