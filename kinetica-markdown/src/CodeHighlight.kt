package io.heapy.kinetica.markdown

import io.heapy.kinetica.ComponentScope
import io.heapy.kinetica.ServerComponent
import io.heapy.kinetica.host
import io.heapy.kinetica.text

public fun interface CodeHighlighter {
    public fun highlight(language: String?, code: String): CodeHighlight
}

public fun interface LanguageCodeHighlighter {
    public fun highlight(code: String): List<CodeToken>
}

public data class CodeHighlight(
    val language: String?,
    val tokens: List<CodeToken>,
) {
    public companion object {
        public fun plain(language: String?, code: String): CodeHighlight =
            CodeHighlight(language, listOf(CodeToken.plain(code)))
    }
}

public data class CodeToken(
    val text: String,
    val kind: CodeTokenKind? = null,
) {
    public companion object {
        public fun plain(text: String): CodeToken = CodeToken(text)
    }
}

public enum class CodeTokenKind(public val cssClass: String) {
    Keyword("tok-keyword"),
    Type("tok-type"),
    Function("tok-function"),
    StringLiteral("tok-string"),
    Number("tok-number"),
    BooleanLiteral("tok-boolean"),
    NullLiteral("tok-null"),
    Comment("tok-comment"),
    Operator("tok-operator"),
    Punctuation("tok-punctuation"),
    Property("tok-property"),
    Tag("tok-tag"),
    Attribute("tok-attribute"),
    Entity("tok-entity"),
}

public class CodeHighlighterRegistry(
    languages: Map<String, LanguageCodeHighlighter>,
    aliases: Map<String, String> = emptyMap(),
) : CodeHighlighter {
    private val languages: Map<String, LanguageCodeHighlighter> =
        languages.mapKeys { (language, _) -> language.normalizedCodeLanguageKey() }

    private val aliases: Map<String, String> =
        aliases.mapKeys { (alias, _) -> alias.normalizedCodeLanguageKey() }
            .mapValues { (_, language) -> language.normalizedCodeLanguageKey() }

    override fun highlight(language: String?, code: String): CodeHighlight {
        val normalized = language.toCodeLanguage()
        val languageKey = normalized?.normalizedCodeLanguageKey()
        val resolved = aliases[languageKey] ?: languageKey
        val highlighter = languages[resolved]
        return if (highlighter == null) {
            CodeHighlight.plain(normalized, code)
        } else {
            CodeHighlight(resolved, highlighter.highlight(code))
        }
    }

    public fun plus(
        language: String,
        highlighter: LanguageCodeHighlighter,
        aliases: Iterable<String> = emptyList(),
    ): CodeHighlighterRegistry {
        val languageKey = language.normalizedCodeLanguageKey()
        return CodeHighlighterRegistry(
            languages = this.languages + (languageKey to highlighter),
            aliases = this.aliases + aliases.associate { alias -> alias.normalizedCodeLanguageKey() to languageKey },
        )
    }
}

public object MarkdownCodeHighlighter : CodeHighlighter {
    private val registry = CodeHighlighterRegistry(
        languages = mapOf(
            "kotlin" to KotlinCodeHighlighter,
            "json" to JsonCodeHighlighter,
            "html" to HtmlCodeHighlighter,
        ),
        aliases = mapOf(
            "kt" to "kotlin",
            "kts" to "kotlin",
            "jsonc" to "json",
            "htm" to "html",
            "xml" to "html",
        ),
    )

    override fun highlight(language: String?, code: String): CodeHighlight =
        registry.highlight(language, code)
}

@ServerComponent
public fun ComponentScope.highlightedCodeBlock(
    code: String,
    language: String?,
    highlighter: CodeHighlighter = MarkdownCodeHighlighter,
) {
    val highlighted = highlighter.highlight(language, code)
    val props = highlighted.language
        ?.let { normalizedLanguage -> mapOf("class" to "language-$normalizedLanguage") }
        ?: emptyMap()
    host("pre") {
        host("code", props = props) {
            renderCodeTokens(highlighted.tokens)
        }
    }
}

