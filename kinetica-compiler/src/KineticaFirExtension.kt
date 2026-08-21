package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.contracts.description.EventOccurrencesRange
import org.jetbrains.kotlin.contracts.description.KtCallsEffectDeclaration
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory2
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory3
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.error2
import org.jetbrains.kotlin.diagnostics.error3
import org.jetbrains.kotlin.diagnostics.warning1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.directOverriddenFunctionsSafe
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.expressions.unwrapArgument
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirLocalPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.customAnnotations
import org.jetbrains.kotlin.fir.types.receiverType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.ConstantValueKind
import java.util.concurrent.ConcurrentHashMap

/**
 * Frontend rules for the plugin-only slot model. Registered unconditionally on every
 * backend (FIR is platform-neutral): rules A-D and F-H are SOUNDNESS rules — their
 * absence converts a compile error into a runtime crash or silent state aliasing — so
 * no `checks` value can remove them (S1). Rule E is the one STYLE rule; the `checks`
 * option governs only its severity (error/warning/off):
 *
 * A. Slot/event DSL calls are legal only lexically inside `@UiComponent` functions.
 * B. `@UiComponent` calls are legal inside `@UiComponent` bodies or inside lambda
 *    literals whose expected function type carries `@UiComponent` (entry-point content).
 * C. Region-construct content/fallback arguments must be lambda literals — the compiler
 *    numbers their bodies into fresh frame tables, which is impossible for references.
 *    The same holds for every `@UiComponent`-typed content parameter (entry points such
 *    as `render`, user content wrappers): IR wraps only lambda LITERALS into fresh frame
 *    regions, so content hoisted into a local variable is never wrapped and fails at
 *    render. Forwarding an already-wrapped value stays legal: reads of value parameters
 *    and non-local properties carry content that was wrapped at its own literal site.
 * D. No slot/event/component call directly inside a loop body: static ordinals cannot
 *    tell iterations apart. `keyed {}` / `each(key = …)` are the sanctioned loop forms.
 * E. `@UiComponent` functions must have a `ComponentScope` receiver (style: a
 *    scope-free component is never framed or staged, so the declaration alone is inert
 *    at runtime — and anything unsound INSIDE it stays OUTSIDE for rules A/B/D/F, see
 *    `classifyContainment`).
 * F. Ordinal-consuming calls cannot sit in arbitrary multi-run lambdas. Kinetica DSL
 *    content and Kotlin's single-run scope functions share the enclosing frame safely.
 *    A lambda that is not a resolved call argument (stored in a val/var or property)
 *    has no run-count contract at all and counts as an unknown-run host.
 * G. Explicit non-null `key` arguments the IR pass declines to number are compile
 *    errors: `suspendSubtree(key = …)` keeps the whole call on the legacy path
 *    (guaranteed MissingKineticaPluginException at first render), and key-addressed
 *    persistent state skips the SlotId retarget (the slot silently never persists).
 *    Absent and literal-null keys stay allowed — both provably keep the compiler path.
 * H. `@UiComponent` calls must be invoked on a simple receiver — `this`, a parameter,
 *    or a plain local variable — mirroring IR's `IrGetValue` staging requirement: the
 *    child frame ordinal is staged by re-reading the receiver variable, which is
 *    impossible for call results, safe calls, and smart-cast values (probe-verified:
 *    IR leaves all three unstaged). An unstaged call throws at first render or, in
 *    argument position of another staged call, consumes the enclosing component's
 *    ordinal and renders into the wrong frame (F10).
 *
 * Further soundness rules mirror hard IR limitations, all of them errors in every mode:
 * `@UiComponent` functions declared inside a function body are rejected
 * (LOCAL_COMPONENT_FUNCTION — the framing pass reaches only declarations whose path from
 * the file crosses no function body, so this rule is its exact complement); content
 * literals bound to a receiver-less `@UiComponent` function type are rejected
 * (COMPONENT_CONTENT_WITHOUT_SCOPE_RECEIVER — IR wraps content by its ComponentScope
 * receiver); ordinal consumers in a component's default argument values are rejected
 * (ORDINAL_CALL_IN_DEFAULT_ARGUMENT — the default runs before the frame prologue);
 * callable references to `@UiComponent` functions are rejected
 * (COMPONENT_CALLABLE_REFERENCE — staging happens at a call site a reference does not
 * have); and `@UiComponent` must be present on all or none of an override chain
 * (COMPONENT_OVERRIDE_ANNOTATION_MISMATCH — framing reads the override's annotation while
 * staging reads the resolved base's).
 */
// Every classification table (ordinal-consumer names, region content, loop-safe regions,
// optional handlers) lives in KineticaFramePolicy, shared with the IR frame pass, so the
// checker's predictions can never drift from what IR actually numbers; use sites
// reference the policy object directly instead of phase-local aliases.
internal val UI_COMPONENT_CLASS_ID: ClassId =
    ClassId(KineticaFramePolicy.KINETICA_PACKAGE, Name.identifier("UiComponent"))
internal val COMPONENT_SCOPE_CLASS_ID: ClassId = KineticaFramePolicy.COMPONENT_SCOPE_CLASS_ID

public object KineticaFirErrors : KtDiagnosticsContainer() {
    public val SLOT_CALL_OUTSIDE_COMPONENT: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
    public val COMPONENT_CALL_OUTSIDE_COMPONENT: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
    public val SLOT_CALL_IN_LOOP: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
    public val CALL_IN_MULTI_RUN_LAMBDA: KtDiagnosticFactory3<String, String, String> by
        error3<PsiElement, String, String, String>()

