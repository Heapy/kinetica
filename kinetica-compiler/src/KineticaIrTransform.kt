package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isBoolean
import org.jetbrains.kotlin.ir.types.isByte
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isDouble
import org.jetbrains.kotlin.ir.types.isFloat
import org.jetbrains.kotlin.ir.types.isInt
import org.jetbrains.kotlin.ir.types.isLong
import org.jetbrains.kotlin.ir.types.isShort
import org.jetbrains.kotlin.ir.types.isString
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isEnumClass
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/**
 * K2 backend transform (runs for JVM and JS alike): rewrites receiver-style UI components
 *
 *   @UiComponent fun ComponentScope.X(a: A, b: B) { <body> }
 *
 * whose inputs are all provably stable into
 *
 *   fun ComponentScope.X(a: A, b: B) { emit(skippableNode("fq.X", listOf(a, b)) { renderNode { <body> } }) }
 *
 * so re-renders with unchanged inputs reuse the cached Node subtree. Scope-free components
 * (the source-processing extension's authoring model) already return Node and are skipped
 * here. Anything unprovable is left untouched — a wrong skip means stale UI.
 */
public class KineticaIrGenerationExtension(
    private val messageCollector: MessageCollector,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        val symbols = KineticaIrSymbols.resolve(pluginContext) ?: return
        val stability = KineticaStability()
        moduleFragment.files.forEach { file ->
            // snapshot: the transform appends nothing, but avoid concurrent modification anyway
            file.declarations.filterIsInstance<IrSimpleFunction>().forEach { function ->
                transformComponent(function, symbols, stability, pluginContext)
            }
        }
    }

    private fun transformComponent(
        function: IrSimpleFunction,
        symbols: KineticaIrSymbols,
        stability: KineticaStability,
        pluginContext: IrPluginContext,
    ) {
        val uiComponent = function.annotations.firstOrNull {
            it.type.classOrNull?.owner?.kotlinFqName == UI_COMPONENT_FQ
        } ?: return
        if (!function.returnType.isUnit()) return
        val receiver = function.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
            ?: return
        if (receiver.type.classOrNull?.owner?.kotlinFqName != COMPONENT_SCOPE_FQ) return
        val body = function.body ?: return
        val componentFqName = function.fqNameWhenAvailable?.asString() ?: return

        if (!uiComponent.skippableFlag()) {
            report("$componentFqName: skipping disabled via @UiComponent(skippable = false).")
            return
        }
        if (function.isSuspend) {
            report("$componentFqName: not skippable — suspend receiver-style components are not transformed yet.")
            return
        }
        val regularParameters = function.parameters.filter { it.kind == IrParameterKind.Regular }
        val unstable = regularParameters.firstOrNull { !stability.isStable(it.type) }
        if (unstable != null) {
            report(
                "$componentFqName: not skippable — parameter '${unstable.name}' of type " +
                    "${unstable.type.getClass()?.kotlinFqName ?: unstable.type} is not provably stable " +
                    "(annotate the class with @io.heapy.kinetica.Stable to assert immutability).",
            )
            return
        }
        if (body.containsReturnTargeting(function)) {
            report("$componentFqName: not skippable — early returns are not supported in skippable components.")
            return
        }

        val builder = DeclarationIrBuilder(pluginContext, function.symbol)

        // inner lambda: ComponentScope.() -> Unit containing the original body; statements
        // keep referencing the outer function's receiver/parameters via closure capture.
        val contentLambda = pluginContext.irFactory.buildFun {
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = SpecialNames.ANONYMOUS
            visibility = DescriptorVisibilities.LOCAL
            returnType = pluginContext.irBuiltIns.unitType
        }.apply {
            parent = function
            parameters = listOf(
                buildReceiverParameter(this, receiver.type),
            )
            this.body = pluginContext.irFactory.createBlockBody(body.startOffset, body.endOffset).apply {
                statements += body.statementsOrEmpty()
            }
        }
        contentLambda.patchDeclarationParents(function)

        val contentType = pluginContext.irBuiltIns.functionN(1)
            .typeWith(receiver.type, pluginContext.irBuiltIns.unitType)

        // factory lambda: () -> Node = { renderNode(content) }
        val factoryLambda = pluginContext.irFactory.buildFun {
            origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
            name = SpecialNames.ANONYMOUS
            visibility = DescriptorVisibilities.LOCAL
            returnType = symbols.nodeType
        }.apply {
            parent = function
            val lambda = this
            this.body = DeclarationIrBuilder(pluginContext, lambda.symbol).irBlockBody {
                +irReturnFor(
                    lambda,
                    irCall(symbols.renderNode).apply {
                        arguments[0] = builder.irGet(receiver)
                        arguments[1] = IrFunctionExpressionImpl(
                            startOffset,
                            endOffset,
                            contentType,
                            contentLambda,
                            IrStatementOrigin.LAMBDA,
                        )
                    },
                )
            }
        }

        val factoryType = pluginContext.irBuiltIns.functionN(0).typeWith(symbols.nodeType)

        function.body = builder.irBlockBody {
            +irCall(symbols.emit).apply {
                arguments[0] = irGet(receiver)
                arguments[1] = irCall(symbols.skippableNode).apply {
                    arguments[0] = irGet(receiver)
                    arguments[1] = irString(componentFqName)
                    arguments[2] = irCall(symbols.listOf).apply {
                        typeArguments[0] = pluginContext.irBuiltIns.anyNType
                        arguments[0] = irVararg(
                            pluginContext.irBuiltIns.anyNType,
                            regularParameters.map { irGet(it) },
                        )
                    }
                    arguments[3] = IrFunctionExpressionImpl(
                        startOffset,
                        endOffset,
                        factoryType,
                        factoryLambda,
                        IrStatementOrigin.LAMBDA,
                    )
                }
            }
        }
        function.patchDeclarationParents(function.parent)
        report("$componentFqName: wrapped in skippableNode (inputs: ${regularParameters.joinToString { it.name.asString() }}).")
    }

    private fun buildReceiverParameter(owner: IrSimpleFunction, type: IrType): IrValueParameter =
        owner.factory.createValueParameter(
            startOffset = owner.startOffset,
            endOffset = owner.endOffset,
            origin = IrDeclarationOrigin.DEFINED,
            kind = IrParameterKind.ExtensionReceiver,
            name = Name.identifier("\$this\$content"),
            type = type,
            isAssignable = false,
            symbol = org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl(),
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false,
            isHidden = false,
        ).apply { parent = owner }

    private fun report(message: String) {
        messageCollector.report(CompilerMessageSeverity.LOGGING, "[kinetica] $message")
    }

    private companion object {
        private val UI_COMPONENT_FQ = FqName("io.heapy.kinetica.UiComponent")
        private val COMPONENT_SCOPE_FQ = FqName("io.heapy.kinetica.ComponentScope")
    }
}

