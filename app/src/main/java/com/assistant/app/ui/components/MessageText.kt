package com.assistant.app.ui.components

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.ui.theme.AppCodeFontFamily
import com.assistant.app.ui.theme.AppShape

/**
 * The markdown subset rendered for assistant messages: fenced code, inline
 * code, links, emphasis, headings, lists, tables, quotes and rules. Unknown
 * syntax and unclosed markers fall back to plain text.
 */
sealed interface MessageBlock {
    data class Paragraph(val text: AnnotatedString) : MessageBlock

        /** Deeper `#` levels are capped at 3. */
    data class Heading(val level: Int, val text: AnnotatedString) : MessageBlock

    data class ListItem(val marker: String, val text: AnnotatedString) : MessageBlock

    data class Code(val code: String, val language: String? = null) : MessageBlock

    data class Diagram(val code: String) : MessageBlock

    data class Table(
        val headers: List<AnnotatedString>,
        val rows: List<List<AnnotatedString>>,
    ) : MessageBlock

    data class Blockquote(val text: AnnotatedString) : MessageBlock

    data object HorizontalRule : MessageBlock
}

/**
 * Splits a message into blocks, merging consecutive non-block lines into one
 * paragraph since streamed text has no blank-line guarantees. Fences are
 * line-anchored like CommonMark, so prose mentioning backticks stays prose.
 */
fun messageBlocks(
    text: String,
    codeBackground: Color = Color.Transparent,
    linkColor: Color = Color.Unspecified,
): List<MessageBlock> {
    val blocks = mutableListOf<MessageBlock>()
    var index = 0
    while (index < text.length) {
        val fence = findLineAnchoredFence(text, index) ?: break
        val (fenceLineStart, indent) = fence
        processRegion(text.substring(index, fenceLineStart), blocks, codeBackground, linkColor)
        val lineEnd = text.indexOf('\n', fenceLineStart)
        val fenceLine = if (lineEnd == -1) text.substring(fenceLineStart) else text.substring(fenceLineStart, lineEnd)
        val language = fenceLine.substring(indent + CODE_FENCE.length).trim().lowercase().substringBefore(' ')
        val contentStart = if (lineEnd == -1) text.length else lineEnd + 1
        val closing = findLineAnchoredFence(text, contentStart)
        if (closing == null) {
            // Unterminated fence (e.g. mid-stream): treat the rest as code,
            // skipping it while it is still empty (fence + language tag only).
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
        // Consume the whole closing fence line, not just the backticks.
        val closingLineEnd = text.indexOf('\n', closing.first)
        index = if (closingLineEnd == -1) text.length else closingLineEnd + 1
    }
    processRegion(text.substring(index), blocks, codeBackground, linkColor)
    return blocks
}

private fun dedentCode(code: String, indent: Int): String {
    if (indent == 0) return code
    return code.lines().joinToString("\n") { line ->
        var spaces = 0
        while (spaces < indent && spaces < line.length && line[spaces] == ' ') spaces++
        line.substring(spaces)
    }
}

/**
 * Classifies one fence-free region line by line: headings, list items, tables,
 * quotes and rules become their own blocks; everything else accumulates into
 * merged paragraphs.
 */
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
                    text = richText(quote.first.text.text, codeBackground, linkColor),
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

/** Detects a GFM pipe table at [index]. Returns the block and consumed line count. */
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
    // A blank line right after the body is table structure, not content.
    if (body < lines.size && lines[body].isBlank()) {
        body++
    }
    return MessageBlock.Table(header, rows) to (body - index)
}

