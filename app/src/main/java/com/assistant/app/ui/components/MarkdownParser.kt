package com.assistant.app.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import com.assistant.app.ui.theme.AppCodeFontFamily

/**
 * Robust and safe Markdown subset representing the parsed message.
 */
sealed interface MessageBlock {
    data class Paragraph(val text: AnnotatedString) : MessageBlock
    data class Heading(val level: Int, val text: AnnotatedString) : MessageBlock
    data class ListItem(val marker: String, val text: AnnotatedString) : MessageBlock
    data class Code(val code: String, val language: String? = null) : MessageBlock
    data class Diagram(val code: String) : MessageBlock
    data class LaTeXBlock(val formula: String) : MessageBlock
    data class Table(
        val headers: List<AnnotatedString>,
        val rows: List<List<AnnotatedString>>,
    ) : MessageBlock
    data class Blockquote(val text: AnnotatedString) : MessageBlock
    data object HorizontalRule : MessageBlock
}

object MarkdownParser {
    private const val CODE_FENCE = "```"
    private const val LATEX_FENCE = "$$"
    private const val FENCE_MAX_INDENT = 4
    private val BULLET = "•"
    private val BOLD_SPAN_STYLE = SpanStyle(fontWeight = FontWeight.SemiBold)
    private val ITALIC_SPAN_STYLE = SpanStyle(fontStyle = FontStyle.Italic)
    private val MATH_SPAN_STYLE = SpanStyle(fontStyle = FontStyle.Italic, color = Color.Unspecified)
    private val URL_TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    /**
     * Parse raw markdown string into structured MessageBlock list.
     * Tightly optimized to avoid recursive overhead, exponential regex, or memory blowup.
     */
    fun parse(
        text: String,
        codeBackground: Color = Color.Transparent,
        linkColor: Color = Color.Unspecified,
    ): List<MessageBlock> {
        val blocks = mutableListOf<MessageBlock>()
        var index = 0
        val len = text.length

        while (index < len) {
            // Find next code block or math block fence
            val fenceCode = findLineAnchoredFence(text, index, CODE_FENCE)
            val fenceLatex = findLineAnchoredFence(text, index, LATEX_FENCE)

            if (fenceCode != null && (fenceLatex == null || fenceCode.first < fenceLatex.first)) {
                // Handle fenced Code/Mermaid Block
                val (fenceStart, indent) = fenceCode
                processRegion(text.substring(index, fenceStart), blocks, codeBackground, linkColor)

                val lineEnd = text.indexOf('\n', fenceStart)
                val fenceLine = if (lineEnd == -1) text.substring(fenceStart) else text.substring(fenceStart, lineEnd)
                val language = fenceLine.substring(indent + CODE_FENCE.length).trim().lowercase().substringBefore(' ')
                val contentStart = if (lineEnd == -1) len else lineEnd + 1

                val closing = findLineAnchoredFence(text, contentStart, CODE_FENCE)
                if (closing == null) {
                    val code = dedentCode(text.substring(contentStart), indent).removeSuffix("\n")
                    if (code.isNotBlank()) {
                        blocks += fencedBlock(language, code)
                    }
                    return blocks
                }

                val code = dedentCode(text.substring(contentStart, closing.first), indent).removeSuffix("\n")
                if (code.isNotBlank()) {
                    blocks += fencedBlock(language, code)
                }

                val closingLineEnd = text.indexOf('\n', closing.first)
                index = if (closingLineEnd == -1) len else closingLineEnd + 1
            } else if (fenceLatex != null) {
                // Handle fenced LaTeX Block
                val (fenceStart, indent) = fenceLatex
                processRegion(text.substring(index, fenceStart), blocks, codeBackground, linkColor)

                val lineEnd = text.indexOf('\n', fenceStart)
                val contentStart = if (lineEnd == -1) len else lineEnd + 1

                val closing = findLineAnchoredFence(text, contentStart, LATEX_FENCE)
                if (closing == null) {
                    val formula = text.substring(contentStart).trim()
                    if (formula.isNotEmpty()) {
                        blocks += MessageBlock.LaTeXBlock(formula)
                    }
                    return blocks
                }

                val formula = text.substring(contentStart, closing.first).trim()
                if (formula.isNotEmpty()) {
                    blocks += MessageBlock.LaTeXBlock(formula)
                }

                val closingLineEnd = text.indexOf('\n', closing.first)
                index = if (closingLineEnd == -1) len else closingLineEnd + 1
            } else {
                break
            }
        }

        if (index < len) {
            processRegion(text.substring(index), blocks, codeBackground, linkColor)
        }
        return blocks
    }

