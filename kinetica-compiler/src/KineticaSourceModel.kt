package io.heapy.kinetica.compiler

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.extensions.ProcessSourcesBeforeCompilingExtension
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

public data class KineticaCompilerPluginConfiguration(
    val moduleId: String,
    val serverSourceSet: String,
    val clientSourceSet: String,
) {
    public companion object {
        public fun from(configuration: CompilerConfiguration): KineticaCompilerPluginConfiguration =
            KineticaCompilerPluginConfiguration(
                moduleId = configuration.get(KineticaConfigurationKeys.moduleId) ?: "main",
                serverSourceSet = configuration.get(KineticaConfigurationKeys.serverSourceSet) ?: "server",
                clientSourceSet = configuration.get(KineticaConfigurationKeys.clientSourceSet) ?: "client",
            )
    }
}

public data class KineticaSourceFile(
    val path: String,
    val packageName: String,
    val text: String,
)

public data class KineticaSourceModel(
    val components: List<ComponentDeclaration> = emptyList(),
    val routes: List<RouteDeclaration> = emptyList(),
    val serverActions: List<ServerActionDeclaration> = emptyList(),
    val diagnostics: List<CompilerDiagnostic> = emptyList(),
)

public class KineticaSourceModelExtractor(
    private val configuration: KineticaCompilerPluginConfiguration,
) {
    public fun extract(files: Collection<KineticaSourceFile>): List<ComponentDeclaration> =
        extractModel(files).components

    public fun extractModel(files: Collection<KineticaSourceFile>): KineticaSourceModel {
        val discovered = files.flatMap { file -> file.extractFunctions() }
        val bySimpleName = discovered
            .groupBy { declaration -> declaration.fqName.substringAfterLast('.') }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single().fqName }

        val components = discovered.map { declaration ->
            declaration.copy(
                calls = declaration.calls.mapNotNull { call ->
                    val fqName = bySimpleName[call.calleeFqName] ?: return@mapNotNull null
                    ComponentCall(calleeFqName = fqName, location = call.location)
                },
            )
        }

        return KineticaSourceModel(
            components = components,
            routes = files.flatMap { file -> file.extractRoutes() },
            serverActions = files.flatMap { file -> file.extractServerActions() },
            diagnostics = files.flatMap { file -> file.extractSourceDiagnostics() },
        )
    }

    private fun KineticaSourceFile.extractFunctions(): List<ComponentDeclaration> {
        val declarations = mutableListOf<ComponentDeclaration>()
        FUNCTION.findAll(text).forEach { match ->
            val annotationsText = match.groups["annotations"]?.value.orEmpty()
            val signature = extractFunctionSignature(text, match) ?: return@forEach
            val annotations = annotationsText.toComponentAnnotations()
            if (annotations.isEmpty()) {
                return@forEach
            }
            val declarationOffset = match.annotationOffset()
            val body = extractBody(text, signature.bodySearchStart)
            declarations += ComponentDeclaration(
                fqName = fqName(signature.name),
                sourceSet = sourceSet(),
                annotations = annotations,
                parameters = signature.parameters.extractComponentParameters(packageName),
                slots = body.text.extractSlots(),
                calls = extractCalls(body),
                staticNodes = extractStaticNodes(body),
                previewName = annotationsText.extractPreviewName(),
                serializablePropsType = signature.parameters.serializablePropsType(signature.name, packageName),
                location = sourceLocation(declarationOffset),
            )
        }
        return declarations
    }

    private fun KineticaSourceFile.extractRoutes(): List<RouteDeclaration> =
        ROUTE.findAll(text)
            .mapNotNull { match ->
                val supertypes = match.groups["supertypes"]?.value.orEmpty()
                if (!supertypes.extendsRoute()) {
                    return@mapNotNull null
                }
                val annotationsText = match.groups["annotations"]?.value.orEmpty()
                val name = match.groups["name"]?.value ?: return@mapNotNull null

                RouteDeclaration(
                    fqName = fqName(name),
                    serializable = annotationsText.hasAnnotation("Serializable"),
                    location = sourceLocation(match.annotationOffset()),
                )
            }
            .distinctBy { route -> route.fqName }
            .toList()

    private fun KineticaSourceFile.extractServerActions(): List<ServerActionDeclaration> =
        FUNCTION.findAll(text)
            .mapNotNull { match ->
                val annotationsText = match.groups["annotations"]?.value.orEmpty()
                if (!annotationsText.hasAnnotation("ServerAction")) {
                    return@mapNotNull null
                }
                val signature = extractFunctionSignature(text, match) ?: return@mapNotNull null
                val returnType = signature.returnType ?: "kotlin.Unit"

                ServerActionDeclaration(
                    functionFqName = fqName(signature.name),
                    inputType = signature.parameters.actionInputType(signature.name, packageName),
                    outputType = returnType.qualifyTypeExpression(packageName),
                    invalidates = annotationsText.extractServerActionInvalidates(),
                    location = sourceLocation(match.annotationOffset()),
                )
            }
            .distinctBy { action -> action.functionFqName }
            .toList()

    private fun KineticaSourceFile.extractSourceDiagnostics(): List<CompilerDiagnostic> {
        val diagnostics = mutableListOf<CompilerDiagnostic>()
        FUNCTION.findAll(text).forEach { match ->
            val annotationsText = match.groups["annotations"]?.value.orEmpty()
            if (annotationsText.toComponentAnnotations().isEmpty()) {
                return@forEach
            }
            val signature = extractFunctionSignature(text, match) ?: return@forEach
            val declarationFqName = fqName(signature.name)
            val body = extractBody(text, signature.bodySearchStart)
            val renderBody = body.text.maskAllowedPhaseBlocks()

            REF_READ.findAll(renderBody).forEach { refMatch ->
                diagnostics += CompilerDiagnostic(
                    severity = DiagnosticSeverity.Error,
                    code = "KINETICA_REF_READ_IN_RENDER",
                    message = "Ref.current can only be read from layoutEffect or effect/event phases.",
                    declarationFqName = declarationFqName,
                    location = sourceLocation(body.contentStart + refMatch.range.first),
                )
            }

            RENDER_SIDE_EFFECT.findAll(renderBody).forEach { sideEffectMatch ->
                diagnostics += CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_RENDER_SIDE_EFFECT",
                    message = "Render-phase side effects should be moved to event, watch, launchEffect, or layoutEffect.",
                    declarationFqName = declarationFqName,
                    location = sourceLocation(body.contentStart + sideEffectMatch.range.first),
                )
            }

            WATCH_SOURCE_PEEK.findAll(body.text).forEach { watchMatch ->
                diagnostics += CompilerDiagnostic(
                    severity = DiagnosticSeverity.Warning,
                    code = "KINETICA_WATCH_SOURCE_UNTRACKED_READ",
                    message = "watch source expressions should not use peek; untracked reads make restart causes invisible.",
                    declarationFqName = declarationFqName,
                    location = sourceLocation(body.contentStart + watchMatch.range.first),
                )
            }
        }
        return diagnostics
    }

    private fun MatchResult.annotationOffset(): Int {
        val annotationsGroup = groups["annotations"] ?: return range.first
        val firstAnnotation = annotationsGroup.value.indexOf('@')
        if (firstAnnotation >= 0) {
            return annotationsGroup.range.first + firstAnnotation
        }
        val firstCode = value.indexOfFirst { char -> !char.isWhitespace() }
        return if (firstCode >= 0) range.first + firstCode else range.first
    }

    private fun KineticaSourceFile.sourceSet(): ComponentSourceSet =
        when {
            path.contains("/${configuration.serverSourceSet}/") -> ComponentSourceSet.Server
            path.contains("/${configuration.clientSourceSet}/") -> ComponentSourceSet.Client
            else -> ComponentSourceSet.Common
        }

    private fun KineticaSourceFile.fqName(name: String): String =
        if (packageName.isBlank()) name else "$packageName.$name"

    private fun String.toComponentAnnotations(): Set<ComponentAnnotation> =
        ANNOTATION.findAll(this)
            .mapNotNull { match ->
                when (match.groups["name"]?.value?.substringAfterLast('.')) {
                    "UiComponent" -> ComponentAnnotation.UiComponent
                    "ServerComponent" -> ComponentAnnotation.ServerComponent
                    "ClientComponent" -> ComponentAnnotation.ClientComponent
                    "Preview" -> ComponentAnnotation.Preview
                    else -> null
                }
            }
            .toSet()

    private fun String.hasAnnotation(simpleName: String): Boolean =
        ANNOTATION.findAll(this)
            .any { match -> match.groups["name"]?.value?.substringAfterLast('.') == simpleName }

    private fun String.extractServerActionInvalidates(): List<String> {
        val match = SERVER_ACTION.find(this) ?: return emptyList()
        return STRING_LITERAL.findAll(match.groups["arguments"]?.value.orEmpty())
            .mapNotNull { literal -> literal.groups["value"]?.value }
            .toList()
    }

    private fun String.extendsRoute(): Boolean =
        split(',')
            .map { supertype -> supertype.trim().substringBefore('<').substringAfterLast('.') }
            .any { supertype -> supertype == "Route" }

    private fun String.maskAllowedPhaseBlocks(): String {
        val masked = toCharArray()
        ALLOWED_PHASE_BLOCK.findAll(this).forEach { match ->
            val openingBrace = lastIndexOf('{', startIndex = match.range.last)
            if (openingBrace < 0) {
                return@forEach
            }
            val closingBrace = findMatchingBrace(openingBrace) ?: return@forEach
            for (index in openingBrace..closingBrace) {
                masked[index] = ' '
            }
        }
        return String(masked)
    }

    private fun String.findMatchingBrace(openingBrace: Int): Int? {
        var depth = 0
        for (index in openingBrace until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return index
                    }
                }
            }
        }
        return null
    }

    private fun String.extractPreviewName(): String? {
        val match = PREVIEW.find(this) ?: return null
        return match.groups["named"]?.value ?: match.groups["positional"]?.value
    }

    private fun String.extractSlots(): List<SlotDeclaration> =
        SLOT.findAll(this).map { match ->
            val variableName = match.groups["name"]?.value.orEmpty()
            val call = match.groups["call"]?.value.orEmpty()
            val args = match.groups["args"]?.value.orEmpty()
            SlotDeclaration(
                variableName = variableName,
                persistent = args.contains("persistent = true") || call == "persistentState",
                transient = args.contains("transient = true"),
                disambiguator = variableName,
            )
        }.toList()

    private fun KineticaSourceFile.extractCalls(body: ExtractedBody): List<ComponentCall> =
        CALL.findAll(body.text)
            .mapNotNull { match ->
                val name = match.groups["name"]?.value ?: return@mapNotNull null
                if (name in NON_COMPONENT_CALLS) {
                    null
                } else {
                    ComponentCall(
                        calleeFqName = name,
                        location = sourceLocation(body.contentStart + match.range.first),
                    )
                }
            }
            .distinctBy { it.calleeFqName }
            .toList()

    private fun KineticaSourceFile.extractStaticNodes(body: ExtractedBody): List<StaticNodeDeclaration> =
        STATIC_TEXT.findAll(body.text)
            .mapNotNull { match ->
                val sourceValue = match.groups["value"]?.value ?: return@mapNotNull null
                if (sourceValue.containsUnescapedTemplateMarker()) {
                    return@mapNotNull null
                }
                val value = sourceValue.decodeKotlinStringContent()
                StaticNodeDeclaration(
                    nodeSource = "TextNode(value = ${value.kotlinStringLiteral()})",
                    location = sourceLocation(body.contentStart + match.range.first),
                )
            }
            .toList()

    private fun String.serializablePropsType(functionName: String, packageName: String): String =
        if (isBlank()) {
            "kotlin.Unit"
        } else {
            listOf(packageName, functionName, "Props")
                .filter { part -> part.isNotBlank() }
                .joinToString(separator = ".")
        }

    private fun String.extractComponentParameters(packageName: String): List<ComponentParameterDeclaration> =
        splitTopLevelCommas()
            .map { parameter -> parameter.trim() }
            .filter { parameter -> parameter.isNotBlank() }
            .mapNotNull { parameter ->
                val name = parameter.substringBefore(':').trim().substringAfterLast(' ')
                val type = parameter.substringAfter(':', missingDelimiterValue = "").substringBefore('=').trim()
                if (name.isBlank() || type.isBlank()) {
                    return@mapNotNull null
                }
                ComponentParameterDeclaration(
                    name = name,
                    type = type.qualifyTypeExpression(packageName),
                )
            }

    private fun String.actionInputType(
        functionName: String,
        packageName: String,
    ): String {
        if (isBlank()) {
            return "kotlin.Unit"
        }
        val parameters = splitTopLevelCommas()
            .map { parameter -> parameter.trim() }
            .filter { parameter -> parameter.isNotBlank() }
        if (parameters.size != 1) {
            return "$packageName.$functionName.Input"
        }
        return parameters.single()
            .substringAfter(':', missingDelimiterValue = "$packageName.$functionName.Input")
            .substringBefore('=')
            .trim()
            .qualifyTypeExpression(packageName)
    }

    private fun String.qualifyTypeExpression(packageName: String): String {
        val source = trim()
        if (source.isBlank()) {
            return "kotlin.Unit"
        }
        return TYPE_IDENTIFIER.replace(source) { match ->
            val token = match.value
            val indexBefore = match.range.first - 1
            val indexAfter = match.range.last + 1
            val isAlreadyQualified =
                source.getOrNull(indexBefore) == '.' || source.getOrNull(indexAfter) == '.'
            when {
                isAlreadyQualified -> token
                token in TYPE_KEYWORDS -> token
                token in DEFAULT_TYPE_NAMES -> DEFAULT_TYPE_NAMES.getValue(token)
                token.first().isUpperCase() && packageName.isNotBlank() -> "$packageName.$token"
                else -> token
            }
        }
    }

    private fun String.kotlinStringLiteral(): String =
        buildString {
            append('"')
            this@kotlinStringLiteral.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '$' -> {
                        append('\\')
                        append('$')
                    }
                    else -> append(character)
                }
            }
            append('"')
        }

    private fun String.splitTopLevelCommas(): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var angleDepth = 0
        var parenDepth = 0
        var bracketDepth = 0
        forEachIndexed { index, character ->
            when (character) {
                '<' -> angleDepth += 1
                '>' -> if (angleDepth > 0) angleDepth -= 1
                '(' -> parenDepth += 1
                ')' -> if (parenDepth > 0) parenDepth -= 1
                '[' -> bracketDepth += 1
                ']' -> if (bracketDepth > 0) bracketDepth -= 1
                ',' -> if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                    parts += substring(start, index)
                    start = index + 1
                }
            }
        }
        parts += substring(start)
        return parts
    }

    private fun KineticaSourceFile.sourceLocation(offset: Int): String {
        val position = text.lineAndColumnAt(offset)
        return "$path:${position.line}:${position.column}"
    }

    private fun String.lineAndColumnAt(offset: Int): SourcePosition {
        var line = 1
        var column = 1
        val boundedOffset = offset.coerceIn(0, length)
        for (index in 0 until boundedOffset) {
            if (this[index] == '\n') {
                line += 1
                column = 1
            } else {
                column += 1
            }
        }
        return SourcePosition(line, column)
    }

    private fun extractBody(source: String, start: Int): ExtractedBody {
        val openingBrace = source.indexOf('{', start)
        if (openingBrace < 0) {
            return ExtractedBody(source.substring(start), start)
        }
        val contentStart = openingBrace + 1
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return ExtractedBody(source.substring(contentStart, index), contentStart)
                    }
                }
            }
        }
        return ExtractedBody(source.substring(contentStart), contentStart)
    }

    private fun extractFunctionSignature(
        source: String,
        match: MatchResult,
    ): FunctionSignature? {
        val name = match.groups["name"]?.value ?: return null
        val parametersStart = match.range.last
        val parametersEnd = source.findMatchingDelimiter(parametersStart, opening = '(', closing = ')') ?: return null
        return FunctionSignature(
            name = name,
            parameters = source.substring(parametersStart + 1, parametersEnd),
            returnType = source.extractReturnType(parametersEnd + 1),
            bodySearchStart = parametersEnd + 1,
        )
    }

    private fun String.extractReturnType(start: Int): String? {
        var index = start
        while (index < length && this[index].isWhitespace()) {
            index += 1
        }
        if (index >= length || this[index] != ':') {
            return null
        }
        index += 1
        val typeStart = index
        while (index < length && this[index] != '{' && this[index] != '=' && this[index] != '\n') {
            index += 1
        }
        return substring(typeStart, index).trim().takeIf { it.isNotBlank() }
    }

    private fun String.findMatchingDelimiter(
        openingIndex: Int,
        opening: Char,
        closing: Char,
    ): Int? {
        var depth = 0
        for (index in openingIndex until length) {
            when (this[index]) {
                opening -> depth += 1
                closing -> {
                    depth -= 1
                    if (depth == 0) {
                        return index
                    }
                }
            }
        }
        return null
    }

    private companion object {
        private val FUNCTION = Regex(
            pattern = """(?<annotations>(?:\s*@[\w.]+(?:\([^)]*\))?\s*)+)(?:suspend\s+)?fun\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(""",
            options = setOf(RegexOption.MULTILINE),
        )
        private val ROUTE = Regex(
            pattern = """(?<annotations>(?:\s*@[\w.]+(?:\([^)]*\))?\s*)*)\b(?:(?:public|internal|private)\s+)?(?:(?:sealed|data)\s+)?(?:interface|class|object)\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)(?:\s*\([^)]*\))?\s*(?::\s*(?<supertypes>[^{\n=]+))?""",
            options = setOf(RegexOption.MULTILINE),
        )
        private val ANNOTATION = Regex("""@(?<name>[\w.]+)""")
        private val SERVER_ACTION = Regex("""@(?:[\w.]+\.)?ServerAction\s*(?:\((?<arguments>[^)]*)\))?""")
        private val STRING_LITERAL = Regex(""""(?<value>[^"]*)"""")
        private val PREVIEW = Regex("""@(?:[\w.]+\.)?Preview\s*\((?:\s*name\s*=\s*"(?<named>[^"]*)"|\s*"(?<positional>[^"]*)")?""")
        private val SLOT = Regex("""(?:var|val)\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)\s+(?:by|=)\s+(?<call>state|persistentState)\s*(?:\((?<args>[^)]*)\))?\s*\{""")
        private val CALL = Regex("""\b(?<name>[A-Z][A-Za-z0-9_]*)\s*\(""")
        private val STATIC_TEXT = Regex("""\btext\s*\(\s*"(?<value>(?:\\.|[^"\\])*)"\s*(?:,\s*strikethrough\s*=\s*false\s*)?\)""")
        private val REF_READ = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\.current\b""")
        private val RENDER_SIDE_EFFECT = Regex("""\b(?:print|println)\s*\(""")
        private val WATCH_SOURCE_PEEK = Regex(
            pattern = """\bwatch\s*\(\s*\{[^}]*\bpeek\s*\{""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val TYPE_IDENTIFIER = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\b""")
        private val TYPE_KEYWORDS = setOf("in", "out")
        private val DEFAULT_TYPE_NAMES = mapOf(
            "Any" to "kotlin.Any",
            "Boolean" to "kotlin.Boolean",
            "Byte" to "kotlin.Byte",
            "Char" to "kotlin.Char",
            "CharSequence" to "kotlin.CharSequence",
            "Double" to "kotlin.Double",
            "Float" to "kotlin.Float",
            "Int" to "kotlin.Int",
            "Long" to "kotlin.Long",
            "Nothing" to "kotlin.Nothing",
            "Short" to "kotlin.Short",
            "String" to "kotlin.String",
            "Unit" to "kotlin.Unit",
            "List" to "kotlin.collections.List",
            "Map" to "kotlin.collections.Map",
            "Set" to "kotlin.collections.Set",
        )
        private val ALLOWED_PHASE_BLOCK = Regex(
            pattern = """\b(?:event|watch|launchEffect|layoutEffect)(?:<[^>]+>)?\s*(?:\([^)]*\)\s*)?\{""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val NON_COMPONENT_CALLS = setOf(
            "SlotId",
            "Semantics",
            "TextNode",
            "HostNode",
            "FragmentNode",
            "ClientRef",
            "JsonObject",
            "JsonPrimitive",
        )
    }

    private data class ExtractedBody(
        val text: String,
        val contentStart: Int,
    )

    private data class FunctionSignature(
        val name: String,
        val parameters: String,
        val returnType: String?,
        val bodySearchStart: Int,
    )

    private data class SourcePosition(
        val line: Int,
        val column: Int,
    )
}

public data class KineticaSourceTransformation(
    val path: String,
    val text: String,
    val changed: Boolean,
)

public class KineticaComponentSourceTransformer(
    private val moduleId: String,
) {
    public fun transform(
        file: KineticaSourceFile,
        components: Collection<ComponentDeclaration>,
    ): KineticaSourceTransformation {
        val declarations = components.associateBy { declaration -> declaration.fqName }
        val componentSimpleNames = components
            .filter { declaration -> declaration.isUiComponentLikeForTransform() }
            .mapTo(mutableSetOf()) { declaration -> declaration.fqName.substringAfterLast('.') }
        val replacements = mutableListOf<Replacement>()

        FUNCTION.findAll(file.text).forEach { match ->
            val annotationsText = match.groups["annotations"]?.value.orEmpty()
            if (!annotationsText.hasComponentAnnotation()) {
                return@forEach
            }
            val parsed = file.text.parseFunction(match) ?: return@forEach
            val declaration = declarations[file.fqName(parsed.name)] ?: return@forEach
            val body = file.text.substring(parsed.bodyStart, parsed.bodyEnd)
            replacements += Replacement(
                start = parsed.annotationStart,
                endExclusive = parsed.bodyEnd + 1,
                text = parsed.toTransformedSource(
                    body = body,
                    declaration = declaration,
                    componentSimpleNames = componentSimpleNames,
                ),
            )
        }

        if (replacements.isEmpty()) {
            return KineticaSourceTransformation(file.path, file.text, changed = false)
        }

        val transformed = buildString {
            var cursor = 0
            replacements.sortedBy { it.start }.forEach { replacement ->
                append(file.text.substring(cursor, replacement.start))
                append(replacement.text)
                cursor = replacement.endExclusive
            }
            append(file.text.substring(cursor))
        }

        return KineticaSourceTransformation(file.path, transformed, changed = transformed != file.text)
    }

    private fun FunctionToTransform.toTransformedSource(
        body: String,
        declaration: ComponentDeclaration,
        componentSimpleNames: Set<String>,
    ): String {
        val skippable = declaration.isSkippableForTransform()
        val bodyIndent = if (skippable) "            " else "        "
        val transformedBody = body
            .trimBlankEdges()
            .injectSlotIds(declaration)
            .hoistStaticTextNodes(declaration)
            .emitStandaloneComponentCalls(componentSimpleNames)
            .prependIndent(bodyIndent)
        val suspendPrefix = if (isSuspend) "suspend " else ""
        val renderCall = if (isSuspend) "renderSuspendNode" else "renderNode"
        if (skippable) {
            val skipCall = if (isSuspend) "skippableSuspendNode" else "skippableNode"
            return """
                $annotations
                ${suspendPrefix}fun io.heapy.kinetica.ComponentScope.$name($parameters): io.heapy.kinetica.Node =
                    $skipCall(
                        componentId = ${declaration.fqName.kotlinStringLiteral()},
                        inputs = ${declaration.skipInputsSource()},
                    ) {
                        $renderCall {
                $transformedBody
                        }
                    }
            """.trimIndent()
        }
        return """
            $annotations
            ${suspendPrefix}fun io.heapy.kinetica.ComponentScope.$name($parameters): io.heapy.kinetica.Node =
                $renderCall {
            $transformedBody
                }
        """.trimIndent()
    }

    private fun String.injectSlotIds(declaration: ComponentDeclaration): String =
        STATE_CALL.replace(this) { match ->
            val variableName = match.groups["name"]?.value ?: return@replace match.value
            val slotIndex = declaration.slots.indexOfFirst { slot -> slot.variableName == variableName }
            if (slotIndex < 0) {
                return@replace match.value
            }
            val call = match.groups["call"]?.value ?: return@replace match.value
            val opening = match.groups["opening"]?.value ?: return@replace match.value
            var hasArguments = false
            var hasRestoredValueArgument = false
            if (opening == "(") {
                val argsEnd = findMatchingDelimiter(match.range.last, opening = '(', closing = ')')
                    ?: return@replace match.value
                val args = substring(match.range.last + 1, argsEnd)
                if (SLOT_ID_ARGUMENT in args) {
                    return@replace match.value
                }
                hasArguments = args.isNotBlank()
                hasRestoredValueArgument = RESTORED_VALUE_ARGUMENT in args
            }
            val slot = declaration.slots[slotIndex]
            val slotId = slot.toSlotIdSource(declaration.fqName, slotIndex)
            val prefix = match.groups["prefix"]?.value.orEmpty()
            if (call == "persistentState") {
                val insertedArguments = listOfNotNull(
                    "slotId = $slotId",
                    "restoredValue = null".takeUnless { hasRestoredValueArgument },
                ).joinToString(separator = ", ")
                return@replace if (opening == "(") {
                    val separator = if (hasArguments) ", " else ""
                    "${prefix}persistentState($insertedArguments$separator"
                } else {
                    "${prefix}persistentState($insertedArguments) {"
                }
            }
            if (opening == "(") {
                val separator = if (hasArguments) ", " else ""
                "${prefix}state(slotId = $slotId$separator"
            } else {
                "${prefix}state(slotId = $slotId) {"
            }
        }

    private fun String.hoistStaticTextNodes(declaration: ComponentDeclaration): String {
        var hoistIndex = 0
        return lines().joinToString(separator = "\n") { line ->
            val match = STANDALONE_STATIC_TEXT.matchEntire(line) ?: return@joinToString line
            val value = match.groups["value"]?.value.orEmpty()
            if (value.containsUnescapedTemplateMarker()) {
                return@joinToString line
            }
            val staticNode = declaration.staticNodes.getOrNull(hoistIndex++) ?: return@joinToString line
            val indent = match.groups["indent"]?.value.orEmpty()
            val comment = match.groups["comment"]?.value?.let { " $it" }.orEmpty()
            val hoistId = declaration.staticHoistId(hoistIndex - 1)
            "$indent" +
                "emit(staticNode(${hoistId.kotlinStringLiteral()}) { ${staticNode.nodeSource.fullyQualifyNodeSource()} })" +
                comment
        }
    }

    private fun String.emitStandaloneComponentCalls(componentSimpleNames: Set<String>): String =
        lines().joinToString(separator = "\n") { line ->
            val match = STANDALONE_COMPONENT_CALL.matchEntire(line) ?: return@joinToString line
            val name = match.groups["name"]?.value ?: return@joinToString line
            if (name !in componentSimpleNames) {
                return@joinToString line
            }
            val indent = match.groups["indent"]?.value.orEmpty()
            val args = match.groups["args"]?.value.orEmpty()
            val comment = match.groups["comment"]?.value?.let { " $it" }.orEmpty()
            "$indent" + "emit($name($args))$comment"
        }

    private fun SlotDeclaration.toSlotIdSource(
        functionFqName: String,
        ordinal: Int,
    ): String =
        "io.heapy.kinetica.SlotId(" +
            "moduleId = ${moduleId.kotlinStringLiteral()}, " +
            "functionFqName = ${functionFqName.kotlinStringLiteral()}, " +
            "declarationOrdinal = $ordinal, " +
            "disambiguator = ${disambiguator.kotlinStringLiteral()}" +
            ")"

    private fun ComponentDeclaration.staticHoistId(ordinal: Int): String =
        "${fqName.toClientId()}#static#$ordinal"

    private fun String.toClientId(): String =
        replace('.', '/')

    private fun String.fullyQualifyNodeSource(): String =
        replace("TextNode(", "io.heapy.kinetica.TextNode(")

    private fun String.parseFunction(match: MatchResult): FunctionToTransform? {
        val name = match.groups["name"]?.value ?: return null
        val annotationsGroup = match.groups["annotations"] ?: return null
        val annotationStart = annotationsGroup.range.first + annotationsGroup.value.indexOf('@').coerceAtLeast(0)
        val parametersStart = match.range.last
        val parametersEnd = findMatchingDelimiter(parametersStart, opening = '(', closing = ')') ?: return null
        val bodyStart = indexOf('{', startIndex = parametersEnd + 1).takeIf { it >= 0 } ?: return null
        val bodyEnd = findMatchingDelimiter(bodyStart, opening = '{', closing = '}') ?: return null
        val isSuspend = match.groups["suspend"] != null
        val firstAnnotation = annotationsGroup.value.indexOf('@').coerceAtLeast(0)
        return FunctionToTransform(
            annotationStart = annotationStart,
            annotations = annotationsGroup.value.substring(firstAnnotation).trim(),
            isSuspend = isSuspend,
            name = name,
            parameters = substring(parametersStart + 1, parametersEnd),
            bodyStart = bodyStart + 1,
            bodyEnd = bodyEnd,
        )
    }

    private fun KineticaSourceFile.fqName(name: String): String =
        if (packageName.isBlank()) name else "$packageName.$name"

    private fun String.hasComponentAnnotation(): Boolean =
        ANNOTATION.findAll(this)
            .mapNotNull { match -> match.groups["name"]?.value?.substringAfterLast('.') }
            .any { name -> name == "UiComponent" || name == "ServerComponent" || name == "ClientComponent" }

    private fun ComponentDeclaration.isUiComponentLikeForTransform(): Boolean =
        ComponentAnnotation.UiComponent in annotations ||
            ComponentAnnotation.ServerComponent in annotations ||
            ComponentAnnotation.ClientComponent in annotations

    private fun ComponentDeclaration.isSkippableForTransform(): Boolean =
        parameters.all { parameter -> parameter.type.isStableComponentInputForTransform() }

    private fun ComponentDeclaration.skipInputsSource(): String =
        if (parameters.isEmpty()) {
            "emptyList()"
        } else {
            parameters.joinToString(prefix = "listOf(", postfix = ")") { parameter -> parameter.name }
        }

    private fun String.isStableComponentInputForTransform(): Boolean =
        "->" !in this && !contains("kotlin.Function")

    private fun String.findMatchingDelimiter(
        openingIndex: Int,
        opening: Char,
        closing: Char,
    ): Int? {
        var depth = 0
        for (index in openingIndex until length) {
            when (this[index]) {
                opening -> depth += 1
                closing -> {
                    depth -= 1
                    if (depth == 0) {
                        return index
                    }
                }
            }
        }
        return null
    }

    private fun String.trimBlankEdges(): String =
        lines()
            .dropWhile { line -> line.isBlank() }
            .dropLastWhile { line -> line.isBlank() }
            .joinToString(separator = "\n")

    private fun String.kotlinStringLiteral(): String =
        buildString {
            append('"')
            this@kotlinStringLiteral.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '$' -> {
                        append('\\')
                        append('$')
                    }
                    else -> append(character)
                }
            }
            append('"')
        }

    private data class FunctionToTransform(
        val annotationStart: Int,
        val annotations: String,
        val isSuspend: Boolean,
        val name: String,
        val parameters: String,
        val bodyStart: Int,
        val bodyEnd: Int,
    )

    private data class Replacement(
        val start: Int,
        val endExclusive: Int,
        val text: String,
    )

    private companion object {
        private const val SLOT_ID_ARGUMENT = "slotId"
        private const val RESTORED_VALUE_ARGUMENT = "restoredValue"
        private val FUNCTION = Regex(
            pattern = """(?<annotations>(?:\s*@[\w.]+(?:\([^)]*\))?\s*)+)(?<suspend>suspend\s+)?fun\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*\(""",
            options = setOf(RegexOption.MULTILINE),
        )
        private val ANNOTATION = Regex("""@(?<name>[\w.]+)""")
        private val STATE_CALL = Regex(
            """(?<prefix>(?:var|val)\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)\s+(?:by|=)\s+)(?<call>state|persistentState)\s*(?<opening>\(|\{)""",
        )
        private val STANDALONE_COMPONENT_CALL = Regex(
            """(?<indent>\s*)(?<name>[A-Z][A-Za-z0-9_]*)\s*\((?<args>.*)\)\s*(?<comment>//.*)?""",
        )
        private val STANDALONE_STATIC_TEXT = Regex(
            """(?<indent>\s*)text\s*\(\s*"(?<value>(?:\\.|[^"\\])*)"\s*(?:,\s*strikethrough\s*=\s*false\s*)?\)\s*(?<comment>//.*)?""",
        )
    }
}

public class KineticaProcessSourcesExtension(
    private val pluginConfiguration: KineticaCompilerPluginConfiguration,
) : ProcessSourcesBeforeCompilingExtension {
    override fun processSources(
        sources: Collection<KtFile>,
        configuration: CompilerConfiguration,
    ): Collection<KtFile> {
        val sourceFiles = sources.map { file ->
            KineticaSourceFile(
                path = file.virtualFile?.path ?: file.name,
                packageName = file.packageFqName.asString(),
                text = file.text,
            )
        }
        val sourceModel = KineticaSourceModelExtractor(pluginConfiguration).extractModel(sourceFiles)
        val plan = KineticaCompilerAnalyzer(pluginConfiguration.moduleId).analyze(
            declarations = sourceModel.components,
            routes = sourceModel.routes,
            serverActions = sourceModel.serverActions,
            sourceDiagnostics = sourceModel.diagnostics,
        )
        configuration.put(KineticaConfigurationKeys.compilerPlan, plan)
        val generatedSources = KineticaGeneratedSourceEmitter().emit(plan)
        configuration.put(KineticaConfigurationKeys.generatedSources, generatedSources)
        KineticaDiagnosticReporter.report(configuration, plan.diagnostics)
        val project = sources.firstOrNull()?.project ?: return sources
        val psiFactory = KtPsiFactory(project)
        val transformer = KineticaComponentSourceTransformer(pluginConfiguration.moduleId)
        val transformedSources = sourceFiles.associate { sourceFile ->
            sourceFile.path to transformer.transform(sourceFile, sourceModel.components)
        }
        val transformedFiles = sources.mapIndexed { index, source ->
            val transformed = transformedSources.getValue(sourceFiles[index].path)
            if (transformed.changed) {
                psiFactory.createPhysicalFile(source.name, transformed.text)
            } else {
                source
            }
        }
        val generatedFiles = generatedSources.map { source ->
            psiFactory.createPhysicalFile(source.path.substringAfterLast('/'), source.text)
        }
        return transformedFiles + generatedFiles
    }
}

private fun String.containsUnescapedTemplateMarker(): Boolean {
    forEachIndexed { index, character ->
        if (character == '$' && countBackslashesBefore(index) % 2 == 0) {
            return true
        }
    }
    return false
}

private fun String.decodeKotlinStringContent(): String =
    buildString {
        var index = 0
        while (index < this@decodeKotlinStringContent.length) {
            val character = this@decodeKotlinStringContent[index]
            if (character != '\\' || index == this@decodeKotlinStringContent.lastIndex) {
                append(character)
                index += 1
                continue
            }
            val escaped = this@decodeKotlinStringContent[index + 1]
            when (escaped) {
                't' -> append('\t')
                'b' -> append('\b')
                'n' -> append('\n')
                'r' -> append('\r')
                '\'' -> append('\'')
                '"' -> append('"')
                '\\' -> append('\\')
                '$' -> append('$')
                'u' -> {
                    val hex = this@decodeKotlinStringContent.substringOrNull(index + 2, index + 6)
                    val code = hex?.takeIf { value -> value.all { it.isHexDigit() } }?.toInt(radix = 16)
                    if (code != null) {
                        append(code.toChar())
                        index += 4
                    } else {
                        append('\\')
                        append(escaped)
                    }
                }
                else -> {
                    append('\\')
                    append(escaped)
                }
            }
            index += 2
        }
    }

private fun String.countBackslashesBefore(index: Int): Int {
    var cursor = index - 1
    var count = 0
    while (cursor >= 0 && this[cursor] == '\\') {
        count += 1
        cursor -= 1
    }
    return count
}

private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? =
    if (startIndex >= 0 && endIndex <= length && startIndex <= endIndex) {
        substring(startIndex, endIndex)
    } else {
        null
    }

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
