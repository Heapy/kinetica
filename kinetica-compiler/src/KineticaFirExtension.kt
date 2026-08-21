package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.contracts.description.EventOccurrencesRange
import org.jetbrains.kotlin.contracts.description.KtCallsEffectDeclaration
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.error2
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.ConstantValueKind

/**
 * Frontend authoring rules for the plugin-only slot model. Registered on every backend
 * (FIR is platform-neutral) when the `checks` plugin option is not `off`:
 *
 * A. Slot/event DSL calls are legal only lexically inside `@UiComponent` functions.
 * B. `@UiComponent` calls are legal inside `@UiComponent` bodies or inside lambda
 *    literals whose expected function type carries `@UiComponent` (entry-point content).
 * C. Region-construct content/fallback arguments must be lambda literals — the compiler
 *    numbers their bodies into fresh frame tables, which is impossible for references.
 * D. No slot/event/component call directly inside a loop body: static ordinals cannot
 *    tell iterations apart. `keyed {}` / `each(key = …)` are the sanctioned loop forms.
 * E. `@UiComponent` functions must have a `ComponentScope` receiver.
 * F. Ordinal-consuming calls cannot sit in arbitrary multi-run lambdas. Kinetica DSL
 *    content and Kotlin's single-run scope functions share the enclosing frame safely.
 */
internal val KINETICA_PACKAGE: FqName = KineticaFramePolicy.KINETICA_PACKAGE
internal val UI_COMPONENT_CLASS_ID: ClassId = ClassId(KINETICA_PACKAGE, Name.identifier("UiComponent"))
internal val COMPONENT_SCOPE_CLASS_ID: ClassId = KineticaFramePolicy.COMPONENT_SCOPE_CLASS_ID

/** Runtime DSL calls that consume slot or event ordinals. */
private val SLOT_DSL_NAMES = setOf(
    "state", "derived", "launchEffect", "watch", "event",
    "hostRef", "imperativeHandle", "resource", "frameValue",
    "errorBoundary", "loadingBoundary", "suspendSubtree", "exitGroup",
    "hostEvent", "hostEventBlock", "button", "textInput", "checkbox",
)

/** Region constructs that disambiguate loop iterations by user key (allowed in loops). */
private val LOOP_SAFE_REGION_NAMES = setOf("keyed", "suspendKeyed", "each", "lazyEach")

// Region tables and single-run tables live in KineticaFramePolicy, shared with the IR
// frame pass, so the checker's predictions can never drift from what IR actually numbers.
private val REGION_CONTENT_PARAMETERS = KineticaFramePolicy.REGION_CONTENT_PARAMETERS
private val REGION_CONSTRUCT_NAMES = KineticaFramePolicy.REGION_CONSTRUCT_NAMES
private val OPTIONAL_EVENT_PARAMETERS = mapOf(
    "button" to setOf("onClick"),
    "textInput" to setOf("onInput", "onSubmit"),
    "checkbox" to setOf("onToggle"),
)

public object KineticaFirErrors : KtDiagnosticsContainer() {
    public val SLOT_CALL_OUTSIDE_COMPONENT: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
    public val COMPONENT_CALL_OUTSIDE_COMPONENT: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
    public val SLOT_CALL_IN_LOOP: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
    public val CALL_IN_MULTI_RUN_LAMBDA: KtDiagnosticFactory2<String, String> by
        error2<PsiElement, String, String>()
    public val REGION_CONTENT_NOT_LITERAL: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
    public val COMPONENT_WITHOUT_SCOPE_RECEIVER: KtDiagnosticFactory1<String> by error1<PsiElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = KineticaFirErrorRenderers
}