    /**
     * A call passing a `@UiComponent` content lambda literal from inside a multi-run (or
     * stored, unknown-run) lambda whose numbering root is a component declaration. The IR
     * walker never descends into such lambdas, so the content is never frame-wrapped: its
     * component calls and slots crash at first render while no other rule fires — the
     * wrapper consumes no ordinal (F1) and the rule-F walk stops at the content lambda
     * boundary. What decides is the numbering ROOT, not the innermost containment: nested
     * content lambdas and local functions inside a component are walked by the same gated
     * walker. Outside a component declaration the ungated entry-point pass wraps content
     * bottom-up everywhere, so the identical shape is sound there.
     */
    public val COMPONENT_CONTENT_IN_MULTI_RUN_LAMBDA: KtDiagnosticFactory2<String, String> by
        error2<PsiElement, String, String>()
    public val REGION_CONTENT_NOT_LITERAL: KtDiagnosticFactory1<String> by error1<PsiElement, String>()
    public val UNSUPPORTED_EXPLICIT_KEY: KtDiagnosticFactory2<String, String> by
        error2<PsiElement, String, String>()
    public val COMPONENT_WITHOUT_SCOPE_RECEIVER: KtDiagnosticFactory1<String> by error1<PsiElement, String>()

    /**
     * `checks=warning` twin of [COMPONENT_WITHOUT_SCOPE_RECEIVER]: KtDiagnosticFactory
     * severities are fixed per factory, so the style-only downgrade needs a parallel
     * warning factory rather than report-time severity remapping.
     */
    public val COMPONENT_WITHOUT_SCOPE_RECEIVER_WARNING: KtDiagnosticFactory1<String> by
        warning1<PsiElement, String>()
    public val COMPONENT_RECEIVER_NOT_SIMPLE: KtDiagnosticFactory1<String> by error1<PsiElement, String>()

    /**
     * A `@UiComponent` function declared inside a function body — a local function, a
     * lambda, a property accessor, or an object literal in one of those. The IR frame
     * pass reaches only declarations whose path from the file crosses no function body,
     * so such a component is never framed or staged: the enclosing walker numbers its
     * body's slots into the ENCLOSING component's region — every call site silently
     * shares one set of cells — and each call leaks a staged child ordinal. Soundness
     * rule, active in every checks mode.
     */
    public val LOCAL_COMPONENT_FUNCTION: KtDiagnosticFactory1<String> by error1<PsiElement, String>()

    /**
     * A callable reference (`ComponentScope::Badge`, `::Badge`) whose target is
     * `@UiComponent`. IR stages a component's child frame ordinal only at a call site
     * (`visitCall`), never from an `IrFunctionReference`, and the reference binds to a
     * plain function type, so the eventual invoke is not a component call either: the
     * callee's frame prologue finds an empty ordinal stack and throws at first render.
     * Soundness rule, active in every checks mode.
     */
    public val COMPONENT_CALLABLE_REFERENCE: KtDiagnosticFactory1<String> by error1<PsiElement, String>()

    /**
     * An ordinal-consuming call in a value parameter's DEFAULT VALUE inside a
     * `@UiComponent` function. The default is evaluated by the `$default` stub BEFORE the
     * component's `beginComponentFrame` prologue runs, so there is no frame to number it
     * into — and neither IR pass walks `IrValueParameter.defaultValue` in the first place.
     * The ordinal keeps its `-1` sentinel and the call throws at first render. Soundness
     * rule, active in every checks mode.
     */
    public val ORDINAL_CALL_IN_DEFAULT_ARGUMENT: KtDiagnosticFactory1<String> by error1<PsiElement, String>()

    /**
     * A `@UiComponent` annotation that is present on one end of an override chain and
     * missing on the other. IR frames a component by the annotation on its OWN
     * declaration, while a call site stages the child ordinal by the annotation on the
     * declaration it RESOLVES to — the base, for a virtual call. An annotated override of
     * an unannotated base is framed but never staged (MissingKineticaPluginException at
     * first render); the mirror image stages an ordinal nothing consumes. Soundness rule,
     * active in every checks mode.
     */
    public val COMPONENT_OVERRIDE_ANNOTATION_MISMATCH: KtDiagnosticFactory2<String, String> by
        error2<PsiElement, String, String>()

