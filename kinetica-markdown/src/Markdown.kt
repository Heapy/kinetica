package io.heapy.kinetica.markdown

public sealed interface MdBlock

public data class MdHeading(val level: Int, val inlines: List<MdInline>) : MdBlock

public data class MdParagraph(val inlines: List<MdInline>) : MdBlock

public data class MdCodeBlock(val language: String?, val code: String) : MdBlock

public data class MdList(val ordered: Boolean, val items: List<List<MdInline>>) : MdBlock

public data class MdQuote(val blocks: List<MdBlock>) : MdBlock

public data object MdRule : MdBlock

public data class MdTable(
    val header: List<List<MdInline>>,
    val rows: List<List<List<MdInline>>>,
) : MdBlock

/** Single-line extension block: `::: name argument` — the site uses it for live examples. */
public data class MdDirective(val name: String, val argument: String) : MdBlock

public sealed interface MdInline

public data class MdText(val text: String) : MdInline

public data class MdCode(val text: String) : MdInline

public data class MdEmphasis(val strong: Boolean, val inlines: List<MdInline>) : MdInline

public data class MdLink(val label: List<MdInline>, val href: String) : MdInline

public fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.lines()
    val blocks = mutableListOf<MdBlock>()
    var index = 0

    fun currentLine(): String = lines[index]

    while (index < lines.size) {
        val line = currentLine()
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> index++

            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim().ifEmpty { null }
                index++
                val code = StringBuilder()
                while (index < lines.size && !lines[index].trim().startsWith("```")) {
                    code.append(lines[index]).append('\n')
                    index++
                }
                if (index < lines.size) index++ // consume closing fence
                blocks += MdCodeBlock(language, code.toString().trimEnd('\n'))
            }

            trimmed.startsWith("<!--") -> {
                while (index < lines.size && !lines[index].contains("-->")) index++
                if (index < lines.size) index++ // consume the line closing the comment
            }

            trimmed.startsWith(":::") -> {
                val body = trimmed.removePrefix(":::").trim()
                if (body.isNotEmpty()) {
                    val name = body.substringBefore(' ')
                    val argument = body.substringAfter(' ', missingDelimiterValue = "").trim()
                    blocks += MdDirective(name, argument)
                }
                index++
            }

            headingLevel(trimmed) > 0 -> {
                val level = headingLevel(trimmed)
                val text = trimmed.drop(level).trim().trimEnd('#').trim()
                blocks += MdHeading(level, parseInlines(text))
                index++
            }

            isRule(trimmed) -> {
                blocks += MdRule
                index++
            }

            trimmed.startsWith(">") -> {
                val quoted = StringBuilder()
                while (index < lines.size && lines[index].trim().startsWith(">")) {
                    quoted.append(lines[index].trim().removePrefix(">").removePrefix(" ")).append('\n')
                    index++
                }
                blocks += MdQuote(parseMarkdown(quoted.toString()))
            }

            isTableStart(lines, index) -> {
                val header = parseTableRow(lines[index])
                index += 2 // header + separator
                val rows = mutableListOf<List<List<MdInline>>>()
                while (index < lines.size && lines[index].trim().let { it.startsWith("|") || it.contains(" | ") }) {
                    rows += parseTableRow(lines[index])
                    index++
                }
                blocks += MdTable(header, rows)
            }

            listMarker(line) != null -> {
                val ordered = listMarker(line) == ListKind.Ordered
                val items = mutableListOf<List<MdInline>>()
                while (index < lines.size) {
                    val itemLine = lines[index]
                    val marker = listMarker(itemLine)
                    if (marker == null) break
                    if ((marker == ListKind.Ordered) != ordered) break
                    var itemText = stripListMarker(itemLine)
                    index++
                    // indented continuation lines belong to the same item
                    while (index < lines.size && lines[index].startsWith("  ") &&
                        lines[index].isNotBlank() && listMarker(lines[index]) == null
                    ) {
                        itemText += " " + lines[index].trim()
                        index++
                    }
                    items += parseInlines(itemText)
                }
                blocks += MdList(ordered, items)
            }

            else -> {
                val paragraph = StringBuilder(trimmed)
                index++
                while (index < lines.size) {
                    val next = lines[index]
                    val nextTrimmed = next.trim()
                    val continues = nextTrimmed.isNotEmpty() &&
                        !nextTrimmed.startsWith("```") &&
                        !nextTrimmed.startsWith("<!--") &&
                        !nextTrimmed.startsWith(":::") &&
                        !nextTrimmed.startsWith(">") &&
                        headingLevel(nextTrimmed) == 0 &&
                        !isRule(nextTrimmed) &&
                        listMarker(next) == null &&
                        !isTableStart(lines, index)
                    if (!continues) break
                    paragraph.append(' ').append(nextTrimmed)
                    index++
                }
                blocks += MdParagraph(parseInlines(paragraph.toString()))
            }
        }
    }
    return blocks
}