public object KineticaFirErrorRenderers : BaseDiagnosticRendererFactory() {
    override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Kinetica") { map ->
        map.put(
            KineticaFirErrors.SLOT_CALL_OUTSIDE_COMPONENT,
            "''{0}'' can only be called inside a @UiComponent function. " +
                "Move the call into a @UiComponent, or annotate the enclosing function.",
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.COMPONENT_CALL_OUTSIDE_COMPONENT,
            "@UiComponent function ''{0}'' can only be called from a @UiComponent function " +
                "or a lambda whose type is annotated with @UiComponent (such as render content).",
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.SLOT_CALL_IN_LOOP,
            "''{0}'' must not be called directly inside a loop: compiler-assigned slot ordinals " +
                "cannot tell iterations apart. Wrap the loop body in keyed(key) '{' … '}' or use each(items, key = '{' … '}').",
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.CALL_IN_MULTI_RUN_LAMBDA,
            "Kinetica call ''{0}'' cannot use a compiler-assigned ordinal inside the multi-run ''{1}'' lambda. " +
                "Use each(items, key = ...) or keyed(...) for repeated rendering.",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.REGION_CONTENT_NOT_LITERAL,
            "The ''{0}'' argument of a Kinetica region must be a lambda literal so the compiler " +
                "can assign its slot ordinals; passing a function reference or variable is not supported.",
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.COMPONENT_WITHOUT_SCOPE_RECEIVER,
            "@UiComponent function ''{0}'' must be an extension of io.heapy.kinetica.ComponentScope.",
            CommonRenderers.STRING,
        )
    }
}

public class KineticaFirExtensionRegistrar internal constructor(
    private val singleRunOracle: SingleRunOracle,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        val factory: (FirSession) -> FirAdditionalCheckersExtension = { session ->
            KineticaFirCheckersExtension(session, singleRunOracle)
        }
        +factory
    }
}

internal class KineticaFirCheckersExtension(
    session: FirSession,
    singleRunOracle: SingleRunOracle,
) : FirAdditionalCheckersExtension(session) {

    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirExpressionChecker<FirFunctionCall>> =
            setOf(KineticaCallChecker(singleRunOracle))
    }

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val simpleFunctionCheckers: Set<FirDeclarationChecker<FirNamedFunction>> =
            setOf(KineticaComponentDeclarationChecker)
    }
}

private class KineticaCallChecker(
    private val singleRunOracle: SingleRunOracle,
) : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return
        // Verdicts are recorded for EVERY call carrying a lambda literal — before the
        // Kinetica-relevance early return, and independent of the rule-F walk — so oracle
        // coverage never depends on which calls happen to classify as ordinal consumers.
        expression.recordContractVerdicts(callee, singleRunOracle)
        val session = context.session
        val name = callee.callableId?.callableName?.asString() ?: callee.name.asString()
        // Same-package members of other classes (e.g. KineticaRuntime.frameValue) are not
        // slot DSL: only ComponentScope members and ComponentScope extensions qualify.
        val isKineticaDsl = callee.isKineticaDsl()
        val isSlotDsl = isKineticaDsl && name in SLOT_DSL_NAMES
        val isRegionConstruct = isKineticaDsl && name in REGION_CONSTRUCT_NAMES
        val isLoopSafeRegion = isKineticaDsl && name in LOOP_SAFE_REGION_NAMES
        val isComponentCall = callee.hasAnnotation(UI_COMPONENT_CLASS_ID, session)
        val hasComponentTypedLambdaArgument = expression.hasComponentTypedLambdaArgument()
        if (!isSlotDsl && !isRegionConstruct && !isLoopSafeRegion &&
            !isComponentCall && !hasComponentTypedLambdaArgument
        ) {
            return
        }

        val containment = context.classifyContainment(session)

        if ((isSlotDsl || isRegionConstruct) && containment != KineticaContainment.COMPONENT_BODY) {
            reporter.reportOn(expression.source, KineticaFirErrors.SLOT_CALL_OUTSIDE_COMPONENT, name, context)
            return
        }

        if (isComponentCall && containment == KineticaContainment.OUTSIDE) {
            reporter.reportOn(expression.source, KineticaFirErrors.COMPONENT_CALL_OUTSIDE_COMPONENT, name, context)
            return
        }

        val consumesCompilerOrdinal = expression.consumesCompilerOrdinal(session)

        // Static ordinals cannot tell loop iterations apart. Optional host-event calls
        // whose handler is absent or a literal null never register an event at runtime.
        if (!isLoopSafeRegion && consumesCompilerOrdinal && context.isDirectlyInsideLoop(session)) {
            reporter.reportOn(expression.source, KineticaFirErrors.SLOT_CALL_IN_LOOP, name, context)
            return
        }

        // Static ordinals are sound only in lambdas known to run at most once per frame.
        // FIR owns this authoring error so `checks=off` consistently disables it before IR.
        if (consumesCompilerOrdinal) {
            val unsafeHost = context.multiRunLambdaHost(expression, session)
            if (unsafeHost != null) {
                reporter.reportOn(
                    expression.source,
                    KineticaFirErrors.CALL_IN_MULTI_RUN_LAMBDA,
                    name,
                    unsafeHost,
                    context,
                )
                return
            }
        }

        if (isRegionConstruct) {
            val mapping = (expression.argumentList as? FirResolvedArgumentList)?.mapping ?: return
            for ((argument, parameter) in mapping) {
                if (parameter.name.asString() !in REGION_CONTENT_PARAMETERS.getValue(name)) continue
                if (argument.unwrapArgument() !is FirAnonymousFunctionExpression) {
                    reporter.reportOn(
                        argument.source ?: expression.source,
                        KineticaFirErrors.REGION_CONTENT_NOT_LITERAL,
                        parameter.name.asString(),
                        context,
                    )
                }
            }
        }
    }
}

