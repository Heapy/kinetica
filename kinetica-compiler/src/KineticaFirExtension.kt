package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
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
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

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
 */
internal val KINETICA_PACKAGE: FqName = FqName("io.heapy.kinetica")
internal val UI_COMPONENT_CLASS_ID: ClassId = ClassId(KINETICA_PACKAGE, Name.identifier("UiComponent"))
internal val COMPONENT_SCOPE_CLASS_ID: ClassId = ClassId(KINETICA_PACKAGE, Name.identifier("ComponentScope"))

/** Slot- or event-ordinal-consuming runtime DSL (rule A + D). */
private val SLOT_DSL_NAMES = setOf(
    "state", "derived", "launchEffect", "watch", "event",
    "hostRef", "imperativeHandle", "resource", "frameValue",
    "errorBoundary", "loadingBoundary", "suspendSubtree", "exitGroup",
    "hostEvent", "hostEventBlock", "button", "textInput", "checkbox",
)

/** Region constructs that disambiguate loop iterations by user key (allowed in loops). */
private val LOOP_SAFE_REGION_NAMES = setOf("keyed", "suspendKeyed", "each", "lazyEach")

/** Region constructs whose function-typed content arguments must be lambda literals (rule C). */
private val REGION_CONSTRUCT_NAMES = LOOP_SAFE_REGION_NAMES +
    setOf("errorBoundary", "loadingBoundary", "suspendSubtree", "exitGroup")

private val REGION_CONTENT_PARAMETER_NAMES = setOf("content", "fallback")

public object KineticaFirErrors : KtDiagnosticsContainer() {
    public val SLOT_CALL_OUTSIDE_COMPONENT: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
    public val COMPONENT_CALL_OUTSIDE_COMPONENT: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
    public val SLOT_CALL_IN_LOOP: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
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

public class KineticaFirExtensionRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::KineticaFirCheckersExtension
    }
}

internal class KineticaFirCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {

    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirExpressionChecker<FirFunctionCall>> =
            setOf(KineticaCallChecker)
    }

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val simpleFunctionCheckers: Set<FirDeclarationChecker<FirNamedFunction>> =
            setOf(KineticaComponentDeclarationChecker)
    }
}

private object KineticaCallChecker : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return
        val callableId = callee.callableId ?: return
        val session = context.session
        val name = callableId.callableName.asString()
        // Same-package members of other classes (e.g. KineticaRuntime.frameValue) are not
        // slot DSL: only ComponentScope members and ComponentScope extensions qualify.
        val isKineticaDsl = callableId.packageName == KINETICA_PACKAGE &&
            (callableId.classId == COMPONENT_SCOPE_CLASS_ID ||
                (callableId.classId == null &&
                    callee.resolvedReceiverTypeRef?.coneType?.classId == COMPONENT_SCOPE_CLASS_ID))
        val isSlotDsl = isKineticaDsl && name in SLOT_DSL_NAMES
        val isRegionConstruct = isKineticaDsl && name in REGION_CONSTRUCT_NAMES
        val isLoopSafeRegion = isKineticaDsl && name in LOOP_SAFE_REGION_NAMES
        val isComponentCall = callee.hasAnnotation(UI_COMPONENT_CLASS_ID, session)
        if (!isSlotDsl && !isRegionConstruct && !isLoopSafeRegion && !isComponentCall) {
            return
        }

        val containment = context.classifyContainment(session)

        // Rule A: slot DSL and region constructs require a @UiComponent function body.
        if ((isSlotDsl || isRegionConstruct) && containment != KineticaContainment.COMPONENT_BODY) {
            reporter.reportOn(expression.source, KineticaFirErrors.SLOT_CALL_OUTSIDE_COMPONENT, name, context)
            return
        }

        // Rule B: component calls also allow @UiComponent-typed lambda literals.
        if (isComponentCall && containment == KineticaContainment.OUTSIDE) {
            reporter.reportOn(expression.source, KineticaFirErrors.COMPONENT_CALL_OUTSIDE_COMPONENT, name, context)
            return
        }

        // Rule D: static ordinals cannot tell loop iterations apart.
        if (!isLoopSafeRegion && context.isDirectlyInsideLoop(session)) {
            reporter.reportOn(expression.source, KineticaFirErrors.SLOT_CALL_IN_LOOP, name, context)
            return
        }

        // Rule C: region content arguments must be literal lambdas.
        if (isRegionConstruct) {
            val mapping = (expression.argumentList as? FirResolvedArgumentList)?.mapping ?: return
            for ((argument, parameter) in mapping) {
                if (parameter.name.asString() !in REGION_CONTENT_PARAMETER_NAMES) continue
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
                if (isRegionContentArgument(elements, index)) return false
                // Plain lambda: still executes per iteration if a loop encloses it.
            }
            else -> {}
        }
    }
    return false
}

/** Whether the anonymous function at [index] is passed as an argument to a region construct. */
private fun isRegionContentArgument(elements: List<FirElement>, index: Int): Boolean {
    for (outer in (index - 1) downTo maxOf(0, index - 3)) {
        val element = elements[outer]
        if (element is FirFunctionCall) {
            val callee = element.calleeReference.toResolvedCallableSymbol() ?: return false
            val callableId = callee.callableId ?: return false
            return callableId.packageName == KINETICA_PACKAGE &&
                callableId.callableName.asString() in REGION_CONSTRUCT_NAMES + LOOP_SAFE_REGION_NAMES
        }
    }
    return false
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
    for (outer in (index - 1) downTo maxOf(0, index - 3)) {
        val call = elements[outer] as? FirFunctionCall ?: continue
        val mapping = (call.argumentList as? FirResolvedArgumentList)?.mapping ?: return false
        for ((argument, parameter) in mapping) {
            val unwrapped = argument.unwrapArgument()
            if (unwrapped is FirAnonymousFunctionExpression && unwrapped.anonymousFunction === lambda) {
                return parameter.returnTypeRef.coneTypeOrNull.hasUiComponentAnnotation() ||
                    parameter.returnTypeRef.annotations.any { annotation ->
                        annotation.annotationTypeRef.coneTypeOrNull?.classId == UI_COMPONENT_CLASS_ID
                    }
            }
        }
        return false
    }
    return false
}

private fun ConeKotlinType?.hasUiComponentAnnotation(): Boolean {
    val annotations = this?.customAnnotations ?: return false
    return annotations.any { annotation ->
        annotation.annotationTypeRef.coneTypeOrNull?.classId == UI_COMPONENT_CLASS_ID
    }
}
