package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
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
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import java.io.File

internal class KineticaTemplateSymbols private constructor(
    val emit: IrSimpleFunctionSymbol,
    val templateNode: IrSimpleFunctionSymbol,
    val singleTextTemplateDefinition: IrSimpleFunctionSymbol,
    val listOf: IrSimpleFunctionSymbol,
    val propsOfFunctions: Collection<IrSimpleFunctionSymbol>,
    val templateDefinitionType: IrType,
) {
    companion object {
        fun resolve(pluginContext: IrPluginContext): KineticaTemplateSymbols? {
            val pkg = FqName("io.heapy.kinetica")
            val scopeId = ClassId(pkg, Name.identifier("ComponentScope"))
            val emit = pluginContext
                .referenceFunctions(CallableId(scopeId, Name.identifier("emit")))
                .firstOrNull() ?: return null
            val templateNode = pluginContext
                .referenceFunctions(CallableId(pkg, Name.identifier("templateNode")))
                .firstOrNull() ?: return null
            val singleTextTemplateDefinition = pluginContext
                .referenceFunctions(CallableId(pkg, Name.identifier("singleTextTemplateDefinition")))
                .firstOrNull() ?: return null
            val listOf = pluginContext
                .referenceFunctions(CallableId(FqName("kotlin.collections"), Name.identifier("listOf")))
                .firstOrNull { symbol ->
                    symbol.owner.parameters.singleOrNull { it.kind == IrParameterKind.Regular }
                        ?.varargElementType != null
                } ?: return null
            val templateDefinition = pluginContext
                .referenceClass(ClassId(pkg, Name.identifier("TemplateDefinition"))) ?: return null
            val propsOfFunctions = pluginContext
                .referenceFunctions(CallableId(pkg, Name.identifier("propsOf")))
                .toList()
            return KineticaTemplateSymbols(
                emit = emit,
                templateNode = templateNode,
                singleTextTemplateDefinition = singleTextTemplateDefinition,
                listOf = listOf,
                propsOfFunctions = propsOfFunctions,
                templateDefinitionType = templateDefinition.owner.defaultType,
            )
        }
    }
}