/**
 * Records the callee's `callsInPlace` single-run verdicts in the per-compilation
 * [SingleRunOracle] when this call passes at least one lambda literal. The IR frame pass
 * descends into exactly the lambda parameters recorded here; everything else falls back
 * to the [KineticaFramePolicy] name lists on both sides.
 */
private fun FirFunctionCall.recordContractVerdicts(
    callee: FirCallableSymbol<*>,
    singleRunOracle: SingleRunOracle,
) {
    // Cheap gates first: most calls carry no lambda literal, and contract resolution is
    // comparatively expensive (lazy resolve to the CONTRACTS phase).
    val mapping = (argumentList as? FirResolvedArgumentList)?.mapping ?: return
    if (mapping.keys.none { argument -> argument.unwrapArgument() is FirAnonymousFunctionExpression }) return
    val function = callee as? FirFunctionSymbol<*> ?: return
    val callableId = function.callableId ?: return
    val singleRunParameters = function.contractSingleRunParameterNames()
    if (singleRunParameters.isEmpty()) return
    val regularParameterCount = function.valueParameterSymbols.size
    for (parameterName in singleRunParameters) {
        singleRunOracle.recordSingleRun(callableId, regularParameterCount, parameterName)
    }
}

/**
 * Names of the callee's lambda parameters whose resolved contract proves them single-run:
 * `callsInPlace(block, EXACTLY_ONCE)` or `AT_MOST_ONCE`. Any other occurrence kind — or
 * no contract at all — contributes nothing: absence is NOT a multi-run verdict, it falls
 * through to the [KineticaFramePolicy] name lists on both compiler phases, so the Kotlin
 * scope functions never regress if contract resolution fails.
 */
private fun FirCallableSymbol<*>.contractSingleRunParameterNames(): Set<String> {
    val function = this as? FirFunctionSymbol<*> ?: return emptySet()
    val effects = function.resolvedContractDescription?.effects ?: return emptySet()
    if (effects.isEmpty()) return emptySet()
    val parameters = function.valueParameterSymbols
    var names: MutableSet<String>? = null
    for (declaration in effects) {
        val effect = declaration.effect as? KtCallsEffectDeclaration<*, *> ?: continue
        if (effect.kind != EventOccurrencesRange.EXACTLY_ONCE &&
            effect.kind != EventOccurrencesRange.AT_MOST_ONCE
        ) {
            continue
        }
        val parameter = parameters.getOrNull(effect.valueParameterReference.parameterIndex) ?: continue
        (names ?: mutableSetOf<String>().also { names = it }) += parameter.name.asString()
    }
    return names ?: emptySet()
}