/** Cells contain only `-`, `:`, spaces, and at least one `|` and one `-`. */
private fun isTableSeparator(line: String): Boolean {
    if (!line.contains('|') || !line.contains('-')) return false
    return line.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

/**
 * Splits a table row into trimmed cells. `\|` is a literal pipe inside a cell,
 * not a separator; empty first/last cells from leading/trailing pipes are
 * dropped.
 */
private fun splitCells(line: String): List<String> {
    val trimmed = line.trim()
    val masked = trimmed.replace("\\|", "\u0000")
    var cells = masked.split('|').map { it.trim().replace("\u0000", "|") }
    if (trimmed.startsWith('|')) cells = cells.drop(1)
    if (trimmed.endsWith('|')) cells = cells.dropLast(1)
    return cells
}

private fun headingOf(line: String): MessageBlock.Heading? {
    var level = 0
    while (level < line.length && line[level] == '#') level++
    if (level !in 1..6) return null
    if (level >= line.length) return null
    val content = line.substring(level).trim()
    if (content.isEmpty()) return null
    // "#hashtag" (no space) stays prose — only spaced headings are real.
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

private const val BULLET = "\u2022"

private fun isQuoteLine(line: String): Boolean = line.startsWith(">")

/** Consecutive `>` lines merge into one quote; returns block and consumed count. */
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

private fun fencedBlock(language: String, code: String): MessageBlock = when (language) {
    "mermaid" -> MessageBlock.Diagram(code)
    else -> MessageBlock.Code(code, language.takeIf { it.isNotBlank() })
}

/** First line-anchored ``` at or after [from]; returns its line start and indent. */
private fun findLineAnchoredFence(text: String, from: Int): Pair<Int, Int>? {
    var search = from
    while (true) {
        val candidate = text.indexOf(CODE_FENCE, search)
        if (candidate == -1) return null
        var lineStart = candidate
        while (lineStart > 0 && text[lineStart - 1] == ' ') lineStart--
        val atLineStart = lineStart == 0 || text[lineStart - 1] == '\n'
        val indent = candidate - lineStart
        if (atLineStart && indent <= FENCE_MAX_INDENT) return lineStart to indent
        search = candidate + 1
    }
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

/**
 * Builds the inline-markdown [AnnotatedString]: `` `code` `` spans get a
 * monospace style on [codeBackground], `**bold**`/`*italic*` their weights, and
 * bare URLs become [LinkAnnotation.Url]s styled with [linkColor]. Parsing is
 * recursive, so code spans and links work inside emphasis.
 */
fun richText(
    text: String,
    codeBackground: Color = Color.Transparent,
    linkColor: Color = Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    appendInline(text, 0, text.length, null, codeBackground, linkColor)
}

/**
 * Appends [text] from [start] until [limit]. [enclosing] is the emphasis style
 * already active here, so links merge with it and stay bold. Regions shrink on
 * every recursion, so this terminates.
 */
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
            append(text.substring(index, next))
        }
        val consumed = when (text[next]) {
            '`' -> appendBackticks(text, next, limit, codeBackground)
            '*' -> appendEmphasis(text, next, limit, enclosing, codeBackground, linkColor)
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
        append(text.substring(index, limit))
    }
}

private fun firstMarker(text: String, from: Int, limit: Int): Int? {
    var i = from
    while (i < limit) {
        when (text[i]) {
            '`' -> return i
            '*' -> return i
            'h' -> if (startsWithUrl(text, i)) return i
        }
        i++
    }
    return null
}

private fun startsWithUrl(text: String, at: Int): Boolean =
    text.regionMatches(at, "http://", 0, 7) || text.regionMatches(at, "https://", 0, 8)

/**
 * Appends the backtick run at [at]: three or more backticks are literal prose;
 * a shorter run becomes a monospace span closed by the next run of the same
 * length (CommonMark). The content may not cross a blank line, so a stray
 * opener never pairs with a backtick in a later paragraph. Returns the
 * consumed length.
 */
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
    withStyle(codeSpanStyle(background)) { append(codeSpanContent(content)) }
    return close + runLength - at
}

/**
 * A backtick run of exactly [length] at or after [from], before [limit], or
 * null. Runs of the wrong length are skipped whole, so `` ``a` `` does not
 * close a double-backtick span on its single backtick.
 */
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

/** CommonMark: one leading and trailing space vanish from code span content. */
private fun codeSpanContent(content: String): String {
    if (content.length < 2 || content.first() != ' ' || content.last() != ' ') return content
    if (content.all { it == ' ' }) return content
    return content.substring(1, content.length - 1)
}