private fun IrConstructorCall.skippableFlag(): Boolean {
    val argument = arguments.getOrNull(0) as? IrConst ?: return true
    return argument.value as? Boolean ?: true
}

private fun org.jetbrains.kotlin.ir.expressions.IrBody.statementsOrEmpty(): List<IrStatement> =
    when (this) {
        is org.jetbrains.kotlin.ir.expressions.IrBlockBody -> statements.toList()
        is org.jetbrains.kotlin.ir.expressions.IrExpressionBody -> listOf(expression)
        else -> emptyList()
    }

private fun org.jetbrains.kotlin.ir.expressions.IrBody.containsReturnTargeting(
    function: IrSimpleFunction,
): Boolean {
    var found = false
    acceptVoid(object : IrVisitorVoid() {
        override fun visitElement(element: org.jetbrains.kotlin.ir.IrElement) {
            if (!found) element.acceptChildrenVoid(this)
        }

        override fun visitReturn(expression: IrReturn) {
            if (expression.returnTargetSymbol == function.symbol) {
                found = true
            }
            super.visitReturn(expression)
        }
    })
    return found
}

private fun org.jetbrains.kotlin.ir.builders.IrBuilderWithScope.irReturnFor(
    target: IrSimpleFunction,
    value: IrExpression,
): IrExpression =
    org.jetbrains.kotlin.ir.expressions.impl.IrReturnImpl(
        startOffset,
        endOffset,
        context.irBuiltIns.nothingType,
        target.symbol,
        value,
    )

