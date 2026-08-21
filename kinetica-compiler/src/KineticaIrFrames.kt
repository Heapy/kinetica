@file:Suppress("DEPRECATION")

package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * The frame/ordinal emission pass — the mechanism that makes the compiler plugin
 * load-bearing for slot identity. For every receiver-style `@UiComponent` function it:
 *
 * 1. numbers each slot- and event-consuming DSL call site with dense per-region ordinals
 *    and fills the call's trailing `ordinal` parameter;
 * 2. retargets region constructs (`keyed`, `each`, boundaries, …) to their frame-native
 *    `*Region` overloads, assigning child ordinals from the enclosing region;
 * 3. gives every fresh-region lambda (region content, `@UiComponent`-typed content
 *    parameters) its own numbering region and file-level [io.heapy.kinetica.FrameTable]
 *    static; most are wrapped in `beginRegionFrame(<table>) / try { … } finally {
 *    endRegionFrame() }`, while `each`/`lazyEach` row content passes its table to the
 *    keyed row frame directly;
 * 4. stages a child ordinal before each `@UiComponent` call (`scope.ordinal(n)`; LIFO in
 *    the runtime, so calls in argument position of other component calls stay correct)
 *    and gives the component body a `beginComponentFrame(<table>)` prologue;
 * 5. rewrites keyless persistent `state` calls to the `state(slotId = …)` overload with a
 *    compiler-computed [io.heapy.kinetica.SlotId] whose ordinal follows the PSI pipeline's
 *    recipe (index among the function's state calls), keeping persisted data compatible.
 *
 * Plain lambdas (host content, `let`/branches) share the enclosing region's counters —
 * they run at most once per render in the same frame. Fresh counters start only at region
 * boundaries. Anything the pass cannot prove (non-trivial staging receiver, explicit
 * suspendSubtree key, unwrappable content lambda shapes) is left on the legacy
 * string-keyed path and reported — every decline whose legacy path crashes at render,
 * aliases frames, or silently loses persistence is a located compile ERROR (F9, F10);
 * purely informational skips stay LOGGING.
 */
internal class KineticaFrameSymbols private constructor(
    val frameTableConstructor: IrConstructorSymbol,
    val frameTableType: IrType,
    val slotIdConstructor: IrConstructorSymbol,
    val ordinalFn: IrSimpleFunctionSymbol,
    val beginComponentFrame: IrSimpleFunctionSymbol,
    val endComponentFrame: IrSimpleFunctionSymbol,
    val beginRegionFrame: IrSimpleFunctionSymbol,
    val endRegionFrame: IrSimpleFunctionSymbol,
    val intArrayOf: IrSimpleFunctionSymbol,
    val regionTargets: Map<String, IrSimpleFunctionSymbol>,
    val statePersistentOverload: IrSimpleFunctionSymbol,
    val hostEventBlock: IrSimpleFunctionSymbol,
) {
    companion object {
        private val PKG = FqName("io.heapy.kinetica")
        private val SCOPE_ID = ClassId(PKG, Name.identifier("ComponentScope"))

        fun resolve(pluginContext: IrPluginContext): KineticaFrameSymbols? {
            val frameTable = pluginContext.referenceClass(ClassId(PKG, Name.identifier("FrameTable")))
                ?: return null
            val slotId = pluginContext.referenceClass(ClassId(PKG, Name.identifier("SlotId")))
                ?: return null

            fun member(name: String): IrSimpleFunctionSymbol? =
                pluginContext.referenceFunctions(CallableId(SCOPE_ID, Name.identifier(name))).firstOrNull()

            fun topLevel(name: String): IrSimpleFunctionSymbol? =
                pluginContext.referenceFunctions(CallableId(PKG, Name.identifier(name))).firstOrNull()

            val ordinalFn = member("ordinal") ?: return null
            val beginComponent = member("beginComponentFrame") ?: return null
            val endComponent = member("endComponentFrame") ?: return null
            val beginRegion = member("beginRegionFrame") ?: return null
            val endRegion = member("endRegionFrame") ?: return null
            val intArrayOf = pluginContext
                .referenceFunctions(CallableId(FqName("kotlin"), Name.identifier("intArrayOf")))
                .firstOrNull() ?: return null
            val statePersistent = pluginContext
                .referenceFunctions(CallableId(PKG, Name.identifier("state")))
                .firstOrNull { symbol ->
                    symbol.owner.parameters.any {
                        it.kind == IrParameterKind.Regular && it.name.asString() == "slotId"
                    }
                } ?: return null

            val regionTargets = mutableMapOf<String, IrSimpleFunctionSymbol>()
            regionTargets["keyed"] = member("keyedRegion") ?: return null
            regionTargets["suspendKeyed"] = member("suspendKeyedRegion") ?: return null
            regionTargets["each"] = topLevel("eachRegion") ?: return null
            regionTargets["lazyEach"] = topLevel("lazyEachRegion") ?: return null
            regionTargets["errorBoundary"] = topLevel("errorBoundaryRegion") ?: return null
            regionTargets["loadingBoundary"] = topLevel("loadingBoundaryRegion") ?: return null
            regionTargets["suspendSubtree"] = topLevel("suspendSubtreeRegion") ?: return null
            regionTargets["exitGroup"] = topLevel("exitGroupRegion") ?: return null

            return KineticaFrameSymbols(
                frameTableConstructor = frameTable.constructors.first(),
                frameTableType = frameTable.owner.defaultType,
                slotIdConstructor = slotId.constructors.first(),
                ordinalFn = ordinalFn,
                beginComponentFrame = beginComponent,
                endComponentFrame = endComponent,
                beginRegionFrame = beginRegion,
                endRegionFrame = endRegion,
                intArrayOf = intArrayOf,
                regionTargets = regionTargets,
                statePersistentOverload = statePersistent,
                hostEventBlock = topLevel("hostEventBlock") ?: return null,
            )
        }
    }
}

// Every classification table (slot DSL, event DSL, region content, single-run lambdas)
// lives in KineticaFramePolicy, shared with the FIR checker, so the two phases cannot
// drift; use sites reference the policy object directly instead of phase-local aliases.
private val UI_COMPONENT_FQ = FqName("io.heapy.kinetica.UiComponent")
private val EVENT_SCOPE_FQ = FqName("io.heapy.kinetica.EventScope")

internal class KineticaFrameTransformer(
    private val file: IrFile,
    private val pluginContext: IrPluginContext,
    private val symbols: KineticaFrameSymbols,
    private val moduleId: String,
    private val report: (String, CompilerMessageSeverity, CompilerMessageSourceLocation?) -> Unit,
    private val singleRunOracle: SingleRunOracle,
) {
    var componentsFramed: Int = 0
        private set
    private var tableFieldCount = 0

    /**
     * Every content lambda this transformer already wrapped into a fresh region.
     * Wrapping must be idempotent: the entry pass wraps a nested wrapper's content
     * bottom-up at its own call, and the ENCLOSING content's fresh-region walk then
     * revisits the same call — without this guard the literal was wrapped and staged
     * twice, leaking one staged ordinal per invocation and tripping the runtime's
     * frame-pairing check (F10) whenever the leak crossed a frame boundary.
     */
    private val wrappedContentLambdas = HashSet<IrFunctionExpression>()

    private fun log(message: String) {
        report(message, CompilerMessageSeverity.LOGGING, null)
    }

    /**
     * A decline-to-transform is a compile ERROR, never a LOGGING line (F9, F10): the
     * surviving legacy call either throws MissingKineticaPluginException at first render,
     * renders into another call's frame (unstaged component call), or silently loses
     * behavior (persistence addressing). An error without a location is not actionable,
     * so the declined element's file offset travels along.
     */
    private fun reportDecline(message: String, element: IrElement) {
        report(message, CompilerMessageSeverity.ERROR, sourceLocation(element))
    }

    private fun sourceLocation(element: IrElement): CompilerMessageSourceLocation? {
        if (element.startOffset < 0) return CompilerMessageLocation.create(file.fileEntry.name)
        val range = file.fileEntry.getSourceRangeInfo(element.startOffset, element.endOffset)
        return CompilerMessageLocation.create(
            range.filePath,
            range.startLineNumber + 1,
            range.startColumnNumber + 1,
            null,
        )
    }

    /** Per-function shared state: the PSI-compatible persistent-slot ordinal counter. */
    private class FunctionShared(val functionFqName: String) {
        var stateCallIndex = 0
    }

    private class RegionNumbering {
        var slots = 0
        var events = 0
        var children = 0
        val transientSlots = mutableListOf<Int>()
    }

    fun transform(function: IrSimpleFunction) {
        val receiver = function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
            ?: return
        if (receiver.type.classOrNull?.owner?.kotlinFqName != KineticaFramePolicy.COMPONENT_SCOPE_FQ) return
        val body = function.body as? IrBlockBody ?: return
        val fqName = function.fqNameWhenAvailable?.asString() ?: return

        val shared = FunctionShared(fqName)
        val numbering = RegionNumbering()
        body.transformChildrenVoid(Walker(function, shared, numbering))
        val table = addTableField(shared.functionFqName, numbering)
        wrapBodyInFrame(function, receiver, table, component = true)
        function.patchDeclarationParents(function.parent)
        componentsFramed++
        log(
            "$fqName: framed (slots=${numbering.slots}, events=${numbering.events}, " +
                "children=${numbering.children}).",
        )
    }

    private inner class Walker(
        private val enclosingFunction: IrSimpleFunction,
        private val shared: FunctionShared,
        private val numbering: RegionNumbering,
    ) : IrElementTransformerVoid() {
        private val variableNames = ArrayDeque<String>()

        override fun visitVariable(declaration: IrVariable): IrStatement {
            // A lambda literal stored in a variable is not a call argument, so no
            // single-run verdict can exist for it: it can run any number of times, long
            // after this render pass. Gate descent exactly like
            // transformArgumentsSelectively gates call arguments — the body stays
            // unnumbered so its ordinal consumers fail fast at runtime instead of
            // aliasing the enclosing region's ordinals (F6); FIR rule F rejects them
            // at compile time.
            if (declaration.initializer is IrFunctionExpression) return declaration
            variableNames.addLast(declaration.name.asString())
            try {
                return super.visitVariable(declaration)
            } finally {
                variableNames.removeLast()
            }
        }

        override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty): IrStatement {
            variableNames.addLast(declaration.name.asString())
            try {
                return super.visitLocalDelegatedProperty(declaration)
            } finally {
                variableNames.removeLast()
            }
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
            // A @UiComponent function declared inside this body can never be framed —
            // the framing pass reaches only declarations whose path from the file
            // crosses no function body — so its own body must not be numbered into the
            // ENCLOSING region: every call site would share one set of slots (silent
            // state aliasing) while each staged child ordinal leaks.
            // Leave the body untouched so its consumers fail fast at render; FIR
            // rejects the declaration itself (LOCAL_COMPONENT_FUNCTION).
            if (declaration.isUiComponent()) return declaration
            return super.visitSimpleFunction(declaration)
        }

        override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
            // Constructor calls (plain calls dispatch to visitCall): a constructor
            // STORES its lambda arguments, so no single-run verdict can exist — lambda
            // literals are never descended into and never numbered with the enclosing
            // region's counters (the same gate stored lambdas get, F6); FIR rule F
            // rejects ordinal consumers inside them. A @UiComponent-typed content
            // literal instead becomes its own fresh region below, mirroring FIR's
            // wrapped-boundary classification of constructor content arguments.
            val callee = expression.symbol.owner
            for (parameter in callee.parameters) {
                val argument = expression.arguments[parameter.indexInParameters] ?: continue
                if (argument is IrFunctionExpression) continue
                expression.arguments[parameter.indexInParameters] = argument.transform(this, null)
            }
            wrapAnnotatedContentArgumentsOf(expression, shared)
            return expression
        }

        override fun visitCall(expression: IrCall): IrExpression {
            val callee = expression.symbol.owner
            val calleeFqName = callee.kotlinFqName
            val parent = calleeFqName.parentOrNull()
            val name = calleeFqName.shortName().asString()
            val inKinetica = KineticaFramePolicy.isKineticaDsl(
                containerFqName = parent,
                hasComponentScopeExtensionReceiver = callee.hasComponentScopeExtensionReceiver(),
                name = name,
            )

            // INVARIANT, and it must hold on EVERY branch below: a @UiComponent-typed
            // lambda literal argument is ALWAYS wrapped into its own fresh region and
            // NEVER descended into with this region's counters. Wrapping used to sit on
            // the fall-through branches only, so the same literal was wrapped after a
            // component call, numbered inline by a region's non-content loop, and
            // silently dropped by the slot/event/hostEvent branches — three behaviors
            // for one shape, the third of them a crash at first render. A region
            // construct's own content parameters are excluded: transformRegion owns
            // those (each/lazyEach row content merges into the keyed row frame instead
            // of getting its own region wrapper).
            wrapAnnotatedContentArguments(
                expression,
                skipParameters = if (inKinetica) {
                    KineticaFramePolicy.REGION_CONTENT_PARAMETERS[name].orEmpty()
                } else {
                    emptySet()
                },
            )

            if (inKinetica && name in KineticaFramePolicy.REGION_CONTENT_PARAMETERS) {
                return transformRegion(expression, name)
            }

            if (inKinetica && name == "hostEvent") {
                tryFuseHostEventBlock(expression)?.let { return it }
            }

            transformArgumentsSelectively(expression, inKinetica, parent)

            if (inKinetica && name in KineticaFramePolicy.SLOT_DSL_TRANSIENT) {
                return transformSlotCall(expression, name)
            }
            if (inKinetica && name in KineticaFramePolicy.EVENT_DSL_NAMES) {
                fillOrdinal(expression, numbering.events++)
                return expression
            }
            if (callee.isUiComponent()) {
                return stageComponentCall(expression)
            }
            return expression
        }

        private fun tryFuseHostEventBlock(expression: IrCall): IrExpression? {
            val eventCall = expression.argumentByName("onEvent") as? IrCall ?: return null
            val eventLambda = eventCall.inlineUnitEventLambda() ?: return null

            val callee = expression.symbol.owner
            // The fusion retargets to hostEventBlock(ordinal, block): any OTHER regular
            // parameter of the source call has nowhere to go and would be dropped
            // silently. Decline instead — the normal event-DSL path below numbers the
            // call and keeps every argument (no-silent-bail-outs).
            val fusableParameters = callee.parameters.none { parameter ->
                parameter.kind == IrParameterKind.Regular &&
                    parameter.name.asString() !in FUSED_HOST_EVENT_PARAMETERS
            }
            if (!fusableParameters) return null
            for (parameter in callee.parameters) {
                val parameterName = parameter.name.asString()
                if (parameter.kind == IrParameterKind.Regular &&
                    (parameterName == "ordinal" || parameterName == "onEvent")
                ) {
                    continue
                }
                val argument = expression.arguments[parameter.indexInParameters] ?: continue
                if (argument is IrFunctionExpression && parameter.hasUiComponentContentType()) continue
                expression.arguments[parameter.indexInParameters] = argument.transform(this, null)
            }

            val builder = DeclarationIrBuilder(pluginContext, enclosingFunction.symbol)
            return retargetCall(
                expression,
                symbols.hostEventBlock,
                extraArguments = mapOf(
                    "ordinal" to builder.irInt(numbering.events++),
                    "block" to eventLambda,
                ),
            )
        }

        private fun transformSlotCall(expression: IrCall, name: String): IrExpression {
            val ordinal = numbering.slots++
            var transient = KineticaFramePolicy.SLOT_DSL_TRANSIENT.getValue(name)
            if (name == "state") {
                if (expression.constBooleanArgument("transient") == true) {
                    transient = true
                }
                val stateIndex = shared.stateCallIndex++
                val retargeted = maybeRetargetPersistentState(expression, ordinal, stateIndex)
                if (transient) numbering.transientSlots += ordinal
                return retargeted
            }
            if (transient) numbering.transientSlots += ordinal
            fillOrdinal(expression, ordinal)
            return expression
        }

        /**
         * Keyless persistent `state` becomes `state(slotId = SlotId(moduleId, fq, index,
         * varName), …)` so persisted data keeps its durable address without the PSI
         * pipeline. Non-persistent or already-addressed calls just get their ordinal.
         */
        private fun maybeRetargetPersistentState(
            expression: IrCall,
            ordinal: Int,
            stateIndex: Int,
        ): IrExpression {
            fillOrdinal(expression, ordinal)
            val callee = expression.symbol.owner
            val hasSlotId = callee.parameters.any {
                it.kind == IrParameterKind.Regular && it.name.asString() == "slotId"
            }
            if (hasSlotId) return expression
            if (expression.constBooleanArgument("persistent") != true) {
                // A non-literal `persistent` argument cannot be retargeted: the compiler
                // cannot prove persistence was requested, so the slot silently stays
                // non-persistent. Never silent — surface it (FIR rule G mirrors the
                // literal-true gate and is equally unable to see through the value).
                val persistentArgument = expression.argumentByName("persistent")
                if (persistentArgument != null && persistentArgument !is IrConst) {
                    report(
                        "${shared.functionFqName}: state(persistent = <non-literal>) cannot be " +
                            "retargeted to a compiler SlotId — the slot is treated as " +
                            "non-persistent. Pass a literal `persistent = true` (or use the " +
                            "state(slotId = ...) overload) to persist it.",
                        CompilerMessageSeverity.WARNING,
                        sourceLocation(expression),
                    )
                }
                return expression
            }
            // A literal null key is provably absent — same sound proxy as FIR rule G,
            // so the two phases agree on exactly which calls stay on the compiler path.
            val keyArgument = expression.argumentByName("key")
            if (keyArgument != null && !keyArgument.isNullConst()) {
                reportDecline(
                    "${shared.functionFqName}: persistent state with an explicit key cannot be " +
                        "retargeted to a compiler SlotId; left on the legacy path — the slot is " +
                        "never registered for persistence. Use state(slotId = ...) or omit the key.",
                    expression,
                )
                return expression
            }
            val disambiguator = variableNames.lastOrNull() ?: "state$stateIndex"
            val builder = DeclarationIrBuilder(pluginContext, enclosingFunction.symbol)
            val slotIdExpression = builder.irCallConstructor(symbols.slotIdConstructor, emptyList()).apply {
                symbols.slotIdConstructor.owner.parameters
                    .filter { it.kind == IrParameterKind.Regular }
                    .forEach { parameter ->
                        arguments[parameter.indexInParameters] = when (parameter.name.asString()) {
                            "moduleId" -> builder.irString(moduleId)
                            "functionFqName" -> builder.irString(shared.functionFqName)
                            "declarationOrdinal" -> builder.irInt(stateIndex)
                            "disambiguator" -> builder.irString(disambiguator)
                            else -> return expression
                        }
                    }
            }
            return retargetCall(
                expression,
                symbols.statePersistentOverload,
                extraArguments = mapOf("slotId" to slotIdExpression),
                dropParameters = setOf("key"),
            )
        }

        private fun transformRegion(expression: IrCall, name: String): IrExpression {
            val contentParams = KineticaFramePolicy.REGION_CONTENT_PARAMETERS.getValue(name)
            val target = symbols.regionTargets.getValue(name)

            if (name == "suspendSubtree") {
                val keyArgument = expression.argumentByName("key")
                if (keyArgument != null && !keyArgument.isNullConst()) {
                    reportDecline(
                        "${shared.functionFqName}: suspendSubtree with an explicit key cannot be " +
                            "assigned compiler ordinals; left on the legacy path — the untransformed " +
                            "call throws MissingKineticaPluginException at first render. Remove the " +
                            "key argument or wrap the call in keyed(...).",
                        expression,
                    )
                    expression.transformChildrenVoid(this)
                    return expression
                }
            }

            // Non-content arguments share this region's counters — except lambda literals
            // bound to multi-run parameters (the each/lazyEach key selector): those run
            // per item outside this region's numbering and must fail FIR, not get unsound
            // ordinals from the enclosing counters (F5).
            val callee = expression.symbol.owner
            for (parameter in callee.parameters) {
                val argument = expression.arguments[parameter.indexInParameters] ?: continue
                if (parameter.kind == IrParameterKind.Regular && parameter.name.asString() in contentParams) continue
                if (argument is IrFunctionExpression &&
                    KineticaFramePolicy.isMultiRunDslParameter(name, parameter.name.asString())
                ) {
                    continue
                }
                // Already wrapped into its own region by visitCall's invariant; descending
                // here as well would re-fill its ordinals from THIS region's counters.
                if (argument is IrFunctionExpression && parameter.hasUiComponentContentType()) continue
                expression.arguments[parameter.indexInParameters] = argument.transform(this, null)
            }

            val extraArguments = mutableMapOf<String, IrExpression>()
            val builder = DeclarationIrBuilder(pluginContext, enclosingFunction.symbol)
            if (name in KineticaFramePolicy.REGION_STATE_SLOTS) {
                val stateOrdinal = numbering.slots++
                extraArguments["stateOrdinal"] = builder.irInt(stateOrdinal)
                if (name == "suspendSubtree") {
                    // The subtree state owns a pending coroutine and a nested scope; it must
                    // expire (and dispose) when a render stops touching the call site.
                    numbering.transientSlots += stateOrdinal
                } else {
                    extraArguments["contentOrdinal"] = builder.irInt(numbering.children++)
                }
                extraArguments["fallbackOrdinal"] = builder.irInt(numbering.children++)
            } else {
                extraArguments["ordinal"] = builder.irInt(numbering.children++)
            }

            // Content lambdas become fresh numbering regions with their own tables.
            for (parameter in callee.parameters) {
                if (parameter.kind != IrParameterKind.Regular) continue
                val parameterName = parameter.name.asString()
                if (parameterName !in contentParams) continue
                val argument = expression.arguments[parameter.indexInParameters] ?: continue
                val lambda = argument as? IrFunctionExpression ?: run {
                    reportDecline(
                        "${shared.functionFqName}: $name ${parameter.name} is not a lambda literal; " +
                            "left on the legacy path — the untransformed call throws " +
                            "MissingKineticaPluginException at first render. Pass the content " +
                            "lambda literally.",
                        expression,
                    )
                    expression.transformChildrenVoid(this)
                    return expression
                }
                if (name in KineticaFramePolicy.MERGED_EACH_REGION_NAMES && parameterName == "content") {
                    val table = buildFreshRegionTable(lambda) ?: run {
                        expression.transformChildrenVoid(this)
                        return expression
                    }
                    extraArguments["rowTable"] = builder.irGetField(null, table)
                } else {
                    wrapFreshRegion(lambda)
                }
            }

            val dropParameters = if (name == "suspendSubtree") setOf("key") else emptySet()
            return retargetCall(expression, target, extraArguments, dropParameters)
        }

        private fun stageComponentCall(expression: IrCall): IrExpression {
            val callee = expression.symbol.owner
            val receiverParameter = callee.parameters
                .firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
                ?: return expression
            if (receiverParameter.type.classOrNull?.owner?.kotlinFqName != KineticaFramePolicy.COMPONENT_SCOPE_FQ) {
                return expression
            }
            val receiverArgument = expression.arguments[receiverParameter.indexInParameters]
            if (receiverArgument !is IrGetValue) {
                // Paired with FIR rule H (F10): safe-call subjects and smart-cast values
                // also land here — fir2ir wraps the variable read, defeating IrGetValue.
                reportDecline(
                    "${shared.functionFqName}: component call ${callee.name} has a non-trivial " +
                        "receiver expression, so its child frame ordinal cannot be staged; " +
                        "left unstaged — the callee prologue throws " +
                        "MissingKineticaPluginException at first render or consumes an " +
                        "enclosing call's staged ordinal and renders into the wrong frame. " +
                        "Bind the receiver to a plain local val of the scope type first.",
                    expression,
                )
                return expression
            }
            val ordinal = numbering.children++
            val builder = DeclarationIrBuilder(pluginContext, enclosingFunction.symbol)
            val staging = builder.irCall(symbols.ordinalFn).apply {
                arguments[0] = builder.irGet(receiverArgument.symbol.owner)
                arguments[1] = builder.irInt(ordinal)
            }
            return IrBlockImpl(
                expression.startOffset,
                expression.endOffset,
                expression.type,
                null,
                listOf(staging, expression),
            )
        }

        private fun wrapAnnotatedContentArguments(expression: IrCall, skipParameters: Set<String> = emptySet()) {
            wrapAnnotatedContentArgumentsOf(expression, shared, skipParameters)
        }

        /**
         * Static ordinals are only sound for code that runs at most once per render of its
         * frame. Lambdas passed to arbitrary functions (List(n) { … }, repeat, map) can run
         * any number of times, so the walker descends only into lambdas whose single-run
         * contract is known: Kinetica DSL content (classified per parameter — deferred
         * handler lambdas and key selectors are multi-run, see
         * [KineticaFramePolicy.MULTI_RUN_DSL_PARAMETERS]), the shared name-list scope
         * functions, and parameters whose `callsInPlace(…, EXACTLY_ONCE / AT_MOST_ONCE)`
         * contract the FIR checker recorded in the per-compilation [SingleRunOracle].
         * Everything else keeps the legacy positional path (its cursors advance per
         * invocation).
         */
        private fun transformArgumentsSelectively(expression: IrCall, inKinetica: Boolean, parent: FqName?) {
            val callee = expression.symbol.owner
            val name = callee.name.asString()
            val singleRunScopeFunction = KineticaFramePolicy.isSingleRunScopeFunction(
                containerFqName = parent,
                name = name,
            )
            val oracleKey = if (inKinetica || singleRunScopeFunction) null else callee.callableIdOrNull()
            val regularParameterCount = callee.parameters.count { it.kind == IrParameterKind.Regular }
            for (parameter in callee.parameters) {
                val argument = expression.arguments[parameter.indexInParameters] ?: continue
                if (argument is IrFunctionExpression) {
                    // @UiComponent-typed content is owned by wrapAnnotatedContentArguments:
                    // it becomes its own fresh region even when a callsInPlace contract
                    // proves the parameter single-run. Descending here as well numbered
                    // the same body twice — once into this region and once into the fresh
                    // one — staging every component call inside it twice (a leaked ordinal
                    // per invocation and an F10 frame-pairing throw across frames).
                    if (parameter.hasUiComponentContentType()) continue
                    val parameterName = parameter.name.asString()
                    val singleRun = when {
                        inKinetica -> !KineticaFramePolicy.isMultiRunDslParameter(name, parameterName)
                        singleRunScopeFunction -> true
                        else -> singleRunOracle.isSingleRun(oracleKey, regularParameterCount, parameterName)
                    }
                    if (!singleRun) continue
                }
                expression.arguments[parameter.indexInParameters] = argument.transform(this, null)
            }
        }

        private fun wrapFreshRegion(lambdaExpression: IrFunctionExpression) {
            wrapFreshRegionOf(lambdaExpression, shared)
        }

        private fun buildFreshRegionTable(lambdaExpression: IrFunctionExpression): IrField? =
            buildFreshRegionTableOf(lambdaExpression, shared)
    }

    /**
     * Entry-point pass for functions that are not components themselves: lambda literals
     * passed to `@UiComponent`-annotated function-type parameters (`runtime.render { … }`,
     * content slots) become fresh regions, so component calls inside them are staged and
     * their slots land in a region frame.
     */
    fun transformEntryPoints(function: IrSimpleFunction) {
        val body = function.body ?: return
        val fqName = function.fqNameWhenAvailable?.asString() ?: return
        transformEntryPointsIn(body, fqName, descendIntoNestedClasses = true)
        function.patchDeclarationParents(function.parent)
    }

    /**
     * Entry-point pass for the bodies the framing collection reaches that are not
     * [IrSimpleFunction]s: constructor bodies, property/field initializers, `init { }`
     * blocks and enum-entry initializers all hold `runtime.render { … }` content exactly
     * like a function body. [descendIntoNestedClasses] is false for initializer
     * expressions, whose nested classes the collection reaches on its own — descending
     * here as well would number one body into two regions.
     */
    fun transformEntryPointBody(
        body: IrElement,
        fqName: String,
        owner: IrDeclaration,
        descendIntoNestedClasses: Boolean,
    ) {
        transformEntryPointsIn(body, fqName, descendIntoNestedClasses)
        owner.patchDeclarationParents(owner.parent)
    }

    private fun transformEntryPointsIn(
        body: IrElement,
        fqName: String,
        descendIntoNestedClasses: Boolean,
    ) {
        val shared = FunctionShared(fqName)
        body.transformChildrenVoid(object : IrElementTransformerVoid() {
            // A nested receiver-style @UiComponent function is framed by the collection
            // pass when it is reachable and rejected by FIR (LOCAL_COMPONENT_FUNCTION)
            // when it is not; either way its content must not be wrapped with THIS
            // body's region. The predicate mirrors the collection loop's
            // isUiComponentWithScopeReceiver exactly, so scope-free components — which
            // that loop hands to THIS pass — keep getting their entry content wrapped.
            override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement =
                if (declaration.isUiComponent() && declaration.hasComponentScopeExtensionReceiver()) {
                    declaration
                } else {
                    super.visitSimpleFunction(declaration)
                }

            override fun visitClass(declaration: IrClass): IrStatement =
                if (descendIntoNestedClasses) super.visitClass(declaration) else declaration

            // visitFunctionAccess (not visitCall) so constructor calls wrap too:
            // a @UiComponent content literal handed to a constructor is stored and
            // invoked later, exactly like BrowserKineticaApp's content — left
            // unwrapped it throws MissingKineticaPluginException at first render.
            override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
                expression.transformChildrenVoid(this)
                wrapAnnotatedContentArgumentsOf(expression, shared)
                return expression
            }
        })
    }

    private fun wrapAnnotatedContentArgumentsOf(
        expression: IrFunctionAccessExpression,
        shared: FunctionShared,
        skipParameters: Set<String> = emptySet(),
    ) {
        val callee = expression.symbol.owner
        for (parameter in callee.parameters) {
            if (parameter.kind != IrParameterKind.Regular) continue
            if (parameter.name.asString() in skipParameters) continue
            if (!parameter.hasUiComponentContentType()) continue
            val argument = expression.arguments[parameter.indexInParameters] as? IrFunctionExpression
                ?: continue
            wrapFreshRegionOf(argument, shared)
        }
    }

    private fun wrapFreshRegionOf(lambdaExpression: IrFunctionExpression, shared: FunctionShared) {
        if (!wrappedContentLambdas.add(lambdaExpression)) return
        val table = buildFreshRegionTableOf(lambdaExpression, shared) ?: return
        val lambda = lambdaExpression.function
        val receiver = lambda.parameters.first {
            it.kind == IrParameterKind.ExtensionReceiver &&
                it.type.classOrNull?.owner?.kotlinFqName == KineticaFramePolicy.COMPONENT_SCOPE_FQ
        }
        wrapBodyInFrame(lambda, receiver, table, component = false)
    }

    private fun buildFreshRegionTableOf(
        lambdaExpression: IrFunctionExpression,
        shared: FunctionShared,
    ): IrField? {
        val lambda = lambdaExpression.function
        val receiver = lambda.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
        if (receiver == null || receiver.type.classOrNull?.owner?.kotlinFqName != KineticaFramePolicy.COMPONENT_SCOPE_FQ) {
            // Paired with FIR's COMPONENT_CONTENT_WITHOUT_SCOPE_RECEIVER: a lambda the
            // pass cannot wrap crashes at first render if anything inside consumes an
            // ordinal, so the decline is a located ERROR, never a silent LOGGING line.
            reportDecline(
                "${shared.functionFqName}: @UiComponent content lambda has no ComponentScope " +
                    "receiver; left unwrapped — ordinal consumers inside it throw " +
                    "MissingKineticaPluginException at first render. Type the content " +
                    "parameter as @UiComponent ComponentScope.() -> Unit.",
                lambdaExpression,
            )
            return null
        }
        if (lambda.body !is IrBlockBody) {
            reportDecline(
                "${shared.functionFqName}: @UiComponent content lambda has no block body; " +
                    "left unwrapped — ordinal consumers inside it throw " +
                    "MissingKineticaPluginException at first render.",
                lambdaExpression,
            )
            return null
        }
        val regionNumbering = RegionNumbering()
        lambda.body?.transformChildrenVoid(Walker(lambda, shared, regionNumbering))
        return addTableField(shared.functionFqName, regionNumbering)
    }

    private fun fillOrdinal(expression: IrCall, ordinal: Int) {
        val parameter = expression.symbol.owner.parameters.firstOrNull {
            it.kind == IrParameterKind.Regular && it.name.asString() == "ordinal"
        }
        if (parameter == null) {
            log("${file.fileEntry.name}: no ordinal parameter on ${expression.symbol.owner.name}; skipped.")
            return
        }
        val builder = DeclarationIrBuilder(pluginContext, expression.symbol)
        expression.arguments[parameter.indexInParameters] = builder.irInt(ordinal)
    }

    private fun retargetCall(
        expression: IrCall,
        target: IrSimpleFunctionSymbol,
        extraArguments: Map<String, IrExpression>,
        dropParameters: Set<String> = emptySet(),
    ): IrExpression {
        val source = expression.symbol.owner
        val sourceByName = source.parameters.associateBy { parameter ->
            when (parameter.kind) {
                IrParameterKind.Regular -> parameter.name.asString()
                IrParameterKind.ExtensionReceiver -> EXTENSION_RECEIVER
                IrParameterKind.DispatchReceiver -> DISPATCH_RECEIVER
                else -> "context:${parameter.name}"
            }
        }
        val call = org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl(
            expression.startOffset,
            expression.endOffset,
            target.owner.returnType,
            target,
            expression.typeArguments.size,
            null,
            null,
        )
        for (index in expression.typeArguments.indices) {
            call.typeArguments[index] = expression.typeArguments[index]
        }
        for (parameter in target.owner.parameters) {
            val key = when (parameter.kind) {
                IrParameterKind.Regular -> parameter.name.asString()
                IrParameterKind.ExtensionReceiver -> EXTENSION_RECEIVER
                IrParameterKind.DispatchReceiver -> DISPATCH_RECEIVER
                else -> "context:${parameter.name}"
            }
            if (key in dropParameters) continue
            val extra = extraArguments[key]
            if (extra != null) {
                call.arguments[parameter.indexInParameters] = extra
                continue
            }
            val sourceParameter = sourceByName[key]
                ?: sourceByName[EXTENSION_RECEIVER].takeIf { key == DISPATCH_RECEIVER }
                ?: sourceByName[DISPATCH_RECEIVER].takeIf { key == EXTENSION_RECEIVER }
                ?: continue
            call.arguments[parameter.indexInParameters] = expression.arguments[sourceParameter.indexInParameters]
        }
        return call
    }

    /**
     * Injects `begin*Frame(table); try { <body> } finally { end*Frame() }` around the
     * function's statements. The component form consumes the caller-staged child ordinal.
     */
    private fun wrapBodyInFrame(
        function: IrSimpleFunction,
        receiver: IrValueParameter,
        table: IrField,
        component: Boolean,
    ) {
        val body = function.body as? IrBlockBody ?: return
        val builder = DeclarationIrBuilder(pluginContext, function.symbol)
        val begin = builder.irCall(if (component) symbols.beginComponentFrame else symbols.beginRegionFrame).apply {
            arguments[0] = builder.irGet(receiver)
            arguments[1] = builder.irGetField(null, table)
        }
        val end = builder.irCall(if (component) symbols.endComponentFrame else symbols.endRegionFrame).apply {
            arguments[0] = builder.irGet(receiver)
        }
        val tryBlock = IrBlockImpl(
            body.startOffset,
            body.endOffset,
            pluginContext.irBuiltIns.unitType,
            null,
            body.statements.toList(),
        )
        val guarded = IrTryImpl(
            body.startOffset,
            body.endOffset,
            pluginContext.irBuiltIns.unitType,
            tryBlock,
            emptyList(),
            end,
        )
        body.statements.clear()
        body.statements += begin
        body.statements += guarded
    }

    private fun addTableField(functionFqName: String, numbering: RegionNumbering): IrField =
        addStaticFileField(
            file = file,
            pluginContext = pluginContext,
            // Kotlin/JS klib signatures for top-level privates are package-scoped, so the
            // name must be unique across files of one package, not just within the file.
            name = Name.identifier("kineticaFrame\$${file.fileUniqueTag()}\$${tableFieldCount++}"),
            type = symbols.frameTableType,
        ) { builder ->
            val constructor = symbols.frameTableConstructor
            builder.irCallConstructor(constructor, emptyList()).apply {
                constructor.owner.parameters
                    .filter { it.kind == IrParameterKind.Regular }
                    .forEach { parameter ->
                        arguments[parameter.indexInParameters] = when (parameter.name.asString()) {
                            "functionFqName" -> builder.irString(functionFqName)
                            "slotCount" -> builder.irInt(numbering.slots)
                            "eventCount" -> builder.irInt(numbering.events)
                            "childCount" -> builder.irInt(numbering.children)
                            "transientSlotOrdinals" -> builder.irCall(symbols.intArrayOf).apply {
                                val varargParameter = symbols.intArrayOf.owner.parameters
                                    .first { it.varargElementType != null }
                                // irVararg would type this Array<Int>; intArrayOf needs IntArray.
                                arguments[0] = org.jetbrains.kotlin.ir.expressions.impl.IrVarargImpl(
                                    file.startOffset,
                                    file.endOffset,
                                    varargParameter.type,
                                    varargParameter.varargElementType!!,
                                    numbering.transientSlots.map { builder.irInt(it) },
                                )
                            }
                            else -> builder.irNull(parameter.type.makeNullable())
                        }
                    }
            }
        }

    private companion object {
        private const val EXTENSION_RECEIVER = "<extension>"
        private const val DISPATCH_RECEIVER = "<dispatch>"

        /** The only source parameters `hostEventBlock(ordinal, block)` can carry over. */
        private val FUSED_HOST_EVENT_PARAMETERS = setOf("ordinal", "onEvent")
    }
}

