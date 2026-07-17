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
    // Initialization timing is backend-specific and load-bearing: frame tables key region
    // identity, so a single pre-initialization null read permanently splits the frame.
    // JVM (<clinit>) and JS/wasm (eager module init for bare fields) are safe as-is —
    // wrapping in a property must NOT happen there, because the JS lowering initializes
    // top-level *properties* lazily per file and would reintroduce the null window.
    // Kotlin/Native is the inverse: bare fields never initialize (lazy file init skips
    // them), so there the field needs a property carrying @EagerInitialization.
    val eagerInitialization = pluginContext.referenceClass(EAGER_INITIALIZATION_ID)
    val field = pluginContext.irFactory.buildField {
        this.name = name
        this.type = type
        isFinal = true
        isStatic = true
        visibility = DescriptorVisibilities.PRIVATE
        origin = if (eagerInitialization != null) {
            IrDeclarationOrigin.PROPERTY_BACKING_FIELD
        } else {
            IrDeclarationOrigin.DEFINED
        }
    }.apply {
        parent = file
    }
    val fieldBuilder = DeclarationIrBuilder(pluginContext, field.symbol)
    field.initializer = pluginContext.irFactory.createExpressionBody(
        file.startOffset,
        file.endOffset,
        initializer(fieldBuilder),
    )
    if (eagerInitialization == null) {
        file.declarations += field
        return field
    }
    val property = pluginContext.irFactory.buildProperty {
        this.name = name
        visibility = DescriptorVisibilities.PRIVATE
        modality = Modality.FINAL
        origin = IrDeclarationOrigin.DEFINED
    }.apply {
        parent = file
    }
    field.correspondingPropertySymbol = property.symbol
    property.backingField = field
    property.annotations += IrAnnotationImpl.fromSymbolOwner(
        eagerInitialization.owner.defaultType,
        eagerInitialization.constructors.first(),
    )
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
