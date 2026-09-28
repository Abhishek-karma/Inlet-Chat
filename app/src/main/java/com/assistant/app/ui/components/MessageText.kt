package com.assistant.app.ui.components

import android.content.ClipData
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing
import com.assistant.app.ui.theme.AppTypographyStyles
import kotlinx.coroutines.delay

@Composable
fun MessageText(
    text: String,
    streaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    val blocks = remember(text, codeBackground, linkColor) {
        MarkdownParser.parse(text, codeBackground, linkColor)
    }

    // Elegant animated typing cursor for real-time streaming
    val cursorAlpha by if (streaming) {
        val transition = rememberInfiniteTransition(label = "cursor")
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cursorAlpha",
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        blocks.forEachIndexed { index, block ->
            val isLastBlock = index == blocks.lastIndex
            when (block) {
                is MessageBlock.Paragraph -> {
                    if (isLastBlock && streaming) {
                        val styledText = remember(block.text, cursorAlpha) {
                            buildAnnotatedString {
                                append(block.text)
                                withStyle(SpanStyle(color = linkColor.copy(alpha = cursorAlpha))) {
                                    append(" ▎")
                                }
                            }
                        }
                        Text(
                            text = styledText,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Text(
                            text = block.text,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                is MessageBlock.Code -> CodeBlock(code = block.code, language = block.language)
                is MessageBlock.Table -> TableBlock(table = block)
                is MessageBlock.Heading -> {
                    Text(
                        text = block.text,
                        style = if (block.level <= 2) {
                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        } else {
                            MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        },
                        modifier = Modifier.padding(top = AppSpacing.sm, bottom = AppSpacing.xs),
                    )
                }
                is MessageBlock.Diagram -> DiagramBlock(code = block.code)
                is MessageBlock.LaTeXBlock -> LaTeXBlock(formula = block.formula)
                is MessageBlock.Blockquote -> BlockquoteBlock(text = block.text)
                is MessageBlock.HorizontalRule -> HorizontalRuleBlock()
                is MessageBlock.ListItem -> {
                    val itemText = if (isLastBlock && streaming) {
                        remember(block.text, cursorAlpha) {
                            buildAnnotatedString {
                                append(block.text)
                                withStyle(SpanStyle(color = linkColor.copy(alpha = cursorAlpha))) {
                                    append(" ▎")
                                }
                            }
                        }
                    } else {
                        block.text
                    }
                    Row(
                        modifier = Modifier.padding(vertical = 1.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = block.marker + " ",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = AppSpacing.sm),
                        )
                        Text(
                            text = itemText,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LaTeXBlock(formula: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formula,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Serif
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

@Composable
private fun CodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language?.uppercase() ?: "CODE",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        clipboard.setClip(ClipEntry(ClipData.newPlainText("code", code)))
                        copied = true
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        text = if (copied) "Copied!" else stringResource(R.string.menu_copy),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = code,
                style = AppTypographyStyles.code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}

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
                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun HorizontalRuleBlock(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = AppSpacing.md),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun TableBlock(table: MessageBlock.Table) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        val columnWidths = tableColumnWidths(table)
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            TableRow(cells = table.headers, columnWidths = columnWidths, isHeader = true, rowIndex = 0)
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            table.rows.forEachIndexed { index, row ->
                TableRow(cells = row, columnWidths = columnWidths, isHeader = false, rowIndex = index + 1)
                if (index < table.rows.lastIndex) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun tableColumnWidths(table: MessageBlock.Table): List<Dp> {
    val count = table.headers.size
    if (count == 0) return emptyList()
    val cellWidths = MutableList<Dp>(count) { 40.dp }
    fun update(column: Int, text: AnnotatedString) {
        if (column < count) {
            cellWidths[column] = maxOf(cellWidths[column], (text.text.length * 8).dp + 24.dp)
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
    rowIndex: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isHeader) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else if (rowIndex % 2 == 0) {
                    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.25f)
                } else {
                    Color.Transparent
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { i, cell ->
            if (i < columnWidths.size) {
                Box(
                    modifier = Modifier
                        .width(columnWidths[i])
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = cell,
                        style = if (isHeader) {
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                        } else {
                            MaterialTheme.typography.bodyMedium
                        },
                    )
                }
            }
        }
    }
}

// Legacy backward-compatibility wrappers
fun messageBlocks(
    text: String,
    codeBackground: Color = Color.Transparent,
    linkColor: Color = Color.Unspecified,
): List<MessageBlock> {
    return MarkdownParser.parse(text, codeBackground, linkColor)
}

fun richText(
    text: String,
    codeBackground: Color = Color.Transparent,
    linkColor: Color = Color.Unspecified,
): AnnotatedString {
    return MarkdownParser.richText(text, codeBackground, linkColor)
}

