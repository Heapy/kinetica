package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Shared-analysis contract: the extension creates one index per file and passes it to both
 * allocation transformers; recognition of hoisted props is index-only, never heuristic.
 */
internal class ConstPropsFieldIndex {
    private val entries = mutableMapOf<IrFieldSymbol, List<Pair<String, String>>>()

    fun register(field: IrField, pairs: List<Pair<String, String>>) {
        entries[field.symbol] = pairs
    }

    fun constPropsFor(expression: IrGetField): List<Pair<String, String>>? =
        entries[expression.symbol]
}

internal fun constPropsOf(
    call: IrCall,
    propsOfFqName: FqName,
): List<Pair<String, String>>? {
    if (call.symbol.owner.kotlinFqName != propsOfFqName) return null
    val values = mutableListOf<String>()
    for (parameter in call.symbol.owner.parameters) {
        if (parameter.kind != IrParameterKind.Regular) continue
        val argument = call.arguments[parameter.indexInParameters] ?: continue
        val const = argument as? IrConst ?: return null
        if (const.kind != IrConstKind.String) return null
        values += const.value as String
    }
    if (values.size % 2 != 0) return null
    return values.chunked(2).map { (name, value) -> name to value }
}

internal fun addStaticFileField(
    file: IrFile,
    pluginContext: IrPluginContext,
    name: Name,
    type: IrType,
    initializer: (DeclarationIrBuilder) -> IrExpression,
): IrField {
    val field = pluginContext.irFactory.buildField {
        this.name = name
        this.type = type
        isFinal = true
        isStatic = true
        visibility = DescriptorVisibilities.PRIVATE
        origin = IrDeclarationOrigin.DEFINED
    }.apply {
        parent = file
    }
    val fieldBuilder = DeclarationIrBuilder(pluginContext, field.symbol)
    field.initializer = pluginContext.irFactory.createExpressionBody(
        file.startOffset,
        file.endOffset,
        initializer(fieldBuilder),
    )
    file.declarations += field
    return field
}

internal fun IrExpression.isNullConst(): Boolean =
    this is IrConst && kind == IrConstKind.Null
