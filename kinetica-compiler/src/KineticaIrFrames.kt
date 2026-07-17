// referenceClass/referenceFunctions are deprecated in favor of the finder API;
// migrating the symbol resolvers is tracked separately.
@file:Suppress("DEPRECATION")

package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
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
 * suspendSubtree key) is left on the legacy string-keyed path and reported.
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

/** Slot-ordinal DSL call names; value = whether the slot is transient by construction. */
private val SLOT_DSL_TRANSIENT = mapOf(
    "state" to false,
    "derived" to false,
    "event" to false,
    "frameValue" to false,
    "launchEffect" to true,
    "watch" to true,
    "hostRef" to true,
    "imperativeHandle" to true,
    "resource" to true,
)

private val EVENT_DSL_NAMES = setOf("button", "textInput", "checkbox", "hostEvent", "hostEventBlock")

/** Region constructs and the parameter names of their fresh-region content lambdas. */
private val REGION_CONTENT_PARAMS = mapOf(
    "keyed" to setOf("content"),
    "suspendKeyed" to setOf("content"),
    "each" to setOf("content"),
    "lazyEach" to setOf("content", "placeholder"),
    "errorBoundary" to setOf("content", "fallback"),
    "loadingBoundary" to setOf("content", "fallback"),
    "suspendSubtree" to setOf("content", "fallback"),
    "exitGroup" to setOf("content"),
)

private val MERGED_EACH_REGION_NAMES = setOf("each", "lazyEach")

/** Region constructs that consume a slot ordinal for their boundary state. */
private val REGION_STATE_SLOTS = setOf("errorBoundary", "loadingBoundary", "suspendSubtree")

private val UI_COMPONENT_FQ = FqName("io.heapy.kinetica.UiComponent")
private val KOTLIN_PKG = FqName("kotlin")
private val SINGLE_RUN_SCOPE_FUNCTIONS = setOf("let", "run", "with", "apply", "also")
private val COMPONENT_SCOPE_FQ = FqName("io.heapy.kinetica.ComponentScope")
private val EVENT_SCOPE_FQ = FqName("io.heapy.kinetica.EventScope")
private val KINETICA_PKG = FqName("io.heapy.kinetica")