    private fun fencedBlock(language: String, code: String): MessageBlock = when (language) {
        "mermaid" -> MessageBlock.Diagram(code)
        else -> MessageBlock.Code(code, language.takeIf { it.isNotBlank() })
    }

    private fun findLineAnchoredFence(text: String, from: Int, fence: String): Pair<Int, Int>? {
        var search = from
        val len = text.length
        val fenceLen = fence.length
        while (true) {
            val candidate = text.indexOf(fence, search)
            if (candidate == -1) return null
            var lineStart = candidate
            while (lineStart > 0 && text[lineStart - 1] == ' ') lineStart--
            val atLineStart = lineStart == 0 || text[lineStart - 1] == '\n'
            val indent = candidate - lineStart
            if (atLineStart && indent <= FENCE_MAX_INDENT) {
                // Ensure it's not a larger run (e.g. double fence or more backticks)
                val nextCharIdx = candidate + fenceLen
                val isExact = nextCharIdx >= len || text[nextCharIdx] != fence[0]
                if (isExact) {
                    return lineStart to indent
                }
            }
            search = candidate + 1
        }
    }

    private fun dedentCode(code: String, indent: Int): String {
        if (indent == 0) return code
        return code.lines().joinToString("\n") { line ->
            var spaces = 0
            while (spaces < indent && spaces < line.length && line[spaces] == ' ') spaces++
            line.substring(spaces)
        }
    }