/**
 * Whether this call needs an ordinal injected by the IR frame pass — true only for the
 * calls IR actually numbers: slot DSL calls, region constructs, host event registrations,
 * and staged @UiComponent component calls. Merely receiving a @UiComponent-typed lambda
 * argument (an entry point such as `render`, or a user content-wrapper helper) does NOT
 * consume an ordinal: IR only wraps that content into a fresh frame table and numbers
 * nothing on the call itself. Optional host-event handlers are exempt only when the
 * argument is absent or a literal `null` — then the runtime provably never registers the
 * event, so the statically filled ordinal stays unused. The parameter's declared
 * nullability is deliberately NOT consulted: a nullable-typed value can still be non-null
 * at runtime, registering an ordinal IR never assigned (crash) or one static ordinal
 * shared across iterations (event aliasing).
 */
private fun FirFunctionCall.consumesCompilerOrdinal(session: FirSession): Boolean {
    val callee = calleeReference.toResolvedCallableSymbol() ?: return false
    val callableId = callee.callableId ?: return false
    val name = callableId.callableName.asString()
    if (callee.hasAnnotation(UI_COMPONENT_CLASS_ID, session)) return true
    if (!callee.isKineticaDsl()) return false
    if (name in REGION_CONSTRUCT_NAMES) return true
    if (name !in SLOT_DSL_NAMES) return false

    val optionalEventParameters = OPTIONAL_EVENT_PARAMETERS[name] ?: return true
    val mapping = (argumentList as? FirResolvedArgumentList)?.mapping ?: return false
    return mapping.any { (argument, parameter) ->
        parameter.name.asString() in optionalEventParameters && !argument.isLiteralNull()
    }
}

private fun FirFunctionCall.hasComponentTypedLambdaArgument(): Boolean {
    val mapping = (argumentList as? FirResolvedArgumentList)?.mapping ?: return false
    return mapping.any { (argument, parameter) ->
        argument.unwrapArgument() is FirAnonymousFunctionExpression && parameter.hasUiComponentFunctionType()
    }
}

private fun FirValueParameter.hasUiComponentFunctionType(): Boolean =
    returnTypeRef.coneTypeOrNull.hasUiComponentAnnotation() ||
        returnTypeRef.annotations.any { annotation ->
            annotation.annotationTypeRef.coneTypeOrNull?.classId == UI_COMPONENT_CLASS_ID
        }

private fun FirCallableSymbol<*>.isKineticaDsl(): Boolean {
    val callableId = callableId ?: return false
    return KineticaFramePolicy.isKineticaDsl(
        containerFqName = callableId.classId?.asSingleFqName() ?: callableId.packageName,
        hasComponentScopeExtensionReceiver = callableId.classId == null &&
            resolvedReceiverTypeRef?.coneType?.classId == COMPONENT_SCOPE_CLASS_ID,
        name = callableId.callableName.asString(),
    )
}

private fun FirExpression.isLiteralNull(): Boolean {
    val unwrapped = unwrapArgument()
    return unwrapped is FirLiteralExpression && unwrapped.kind == ConstantValueKind.Null
}

private object KineticaComponentDeclarationChecker : FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!declaration.hasAnnotation(UI_COMPONENT_CLASS_ID, context.session)) return
        val receiverClassId = declaration.receiverParameter?.typeRef?.coneTypeOrNull?.classId
        if (receiverClassId != COMPONENT_SCOPE_CLASS_ID) {
            reporter.reportOn(
                declaration.source,
                KineticaFirErrors.COMPONENT_WITHOUT_SCOPE_RECEIVER,
                declaration.name.asString(),
                context,
            )
        }
    }
}