private fun ComponentScope.renderCodeTokens(tokens: List<CodeToken>) {
    tokens.forEach { token ->
        val kind = token.kind
        if (kind == null) {
            text(token.text, semantics = null)
        } else {
            host("span", props = mapOf("class" to kind.cssClass)) {
                text(token.text, semantics = null)
            }
        }
    }
}

private object KotlinCodeHighlighter : LanguageCodeHighlighter {
    override fun highlight(code: String): List<CodeToken> =
        CodeLexer(code).highlightKotlin()
}

private object JsonCodeHighlighter : LanguageCodeHighlighter {
    override fun highlight(code: String): List<CodeToken> =
        CodeLexer(code).highlightJson()
}

private object HtmlCodeHighlighter : LanguageCodeHighlighter {
    override fun highlight(code: String): List<CodeToken> =
        CodeLexer(code).highlightHtml()
}

private class CodeLexer(private val source: String) {
    private val tokens = mutableListOf<CodeToken>()
    private var index = 0

    fun highlightKotlin(): List<CodeToken> {
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> readLineComment()
                source.startsWith("/*", index) -> readBlockComment()
                source.startsWith("\"\"\"", index) -> readDelimitedString("\"\"\"")
                source[index] == '"' -> readDelimitedString("\"")
                source[index] == '\'' -> readDelimitedString("'")
                source[index].isDigit() -> readNumber()
                source[index].isIdentifierStart() -> readKotlinIdentifier()
                source[index] in KotlinOperatorChars -> readRun(CodeTokenKind.Operator) { it in KotlinOperatorChars }
                source[index] in KotlinPunctuationChars -> readOne(CodeTokenKind.Punctuation)
                else -> readPlain()
            }
        }
        return tokens
    }

    fun highlightJson(): List<CodeToken> {
        while (index < source.length) {
            when {
                source[index] == '"' -> readJsonString()
                source[index] == '-' || source[index].isDigit() -> readNumber()
                source.startsWith("true", index) && isJsonWordBoundary(index + 4) -> readFixed(4, CodeTokenKind.BooleanLiteral)
                source.startsWith("false", index) && isJsonWordBoundary(index + 5) -> readFixed(5, CodeTokenKind.BooleanLiteral)
                source.startsWith("null", index) && isJsonWordBoundary(index + 4) -> readFixed(4, CodeTokenKind.NullLiteral)
                source[index] in JsonPunctuationChars -> readOne(CodeTokenKind.Punctuation)
                else -> readPlain()
            }
        }
        return tokens
    }

    fun highlightHtml(): List<CodeToken> {
        while (index < source.length) {
            when {
                source.startsWith("<!--", index) -> readUntil("-->", CodeTokenKind.Comment, includeDelimiter = true)
                source[index] == '<' -> readHtmlTag()
                source[index] == '&' -> readHtmlEntity()
                else -> readHtmlText()
            }
        }
        return tokens
    }

    private fun readLineComment() {
        val start = index
        while (index < source.length && source[index] != '\n') index++
        emit(source.substring(start, index), CodeTokenKind.Comment)
    }

    private fun readBlockComment() {
        val start = index
        index += 2
        var depth = 1
        while (index < source.length && depth > 0) {
            when {
                source.startsWith("/*", index) -> {
                    depth++
                    index += 2
                }
                source.startsWith("*/", index) -> {
                    depth--
                    index += 2
                }
                else -> index++
            }
        }
        emit(source.substring(start, index), CodeTokenKind.Comment)
    }

    private fun readDelimitedString(delimiter: String) {
        val start = index
        index += delimiter.length
        if (delimiter == "\"\"\"") {
            val end = source.indexOf(delimiter, index)
            index = if (end == -1) source.length else end + delimiter.length
        } else {
            while (index < source.length) {
                when {
                    source[index] == '\\' -> index = (index + 2).coerceAtMost(source.length)
                    source.startsWith(delimiter, index) -> {
                        index += delimiter.length
                        break
                    }
                    source[index] == '\n' -> break
                    else -> index++
                }
            }
        }
        emit(source.substring(start, index), CodeTokenKind.StringLiteral)
    }

    private fun readNumber() {
        val start = index
        if (source[index] == '-') index++
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] in "._+-")) {
            index++
        }
        emit(source.substring(start, index), CodeTokenKind.Number)
    }

    private fun readKotlinIdentifier() {
        val start = index
        index++
        while (index < source.length && source[index].isIdentifierPart()) index++
        val value = source.substring(start, index)
        val kind = when {
            value in KotlinKeywords -> CodeTokenKind.Keyword
            value == "true" || value == "false" -> CodeTokenKind.BooleanLiteral
            value == "null" -> CodeTokenKind.NullLiteral
            value in KotlinBuiltInTypes -> CodeTokenKind.Type
            value.first().isUpperCase() -> CodeTokenKind.Type
            nextNonWhitespace() == '(' -> CodeTokenKind.Function
            else -> null
        }
        emit(value, kind)
    }

    private fun readJsonString() {
        val start = index
        readJsonStringContent()
        val value = source.substring(start, index)
        val kind = if (nextNonWhitespace() == ':') CodeTokenKind.Property else CodeTokenKind.StringLiteral
        emit(value, kind)
    }

    private fun readJsonStringContent() {
        index++
        while (index < source.length) {
            when (source[index]) {
                '\\' -> index = (index + 2).coerceAtMost(source.length)
                '"' -> {
                    index++
                    return
                }
                else -> index++
            }
        }
    }

    private fun readHtmlTag() {
        if (source.startsWith("<!", index) && !source.startsWith("<!--", index)) {
            readHtmlDeclaration()
            return
        }

        readOne(CodeTokenKind.Punctuation)
        if (index < source.length && source[index] == '/') {
            readOne(CodeTokenKind.Punctuation)
        }
        readHtmlName(CodeTokenKind.Tag)
        while (index < source.length && source[index] != '>') {
            when {
                source.startsWith("/>", index) -> {
                    readFixed(2, CodeTokenKind.Punctuation)
                    return
                }
                source[index].isWhitespace() -> readRun(null) { it.isWhitespace() }
                source[index] == '=' -> readOne(CodeTokenKind.Operator)
                source[index] == '"' || source[index] == '\'' -> readHtmlAttributeValue()
                source[index] == '&' -> readHtmlEntity()
                source[index].isHtmlNameStart() -> readHtmlName(CodeTokenKind.Attribute)
                else -> readOne(CodeTokenKind.Punctuation)
            }
        }
        if (index < source.length) readOne(CodeTokenKind.Punctuation)
    }

    private fun readHtmlDeclaration() {
        readFixed(2, CodeTokenKind.Punctuation)
        readHtmlName(CodeTokenKind.Tag)
        while (index < source.length && source[index] != '>') {
            if (source[index].isWhitespace()) {
                readRun(null) { it.isWhitespace() }
            } else if (source[index] == '"' || source[index] == '\'') {
                readHtmlAttributeValue()
            } else {
                readRun(null) { it != '>' && !it.isWhitespace() }
            }
        }
        if (index < source.length) readOne(CodeTokenKind.Punctuation)
    }

    private fun readHtmlName(kind: CodeTokenKind) {
        if (index >= source.length || !source[index].isHtmlNameStart()) return
        readRun(kind) { it.isHtmlNamePart() }
    }

    private fun readHtmlAttributeValue() {
        val quote = source[index]
        val start = index
        index++
        while (index < source.length && source[index] != quote) index++
        if (index < source.length) index++
        emit(source.substring(start, index), CodeTokenKind.StringLiteral)
    }

    private fun readHtmlEntity() {
        val start = index
        index++
        while (index < source.length && source[index] != ';' && !source[index].isWhitespace()) index++
        if (index < source.length && source[index] == ';') index++
        emit(source.substring(start, index), CodeTokenKind.Entity)
    }

    private fun readHtmlText() {
        val start = index
        while (index < source.length && source[index] != '<' && source[index] != '&') index++
        emit(source.substring(start, index), null)
    }

    private fun readUntil(delimiter: String, kind: CodeTokenKind, includeDelimiter: Boolean) {
        val start = index
        val end = source.indexOf(delimiter, index + delimiter.length)
        index = if (end == -1) {
            source.length
        } else if (includeDelimiter) {
            end + delimiter.length
        } else {
            end
        }
        emit(source.substring(start, index), kind)
    }

    private fun readFixed(length: Int, kind: CodeTokenKind?) {
        val end = (index + length).coerceAtMost(source.length)
        emit(source.substring(index, end), kind)
        index = end
    }

    private fun readOne(kind: CodeTokenKind?) {
        emit(source[index].toString(), kind)
        index++
    }

    private fun readRun(kind: CodeTokenKind?, predicate: (Char) -> Boolean) {
        val start = index
        while (index < source.length && predicate(source[index])) index++
        emit(source.substring(start, index), kind)
    }

    private fun readPlain() {
        val start = index
        index++
        while (index < source.length && !source[index].startsToken()) index++
        emit(source.substring(start, index), null)
    }

    private fun nextNonWhitespace(): Char? {
        var cursor = index
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        return source.getOrNull(cursor)
    }

    private fun isJsonWordBoundary(end: Int): Boolean =
        end >= source.length || !source[end].isIdentifierPart()

    private fun emit(text: String, kind: CodeTokenKind?) {
        if (text.isNotEmpty()) {
            tokens += CodeToken(text, kind)
        }
    }

    private fun Char.startsToken(): Boolean =
        this == '"' ||
            this == '\'' ||
            this == '/' ||
            this == '<' ||
            this == '&' ||
            this == '-' ||
            isDigit() ||
            isIdentifierStart() ||
            this in KotlinOperatorChars ||
            this in KotlinPunctuationChars ||
            this in JsonPunctuationChars
}

