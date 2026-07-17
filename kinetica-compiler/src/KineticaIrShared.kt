// referenceClass is deprecated in favor of the finder API; migrating the symbol
// resolvers is tracked separately.
@file:Suppress("DEPRECATION")

package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildProperty
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.impl.IrAnnotationImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.name.ClassId
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
    // The field must hang off a real IrProperty: backends discover top-level initializers
    // through properties, and Kotlin/Native's lazy file-initialization lowering silently
    // skips bare fields — the global then reads as null at runtime.
    val property = pluginContext.irFactory.buildProperty {
        this.name = name
        visibility = DescriptorVisibilities.PRIVATE
        modality = Modality.FINAL
        origin = IrDeclarationOrigin.DEFINED
    }.apply {
        parent = file
    }
    val field = pluginContext.irFactory.buildField {
        this.name = name
        this.type = type
        isFinal = true
        isStatic = true
        visibility = DescriptorVisibilities.PRIVATE
        origin = IrDeclarationOrigin.PROPERTY_BACKING_FIELD
    }.apply {
        parent = file
        correspondingPropertySymbol = property.symbol
    }
    property.backingField = field
    val fieldBuilder = DeclarationIrBuilder(pluginContext, field.symbol)
    field.initializer = pluginContext.irFactory.createExpressionBody(
        file.startOffset,
        file.endOffset,
        initializer(fieldBuilder),
    )
    // Kotlin/Native initializes file globals lazily, triggered by the first call to a
    // top-level function of that file — which can happen AFTER generated prologue code has
    // already read the field (frame tables key region identity, so a transient null read
    // permanently splits the frame). Eager initialization closes that window; the
    // annotation only exists on Native, so resolution failing means it is not needed.
    pluginContext.referenceClass(EAGER_INITIALIZATION_ID)?.let { eager ->
        property.annotations += IrAnnotationImpl.fromSymbolOwner(
            eager.owner.defaultType,
            eager.constructors.first(),
        )
    }
    file.declarations += property
    return field
}

private val EAGER_INITIALIZATION_ID = ClassId(
    FqName("kotlin.native"),
    Name.identifier("EagerInitialization"),
)

/** Package-unique generated field name; JS klib signatures are package-scoped. */
internal fun staticFieldName(
    prefix: String,
    file: IrFile,
    ordinal: Int,
): Name =
    Name.identifier("$prefix\$${file.fileUniqueTag()}\$$ordinal")

/** Stable per-file tag for package-unique generated names. */
internal fun IrFile.fileUniqueTag(): String {
    val base = fileEntry.name.substringAfterLast('/').substringBeforeLast('.')
        .replace(Regex("[^A-Za-z0-9]"), "_")
    val hash = fileEntry.name.hashCode().toUInt().toString(16)
    return "$base\$$hash"
}

internal fun IrExpression.isNullConst(): Boolean =
    this is IrConst && kind == IrConstKind.Null