/**
 * A `**bold**` or `*italic*` span; returns the consumed length, or 0 when there
 * is no matching closer (the markers then render literally). Emphasis must be
 * word-flanking, so "2*3*4" and "one * two ** three" stay literal.
 */
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
 * A clickable bare-URL link; returns the URL length, or 0 if malformed.
 * Trailing punctuation stays surrounding prose, and the link style merges with
 * [enclosing] so links inside bold or italic keep the emphasis.
 */
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

@Composable
fun MessageText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text, codeBackground, linkColor) {
        messageBlocks(text, codeBackground, linkColor)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MessageBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
                is MessageBlock.Code -> CodeBlock(code = block.code, language = block.language)
                is MessageBlock.Table -> TableBlock(table = block)
                is MessageBlock.Heading -> Text(
                    text = block.text,
                    style = if (block.level <= 2) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                )
                is MessageBlock.Diagram -> DiagramBlock(code = block.code)
                is MessageBlock.Blockquote -> BlockquoteBlock(text = block.text)
                is MessageBlock.HorizontalRule -> HorizontalRuleBlock()
                is MessageBlock.ListItem -> Row {
                    Text(
                        text = block.marker + " ",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = block.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * A fenced code block: a header row with the language tag and the copy button,
 * then the monospace code on a subtle surface, horizontally scrollable.
 */
@Composable
private fun CodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (language != null) {
                    Text(
                        text = language,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        clipboard.setClip(ClipEntry(ClipData.newPlainText("code", code)))
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.menu_copy),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = code,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AppCodeFontFamily),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

/** A quote: surface with a primary bar on the leading edge. */
@Composable
private fun BlockquoteBlock(text: AnnotatedString, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = AppShape.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun HorizontalRuleBlock(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 8.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

private fun codeSpanStyle(background: Color) = SpanStyle(
    fontFamily = AppCodeFontFamily,
    background = background,
)

/**
 * A pipe table with one shared width per column, so cells line up across the
 * whole table. It scrolls horizontally when it exceeds the message column.
 */
@Composable
private fun TableBlock(table: MessageBlock.Table) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        val columnWidths = tableColumnWidths(table)
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(4.dp),
        ) {
            TableRow(cells = table.headers, columnWidths = columnWidths, isHeader = true)
            table.rows.forEach { row ->
                TableRow(cells = row, columnWidths = columnWidths, isHeader = false)
            }
        }
    }
}

/** Column widths estimated from character count, with a 40dp minimum. */
private fun tableColumnWidths(table: MessageBlock.Table): List<Dp> {
    val count = table.headers.size
    if (count == 0) return emptyList()
    val cellWidths = MutableList<Dp>(count) { 40.dp }
    fun update(column: Int, text: AnnotatedString) {
        if (column < count) {
            cellWidths[column] = maxOf(cellWidths[column], (text.text.length * 8).dp + 12.dp)
        }
    }
    table.headers.forEachIndexed { i, h -> update(i, h) }
    table.rows.forEach { row -> row.forEachIndexed { i, cell -> update(i, cell) } }
    return cellWidths
}

@Composable
private fun TableRow(
    cells: List<AnnotatedString>,
    columnWidths: List<Dp>,
    isHeader: Boolean,
) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        cells.forEachIndexed { i, cell ->
            if (i < columnWidths.size) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isHeader) MaterialTheme.colorScheme.surfaceContainer else Color.Unspecified,
                    modifier = Modifier.width(columnWidths[i]),
                ) {
                    Text(
                        text = cell,
                        style = if (isHeader) {
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

private val BOLD_SPAN_STYLE = SpanStyle(fontWeight = FontWeight.SemiBold)
private val ITALIC_SPAN_STYLE = SpanStyle(fontStyle = FontStyle.Italic)

private val URL_TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

private const val CODE_FENCE = "```"

/** Leading spaces a fence line may carry (code fenced inside a list item). */
private const val FENCE_MAX_INDENT = 4