    /**
     * A content lambda literal bound to a `@UiComponent` parameter whose function type
     * has no `ComponentScope` receiver (e.g. `@UiComponent (ComponentScope) -> Unit`).
     * IR wraps content lambdas by their scope receiver, so this shape is declined and
     * every ordinal consumer inside it crashes at first render. Soundness rule mirroring
     * IR's `buildFreshRegionTableOf` receiver requirement.
     */
    public val COMPONENT_CONTENT_WITHOUT_SCOPE_RECEIVER: KtDiagnosticFactory1<String> by
        error1<PsiElement, String>()

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
            "Kinetica call ''{0}'' cannot use a compiler-assigned ordinal inside the multi-run ''{1}'' lambda. {2}",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.COMPONENT_CONTENT_IN_MULTI_RUN_LAMBDA,
            "''{0}'' receives @UiComponent content inside the multi-run ''{1}'' lambda. " +
                "Within a component body the compiler never descends into ''{1}'', so the " +
                "content lambda is never frame-wrapped: any component call or slot inside " +
                "it fails at first render. " +
                "Hoist the call out of the multi-run lambda, or use " +
                "each(items, key = ...) or keyed(...) for repeated rendering.",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.REGION_CONTENT_NOT_LITERAL,
            "The ''{0}'' content argument must be a lambda literal so the compiler " +
                "can assign its slot ordinals: content hoisted into a local variable or passed " +
                "as a function reference is never frame-wrapped and fails at render. " +
                "Pass the lambda literal directly.",
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.UNSUPPORTED_EXPLICIT_KEY,
            "''{0}'' with an explicit ''key'' argument cannot use compiler-assigned ordinals: " +
                "the call is left on the legacy path and {1}",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.COMPONENT_WITHOUT_SCOPE_RECEIVER,
            COMPONENT_WITHOUT_SCOPE_RECEIVER_TEMPLATE,
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.COMPONENT_WITHOUT_SCOPE_RECEIVER_WARNING,
            COMPONENT_WITHOUT_SCOPE_RECEIVER_TEMPLATE,
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.COMPONENT_RECEIVER_NOT_SIMPLE,
            "@UiComponent call ''{0}'' must be invoked on a simple receiver — ''this'', a parameter, " +
                "or a plain local val — so the compiler can stage the child frame ordinal. " +
                "Call-result, safe-call, and smart-cast receivers are left unstaged and fail at " +
                "first render. Bind the receiver to a plain local val of the scope type first.",
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.LOCAL_COMPONENT_FUNCTION,
            "@UiComponent function ''{0}'' is declared locally. The compiler frames only " +
                "declarations it can reach without crossing a function body (file, class, " +
                "object, enum entry, property initializer, init block); a component declared " +
                "inside a function, lambda or property accessor is never framed, so its calls " +
                "alias the enclosing component''s state or fail at first render. " +
                "Move the declaration out of the enclosing function body.",
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.COMPONENT_CALLABLE_REFERENCE,
            "@UiComponent function ''{0}'' cannot be used as a callable reference: the compiler " +
                "stages a component''s child frame ordinal at the call site, and a reference has no " +
                "call site to stage. Call it directly, or pass a @UiComponent-typed lambda literal " +
                "('{' {0}() '}') instead.",
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.ORDINAL_CALL_IN_DEFAULT_ARGUMENT,
            "Kinetica call ''{0}'' cannot use a compiler-assigned ordinal in a default argument " +
                "value: the default is evaluated before the component''s frame is entered, so the " +
                "compiler never numbers it. Move the call into the component body (for example " +
                "make the parameter nullable and compute the fallback there).",
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.COMPONENT_OVERRIDE_ANNOTATION_MISMATCH,
            "@UiComponent must agree across an override chain: ''{0}'' {1}. " +
                "The compiler frames a component by the annotation on its own declaration and " +
                "stages the child ordinal by the annotation on the declaration the call resolves " +
                "to, so a mismatch throws MissingKineticaPluginException at first render or " +
                "leaves a staged ordinal unconsumed. Annotate every declaration in the chain, or none.",
            CommonRenderers.STRING,
            CommonRenderers.STRING,
        )
        map.put(
            KineticaFirErrors.COMPONENT_CONTENT_WITHOUT_SCOPE_RECEIVER,
            "The ''{0}'' parameter''s @UiComponent function type has no " +
                "io.heapy.kinetica.ComponentScope receiver, so the compiler cannot frame-wrap " +
                "content passed to it: ordinal consumers inside the lambda fail at first render. " +
                "Declare the parameter as @UiComponent ComponentScope.() -> Unit.",
            CommonRenderers.STRING,
        )
    }
}

private const val COMPONENT_WITHOUT_SCOPE_RECEIVER_TEMPLATE: String =
    "@UiComponent function ''{0}'' must be an extension of io.heapy.kinetica.ComponentScope."

/**
 * Parsed value of the `checks` plugin option. It governs STYLE diagnostics only (rule
 * E): [ERROR] reports them as errors, [WARNING] as warnings, [OFF] not at all. The
 * soundness rules are errors in every mode — removing them would convert compile
 * errors into runtime crashes or silent state aliasing (S1).
 */
internal enum class KineticaChecksMode {
    ERROR,
    WARNING,
    OFF,
    ;

    companion object {
        fun from(value: String): KineticaChecksMode = when (value) {
            "off" -> OFF
            "warning" -> WARNING
            else -> ERROR
        }
    }
}

public class KineticaFirExtensionRegistrar internal constructor(
    private val singleRunOracle: SingleRunOracle,
    private val checksMode: KineticaChecksMode,
) : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        val factory: (FirSession) -> FirAdditionalCheckersExtension = { session ->
            KineticaFirCheckersExtension(session, singleRunOracle, checksMode)
        }
        +factory
    }
}

internal class KineticaFirCheckersExtension(
    session: FirSession,
    singleRunOracle: SingleRunOracle,
    checksMode: KineticaChecksMode,
) : FirAdditionalCheckersExtension(session) {

    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirExpressionChecker<FirFunctionCall>> =
            setOf(KineticaCallChecker(singleRunOracle))

        // Soundness: a @UiComponent reference has no call site for IR to stage, so it can
        // never be sound in any checks mode.
        override val callableReferenceAccessCheckers:
            Set<FirExpressionChecker<FirCallableReferenceAccess>> =
            setOf(KineticaComponentReferenceChecker())
    }

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val simpleFunctionCheckers: Set<FirDeclarationChecker<FirNamedFunction>> = buildSet {
            // Soundness: local @UiComponent functions can never be framed by IR, so the
            // declaration rule is active in every checks mode.
            add(KineticaLocalComponentDeclarationChecker())
            // Soundness: framing and staging read @UiComponent off different declarations
            // of an override chain, so the annotation must be present on all or none.
            add(KineticaComponentOverrideChecker())
            when (checksMode) {
                KineticaChecksMode.ERROR ->
                    add(KineticaComponentDeclarationChecker(KineticaFirErrors.COMPONENT_WITHOUT_SCOPE_RECEIVER))
                KineticaChecksMode.WARNING ->
                    add(KineticaComponentDeclarationChecker(KineticaFirErrors.COMPONENT_WITHOUT_SCOPE_RECEIVER_WARNING))
                KineticaChecksMode.OFF -> {}
            }
        }
    }
}

