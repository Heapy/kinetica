package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * K2 allocation transforms, applied inside @UiComponent receiver-style components (and their
 * nested content lambdas):
 *
 * 1. const-props interning — `propsOf("class", "col-id")` with all-constant arguments becomes
 *    a private file-level val, allocated once instead of per node per render;
 * 2. leaf static-host hoisting — `host("td", props = propsOf(...))` with constant tag,
 *    constant (or absent) props and no children/key/semantics/frameProps becomes
 *    `emit(<file-level HostNode val>)`. Node is an immutable value, so one instance is shared
 *    across every callsite, row and render — and reference-equal nodes ride the renderer's
 *    `===` diff fast path.
 *
 * Hoists are deduplicated structurally per file; anything not provably constant is left
 * untouched (invariant 3 of compiler-perf-design.md).
 */
internal class KineticaHoistSymbols private constructor(
    val emit: IrSimpleFunctionSymbol,
    val hostFqName: FqName,
    val propsOfFqName: FqName,
    val emptyList: IrSimpleFunctionSymbol,
    val hostNodeConstructor: IrConstructorSymbol,
    val hostNodeType: IrType,
    val nodeType: IrType,
    val stringMapType: IrType,
) {
    companion object {
        fun resolve(pluginContext: IrPluginContext): KineticaHoistSymbols? {
            val pkg = FqName("io.heapy.kinetica")
            val scopeId = ClassId(pkg, Name.identifier("ComponentScope"))
            val emit = pluginContext
                .referenceFunctions(CallableId(scopeId, Name.identifier("emit")))
                .firstOrNull() ?: return null
            val hostNode = pluginContext
                .referenceClass(ClassId(pkg, Name.identifier("HostNode"))) ?: return null
            val node = pluginContext
                .referenceClass(ClassId(pkg, Name.identifier("Node"))) ?: return null
            val emptyList = pluginContext
                .referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("emptyList")))
                .firstOrNull() ?: return null
            val hostNodeConstructorParameters = setOf("tag", "props", "children", "key", "semantics", "flags")
            val constructor = hostNode.constructors.firstOrNull { symbol ->
                val parameterNames = symbol.owner.parameters
                    .filter { parameter -> parameter.kind == IrParameterKind.Regular }
                    .map { parameter -> parameter.name.asString() }
                    .toSet()
                parameterNames == hostNodeConstructorParameters
            } ?: return null
            val string = pluginContext.irBuiltIns.stringType
            val mapClass = pluginContext.irBuiltIns.mapClass
            return KineticaHoistSymbols(
                emit = emit,
                hostFqName = FqName("io.heapy.kinetica.host"),
                propsOfFqName = FqName("io.heapy.kinetica.propsOf"),
                emptyList = emptyList,
                hostNodeConstructor = constructor,
                hostNodeType = hostNode.owner.defaultType,
                nodeType = node.owner.defaultType,
                stringMapType = mapClass.typeWith(string, string),
            )
        }
    }
}