private fun String?.toCodeLanguage(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.takeWhile { !it.isWhitespace() }
        ?.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '+' || it == '#' }
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

private fun String.normalizedCodeLanguageKey(): String =
    lowercase()

private fun Char.isIdentifierStart(): Boolean =
    this == '_' || isLetter()

private fun Char.isIdentifierPart(): Boolean =
    this == '_' || isLetterOrDigit()

private fun Char.isHtmlNameStart(): Boolean =
    isLetter() || this == '_' || this == ':'

private fun Char.isHtmlNamePart(): Boolean =
    isHtmlNameStart() || isDigit() || this == '-' || this == '.'

private val KotlinKeywords = setOf(
    "as",
    "break",
    "by",
    "catch",
    "class",
    "companion",
    "const",
    "constructor",
    "continue",
    "data",
    "do",
    "else",
    "enum",
    "expect",
    "finally",
    "for",
    "fun",
    "if",
    "import",
    "in",
    "init",
    "interface",
    "is",
    "object",
    "package",
    "return",
    "sealed",
    "super",
    "this",
    "throw",
    "try",
    "typealias",
    "val",
    "var",
    "when",
    "where",
    "while",
    "actual",
    "abstract",
    "annotation",
    "crossinline",
    "external",
    "final",
    "infix",
    "inline",
    "inner",
    "internal",
    "lateinit",
    "noinline",
    "open",
    "operator",
    "out",
    "override",
    "private",
    "protected",
    "public",
    "reified",
    "suspend",
    "tailrec",
    "vararg",
)

private val KotlinBuiltInTypes = setOf(
    "Any",
    "Array",
    "Boolean",
    "Byte",
    "Char",
    "Double",
    "Float",
    "Int",
    "Long",
    "Nothing",
    "Short",
    "String",
    "Unit",
    "List",
    "Map",
    "Set",
    "MutableList",
    "MutableMap",
    "MutableSet",
    "Sequence",
    "Result",
)

private val KotlinOperatorChars = setOf(
    '+',
    '-',
    '*',
    '/',
    '%',
    '=',
    '!',
    '<',
    '>',
    '&',
    '|',
    '^',
    '~',
    '?',
    ':',
)

private val KotlinPunctuationChars = setOf(
    '(',
    ')',
    '{',
    '}',
    '[',
    ']',
    '.',
    ',',
    ';',
    '@',
)

private val JsonPunctuationChars = setOf(
    '{',
    '}',
    '[',
    ']',
    ':',
    ',',
)
