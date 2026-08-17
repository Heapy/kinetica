package io.heapy.kinetica.gradle

import org.gradle.api.provider.Property

/**
 * `kinetica { }` in a consumer's build script.
 *
 * Option names and accepted values are the compiler plugin's own — see
 * `kinetica-compiler/src/KineticaCommandLineProcessor.kt` for what each one does.
 */
public abstract class KineticaExtension {
    /** Master switch: `false` applies neither the compiler plugin nor the dependencies below. */
    public abstract val enabled: Property<Boolean>

    /** First segment of generated SlotId values. Unset: the compilation's Kotlin module name. */
    public abstract val moduleId: Property<String>

    /** Source set treated as the server side of the server/client boundary, e.g. `jvmMain`. */
    public abstract val serverSourceSet: Property<String>

    /** Source set treated as the client side of the server/client boundary, e.g. `jsMain`. */
    public abstract val clientSourceSet: Property<String>

    /**
     * `psi` or `lightTree`. `psi` enables the JVM-only source-processing pipeline and is passed
     * to JVM compilations only — on JS and Native it must stay unset, so the plugin drops it
     * there instead of failing the build of a multiplatform module.
     */
    public abstract val sourcePipeline: Property<String>

    /** `all` or `off` — kill switch for the IR perf transforms. */
    public abstract val transforms: Property<String>

    /** `error` or `off` — the authoring-rule checkers. Turning them off is a migration escape. */
    public abstract val checks: Property<String>

    /**
     * Adds `kinetica-runtime` (and `kinetica-browser` for JS targets) at [kineticaVersion].
     * Set to `false` to declare them yourself, e.g. to pin a different version.
     */
    public abstract val addRuntimeDependencies: Property<Boolean>

    /**
     * Version of the Kinetica artifacts: the compiler plugin and, when
     * [addRuntimeDependencies] is on, the runtime ones. Defaults to the version of this plugin.
     */
    public abstract val kineticaVersion: Property<String>

    /**
     * Version of `io.heapy.kinetica:kinetica-compiler` alone; defaults to [kineticaVersion].
     * Override it to compile against a different compiler build than the runtime — a compiler
     * bug hunt, not an everyday setting.
     */
    public abstract val compilerVersion: Property<String>
}