private enum class KineticaContainment {
    /** Lexically inside a @UiComponent function declaration. */
    COMPONENT_BODY,

    /** Inside a lambda literal whose function type is annotated @UiComponent (entry content). */
    COMPONENT_TYPED_LAMBDA,

    OUTSIDE,
}

private fun CheckerContext.classifyContainment(session: FirSession): KineticaContainment {
    val elements = containingElements
    for (index in elements.indices.reversed()) {
        when (val element = elements[index]) {
            is FirAnonymousFunction -> {
                if (isComponentTypedLambda(elements, index, element, session)) {
                    return KineticaContainment.COMPONENT_TYPED_LAMBDA
                }
                // Plain lambdas share the enclosing numbering region; keep walking out.
            }
            is FirNamedFunction ->
                return if (element.hasAnnotation(UI_COMPONENT_CLASS_ID, session)) {
                    KineticaContainment.COMPONENT_BODY
                } else {
                    KineticaContainment.OUTSIDE
                }
            else -> {}
        }
    }
    return KineticaContainment.OUTSIDE
}

/**
 * True when a loop sits between the checked call and its numbering-region boundary — the
 * enclosing function declaration or a region-content lambda (fresh frame table per row).
 */
private fun CheckerContext.isDirectlyInsideLoop(session: FirSession): Boolean {
    val elements = containingElements
    for (index in elements.indices.reversed()) {
        when (val element = elements[index]) {
            is FirLoop -> return true
            is FirNamedFunction -> return false
            is FirAnonymousFunction -> {
                if (isComponentTypedLambda(elements, index, element, session)) return false
                if (isRegionContentArgument(elements, index, element)) return false
                // Plain lambda: still executes per iteration if a loop encloses it.
            }
            else -> {}
        }
    }
    return false
}

private data class LambdaHost(
    val callee: FirCallableSymbol<*>,
    val parameter: FirValueParameter,
) {
    val name: String
        get() = callee.callableId?.callableName?.asString() ?: callee.name.asString()

    fun isRegionContent(): Boolean {
        if (!callee.isKineticaDsl()) return false
        val parameters = REGION_CONTENT_PARAMETERS[name] ?: return false
        return parameter.name.asString() in parameters
    }

    fun isKnownSingleRun(): Boolean {
        // Kinetica DSL is classified per (callee, parameter), not blanket-whitelisted:
        // region content and inline content lambdas share the enclosing frame, while
        // deferred handlers and the each/lazyEach key selectors never run inline in the
        // numbering frame (F4, F5) — IR does not number inside them either.
        if (callee.isKineticaDsl()) {
            return !KineticaFramePolicy.isMultiRunDslParameter(name, parameter.name.asString())
        }
        // Contract verdict first: callsInPlace(…, EXACTLY_ONCE / AT_MOST_ONCE) proves the
        // parameter single-run. This predicate stays pure (it re-derives the verdict, it
        // never reads the oracle) so checker traversal order cannot matter; the oracle is
        // populated separately in recordContractVerdicts.
        if (parameter.name.asString() in callee.contractSingleRunParameterNames()) return true
        val callableId = callee.callableId ?: return false
        return KineticaFramePolicy.isSingleRunScopeFunction(
            containerFqName = callableId.classId?.asSingleFqName() ?: callableId.packageName,
            name = name,
        )
    }
}