private enum class ListKind { Ordered, Unordered }

private fun headingLevel(line: String): Int {
    var level = 0
    while (level < line.length && level < 6 && line[level] == '#') level++
    return if (level > 0 && level < line.length && line[level] == ' ') level else 0
}

private fun isRule(line: String): Boolean =
    line.length >= 3 && (line.all { it == '-' } || line.all { it == '*' } || line.all { it == '_' })

private fun listMarker(line: String): ListKind? {
    val trimmed = line.trimStart()
    if (line.length - trimmed.length > 3) return null
    if ((trimmed.startsWith("- ") || trimmed.startsWith("* ")) && !isRule(trimmed)) return ListKind.Unordered
    val digits = trimmed.takeWhile { it.isDigit() }
    if (digits.isNotEmpty() && digits.length <= 3) {
        val rest = trimmed.drop(digits.length)
        if (rest.startsWith(". ") || rest.startsWith(") ")) return ListKind.Ordered
    }
    return null
}

private fun stripListMarker(line: String): String {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) return trimmed.drop(2).trim()
    val digits = trimmed.takeWhile { it.isDigit() }
    return trimmed.drop(digits.length + 2).trim()
}

private fun isTableStart(lines: List<String>, index: Int): Boolean {
    if (index + 1 >= lines.size) return false
    val line = lines[index].trim()
    if (!line.startsWith("|")) return false
    val separator = lines[index + 1].trim()
    if (!separator.startsWith("|")) return false
    val body = separator.filterNot { it == '|' || it == ' ' }
    return body.isNotEmpty() && body.all { it == '-' || it == ':' }
}

private fun parseTableRow(line: String): List<List<MdInline>> =
    line.trim().removePrefix("|").removeSuffix("|")
        .split("|")
        .map { cell -> parseInlines(cell.trim()) }

// --- inline parsing ---

public fun parseInlines(source: String): List<MdInline> {
    val inlines = mutableListOf<MdInline>()
    val text = StringBuilder()
    var index = 0

    fun flushText() {
        if (text.isNotEmpty()) {
            inlines += MdText(text.toString())
            text.clear()
        }
    }

    while (index < source.length) {
        val char = source[index]
        when {
            char == '\\' && index + 1 < source.length -> {
                text.append(source[index + 1])
                index += 2
            }

            char == '`' -> {
                val close = source.indexOf('`', index + 1)
                if (close == -1) {
                    text.append(char)
                    index++
                } else {
                    flushText()
                    inlines += MdCode(source.substring(index + 1, close))
                    index = close + 1
                }
            }

            char == '[' -> {
                val link = tryParseLink(source, index)
                if (link == null) {
                    text.append(char)
                    index++
                } else {
                    flushText()
                    inlines += link.first
                    index = link.second
                }
            }

            char == '*' || char == '_' -> {
                val strong = index + 1 < source.length && source[index + 1] == char
                val delimiter = if (strong) "$char$char" else char.toString()
                val close = source.indexOf(delimiter, index + delimiter.length)
                val inner = if (close == -1) null else source.substring(index + delimiter.length, close)
                if (inner.isNullOrEmpty()) {
                    text.append(char)
                    index++
                } else {
                    flushText()
                    inlines += MdEmphasis(strong, parseInlines(inner))
                    index = close + delimiter.length
                }
            }

            else -> {
                text.append(char)
                index++
            }
        }
    }
    flushText()
    return inlines
}

private fun tryParseLink(source: String, start: Int): Pair<MdLink, Int>? {
    val labelEnd = source.indexOf(']', start + 1)
    if (labelEnd == -1) return null
    if (labelEnd + 1 >= source.length || source[labelEnd + 1] != '(') return null
    val hrefEnd = source.indexOf(')', labelEnd + 2)
    if (hrefEnd == -1) return null
    val label = source.substring(start + 1, labelEnd)
    val href = source.substring(labelEnd + 2, hrefEnd).trim()
    return MdLink(parseInlines(label), href) to hrefEnd + 1
}

public fun plainText(inlines: List<MdInline>): String =
    inlines.joinToString(separator = "") { inline ->
        when (inline) {
            is MdText -> inline.text
            is MdCode -> inline.text
            is MdEmphasis -> plainText(inline.inlines)
            is MdLink -> plainText(inline.label)
        }
    }

public fun headingSlug(inlines: List<MdInline>): String =
    plainText(inlines)
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString(separator = "")
        .split('-')
        .filter { it.isNotEmpty() }
        .joinToString(separator = "-")