private class KineticaCallChecker(
    private val singleRunOracle: SingleRunOracle,
) : FirExpressionChecker<FirFunctionCall>(MppCheckerKind.Common) {
    /**
     * Calls that actually produced a CALL_IN_MULTI_RUN_LAMBDA report, so consumers
     * nested in their arguments can suppress the duplicate report for the same unsafe
     * host. Suppression must key on reports that HAPPENED, never on "the enclosing call
     * is an ordinal consumer": an outer consumer can exit check() early with a different
     * diagnostic (rule A/B/D/H) and would otherwise swallow the only multi-run report
     * (F14). Checkers visit enclosing calls before nested ones, so the outer verdict is
     * always recorded first — a traversal-order detail this suppression deliberately
     * leans on: if that order ever changes, suppression degrades to DUPLICATE reports
     * for one unsafe host, never to a lost report. The set is concurrent because FIR
     * checkers may run on multiple threads; identity-keyed nodes cannot collide across
     * files, and only erroring calls are retained (bounded by the error count).
     */
    private val multiRunReportedCalls: MutableSet<FirFunctionCall> = ConcurrentHashMap.newKeySet()

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val session = context.session
        val facts = expression.classifyKineticaCall(session) ?: return
        // Verdicts are recorded for EVERY call carrying a lambda literal — before the
        // Kinetica-relevance early return, and independent of the rule-F walk — so oracle
        // coverage never depends on which calls happen to classify as ordinal consumers.
        expression.recordContractVerdicts(facts.callee, singleRunOracle)
        val name = facts.name
        val isSlotDsl = facts.isSlotDsl
        val isRegionConstruct = facts.isRegionConstruct
        val isComponentCall = facts.isComponentCall
        // S4: the argument-mapping walk with per-parameter cone-type and annotation
        // resolution is the expensive half of this guard. Lazy keeps it behind the four
        // cheap facts: Kinetica constructs pass the guard without paying it (probing at
        // most once, in the later content-argument rules), and only the none-of-the-four
        // call probes here as the guard's final conjunct.
        val contentArguments by lazy(LazyThreadSafetyMode.NONE) {
            expression.componentContentArguments(session)
        }
        if (!isSlotDsl && !isRegionConstruct && !facts.isLoopSafeRegion &&
            !isComponentCall && contentArguments.isEmpty
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

        // Rule H (F10): IR stages the child frame ordinal only for receivers it can
        // re-read as an IrGetValue. Anything else is left unstaged — the callee prologue
        // throws at first render or steals an enclosing call's staged ordinal.
        if (isComponentCall) {
            val receiver = expression.extensionReceiver
            if (receiver != null && !receiver.isSimpleStagingReceiver()) {
                reporter.reportOn(
                    receiver.source ?: expression.source,
                    KineticaFirErrors.COMPONENT_RECEIVER_NOT_SIMPLE,
                    name,
                    context,
                )
                return
            }
        }

        val consumesCompilerOrdinal = facts.consumesCompilerOrdinal()

        // A default argument value runs in the $default stub, before the component's
        // beginComponentFrame prologue — no frame exists to number the call into, and the
        // IR passes never walk parameter defaults either. Rules A/B already cover defaults
        // of non-component functions (containment there is OUTSIDE), so this fires exactly
        // where they pass: inside a component declaration.
        if (consumesCompilerOrdinal && context.isInsideValueParameterDefault(session)) {
            reporter.reportOn(
                expression.source,
                KineticaFirErrors.ORDINAL_CALL_IN_DEFAULT_ARGUMENT,
                name,
                context,
            )
            return
        }

        // Static ordinals cannot tell loop iterations apart. Optional host-event calls
        // whose handler is absent or a literal null never register an event at runtime.
        if (!facts.isLoopSafeRegion && consumesCompilerOrdinal && context.isDirectlyInsideLoop(session)) {
            reporter.reportOn(expression.source, KineticaFirErrors.SLOT_CALL_IN_LOOP, name, context)
            return
        }

        // Static ordinals are sound only in lambdas known to run at most once per frame.
        // FIR owns this soundness error: IR has no counterpart diagnostic — it declines
        // to number the lambda and the call fails fast at render. Non-consumers need the
        // same walk when they pass @UiComponent content literals from a component body:
        // there the IR walker's descent gates decide whether the content is ever wrapped.
        val wrapsContentInGatedRegion = contentArguments.hasWrappableLiteral &&
            context.isInsideComponentNumberingRoot(session)
        val unsafeHost = if (consumesCompilerOrdinal || wrapsContentInGatedRegion) {
            context.multiRunLambdaHost(expression, session, multiRunReportedCalls)
        } else {
            null
        }
        if (consumesCompilerOrdinal && unsafeHost != null) {
            multiRunReportedCalls += expression
            reporter.reportOn(
                expression.source,
                KineticaFirErrors.CALL_IN_MULTI_RUN_LAMBDA,
                name,
                unsafeHost,
                if (facts.isLoopSafeRegion) {
                    MULTI_RUN_KEYED_CONSTRUCT_ADVICE
                } else {
                    MULTI_RUN_REPEATED_RENDERING_ADVICE
                },
                context,
            )
            return
        }

        // A content-wrapper (or entry-point) call passing a @UiComponent lambda literal
        // from inside a multi-run or stored lambda whose numbering root is a component:
        // the IR walker never descends there, so the literal is never frame-wrapped and
        // its component calls and slots crash at first render — while no other rule fires
        // (post-F1 the wrapper consumes no ordinal, and the rule-F walk from consumers
        // INSIDE the content stops at the content lambda boundary). Declarations rooted
        // outside a component take the ungated entry-point wrap instead, which wraps
        // nested content bottom-up and is therefore sound.
        if (wrapsContentInGatedRegion && unsafeHost != null) {
            reporter.reportOn(
                expression.source,
                KineticaFirErrors.COMPONENT_CONTENT_IN_MULTI_RUN_LAMBDA,
                name,
                unsafeHost,
                context,
            )
            return
        }

        // Rule G (F9): explicit keys the IR pass declines to number must fail here, not
        // surface as a runtime crash (suspendSubtree) or silent persistence loss (state).
        if (isRegionConstruct && name == "suspendSubtree") {
            expression.explicitKeyArgument()?.let { argument ->
                reporter.reportOn(
                    argument.source ?: expression.source,
                    KineticaFirErrors.UNSUPPORTED_EXPLICIT_KEY,
                    name,
                    SUSPEND_SUBTREE_KEY_CONSEQUENCE,
                    context,
                )
            }
        }
        if (isSlotDsl && name == "state" && expression.isPersistentStateWithoutSlotId()) {
            expression.explicitKeyArgument()?.let { argument ->
                reporter.reportOn(
                    argument.source ?: expression.source,
                    KineticaFirErrors.UNSUPPORTED_EXPLICIT_KEY,
                    name,
                    PERSISTENT_STATE_KEY_CONSEQUENCE,
                    context,
                )
            }
        }

        if (isRegionConstruct) {
            val mapping = (expression.argumentList as? FirResolvedArgumentList)?.mapping ?: return
            for ((argument, parameter) in mapping) {
                if (parameter.name.asString() !in KineticaFramePolicy.REGION_CONTENT_PARAMETERS.getValue(name)) continue
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

        // Rule C, extended to @UiComponent-typed content parameters (F8): IR wraps only
        // lambda literals into fresh frame regions (wrapAnnotatedContentArgumentsOf), so
        // content it provably cannot wrap must fail here, not at first render.
        for ((argument, parameter) in contentArguments.unwrappable) {
            if (isRegionConstruct && parameter.name.asString() in KineticaFramePolicy.REGION_CONTENT_PARAMETERS[name].orEmpty()) {
                continue
            }
            reporter.reportOn(
                argument.source ?: expression.source,
                KineticaFirErrors.REGION_CONTENT_NOT_LITERAL,
                parameter.name.asString(),
                context,
            )
        }

        // Content literals bound to a receiver-less @UiComponent function type: IR's
        // buildFreshRegionTableOf declines to wrap them (it wraps by the ComponentScope
        // receiver), so ordinal consumers inside would crash at first render.
        for ((argument, parameter) in contentArguments.nonScopeReceiverLiterals) {
            reporter.reportOn(
                argument.source ?: expression.source,
                KineticaFirErrors.COMPONENT_CONTENT_WITHOUT_SCOPE_RECEIVER,
                parameter.name.asString(),
                context,
            )
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
 * The facts `check()` and [consumesCompilerOrdinal] need about a resolved call — the
 * display name plus the slot-call / region / loop-safe-region / component-call verdicts —
 * derived once per call so the rule dispatch and the ordinal-consumer predicate can never
 * disagree about what a call is (F11). A call that is none of these is at most an entry
 * point (it merely receives `@UiComponent`-typed content), which `check()` covers via
 * [componentContentArguments] and which never consumes an ordinal itself.
 */
private class KineticaCallClassification(
    private val call: FirFunctionCall,
    val callee: FirCallableSymbol<*>,
    val name: String,
    val isSlotDsl: Boolean,
    val isRegionConstruct: Boolean,
    val isLoopSafeRegion: Boolean,
    val isComponentCall: Boolean,
) {
    /**
     * Whether this call needs an ordinal injected by the IR frame pass — true only for
     * the calls IR actually numbers: slot DSL calls, region constructs, host event
     * registrations, and staged @UiComponent component calls. Merely receiving a
     * @UiComponent-typed lambda argument (an entry point such as `render`, or a user
     * content-wrapper helper) does NOT consume an ordinal: IR only wraps that content
     * into a fresh frame table and numbers nothing on the call itself. Optional
     * host-event handlers are exempt only when the argument is absent or a literal
     * `null` — then the runtime provably never registers the event, so the statically
     * filled ordinal stays unused. The parameter's declared nullability is deliberately
     * NOT consulted: a nullable-typed value can still be non-null at runtime,
     * registering an ordinal IR never assigned (crash) or one static ordinal shared
     * across iterations (event aliasing).
     */
    fun consumesCompilerOrdinal(): Boolean {
        if (isComponentCall) return true
        if (isRegionConstruct) return true
        if (!isSlotDsl) return false

        val optionalEventParameters = KineticaFramePolicy.OPTIONAL_EVENT_PARAMETERS[name] ?: return true
        val mapping = (call.argumentList as? FirResolvedArgumentList)?.mapping ?: return false
        return mapping.any { (argument, parameter) ->
            parameter.name.asString() in optionalEventParameters && !argument.isLiteralNull()
        }
    }
}

private fun FirFunctionCall.classifyKineticaCall(session: FirSession): KineticaCallClassification? {
    val callee = calleeReference.toResolvedCallableSymbol() ?: return null
    val name = callee.callableId?.callableName?.asString() ?: callee.name.asString()
    // Same-package members of other classes (e.g. KineticaRuntime.frameValue) are not
    // slot DSL: only ComponentScope members and ComponentScope extensions qualify.
    val isKineticaDsl = callee.isKineticaDsl()
    return KineticaCallClassification(
        call = this,
        callee = callee,
        name = name,
        isSlotDsl = isKineticaDsl && name in KineticaFramePolicy.SLOT_DSL_NAMES,
        isRegionConstruct = isKineticaDsl && name in KineticaFramePolicy.REGION_CONSTRUCT_NAMES,
        isLoopSafeRegion = isKineticaDsl && name in KineticaFramePolicy.LOOP_SAFE_REGION_NAMES,
        // The component verdict must never sit behind a callableId bail (F11): a
        // @UiComponent symbol without a callableId is still a component call, and rules
        // D and F must see it as an ordinal consumer, exactly as the rule dispatch does.
        isComponentCall = callee.hasAnnotation(UI_COMPONENT_CLASS_ID, session),
    )
}

/**
 * Classifies the call's `@UiComponent`-typed content arguments. IR wraps only lambda
 * LITERALS into fresh frame regions (its `wrapAnnotatedContentArgumentsOf` requires an
 * `IrFunctionExpression`) and never descends into non-argument lambdas, so content
 * hoisted into a local variable is never wrapped — every ordinal consumer inside it
 * crashes at first render (F8). Sound shapes land in neither bucket:
 *  - a literal `null` — the runtime never invokes absent content;
 *  - a read of a value parameter or a non-local property — a forwarded value that was
 *    frame-wrapped at its own literal site (`KineticaRuntime.render(content)` forwarding
 *    to the two-arg overload, `HeadlessTestRoot`'s stored content). Only the local hoist
 *    is provably unwrapped, and it is exactly the F8 escape.
 */
private fun FirFunctionCall.componentContentArguments(session: FirSession): ComponentContentArguments {
    val mapping = (argumentList as? FirResolvedArgumentList)?.mapping
        ?: return ComponentContentArguments.NONE
    var hasWrappableLiteral = false
    var violations: MutableList<Pair<FirExpression, FirValueParameter>>? = null
    var nonScopeReceiverLiterals: MutableList<Pair<FirExpression, FirValueParameter>>? = null
    for ((argument, parameter) in mapping) {
        if (!parameter.hasUiComponentFunctionType()) continue
        if (argument.unwrapArgument() is FirAnonymousFunctionExpression) {
            // IR wraps content lambdas by their ComponentScope receiver; a literal bound
            // to a receiver-less @UiComponent function type is declined instead — its own
            // soundness bucket, deliberately NOT a wrappable literal.
            if (parameter.hasComponentScopeReceiverFunctionType(session)) {
                hasWrappableLiteral = true
            } else {
                (
                    nonScopeReceiverLiterals
                        ?: mutableListOf<Pair<FirExpression, FirValueParameter>>()
                            .also { nonScopeReceiverLiterals = it }
                    ).add(argument to parameter)
            }
            continue
        }
        if (argument.isWrappableContentArgument()) continue
        (violations ?: mutableListOf<Pair<FirExpression, FirValueParameter>>().also { violations = it })
            .add(argument to parameter)
    }
    if (!hasWrappableLiteral && violations == null && nonScopeReceiverLiterals == null) {
        return ComponentContentArguments.NONE
    }
    return ComponentContentArguments(
        hasWrappableLiteral,
        violations ?: emptyList(),
        nonScopeReceiverLiterals ?: emptyList(),
    )
}

/**
 * The call's `@UiComponent`-typed content arguments, split by what the IR pass can do
 * with them: [hasWrappableLiteral] — at least one lambda literal IR wraps into a fresh
 * frame region at this call site (but only where its walker reaches the call, see
 * [KineticaFirErrors.COMPONENT_CONTENT_IN_MULTI_RUN_LAMBDA]); [unwrappable] — arguments
 * IR provably cannot wrap (rule C / F8). Sound non-literal shapes (absent, literal
 * `null`, forwarded parameter or non-local property reads) appear in neither.
 */
private class ComponentContentArguments(
    val hasWrappableLiteral: Boolean,
    val unwrappable: List<Pair<FirExpression, FirValueParameter>>,
    /**
     * Lambda literals bound to `@UiComponent` parameters whose function type lacks the
     * `ComponentScope` receiver — IR's `buildFreshRegionTableOf` declines them, so they
     * report [KineticaFirErrors.COMPONENT_CONTENT_WITHOUT_SCOPE_RECEIVER] instead of
     * counting as wrappable.
     */
    val nonScopeReceiverLiterals: List<Pair<FirExpression, FirValueParameter>>,
) {
    val isEmpty: Boolean
        get() = !hasWrappableLiteral && unwrappable.isEmpty() && nonScopeReceiverLiterals.isEmpty()

    companion object {
        val NONE = ComponentContentArguments(
            hasWrappableLiteral = false,
            unwrappable = emptyList(),
            nonScopeReceiverLiterals = emptyList(),
        )
    }
}

/**
 * Whether this extension-receiver expression lowers to the bare `IrGetValue` that IR's
 * `stageComponentCall` requires: `this` (implicit or explicit), a value parameter, or a
 * plain (non-delegated) local variable. Safe-call subjects and smart-cast values do NOT
 * qualify — probe-verified: fir2ir wraps the variable read (checked-subject block,
 * IMPLICIT_CAST), so IR leaves those calls unstaged. Delegated local reads lower to a
 * `getValue` call, not a variable read. Non-local property reads lower to getter calls.
 */
private fun FirExpression.isSimpleStagingReceiver(): Boolean = when (this) {
    is FirThisReceiverExpression -> true
    is FirPropertyAccessExpression -> when (val symbol = calleeReference.toResolvedCallableSymbol()) {
        is FirValueParameterSymbol -> true
        is FirLocalPropertySymbol -> !symbol.hasDelegate
        else -> false
    }
    else -> false
}

private fun FirExpression.isWrappableContentArgument(): Boolean {
    val unwrapped = unwrapArgument()
    if (unwrapped is FirAnonymousFunctionExpression) return true
    if (isLiteralNull()) return true
    if (unwrapped !is FirPropertyAccessExpression) return false
    return when (unwrapped.calleeReference.toResolvedCallableSymbol()) {
        is FirValueParameterSymbol -> true
        // Order matters: the local-property symbol is a FirPropertySymbol subclass.
        is FirLocalPropertySymbol -> false
        is FirPropertySymbol -> true
        else -> false
    }
}

private fun FirValueParameter.hasUiComponentFunctionType(): Boolean =
    returnTypeRef.coneTypeOrNull.hasUiComponentAnnotation() ||
        returnTypeRef.annotations.any { annotation ->
            annotation.annotationTypeRef.coneTypeOrNull?.classId == UI_COMPONENT_CLASS_ID
        }

/**
 * Whether this parameter's function type is the shape IR can frame-wrap: an extension
 * function type whose receiver is `io.heapy.kinetica.ComponentScope` (plain or suspend —
 * `receiverType` resolves both). A lambda literal takes its shape from the declared
 * parameter type, so this predicts exactly IR's `buildFreshRegionTableOf` receiver gate.
 */
private fun FirValueParameter.hasComponentScopeReceiverFunctionType(session: FirSession): Boolean =
    returnTypeRef.coneTypeOrNull?.receiverType(session)?.classId == COMPONENT_SCOPE_CLASS_ID

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

// Rendered as CALL_IN_MULTI_RUN_LAMBDA's third parameter, so the fix advice can depend
// on the FLAGGED callee (S2): telling the author of a keyed construct to "use keyed(...)"
// advises the construct they already wrote. Entry points (`render { … }`) stopped being
// ordinal consumers with F1, so no advice is ever rendered for them.
private const val MULTI_RUN_REPEATED_RENDERING_ADVICE: String =
    "Use each(items, key = ...) or keyed(...) for repeated rendering."

private const val MULTI_RUN_KEYED_CONSTRUCT_ADVICE: String =
    "The call already keys its own content; hoist it out of the multi-run lambda, " +
        "or key the outer repetition with keyed(...)."

// Rendered as UNSUPPORTED_EXPLICIT_KEY's second parameter, so advice can differ per
// callee without a parallel factory (the template itself carries the shared lead-in).
private const val SUSPEND_SUBTREE_KEY_CONSEQUENCE: String =
    "throws MissingKineticaPluginException at first render. Remove the key argument " +
        "(call-site identity is compiler-assigned), or wrap the call in keyed(...) for " +
        "explicit identity."

private const val PERSISTENT_STATE_KEY_CONSEQUENCE: String =
    "the slot is never registered for persistence. Address it with state(slotId = ...) " +
        "or omit the key so the compiler derives a durable SlotId."

/**
 * The argument bound to a parameter literally named `key`, when present and not a literal
 * `null` — the same sound proxy rule F uses for optional handlers: absence and a null
 * literal provably keep the call on the compiler path, so only real keys report.
 */
private fun FirFunctionCall.explicitKeyArgument(): FirExpression? {
    val mapping = (argumentList as? FirResolvedArgumentList)?.mapping ?: return null
    for ((argument, parameter) in mapping) {
        if (parameter.name.asString() != "key") continue
        return argument.takeUnless { it.isLiteralNull() }
    }
    return null
}

/**
 * Mirror of IR's `maybeRetargetPersistentState` gate: the SlotId-addressed overload is
 * already durable (no retarget attempted), and only a LITERAL `persistent = true` makes
 * IR attempt the retarget the explicit key would defeat.
 */
private fun FirFunctionCall.isPersistentStateWithoutSlotId(): Boolean {
    val callee = calleeReference.toResolvedCallableSymbol() as? FirFunctionSymbol<*> ?: return false
    if (callee.valueParameterSymbols.any { it.name.asString() == "slotId" }) return false
    return literalBooleanArgument("persistent") == true
}

private fun FirFunctionCall.literalBooleanArgument(name: String): Boolean? {
    val mapping = (argumentList as? FirResolvedArgumentList)?.mapping ?: return null
    for ((argument, parameter) in mapping) {
        if (parameter.name.asString() != name) continue
        val literal = argument.unwrapArgument() as? FirLiteralExpression ?: return null
        if (literal.kind != ConstantValueKind.Boolean) return null
        return literal.value as? Boolean
    }
    return null
}

/**
 * Rejects `@UiComponent` functions the IR framing pass provably cannot reach, and
 * exactly those. IR collects every declaration whose path from the file crosses no
 * function body — file, class, object, enum-entry body, property initializer, `init { }`
 * block, and anonymous-object literals inside those initializers. The complement, which
 * this rule reports, is "some function is on the containment path": a local function, a
 * lambda, a property accessor, a constructor body, or an object literal inside any of
 * them. Unframed, a component aliases the enclosing component's slots and leaks staged
 * ordinals, so the declaration itself is the violation.
 */
private class KineticaLocalComponentDeclarationChecker :
    FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!declaration.hasAnnotation(UI_COMPONENT_CLASS_ID, context.session)) return
        if (context.containingDeclarations.none { it is FirFunctionSymbol<*> }) return
        reporter.reportOn(
            declaration.source,
            KineticaFirErrors.LOCAL_COMPONENT_FUNCTION,
            declaration.name.asString(),
            context,
        )
    }
}

/**
 * Rejects callable references to `@UiComponent` functions. Staging happens at the call
 * site (`stageComponentCall` runs from `visitCall`), and a reference has no call site: the
 * function value it produces has a plain function type, so invoking it later is not a
 * component call and the callee's frame prologue finds nothing staged.
 */
private class KineticaComponentReferenceChecker :
    FirExpressionChecker<FirCallableReferenceAccess>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirCallableReferenceAccess) {
        val callee = expression.calleeReference.toResolvedCallableSymbol() ?: return
        if (!callee.hasAnnotation(UI_COMPONENT_CLASS_ID, context.session)) return
        reporter.reportOn(
            expression.source,
            KineticaFirErrors.COMPONENT_CALLABLE_REFERENCE,
            callee.callableId?.callableName?.asString() ?: callee.name.asString(),
            context,
        )
    }
}