    private fun processRegion(
        region: String,
        blocks: MutableList<MessageBlock>,
        codeBackground: Color,
        linkColor: Color,
    ) {
        val paragraph = StringBuilder()
        val lines = region.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val heading = headingOf(line)
            val list = listItemOf(line)
            val table = tableAt(lines, index, codeBackground, linkColor)
            val quote = blockquoteAt(lines, index)
            when {
                quote != null -> {
                    flushParagraph(paragraph, blocks, codeBackground, linkColor)
                    blocks += quote.first.copy(
                        text = richText(quote.first.text.text, codeBackground, linkColor)
                    )
                    index += quote.second
                }
                table != null -> {
                    flushParagraph(paragraph, blocks, codeBackground, linkColor)
                    blocks += table.first
                    index += table.second
                }
                isHorizontalRule(line) -> {
                    flushParagraph(paragraph, blocks, codeBackground, linkColor)
                    blocks += MessageBlock.HorizontalRule
                    index++
                }
                heading != null -> {
                    flushParagraph(paragraph, blocks, codeBackground, linkColor)
                    blocks += heading.copy(text = richText(heading.text.text, codeBackground, linkColor))
                    index++
                }
                list != null -> {
                    flushParagraph(paragraph, blocks, codeBackground, linkColor)
                    blocks += list.copy(text = richText(list.text.text, codeBackground, linkColor))
                    index++
                }
                else -> {
                    paragraph.append(line).append('\n')
                    index++
                }
            }
        }
        flushParagraph(paragraph, blocks, codeBackground, linkColor)
    }

    private fun flushParagraph(
        paragraph: StringBuilder,
        blocks: MutableList<MessageBlock>,
        codeBackground: Color,
        linkColor: Color,
    ) {
        if (paragraph.isNotEmpty()) {
            val trimmed = paragraph.toString().trim('\n')
            if (trimmed.isNotEmpty()) {
                blocks += MessageBlock.Paragraph(richText(trimmed, codeBackground, linkColor))
            }
            paragraph.clear()
        }
    }

    private fun headingOf(line: String): MessageBlock.Heading? {
        var level = 0
        while (level < line.length && line[level] == '#') level++
        if (level !in 1..6) return null
        if (level >= line.length) return null
        val content = line.substring(level).trim()
        if (content.isEmpty()) return null
        if (line[level] != ' ' && line[level] != '\t') return null
        return MessageBlock.Heading(level.coerceAtMost(3), AnnotatedString(content))
    }

    private fun listItemOf(line: String): MessageBlock.ListItem? {
        if (line.length < 2) return null
        if (line[0] in listOf('-', '*', '+') && line[1] == ' ') {
            val content = line.substring(2).trim()
            if (content.isEmpty()) return null
            return MessageBlock.ListItem(BULLET, AnnotatedString(content))
        }
        val digits = line.takeWhile { it.isDigit() }
        if (digits.isEmpty() || digits.length > 3) return null
        val after = digits.length
        if (after + 1 >= line.length || (line[after] != '.' && line[after] != ')') || line[after + 1] != ' ') return null
        val content = line.substring(after + 2).trim()
        if (content.isEmpty()) return null
        return MessageBlock.ListItem("$digits.", AnnotatedString(content))
    }

    private fun isQuoteLine(line: String): Boolean = line.startsWith(">")

    private fun blockquoteAt(
        lines: List<String>,
        index: Int,
    ): Pair<MessageBlock.Blockquote, Int>? {
        if (!isQuoteLine(lines[index])) return null
        var end = index
        val stripped = mutableListOf<String>()
        while (end < lines.size && isQuoteLine(lines[end])) {
            stripped += lines[end].substring(1).removePrefix(" ")
            end++
        }
        return MessageBlock.Blockquote(AnnotatedString(stripped.joinToString("\n"))) to (end - index)
    }

    private fun isHorizontalRule(line: String): Boolean {
        if (line.isEmpty()) return false
        val marker = line[0]
        if (marker !in listOf('-', '*', '_')) return false
        var count = 0
        for (char in line) {
            when (char) {
                marker -> count++
                ' ' -> {}
                else -> return false
            }
        }
        return count >= 3
    }

    private fun tableAt(
        lines: List<String>,
        index: Int,
        codeBackground: Color,
        linkColor: Color,
    ): Pair<MessageBlock.Table, Int>? {
        if (index + 1 >= lines.size) return null
        if (!lines[index].contains('|')) return null
        if (!isTableSeparator(lines[index + 1])) return null
        val columns = splitCells(lines[index]).size
        if (columns == 0) return null
        val header = splitCells(lines[index])
            .map { richText(it, codeBackground, linkColor) }
        val rows = mutableListOf<List<AnnotatedString>>()
        var body = index + 2
        while (body < lines.size && lines[body].isNotBlank() && lines[body].contains('|')) {
            val cells = splitCells(lines[body])
            rows += (0 until columns).map { column ->
                val cell = cells.getOrNull(column) ?: ""
                richText(cell, codeBackground, linkColor)
            }
            body++
        }
        if (body < lines.size && lines[body].isBlank()) {
            body++
        }
        return MessageBlock.Table(header, rows) to (body - index)
    }

    private fun isTableSeparator(line: String): Boolean {
        if (!line.contains('|') || !line.contains('-')) return false
        return line.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    private fun splitCells(line: String): List<String> {
        val trimmed = line.trim()
        val masked = trimmed.replace("\\|", "\u0000")
        var cells = masked.split('|').map { it.trim().replace("\u0000", "|") }
        if (trimmed.startsWith('|')) cells = cells.drop(1)
        if (trimmed.endsWith('|')) cells = cells.dropLast(1)
        return cells
    }

    /**
     * Parse inline elements recursively: bold, italic, inline backtick, URL links, and inline LaTeX ($).
     */
    fun richText(
        text: String,
        codeBackground: Color = Color.Transparent,
        linkColor: Color = Color.Unspecified,
    ): AnnotatedString = buildAnnotatedString {
        appendInline(text, 0, text.length, null, codeBackground, linkColor)
    }

    private fun AnnotatedString.Builder.appendInline(
        text: String,
        start: Int,
        limit: Int,
        enclosing: SpanStyle?,
        codeBackground: Color,
        linkColor: Color,
    ) {
        var index = start
        while (index < limit) {
            val next = firstMarker(text, index, limit) ?: break
            if (next > index) {
                append(text, index, next)
            }
            val consumed = when (text[next]) {
                '`' -> appendBackticks(text, next, limit, codeBackground)
                '*' -> appendEmphasis(text, next, limit, enclosing, codeBackground, linkColor)
                '$' -> appendInlineMath(text, next, limit, enclosing)
                'h' -> appendLink(text, next, limit, enclosing, linkColor)
                else -> 0
            }
            if (consumed == 0) {
                append(text[next])
                index = next + 1
            } else {
                index = next + consumed
            }
        }
        if (index < limit) {
            append(text, index, limit)
        }
    }

    private fun firstMarker(text: String, from: Int, limit: Int): Int? {
        var i = from
        while (i < limit) {
            when (text[i]) {
                '`' -> return i
                '*' -> return i
                '$' -> return i
                'h' -> if (startsWithUrl(text, i)) return i
            }
            i++
        }
        return null
    }

    private fun startsWithUrl(text: String, at: Int): Boolean =
        text.regionMatches(at, "http://", 0, 7) || text.regionMatches(at, "https://", 0, 8)

    private fun AnnotatedString.Builder.appendBackticks(
        text: String,
        at: Int,
        limit: Int,
        background: Color,
    ): Int {
        var runEnd = at
        while (runEnd < limit && text[runEnd] == '`') runEnd++
        val runLength = runEnd - at
        if (runLength >= CODE_FENCE.length) {
            append(text.substring(at, runEnd))
            return runLength
        }
        val close = matchingBacktickRun(text, runEnd, limit, runLength)
        if (close == null) {
            append(text.substring(at, runEnd))
            return runLength
        }
        val content = text.substring(runEnd, close)
        if (content.contains("\n\n") || content.contains("\r\n\r\n")) {
            append(text.substring(at, runEnd))
            return runLength
        }
        withStyle(SpanStyle(fontFamily = AppCodeFontFamily, background = background)) {
            append(codeSpanContent(content))
        }
        return close + runLength - at
    }

    private fun matchingBacktickRun(text: String, from: Int, limit: Int, length: Int): Int? {
        var i = from
        while (i <= limit - length) {
            if (text[i] == '`') {
                var runTo = i
                while (runTo < limit && text[runTo] == '`') runTo++
                if (runTo - i == length) return i
                i = runTo
            } else {
                i++
            }
        }
        return null
    }

    private fun codeSpanContent(content: String): String {
        if (content.length < 2 || content.first() != ' ' || content.last() != ' ') return content
        if (content.all { it == ' ' }) return content
        return content.substring(1, content.length - 1)
    }

    private fun AnnotatedString.Builder.appendEmphasis(
        text: String,
        at: Int,
        limit: Int,
        enclosing: SpanStyle?,
        codeBackground: Color,
        linkColor: Color,
    ): Int {
        val double = text.startsWith("**", at)
        val marker = if (double) "**" else "*"
        val close = text.indexOf(marker, at + marker.length)
        if (close == -1 || close + marker.length > limit) return 0
        val inner = text.substring(at + marker.length, close)
        if (inner.isEmpty() || inner.startsWith(" ") || inner.endsWith(" ")) return 0
        val flankedBefore = at == 0 || !text[at - 1].isLetterOrDigit()
        val afterClose = close + marker.length
        val flankedAfter = afterClose >= limit || !text[afterClose].isLetterOrDigit()
        if (!flankedBefore || !flankedAfter) return 0
        val style = if (double) BOLD_SPAN_STYLE else ITALIC_SPAN_STYLE
        val merged = enclosing?.merge(style) ?: style
        withStyle(merged) {
            appendInline(text, at + marker.length, close, merged, codeBackground, linkColor)
        }
        return afterClose - at
    }

    /**
     * Safer inline LaTeX math parsing to avoid currency false positives.
     */
    private fun AnnotatedString.Builder.appendInlineMath(
        text: String,
        at: Int,
        limit: Int,
        enclosing: SpanStyle?,
    ): Int {
        if (at + 1 >= limit || text[at + 1] == ' ' || text[at + 1].isDigit() || text[at + 1] == '$') return 0
        val close = text.indexOf('$', at + 1)
        if (close == -1 || close >= limit) return 0
        if (text[close - 1] == ' ') return 0
        val content = text.substring(at + 1, close)
        if (content.contains("\n\n") || content.contains("\r\n\r\n")) return 0
        val merged = enclosing?.merge(MATH_SPAN_STYLE) ?: MATH_SPAN_STYLE
        withStyle(merged) {
            append(content)
        }
        return close + 1 - at
    }

    private fun AnnotatedString.Builder.appendLink(
        text: String,
        at: Int,
        limit: Int,
        enclosing: SpanStyle?,
        linkColor: Color,
    ): Int {
        var to = at
        while (to < limit && !text[to].isWhitespace()) to++
        var url = text.substring(at, to)
        if (url.length <= "https://".length) return 0
        url = url.trimEnd(*URL_TRAILING_PUNCTUATION)
        if (url.length <= "https://".length) return 0
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(
                    style = if (linkColor.isSpecified) {
                        val link = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                        enclosing?.merge(link) ?: link
                    } else {
                        null
                    },
                ),
            ),
        ) { append(url) }
        return url.length
    }
}