internal class KineticaHoistTransformer(
    private val file: IrFile,
    private val pluginContext: IrPluginContext,
    private val symbols: KineticaHoistSymbols,
) : IrElementTransformerVoid() {
    private val internedProps = mutableMapOf<String, IrField>()
    private val hoistedHosts = mutableMapOf<String, IrField>()
    private lateinit var builder: DeclarationIrBuilder
    var propsInterned: Int = 0
        private set
    var hostsHoisted: Int = 0
        private set

    fun transform(function: IrSimpleFunction) {
        builder = DeclarationIrBuilder(pluginContext, function.symbol)
        function.body?.transformChildrenVoid(this)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        val calleeFqName = expression.symbol.owner.kotlinFqName
        if (calleeFqName == symbols.propsOfFqName) {
            val pairs = expression.constPropsPairs() ?: return expression
            if (pairs.isEmpty()) return expression
            propsInterned++
            return builder.irGetField(null, internProps(pairs))
        }
        if (calleeFqName == symbols.hostFqName) {
            val hoisted = tryHoistLeafHost(expression) ?: return expression
            hostsHoisted++
            return hoisted
        }
        return expression
    }

    /** All value arguments are string constants (absent trailing defaults included). */
    private fun IrCall.constPropsPairs(): List<Pair<String, String>>? {
        val values = mutableListOf<String>()
        for (parameter in symbol.owner.parameters) {
            if (parameter.kind != IrParameterKind.Regular) continue
            val argument = arguments[parameter.indexInParameters] ?: continue
            val const = argument as? IrConst ?: return null
            if (const.kind != IrConstKind.String) return null
            values += const.value as String
        }
        if (values.size % 2 != 0) return null
        return values.chunked(2).map { (name, value) -> name to value }
    }

    private fun internProps(pairs: List<Pair<String, String>>): IrField {
        val key = pairs.joinToString("\u0000") { "${it.first}\u0000${it.second}" }
        return internedProps.getOrPut(key) {
            addFileField("kineticaProps", symbols.stringMapType) { fieldBuilder ->
                buildPropsOfCall(fieldBuilder, pairs)
            }
        }
    }

    private fun buildPropsOfCall(
        fieldBuilder: DeclarationIrBuilder,
        pairs: List<Pair<String, String>>,
    ): IrExpression {
        val arity = pairs.size * 2
        val target = pluginContext
            .referenceFunctions(CallableId(FqName("io.heapy.kinetica"), Name.identifier("propsOf")))
            .firstOrNull { symbol ->
                val regular = symbol.owner.parameters.filter { it.kind == IrParameterKind.Regular }
                regular.size == arity && regular.none { it.varargElementType != null }
            } ?: error("No propsOf overload of arity $arity")
        return fieldBuilder.irCall(target).apply {
            var index = 0
            pairs.forEach { (name, value) ->
                arguments[index++] = fieldBuilder.irString(name)
                arguments[index++] = fieldBuilder.irString(value)
            }
        }
    }

    /**
     * host(tag = const, props = interned-field | absent, frameProps/semantics/key absent or
     * null, content absent or an empty lambda) → emit(<hoisted HostNode>).
     */
    private fun tryHoistLeafHost(call: IrCall): IrExpression? {
        val callee = call.symbol.owner
        val regular = callee.parameters.filter { it.kind == IrParameterKind.Regular }
        val byName = regular.associateBy { it.name.asString() }

        fun argumentOf(name: String): IrExpression? =
            byName[name]?.let { call.arguments[it.indexInParameters] }

        val tag = argumentOf("tag") as? IrConst ?: return null
        if (tag.kind != IrConstKind.String) return null

        val propsField = when (val propsArgument = argumentOf("props")) {
            null -> null
            is IrGetField ->
                internedProps.values.firstOrNull { it.symbol == propsArgument.symbol } ?: return null
            else -> return null
        }

        if (argumentOf("frameProps") != null) return null
        val semantics = argumentOf("semantics")
        if (semantics != null && !semantics.isNullConst()) return null
        val key = argumentOf("key")
        if (key != null && !key.isNullConst()) return null
        val content = argumentOf("content")
        if (content != null && !content.isEmptyLambda()) return null

        val receiver = call.arguments[
            callee.parameters.first { it.kind == IrParameterKind.ExtensionReceiver }.indexInParameters,
        ] ?: return null

        val hoistKey = "${tag.value}\u0000${propsField?.name?.asString().orEmpty()}"
        val field = hoistedHosts.getOrPut(hoistKey) {
            addFileField("kineticaHost", symbols.hostNodeType) { fieldBuilder ->
                buildHostNode(fieldBuilder, tag.value as String, propsField)
            }
        }

        return builder.irCall(symbols.emit).apply {
            arguments[0] = receiver
            arguments[1] = builder.irGetField(null, field)
        }
    }

    private fun buildHostNode(
        fieldBuilder: DeclarationIrBuilder,
        tag: String,
        propsField: IrField?,
    ): IrExpression {
        val constructor = symbols.hostNodeConstructor
        return fieldBuilder.irCallConstructor(constructor, emptyList()).apply {
            constructor.owner.parameters
                .filter { it.kind == IrParameterKind.Regular }
                .forEach { parameter ->
                    when (parameter.name.asString()) {
                        "tag" -> arguments[parameter.indexInParameters] = fieldBuilder.irString(tag)
                        "props" -> propsField?.let {
                            arguments[parameter.indexInParameters] = fieldBuilder.irGetField(null, it)
                        }
                        "children" -> arguments[parameter.indexInParameters] =
                            fieldBuilder.irCall(symbols.emptyList).apply {
                                typeArguments[0] = symbols.nodeType
                            }
                        "key", "semantics" -> arguments[parameter.indexInParameters] =
                            fieldBuilder.irNull(parameter.type)
                    }
                }
        }
    }

    private fun addFileField(
        prefix: String,
        type: IrType,
        initializer: (DeclarationIrBuilder) -> IrExpression,
    ): IrField {
        val field = pluginContext.irFactory.buildField {
            // Package-unique, not just file-unique: JS klib signatures are package-scoped.
            name = Name.identifier("$prefix\$${file.fileUniqueTag()}\$${internedProps.size + hoistedHosts.size}")
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

    private fun IrExpression.isNullConst(): Boolean =
        this is IrConst && kind == IrConstKind.Null

    private fun IrExpression.isEmptyLambda(): Boolean =
        this is IrFunctionExpression && (function.body?.let { body ->
            body is org.jetbrains.kotlin.ir.expressions.IrBlockBody && body.statements.isEmpty()
        } ?: true)
}
