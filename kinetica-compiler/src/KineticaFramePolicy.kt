package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Single source of truth for every classification table and predicate that BOTH compiler
 * phases consult. The FIR checker exists to predict exactly what the IR frame pass does;
 * that promise only holds while the two phases read the same tables and the same
 * Kinetica-lambda classification. Never re-declare these values in a phase-local copy —
 * the copies have drifted before, rejecting correct code on one side and leaving
 * unnumbered ordinals (runtime `MissingKineticaPluginException`) on the other.
 */
internal object KineticaFramePolicy {
    val KINETICA_PACKAGE: FqName = FqName("io.heapy.kinetica")
    val COMPONENT_SCOPE_CLASS_ID: ClassId =
        ClassId(KINETICA_PACKAGE, Name.identifier("ComponentScope"))
    val COMPONENT_SCOPE_FQ: FqName = COMPONENT_SCOPE_CLASS_ID.asSingleFqName()
    val KOTLIN_PACKAGE: FqName = FqName("kotlin")

    /**
     * Region constructs and the parameter names of their fresh-region content lambdas.
     * FIR requires these arguments to be lambda literals and treats their bodies as
     * numbering boundaries; IR wraps exactly the same parameters into fresh frame tables.
     */
    val REGION_CONTENT_PARAMETERS: Map<String, Set<String>> = mapOf(
        "keyed" to setOf("content"),
        "suspendKeyed" to setOf("content"),
        "each" to setOf("content"),
        "lazyEach" to setOf("content", "placeholder"),
        "errorBoundary" to setOf("content", "fallback"),
        "loadingBoundary" to setOf("content", "fallback"),
        "suspendSubtree" to setOf("content", "fallback"),
        "exitGroup" to setOf("content"),
    )

    val REGION_CONSTRUCT_NAMES: Set<String> = REGION_CONTENT_PARAMETERS.keys

    /** `kotlin` package scope functions whose lambdas run exactly once, in place. */
    val SINGLE_RUN_SCOPE_FUNCTIONS: Set<String> = setOf("let", "run", "with", "apply", "also")

    /**
     * Top-level `io.heapy.kinetica` functions WITHOUT a ComponentScope receiver whose
     * lambdas are still single-run numbering contexts. Kept inside the shared predicate
     * (not as a phase-local patch list) so FIR and IR can never disagree about them.
     */
    private val SINGLE_RUN_TOP_LEVEL_FUNCTIONS: Set<String> = setOf("peek")

    /**
     * THE shared Kinetica-lambda classification: whether a callee is Kinetica DSL whose
     * lambda arguments share the enclosing numbering frame (single-run content the IR
     * walker descends into and FIR therefore allows ordinal consumers inside).
     *
     * [containerFqName] is the callee's containing declaration: the class FqName for
     * members, the package FqName for top-level functions. Members of other classes in
     * the Kinetica package (e.g. `KineticaRuntime.frameValue`) are NOT slot DSL: only
     * `ComponentScope` members, top-level `ComponentScope` extensions, and the named
     * single-run top-level helpers qualify.
     */
    fun isKineticaDsl(
        containerFqName: FqName?,
        hasComponentScopeExtensionReceiver: Boolean,
        name: String,
    ): Boolean {
        if (containerFqName == COMPONENT_SCOPE_FQ) return true
        if (containerFqName != KINETICA_PACKAGE) return false
        return hasComponentScopeExtensionReceiver || name in SINGLE_RUN_TOP_LEVEL_FUNCTIONS
    }

    /**
     * Whether a callee is one of Kotlin's single-run scope functions — the only
     * non-Kinetica lambda hosts both phases treat as sharing the enclosing frame.
     */
    fun isSingleRunScopeFunction(containerFqName: FqName?, name: String): Boolean =
        containerFqName == KOTLIN_PACKAGE && name in SINGLE_RUN_SCOPE_FUNCTIONS
}