/**
 * Requires `@UiComponent` to agree across every override chain. The framing pass reads the
 * annotation off the declaration it transforms; the staging site reads it off the
 * declaration the call resolved to, which for a virtual call is the BASE. Only agreement
 * makes the two read the same verdict. Reported at the override, so a base coming from a
 * dependency is covered too.
 */
private class KineticaComponentOverrideChecker :
    FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!declaration.status.isOverride) return
        val annotated = declaration.hasAnnotation(UI_COMPONENT_CLASS_ID, context.session)
        for (base in declaration.symbol.directOverriddenFunctionsSafe(context)) {
            if (base.hasAnnotation(UI_COMPONENT_CLASS_ID, context.session) == annotated) continue
            val owner = base.callableId.classId?.asSingleFqName()?.asString() ?: "the base declaration"
            reporter.reportOn(
                declaration.source,
                KineticaFirErrors.COMPONENT_OVERRIDE_ANNOTATION_MISMATCH,
                declaration.name.asString(),
                if (annotated) {
                    "is annotated @UiComponent while the declaration it overrides in '$owner' is not"
                } else {
                    "overrides the @UiComponent declaration in '$owner' without being annotated itself"
                },
                context,
            )
            return
        }
    }
}

private class KineticaComponentDeclarationChecker(
    private val factory: KtDiagnosticFactory1<String>,
) : FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        if (!declaration.hasAnnotation(UI_COMPONENT_CLASS_ID, context.session)) return
        val receiverClassId = declaration.receiverParameter?.typeRef?.coneTypeOrNull?.classId
        if (receiverClassId != COMPONENT_SCOPE_CLASS_ID) {
            reporter.reportOn(
                declaration.source,
                factory,
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
                // COMPONENT_BODY mirrors exactly what the IR pass frames: @UiComponent
                // WITH a ComponentScope receiver (IR's isUiComponentWithScopeReceiver).
                // A scope-free @UiComponent is never framed, so ordinal consumers inside
                // it (reached through a scope parameter) are as unsound as in any plain
                // function; classifying them OUTSIDE keeps rules A/B guarding the crash
                // even when the style rule E is downgraded to a warning or off.
                return if (element.hasAnnotation(UI_COMPONENT_CLASS_ID, session) &&
                    element.receiverParameter?.typeRef?.coneTypeOrNull?.classId == COMPONENT_SCOPE_CLASS_ID
                ) {
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
 * Whether the checked call sits in a value parameter's default value, with no frame
 * boundary of its own in between. A `@UiComponent`-typed content lambda or a region
 * content lambda in the default IS frame-wrapped at its literal site (the entry pass
 * reaches parameter defaults), so the walk stops there and the shape stays legal.
 */
private fun CheckerContext.isInsideValueParameterDefault(session: FirSession): Boolean {
    val elements = containingElements
    for (index in elements.indices.reversed()) {
        when (val element = elements[index]) {
            is FirValueParameter -> return true
            is FirAnonymousFunction -> {
                if (isComponentTypedLambda(elements, index, element, session)) return false
                if (isRegionContentArgument(elements, index, element)) return false
                // A plain lambda in a default is still evaluated with the default.
            }
            is FirNamedFunction -> return false
            else -> {}
        }
    }
    return false
}

/**
 * Whether the IR pass numbers this position with the GATED walker
 * (`KineticaFrameTransformer.Walker`), which refuses to descend into multi-run and stored
 * lambdas: true exactly when the OUTERMOST enclosing named function is a `@UiComponent`
 * with a `ComponentScope` receiver. Inside such a declaration every position — however
 * deeply nested in further content lambdas or local functions — is reached, or missed, by
 * that walker. Everywhere else the ungated entry-point pass runs first and wraps nested
 * content bottom-up, so the same shape is sound. `LOCAL_COMPONENT_FUNCTION` guarantees a
 * component can never be an INNER named function, so testing the outermost one is exact.
 *
 * Deliberately distinct from [classifyContainment], which stops at the innermost boundary:
 * a call inside a component's content lambda is COMPONENT_TYPED_LAMBDA, and one inside a
 * local function is OUTSIDE, yet both are numbered by the gated walker.
 */
private fun CheckerContext.isInsideComponentNumberingRoot(session: FirSession): Boolean {
    for (element in containingElements) {
        if (element !is FirNamedFunction) continue
        return element.hasAnnotation(UI_COMPONENT_CLASS_ID, session) &&
            element.receiverParameter?.typeRef?.coneTypeOrNull?.classId == COMPONENT_SCOPE_CLASS_ID
    }
    return false
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
        val parameters = KineticaFramePolicy.REGION_CONTENT_PARAMETERS[name] ?: return false
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
                    // Vararg lambda elements hide behind FirVarargArgumentsExpression;
                    // unwrap them so they resolve to their callee like plain arguments
                    // instead of escaping as host-less stored lambdas (F6).
                    val unwrapped = argument.unwrapArgument()
                    val candidates = if (unwrapped is FirVarargArgumentsExpression) {
                        unwrapped.arguments.map { it.unwrapArgument() }
                    } else {
                        listOf(unwrapped)
                    }
                    for (candidate in candidates) {
                        val expression = candidate as? FirAnonymousFunctionExpression ?: continue
                        // Symbol identity is the sole matcher: measured across the whole
                        // suite, every hosted lambda resolved by symbol identity and the
                        // former source-offset fallback hit 0 times — it was also the
                        // only branch able to match the WRONG lambda (S3).
                        if (expression.anonymousFunction.symbol == lambda.symbol) {
                            val callee = element.calleeReference.toResolvedCallableSymbol() ?: return null
                            return LambdaHost(callee, parameter)
                        }
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
 * That is the call the IR walker encounters and declines to enter. When an enclosing
 * ordinal consumer inside that same lambda already REPORTED the multi-run violation
 * ([reportedMultiRunCalls]), the nested call stays silent — one report per unsafe host.
 * An enclosing consumer that reported some OTHER diagnostic (or nothing) never
 * suppresses: predicting "the outer would report" from consumer-hood alone dropped the
 * only multi-run report whenever the outer call exited check() early (F14).
 */
private fun CheckerContext.multiRunLambdaHost(
    expression: FirFunctionCall,
    session: FirSession,
    reportedMultiRunCalls: Set<FirFunctionCall>,
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
                val host = findLambdaHost(elements, index, element)
                if (host == null) {
                    // A lambda that is not a resolved call argument (stored in a local
                    // val, a property, …) has no run-count contract at all: it can run
                    // any number of times, long after this render pass, and the IR
                    // walker never numbers inside it. It is an unknown-run host, not a
                    // transparent one (F6).
                    unsafeLambdaIndex = index
                    unsafeHostName = storedLambdaHostName(elements, index)
                } else if (!host.isKnownSingleRun()) {
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
    val nestedUnderReportedConsumer = ((unsafeLambdaIndex + 1)..elements.lastIndex).any { index ->
        val enclosingCall = elements[index] as? FirFunctionCall ?: return@any false
        enclosingCall !== expression && enclosingCall in reportedMultiRunCalls
    }
    return hostName.takeUnless { nestedUnderReportedConsumer }
}

/**
 * Display name for a lambda that is not a resolved call argument: the variable or
 * property whose initializer stores it when one encloses it, or a generic label.
 */
private fun storedLambdaHostName(elements: List<FirElement>, index: Int): String {
    for (outer in (index - 1) downTo 0) {
        when (val element = elements[outer]) {
            is FirProperty -> return element.name.asString()
            is FirNamedFunction, is FirAnonymousFunction -> return "stored"
            else -> {}
        }
    }
    return "stored"
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