private fun IrSimpleFunction.isUiComponent(): Boolean =
    annotations.any { it.type.classOrNull?.owner?.kotlinFqName == UI_COMPONENT_FQ }

/** Whether this parameter's declared function type carries `@UiComponent` (content). */
private fun IrValueParameter.hasUiComponentContentType(): Boolean =
    type.annotations.any { it.type.classOrNull?.owner?.kotlinFqName == UI_COMPONENT_FQ }

private fun IrSimpleFunction.hasComponentScopeExtensionReceiver(): Boolean =
    parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
        ?.type?.classOrNull?.owner?.kotlinFqName == KineticaFramePolicy.COMPONENT_SCOPE_FQ

/**
 * The [CallableId] this function is recorded under in the SingleRunOracle, or null for
 * local (and otherwise unaddressable) functions — which therefore never get oracle
 * entries and are covered by the name-list fallback on both compiler phases.
 */
private fun IrSimpleFunction.callableIdOrNull(): CallableId? =
    when (val parent = parent) {
        is IrClass -> parent.classId?.let { CallableId(it, name) }
        is IrPackageFragment -> CallableId(parent.packageFqName, name)
        else -> null
    }

private fun FqName.parentOrNull(): FqName? = if (isRoot) null else parent()

private fun IrCall.argumentByName(name: String): IrExpression? {
    val parameter = symbol.owner.parameters.firstOrNull {
        it.kind == IrParameterKind.Regular && it.name.asString() == name
    } ?: return null
    return arguments[parameter.indexInParameters]
}

private fun IrCall.constBooleanArgument(name: String): Boolean? =
    (argumentByName(name) as? IrConst)?.value as? Boolean

private fun IrCall.inlineUnitEventLambda(): IrFunctionExpression? {
    val callee = symbol.owner
    val calleeFqName = callee.kotlinFqName
    if (calleeFqName.parentOrNull() != KineticaFramePolicy.KINETICA_PACKAGE || calleeFqName.shortName().asString() != "event") {
        return null
    }
    val lambda = argumentByName("block") as? IrFunctionExpression ?: return null
    val receiver = lambda.function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
        ?: return null
    if (receiver.type.classOrNull?.owner?.kotlinFqName != EVENT_SCOPE_FQ) {
        return null
    }
    val valueParameters = lambda.function.parameters.count { it.kind == IrParameterKind.Regular }
    return lambda.takeIf { valueParameters == 0 }
}
