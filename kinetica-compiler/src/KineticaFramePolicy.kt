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

    /**
     * Lambda parameters of Kinetica DSL functions that never run inline during the render
     * pass that numbers them. Deferred handlers (event dispatch, post-commit effects,
     * async loaders) execute arbitrarily often when `currentFrame` is no longer the
     * numbering frame, and the per-item `key` selectors of `each`/`lazyEach` run once per
     * item while numbered with the ENCLOSING region's counters. An ordinal consumer
     * inside one of these lambdas aliases state or crashes at runtime, so FIR rejects it
     * (rule F) and the IR walker never assigns ordinals inside the lambda. Every OTHER
     * Kinetica DSL lambda parameter (region content, inline content such as `button`'s
     * `content`, slot initializers) shares the enclosing frame and stays single-run.
     */
    val MULTI_RUN_DSL_PARAMETERS: Map<String, Set<String>> = mapOf(
        "event" to setOf("block"),
        "hostEvent" to setOf("onEvent"),
        "hostEventBlock" to setOf("block"),
        "launchEffect" to setOf("block"),
        "layoutEffect" to setOf("block"),
        "watch" to setOf("source", "block"),
        "action" to setOf("invalidates", "block"),
        "resource" to setOf("loader"),
        "button" to setOf("onClick"),
        "textInput" to setOf("onInput", "onSubmit"),
        "checkbox" to setOf("onToggle"),
        "each" to setOf("key"),
        "lazyEach" to setOf("key"),
        "eachRegion" to setOf("key"),
        "lazyEachRegion" to setOf("key"),
    )

    /** Whether [parameterName] of the Kinetica DSL function [calleeName] is multi-run. */
    fun isMultiRunDslParameter(calleeName: String, parameterName: String): Boolean =
        MULTI_RUN_DSL_PARAMETERS[calleeName]?.contains(parameterName) == true

    /**
     * `kotlin` package functions whose lambdas run at most once, in place. This is the
     * shared FALLBACK for callees without a usable `callsInPlace` contract verdict (the
     * contract-derived verdicts travel FIR→IR through `SingleRunOracle` instead).
     * `runCatching` is listed because the pinned stdlib (2.4.10) declares no contract on
     * it, yet it is semantically a `try` block — single-run by construction. Both
     * overloads (top-level and `T.runCatching`) live in package `kotlin`.
     */
    val SINGLE_RUN_SCOPE_FUNCTIONS: Set<String> =
        setOf("let", "run", "with", "apply", "also", "runCatching")

    /**
     * Top-level `io.heapy.kinetica` functions WITHOUT a ComponentScope receiver whose
     * lambdas are still single-run numbering contexts. Kept inside the shared predicate
     * (not as a phase-local patch list) so FIR and IR can never disagree about them.
     * `peek` runs its block inline exactly once (`ReadTracking.peek`), so ordinal
     * consumers inside it share the enclosing frame soundly.
     */
    val SINGLE_RUN_TOP_LEVEL_FUNCTIONS: Set<String> = setOf("peek")

    /**
     * Top-level `io.heapy.kinetica` functions WITHOUT a ComponentScope receiver whose
     * lambdas are NOT numbering contexts: FIR rejects ordinal consumers inside them
     * (rule F) and the IR walker never descends into them (F12). This ledger is NOT
     * consulted by [isKineticaDsl] — absence from [SINGLE_RUN_TOP_LEVEL_FUNCTIONS]
     * already classifies a top-level lambda as multi-run on both phases. It exists so
     * the enumeration drift test can force an explicit single-run/multi-run decision
     * for every top-level lambda-taking helper the runtime publishes; adding a name
     * here changes nothing behaviorally, it records the decision.
     *
     * Why each is multi-run: `derive`'s compute lambda re-runs reactively whenever a
     * dependency cell changes (`DerivedCell`); `invalidate`'s predicate runs once per
     * cached resource key at invalidation time, long after the numbering render pass;
     * `serverActionStub`'s handler runs per server-action dispatch.
     */
    val MULTI_RUN_TOP_LEVEL_FUNCTIONS: Set<String> =
        setOf("derive", "invalidate", "serverActionStub")

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
