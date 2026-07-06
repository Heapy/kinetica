// referenceClass/referenceFunctions are deprecated in favor of the finder API;
// migrating the symbol resolvers is tracked separately.
@file:Suppress("DEPRECATION")

package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstKind
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetField
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class KineticaTemplateSymbols private constructor(
    val emit: IrSimpleFunctionSymbol,
    val templateNode: IrSimpleFunctionSymbol,
    val singleTextTemplateDefinition: IrSimpleFunctionSymbol,
    val listOf: IrSimpleFunctionSymbol,
    val propsOfFunctions: Collection<IrSimpleFunctionSymbol>,
    val propsOfFqName: FqName,
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
                propsOfFqName = FqName("io.heapy.kinetica.propsOf"),
                templateDefinitionType = templateDefinition.owner.defaultType,
            )
        }
    }
}

internal class KineticaTemplateTransformer(
    private val file: IrFile,
    private val pluginContext: IrPluginContext,
    private val symbols: KineticaTemplateSymbols,
    private val constPropsIndex: ConstPropsFieldIndex,
) : IrElementTransformerVoid() {
    private lateinit var builder: DeclarationIrBuilder
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
        return addStaticFileField(
            file = file,
            pluginContext = pluginContext,
            name = Name.identifier("kineticaTemplate\$$ordinal"),
            type = symbols.templateDefinitionType,
        ) { fieldBuilder ->
            fieldBuilder.irCall(symbols.singleTextTemplateDefinition).apply {
                val parameters = symbol.owner.parameters.associateBy { it.name.asString() }
                arguments[parameters.getValue("id").indexInParameters] =
                    fieldBuilder.irString("${file.fileEntry.name}#template#$ordinal")
                arguments[parameters.getValue("tag").indexInParameters] = fieldBuilder.irString(template.tag)
                arguments[parameters.getValue("props").indexInParameters] =
                    buildPropsOfCall(fieldBuilder, template.props)
            }
        }
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
            null -> emptyList()
            is IrCall -> constPropsOf(propsArgument, symbols.propsOfFqName) ?: return null
            is IrGetField -> constPropsIndex.constPropsFor(propsArgument) ?: return null
            else -> return null
        }
        if (argumentOf("frameProps") != null) return null
        val semantics = argumentOf("semantics")
        if (semantics != null && !semantics.isNullConst()) return null
        val key = argumentOf("key")
        if (key != null && !key.isNullConst()) return null
        val content = argumentOf("content") as? IrFunctionExpression ?: return null
        val textCall = content.singleStatementCall() ?: return null
        if (textCall.symbol.owner.kotlinFqName != TEXT_FQ) return null
        val strikethrough = textCall.argumentOf("strikethrough")
        if (strikethrough != null && !strikethrough.isFalseConst()) return null
        val textSemantics = textCall.argumentOf("semantics") ?: return null
        if (!textSemantics.isNullConst()) return null
        val value = textCall.argumentOf("value") ?: return null
        if (value is IrConst && value.kind == IrConstKind.String) return null
        if (content.referencesLambdaLocalSymbol(value)) return null
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

    private fun IrFunctionExpression.referencesLambdaLocalSymbol(value: IrExpression): Boolean {
        val forbidden = mutableSetOf<IrValueSymbol>()
        function.parameters.forEach { parameter ->
            forbidden += parameter.symbol
        }
        function.body?.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (element !== value) {
                    element.acceptChildrenVoid(this)
                }
            }

            override fun visitVariable(declaration: IrVariable) {
                forbidden += declaration.symbol
                super.visitVariable(declaration)
            }
        })
        var found = false
        value.acceptVoid(object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (!found) {
                    element.acceptChildrenVoid(this)
                }
            }

            override fun visitGetValue(expression: IrGetValue) {
                if (expression.symbol in forbidden) {
                    found = true
                }
                super.visitGetValue(expression)
            }
        })
        return found
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