internal class KineticaIrSymbols private constructor(
    val emit: IrSimpleFunctionSymbol,
    val skippableNode: IrSimpleFunctionSymbol,
    val renderNode: IrSimpleFunctionSymbol,
    val listOf: IrSimpleFunctionSymbol,
    val nodeType: IrType,
) {
    companion object {
        fun resolve(pluginContext: IrPluginContext): KineticaIrSymbols? {
            val scopeId = ClassId(FqName("io.heapy.kinetica"), Name.identifier("ComponentScope"))
            val nodeId = ClassId(FqName("io.heapy.kinetica"), Name.identifier("Node"))
            val nodeClass = pluginContext.referenceClass(nodeId) ?: return null
            fun member(name: String): IrSimpleFunctionSymbol? =
                pluginContext.referenceFunctions(CallableId(scopeId, Name.identifier(name)))
                    .firstOrNull()
            val emit = member("emit") ?: return null
            val skippable = pluginContext
                .referenceFunctions(CallableId(scopeId, Name.identifier("skippableNode")))
                .firstOrNull { symbol -> !symbol.owner.isSuspend } ?: return null
            val renderNode = member("renderNode") ?: return null
            val listOf = pluginContext
                .referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("listOf")))
                .firstOrNull { symbol ->
                    symbol.owner.parameters.singleOrNull { it.kind == IrParameterKind.Regular }
                        ?.varargElementType != null
                } ?: return null
            return KineticaIrSymbols(
                emit = emit,
                skippableNode = skippable,
                renderNode = renderNode,
                listOf = listOf,
                nodeType = nodeClass.owner.defaultType,
            )
        }
    }
}

/**
 * Semantic stability: a type is stable iff equal values keep meaning equal — primitives,
 * String, enums, `val`-only data classes of stable property types, or classes asserting
 * immutability via @io.heapy.kinetica.Stable. Function types, mutable/interface collections
 * and everything unresolved are unstable; unknown means unstable (invariant 3).
 */
internal class KineticaStability {
    private val cache = mutableMapOf<IrClass, Boolean>()

    fun isStable(type: IrType): Boolean {
        if (
            type.isBoolean() || type.isByte() || type.isShort() || type.isInt() || type.isLong() ||
            type.isFloat() || type.isDouble() || type.isChar() || type.isString() || type.isUnit()
        ) {
            return true
        }
        val irClass = type.getClass() ?: return false
        return isStableClass(irClass)
    }

    private fun isStableClass(irClass: IrClass): Boolean =
        cache.getOrPut(irClass) {
            // seed with false so recursive data classes (trees) don't loop; a self-referential
            // property is then treated as unstable unless asserted @Stable
            cache[irClass] = false
            when {
                irClass.hasAnnotation(STABLE_FQ) -> true
                irClass.isEnumClass -> true
                irClass.isData -> {
                    val constructorParameterNames = irClass.primaryConstructor
                        ?.parameters
                        ?.filter { it.kind == IrParameterKind.Regular }
                        ?.map { it.name }
                        ?.toSet()
                        .orEmpty()
                    irClass.properties
                        .filter { property -> property.name in constructorParameterNames }
                        .all { property ->
                            property.isVar.not() &&
                                property.backingField?.type?.let { isStable(it) } == true
                        }
                }
                else -> false
            }
        }

    private companion object {
        private val STABLE_FQ = FqName("io.heapy.kinetica.Stable")
    }
}