internal class KineticaFrameTransformer(
    private val file: IrFile,
    private val pluginContext: IrPluginContext,
    private val symbols: KineticaFrameSymbols,
    private val moduleId: String,
    private val report: (String) -> Unit,
) {
    var componentsFramed: Int = 0
        private set
    private var tableFieldCount = 0

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
        if (receiver.type.classOrNull?.owner?.kotlinFqName != COMPONENT_SCOPE_FQ) return
        val body = function.body as? IrBlockBody ?: return
        val fqName = function.fqNameWhenAvailable?.asString() ?: return

        val shared = FunctionShared(fqName)
        val numbering = RegionNumbering()
        body.transformChildrenVoid(Walker(function, shared, numbering))
        val table = addTableField(shared.functionFqName, numbering)
        wrapBodyInFrame(function, receiver, table, component = true)
        function.patchDeclarationParents(function.parent)
        componentsFramed++
        report(
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

        override fun visitCall(expression: IrCall): IrExpression {
            val callee = expression.symbol.owner
            val calleeFqName = callee.kotlinFqName
            val parent = calleeFqName.parentOrNull()
            val inKinetica = parent == KINETICA_PKG || parent == COMPONENT_SCOPE_FQ
            val name = calleeFqName.shortName().asString()

            if (inKinetica && name in REGION_CONTENT_PARAMS) {
                return transformRegion(expression, name)
            }

            if (inKinetica && name == "hostEvent") {
                tryFuseHostEventBlock(expression)?.let { return it }
            }

            transformArgumentsSelectively(expression, inKinetica, parent)

            if (inKinetica && name in SLOT_DSL_TRANSIENT) {
                return transformSlotCall(expression, name)
            }
            if (inKinetica && name in EVENT_DSL_NAMES) {
                fillOrdinal(expression, numbering.events++)
                return expression
            }
            if (callee.isUiComponent()) {
                // Component calls can carry @UiComponent-typed content parameters; their
                // lambda literals must become regions here too, not only in entry points.
                wrapAnnotatedContentArguments(expression)
                return stageComponentCall(expression)
            }
            wrapAnnotatedContentArguments(expression)
            return expression
        }

        private fun tryFuseHostEventBlock(expression: IrCall): IrExpression? {
            val eventCall = expression.argumentByName("onEvent") as? IrCall ?: return null
            val eventLambda = eventCall.inlineUnitEventLambda() ?: return null

            val callee = expression.symbol.owner
            for (parameter in callee.parameters) {
                val parameterName = parameter.name.asString()
                if (parameter.kind == IrParameterKind.Regular &&
                    (parameterName == "ordinal" || parameterName == "onEvent")
                ) {
                    continue
                }
                val argument = expression.arguments[parameter.indexInParameters] ?: continue
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
            var transient = SLOT_DSL_TRANSIENT.getValue(name)
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
            if (expression.constBooleanArgument("persistent") != true) return expression
            if (expression.argumentByName("key") != null) {
                report("${shared.functionFqName}: persistent state with explicit key left on the legacy path.")
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
            val contentParams = REGION_CONTENT_PARAMS.getValue(name)
            val target = symbols.regionTargets.getValue(name)

            if (name == "suspendSubtree") {
                val keyArgument = expression.argumentByName("key")
                if (keyArgument != null && !keyArgument.isNullConst()) {
                    report("${shared.functionFqName}: suspendSubtree with explicit key left on the legacy path.")
                    expression.transformChildrenVoid(this)
                    return expression
                }
            }

            // Non-content arguments share this region's counters.
            val callee = expression.symbol.owner
            for (parameter in callee.parameters) {
                val argument = expression.arguments[parameter.indexInParameters] ?: continue
                if (parameter.kind == IrParameterKind.Regular && parameter.name.asString() in contentParams) continue
                expression.arguments[parameter.indexInParameters] = argument.transform(this, null)
            }

            val extraArguments = mutableMapOf<String, IrExpression>()
            val builder = DeclarationIrBuilder(pluginContext, enclosingFunction.symbol)
            if (name in REGION_STATE_SLOTS) {
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
                    report("${shared.functionFqName}: $name ${parameter.name} is not a lambda literal; left on the legacy path.")
                    expression.transformChildrenVoid(this)
                    return expression
                }
                if (name in MERGED_EACH_REGION_NAMES && parameterName == "content") {
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
            if (receiverParameter.type.classOrNull?.owner?.kotlinFqName != COMPONENT_SCOPE_FQ) {
                return expression
            }
            val receiverArgument = expression.arguments[receiverParameter.indexInParameters]
            if (receiverArgument !is IrGetValue) {
                report(
                    "${shared.functionFqName}: component call ${callee.name} has a non-trivial " +
                        "receiver expression; left unstaged.",
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

        /** Lambdas passed to `@UiComponent`-annotated function-type parameters are regions. */
        private fun wrapAnnotatedContentArguments(expression: IrCall) {
            wrapAnnotatedContentArgumentsOf(expression, shared)
        }

        /**
         * Static ordinals are only sound for code that runs at most once per render of its
         * frame. Lambdas passed to arbitrary functions (List(n) { … }, repeat, map) can run
         * any number of times, so the walker descends only into lambdas whose single-run
         * contract is known: Kinetica DSL content and the kotlin scope functions. Everything
         * else keeps the legacy positional path (its cursors advance per invocation).
         */
        private fun transformArgumentsSelectively(expression: IrCall, inKinetica: Boolean, parent: FqName?) {
            val descendIntoLambdas = inKinetica || parent == KOTLIN_PKG &&
                expression.symbol.owner.name.asString() in SINGLE_RUN_SCOPE_FUNCTIONS
            val callee = expression.symbol.owner
            for (parameter in callee.parameters) {
                val argument = expression.arguments[parameter.indexInParameters] ?: continue
                if (argument is IrFunctionExpression && !descendIntoLambdas) {
                    continue
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
        val shared = FunctionShared(fqName)
        body.transformChildrenVoid(object : IrElementTransformerVoid() {
            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)
                wrapAnnotatedContentArgumentsOf(expression, shared)
                return expression
            }
        })
        function.patchDeclarationParents(function.parent)
    }

    private fun wrapAnnotatedContentArgumentsOf(expression: IrCall, shared: FunctionShared) {
        val callee = expression.symbol.owner
        for (parameter in callee.parameters) {
            if (parameter.kind != IrParameterKind.Regular) continue
            val annotated = parameter.type.annotations.any {
                it.type.classOrNull?.owner?.kotlinFqName == UI_COMPONENT_FQ
            }
            if (!annotated) continue
            val argument = expression.arguments[parameter.indexInParameters] as? IrFunctionExpression
                ?: continue
            wrapFreshRegionOf(argument, shared)
        }
    }

    private fun wrapFreshRegionOf(lambdaExpression: IrFunctionExpression, shared: FunctionShared) {
        val table = buildFreshRegionTableOf(lambdaExpression, shared) ?: return
        val lambda = lambdaExpression.function
        val receiver = lambda.parameters.first {
            it.kind == IrParameterKind.ExtensionReceiver &&
                it.type.classOrNull?.owner?.kotlinFqName == COMPONENT_SCOPE_FQ
        }
        wrapBodyInFrame(lambda, receiver, table, component = false)
    }

    private fun buildFreshRegionTableOf(
        lambdaExpression: IrFunctionExpression,
        shared: FunctionShared,
    ): IrField? {
        val lambda = lambdaExpression.function
        val receiver = lambda.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
        if (receiver == null || receiver.type.classOrNull?.owner?.kotlinFqName != COMPONENT_SCOPE_FQ) {
            report("${shared.functionFqName}: content lambda without ComponentScope receiver; left unwrapped.")
            return null
        }
        if (lambda.body !is IrBlockBody) {
            report("${shared.functionFqName}: content lambda without a block body; left unwrapped.")
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
            report("${file.fileEntry.name}: no ordinal parameter on ${expression.symbol.owner.name}; skipped.")
            return
        }
        val builder = DeclarationIrBuilder(pluginContext, expression.symbol)
        expression.arguments[parameter.indexInParameters] = builder.irInt(ordinal)
    }

    /** Rebuilds [expression] against [target], mapping arguments by parameter name. */
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
    }
}

private fun IrSimpleFunction.isUiComponent(): Boolean =
    annotations.any { it.type.classOrNull?.owner?.kotlinFqName == UI_COMPONENT_FQ }

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
    if (calleeFqName.parentOrNull() != KINETICA_PKG || calleeFqName.shortName().asString() != "event") {
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