private fun findLambdaHost(
    elements: List<FirElement>,
    index: Int,
    lambda: FirAnonymousFunction,
): LambdaHost? {
    for (outer in (index - 1) downTo 0) {
        when (val element = elements[outer]) {
            is FirFunctionCall -> {
                val mapping = (element.argumentList as? FirResolvedArgumentList)?.mapping ?: continue
                for ((argument, parameter) in mapping) {
                    val unwrapped = argument.unwrapArgument()
                    val expression = unwrapped as? FirAnonymousFunctionExpression ?: continue
                    val argumentFunction = expression.anonymousFunction
                    val argumentSource = argumentFunction.source
                    val lambdaSource = lambda.source
                    val sameLambda = argumentFunction.symbol == lambda.symbol ||
                        argumentSource != null && lambdaSource != null &&
                        argumentSource.startOffset == lambdaSource.startOffset &&
                        argumentSource.endOffset == lambdaSource.endOffset
                    if (sameLambda) {
                        val callee = element.calleeReference.toResolvedCallableSymbol() ?: return null
                        return LambdaHost(callee, parameter)
                    }
                }
            }
            is FirNamedFunction, is FirAnonymousFunction -> return null
            else -> {}
        }
    }
    return null
}

private fun isRegionContentArgument(
    elements: List<FirElement>,
    index: Int,
    lambda: FirAnonymousFunction,
): Boolean = findLambdaHost(elements, index, lambda)?.isRegionContent() == true

/**
 * Returns the outermost arbitrary lambda host before the current numbering boundary.
 * That is the call the IR walker encounters and declines to enter. If another ordinal
 * consumer encloses the checked call inside that same lambda, only the outer call reports.
 */
private fun CheckerContext.multiRunLambdaHost(
    expression: FirFunctionCall,
    session: FirSession,
): String? {
    val elements = containingElements
    var unsafeLambdaIndex = -1
    var unsafeHostName: String? = null

    search@ for (index in elements.indices.reversed()) {
        when (val element = elements[index]) {
            is FirAnonymousFunction -> {
                if (isComponentTypedLambda(elements, index, element, session) ||
                    isRegionContentArgument(elements, index, element)
                ) {
                    break@search
                }
                val host = findLambdaHost(elements, index, element) ?: continue
                if (!host.isKnownSingleRun()) {
                    // Walking inside-out means the last unsafe host found is the outermost
                    // one — exactly where the IR transform stops descending.
                    unsafeLambdaIndex = index
                    unsafeHostName = host.name
                }
            }
            is FirNamedFunction -> break@search
            else -> {}
        }
    }

    val hostName = unsafeHostName ?: return null
    val nestedUnderOrdinalConsumer = ((unsafeLambdaIndex + 1)..elements.lastIndex).any { index ->
        val enclosingCall = elements[index] as? FirFunctionCall ?: return@any false
        enclosingCall !== expression &&
            !enclosingCall.hasSameSourceAs(expression) &&
            enclosingCall.consumesCompilerOrdinal(session)
    }
    return hostName.takeUnless { nestedUnderOrdinalConsumer }
}

private fun FirFunctionCall.hasSameSourceAs(other: FirFunctionCall): Boolean {
    val first = source ?: return false
    val second = other.source ?: return false
    return first.startOffset == second.startOffset && first.endOffset == second.endOffset
}

/**
 * Whether the lambda literal at [index] is component content: its own function type
 * carries `@UiComponent`, or it is passed to a parameter whose declared type does. The
 * inferred lambda type usually drops parameter-type annotations, so the enclosing call's
 * resolved argument mapping is the reliable source.
 */
private fun isComponentTypedLambda(
    elements: List<FirElement>,
    index: Int,
    lambda: FirAnonymousFunction,
    session: FirSession,
): Boolean {
    if (lambda.typeRef.coneTypeOrNull.hasUiComponentAnnotation()) {
        return true
    }
    val parameter = findLambdaHost(elements, index, lambda)?.parameter ?: return false
    return parameter.hasUiComponentFunctionType()
}

private fun ConeKotlinType?.hasUiComponentAnnotation(): Boolean {
    val annotations = this?.customAnnotations ?: return false
    return annotations.any { annotation ->
        annotation.annotationTypeRef.coneTypeOrNull?.classId == UI_COMPONENT_CLASS_ID
    }
}