internal class KineticaTemplateTransformer(
    private val file: IrFile,
    private val pluginContext: IrPluginContext,
    private val symbols: KineticaTemplateSymbols,
) : IrElementTransformerVoid() {
    private lateinit var builder: DeclarationIrBuilder
    private val sourceText: String? by lazy {
        runCatching {
            File(file.fileEntry.name).takeIf { it.isFile }?.readText()
        }.getOrNull()
    }
    private var nextTemplateOrdinal = 0
    var templatesEmitted: Int = 0
        private set

    fun transform(function: IrSimpleFunction) {
        builder = DeclarationIrBuilder(pluginContext, function.symbol)
        function.body?.transformChildrenVoid(this)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        val template = expression.extractSingleTextTemplate() ?: return expression
        val receiver = expression.extensionReceiver() ?: return expression
        val field = addTemplateField(template)
        templatesEmitted++
        return builder.irCall(symbols.emit).apply {
            arguments[0] = receiver
            arguments[1] = builder.irCall(symbols.templateNode).apply {
                val parameters = symbol.owner.parameters.associateBy { it.name.asString() }
                arguments[parameters.getValue("definition").indexInParameters] = builder.irGetField(null, field)
                arguments[parameters.getValue("values").indexInParameters] = builder.irCall(symbols.listOf).apply {
                    val stringType = pluginContext.irBuiltIns.stringType.makeNullable()
                    typeArguments[0] = stringType
                    arguments[0] = builder.irVararg(
                        stringType,
                        listOf(template.textValue),
                    )
                }
                val keyParameter = parameters.getValue("key")
                arguments[keyParameter.indexInParameters] = builder.irNull(keyParameter.type)
            }
        }
    }

    private fun addTemplateField(template: SingleTextTemplate): IrField {
        val ordinal = nextTemplateOrdinal++
        val field = pluginContext.irFactory.buildField {
            name = Name.identifier("kineticaTemplate\$$ordinal")
            type = symbols.templateDefinitionType
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
            fieldBuilder.irCall(symbols.singleTextTemplateDefinition).apply {
                val parameters = symbol.owner.parameters.associateBy { it.name.asString() }
                arguments[parameters.getValue("id").indexInParameters] =
                    fieldBuilder.irString("${file.fileEntry.name}#template#$ordinal")
                arguments[parameters.getValue("tag").indexInParameters] = fieldBuilder.irString(template.tag)
                arguments[parameters.getValue("props").indexInParameters] =
                    buildPropsOfCall(fieldBuilder, template.props)
            },
        )
        file.declarations += field
        return field
    }

    private fun buildPropsOfCall(
        fieldBuilder: DeclarationIrBuilder,
        pairs: List<Pair<String, String>>,
    ): IrExpression {
        val arity = pairs.size * 2
        val target = symbols.propsOfFunctions.firstOrNull { symbol ->
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

    private fun IrCall.extractSingleTextTemplate(): SingleTextTemplate? {
        if (symbol.owner.kotlinFqName != HOST_FQ) return null
        val tag = argumentOf("tag") as? IrConst ?: return null
        if (tag.kind != IrConstKind.String) return null
        val props = when (val propsArgument = argumentOf("props")) {
            null -> return null
            is IrCall -> propsArgument.constPropsPairs() ?: return null
            else -> return null
        }
        if (argumentOf("frameProps") != null) return null
        val semantics = argumentOf("semantics")
        if (semantics != null && !semantics.isNullConst()) return null
        val key = argumentOf("key")
        if (key != null && !key.isNullConst()) return null
        val textCall = (argumentOf("content") as? IrFunctionExpression)?.singleStatementCall() ?: return null
        if (textCall.symbol.owner.kotlinFqName != TEXT_FQ) return null
        val strikethrough = textCall.argumentOf("strikethrough")
        if (strikethrough != null && !strikethrough.isFalseConst()) return null
        val textSemantics = textCall.argumentOf("semantics") ?: return null
        if (!textSemantics.isNullConst()) return null
        if (!textCall.hasExplicitNullArgument("semantics")) return null
        val value = textCall.argumentOf("value") ?: return null
        if (value is IrConst && value.kind == IrConstKind.String) return null
        return SingleTextTemplate(
            tag = tag.value as String,
            props = props,
            textValue = value,
        )
    }

    private fun IrFunctionExpression.singleStatementCall(): IrCall? {
        val body = function.body as? org.jetbrains.kotlin.ir.expressions.IrBlockBody ?: return null
        return body.statements.singleOrNull() as? IrCall
    }

    private fun IrCall.argumentOf(name: String): IrExpression? {
        val parameter = symbol.owner.parameters.firstOrNull {
            it.kind == IrParameterKind.Regular && it.name.asString() == name
        } ?: return null
        return arguments[parameter.indexInParameters]
    }

    private fun IrCall.extensionReceiver(): IrExpression? {
        val parameter = symbol.owner.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }
            ?: return null
        return arguments[parameter.indexInParameters]
    }

    private fun IrCall.hasExplicitNullArgument(name: String): Boolean {
        val source = sourceText ?: return false
        val start = startOffset.coerceAtLeast(0)
        val end = endOffset.coerceAtMost(source.length)
        if (start >= end) return false
        return Regex("""\b${Regex.escape(name)}\s*=\s*null\b""")
            .containsMatchIn(source.substring(start, end))
    }

    private fun IrCall.constPropsPairs(): List<Pair<String, String>>? {
        val values = mutableListOf<String>()
        for (parameter in symbol.owner.parameters) {
            if (parameter.kind != IrParameterKind.Regular) continue
            val argument = arguments[parameter.indexInParameters] ?: continue
            val const = argument as? IrConst ?: return null
            if (const.kind != IrConstKind.String) return null
            values += const.value as String
        }
        if (values.isEmpty() || values.size % 2 != 0) return null
        return values.chunked(2).map { (name, value) -> name to value }
    }

    private fun IrExpression.isNullConst(): Boolean =
        this is IrConst && kind == IrConstKind.Null

    private fun IrExpression.isFalseConst(): Boolean =
        this is IrConst && kind == IrConstKind.Boolean && value == false

    private data class SingleTextTemplate(
        val tag: String,
        val props: List<Pair<String, String>>,
        val textValue: IrExpression,
    )

    private companion object {
        val HOST_FQ: FqName = FqName("io.heapy.kinetica.host")
        val TEXT_FQ: FqName = FqName("io.heapy.kinetica.text")
    }
}
