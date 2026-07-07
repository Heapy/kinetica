// referenceClass/referenceFunctions are deprecated in favor of the finder API;
// migrating the symbol resolvers is tracked separately.
@file:Suppress("DEPRECATION")

package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irEqeqeq
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.primitiveOp2
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFieldAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrBlockImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrSetFieldImpl
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class KineticaAtomicUnwrapLowering(
    private val pluginContext: IrPluginContext,
    private val report: (String) -> Unit,
) {
    private val atomicFields = linkedMapOf<IrFieldSymbol, AtomicField>()
    private val atomicGetterFields = linkedMapOf<IrSimpleFunctionSymbol, AtomicField>()
    private val longPlus = plusSymbol("Long", pluginContext.irBuiltIns.longType)
    private val intPlus = plusSymbol("Int", pluginContext.irBuiltIns.intType)

    fun lower(moduleFragment: IrModuleFragment) {
        moduleFragment.transformChildrenVoid(Collector())
        if (atomicFields.isEmpty()) return
        moduleFragment.transformChildrenVoid(Rewriter())
        report("unwrapped ${atomicFields.size} stdlib atomic field(s) for single-threaded backend.")
    }

    private inner class Collector : IrElementTransformerVoid() {
        override fun visitField(declaration: IrField): IrStatement {
            val atomic = declaration.type.atomicKind() ?: return super.visitField(declaration)
            val valueType = atomic.valueType(declaration.type)
            val atomicField = AtomicField(declaration, atomic, valueType)
            atomicFields[declaration.symbol] = atomicField
            declaration.correspondingPropertySymbol?.owner?.getter?.symbol?.let { getter ->
                atomicGetterFields[getter] = atomicField
            }
            declaration.type = valueType
            declaration.isFinal = false
            declaration.initializer = declaration.initializer?.let { body ->
                val expression = body.expression.transform(this, null)
                val unwrapped = (expression as? IrConstructorCall)?.unwrapAtomicConstructor() ?: expression
                pluginContext.irFactory.createExpressionBody(body.startOffset, body.endOffset, unwrapped)
            }
            return declaration
        }
    }

    private inner class Rewriter : IrElementTransformerVoid() {
        private var currentFunction: IrFunction? = null

        override fun visitFunction(declaration: IrFunction): IrStatement {
            val previous = currentFunction
            currentFunction = declaration
            return try {
                super.visitFunction(declaration)
            } finally {
                currentFunction = previous
            }
        }

        override fun visitGetField(expression: IrGetField): IrExpression {
            expression.transformChildrenVoid(this)
            val field = atomicFields[expression.symbol] ?: return expression
            expression.type = field.valueType
            return expression
        }

        override fun visitCall(expression: IrCall): IrExpression {
            expression.transformChildrenVoid(this)
            val name = expression.symbol.owner.name.asString()
            val atomicReceiver = expression.atomicReceiver() ?: return expression
            val receiver = atomicReceiver.expression.atomicFieldAccess() ?: return expression
            val field = atomicFields[receiver.symbol] ?: return expression
            return when (name) {
                "load" -> getField(receiver, field)
                "store" -> storeField(
                    receiver,
                    field,
                    expression.regularArgument(0, atomicReceiver.firstRegularArgumentIsReceiver) ?: return expression,
                )
                "compareAndSet" -> compareAndSet(
                    receiver,
                    field,
                    expression.regularArgument(0, atomicReceiver.firstRegularArgumentIsReceiver) ?: return expression,
                    expression.regularArgument(1, atomicReceiver.firstRegularArgumentIsReceiver) ?: return expression,
                    currentFunction as? IrSimpleFunction
                        ?: error("Atomic compareAndSet outside a simple function at ${expression.startOffset}."),
                )
                "addAndFetch" -> addAndFetch(
                    receiver,
                    field,
                    expression.regularArgument(0, atomicReceiver.firstRegularArgumentIsReceiver) ?: return expression,
                )
                "incrementAndFetch" -> incrementAndFetch(receiver, field)
                else -> {
                    report(
                        "unsupported atomic operation '${expression.symbol.owner.kotlinFqName}' " +
                            "on unwrapped field '${field.field.name.asString()}'.",
                    )
                    expression
                }
            }
        }
    }

    private fun IrConstructorCall.unwrapAtomicConstructor(): IrExpression? {
        if (type.atomicKind() == null) return null
        val valueParameter = symbol.owner.parameters.firstOrNull { it.kind == IrParameterKind.Regular } ?: return null
        return arguments[valueParameter.indexInParameters]
    }

    private fun IrCall.atomicReceiver(): AtomicReceiver? {
        dispatchReceiver?.let { receiver ->
            return AtomicReceiver(receiver, firstRegularArgumentIsReceiver = false)
        }
        val parameter = symbol.owner.parameters.firstOrNull {
            it.kind == IrParameterKind.DispatchReceiver || it.kind == IrParameterKind.ExtensionReceiver
        }
        if (parameter != null) {
            return arguments[parameter.indexInParameters]?.let { AtomicReceiver(it, firstRegularArgumentIsReceiver = false) }
        }
        val regularParameter = symbol.owner.parameters.firstOrNull { it.kind == IrParameterKind.Regular } ?: return null
        return arguments[regularParameter.indexInParameters]?.let {
            AtomicReceiver(it, firstRegularArgumentIsReceiver = true)
        }
    }

    private fun IrCall.regularArgument(index: Int, skipFirstRegular: Boolean): IrExpression? =
        symbol.owner.parameters
            .filter { it.kind == IrParameterKind.Regular }
            .drop(if (skipFirstRegular) 1 else 0)
            .getOrNull(index)
            ?.let { arguments[it.indexInParameters] }

    private fun IrExpression.atomicFieldAccess(): IrGetField? =
        when (this) {
            is IrGetField -> this
            is IrCall -> atomicGetterFields[symbol]?.let { field ->
                IrGetFieldImpl(
                    startOffset,
                    endOffset,
                    field.field.symbol,
                    field.valueType,
                    dispatchReceiver.copyForAtomicRewrite(),
                )
            }
            else -> null
        }

    private fun getField(access: IrFieldAccessExpression, field: AtomicField): IrExpression =
        IrGetFieldImpl(
            access.startOffset,
            access.endOffset,
            field.field.symbol,
            field.valueType,
            access.receiver.copyForAtomicRewrite(),
        )

    private fun storeField(access: IrFieldAccessExpression, field: AtomicField, value: IrExpression): IrExpression =
        IrSetFieldImpl(
            access.startOffset,
            access.endOffset,
            field.field.symbol,
            access.receiver.copyForAtomicRewrite(),
            value,
            pluginContext.irBuiltIns.unitType,
        )

    private fun compareAndSet(
        access: IrFieldAccessExpression,
        field: AtomicField,
        expected: IrExpression,
        update: IrExpression,
        function: IrSimpleFunction,
    ): IrExpression {
        val current = getField(access, field)
        val condition = if (field.kind == AtomicKind.Reference) {
            builder(access, function).irEqeqeq(current, expected)
        } else {
            builder(access, function).irEquals(current, expected)
        }
        val thenBlock = IrBlockImpl(
            access.startOffset,
            access.endOffset,
            pluginContext.irBuiltIns.booleanType,
        ).apply {
            statements += storeField(access, field, update)
            statements += IrConstImpl.constTrue(access.startOffset, access.endOffset, pluginContext.irBuiltIns.booleanType)
        }
        return builder(access, function).irIfThenElse(
            pluginContext.irBuiltIns.booleanType,
            condition,
            thenBlock,
            IrConstImpl.constFalse(access.startOffset, access.endOffset, pluginContext.irBuiltIns.booleanType),
        )
    }

    private fun incrementAndFetch(access: IrFieldAccessExpression, field: AtomicField): IrExpression {
        val delta = when (field.kind) {
            AtomicKind.Long -> IrConstImpl.long(access.startOffset, access.endOffset, pluginContext.irBuiltIns.longType, 1L)
            AtomicKind.Int -> IrConstImpl.int(access.startOffset, access.endOffset, pluginContext.irBuiltIns.intType, 1)
            else -> return unsupportedNumeric(access, field)
        }
        return addAndFetch(access, field, delta)
    }

    private fun addAndFetch(
        access: IrFieldAccessExpression,
        field: AtomicField,
        delta: IrExpression,
    ): IrExpression {
        val next = add(access, field, getField(access, field), delta) ?: return unsupportedNumeric(access, field)
        return IrBlockImpl(
            access.startOffset,
            access.endOffset,
            field.valueType,
        ).apply {
            statements += storeField(access, field, next)
            statements += getField(access, field)
        }
    }

    private fun add(access: IrFieldAccessExpression, field: AtomicField, left: IrExpression, right: IrExpression): IrExpression? {
        val symbol = when (field.kind) {
            AtomicKind.Long -> longPlus
            AtomicKind.Int -> intPlus
            else -> null
        } ?: return null
        return primitiveOp2(
            access.startOffset,
            access.endOffset,
            symbol,
            field.valueType,
            IrStatementOrigin.PLUS,
            left,
            right,
        )
    }

    private fun unsupportedNumeric(access: IrFieldAccessExpression, field: AtomicField): IrExpression {
        report("unsupported numeric atomic operation on '${field.field.name.asString()}'.")
        return getField(access, field)
    }

    private fun builder(expression: IrFieldAccessExpression, function: IrSimpleFunction): DeclarationIrBuilder {
        return DeclarationIrBuilder(pluginContext, function.symbol, expression.startOffset, expression.endOffset)
    }

    private fun plusSymbol(className: String, operandType: IrType): IrSimpleFunctionSymbol? {
        val classId = ClassId(FqName("kotlin"), Name.identifier(className))
        return pluginContext.referenceFunctions(CallableId(classId, Name.identifier("plus")))
            .firstOrNull { symbol ->
                val parameters = symbol.owner.parameters
                parameters.count { it.kind == IrParameterKind.Regular } == 1 &&
                    parameters.any { it.kind == IrParameterKind.DispatchReceiver && it.type == operandType } &&
                    parameters.any { it.kind == IrParameterKind.Regular && it.type == operandType }
            }
    }

    private fun IrExpression?.copyForAtomicRewrite(): IrExpression? =
        when (this) {
            null -> null
            is IrGetValue -> IrGetValueImpl(startOffset, endOffset, type, symbol, origin)
            is IrGetObjectValue -> IrGetObjectValueImpl(startOffset, endOffset, type, symbol)
            is IrGetField -> IrGetFieldImpl(
                startOffset,
                endOffset,
                symbol,
                type,
                receiver.copyForAtomicRewrite(),
                origin,
                superQualifierSymbol,
            )
            else -> this
        }

    private fun IrType.atomicKind(): AtomicKind? =
        when (classOrNull?.owner?.kotlinFqName?.asString()) {
            "kotlin.concurrent.atomics.AtomicReference" -> AtomicKind.Reference
            "kotlin.concurrent.atomics.AtomicLong" -> AtomicKind.Long
            "kotlin.concurrent.atomics.AtomicInt" -> AtomicKind.Int
            "kotlin.concurrent.atomics.AtomicBoolean" -> AtomicKind.Boolean
            else -> null
        }

    private fun AtomicKind.valueType(type: IrType): IrType =
        when (this) {
            AtomicKind.Reference -> ((type as? IrSimpleType)?.arguments?.firstOrNull() as? IrTypeProjection)?.type
                ?: pluginContext.irBuiltIns.anyNType
            AtomicKind.Long -> pluginContext.irBuiltIns.longType
            AtomicKind.Int -> pluginContext.irBuiltIns.intType
            AtomicKind.Boolean -> pluginContext.irBuiltIns.booleanType
        }

    private data class AtomicField(
        val field: IrField,
        val kind: AtomicKind,
        val valueType: IrType,
    )

    private data class AtomicReceiver(
        val expression: IrExpression,
        val firstRegularArgumentIsReceiver: Boolean,
    )

    private enum class AtomicKind {
        Reference,
        Long,
        Int,
        Boolean,
    }
}
