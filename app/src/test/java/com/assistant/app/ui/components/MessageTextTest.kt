package com.assistant.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import com.assistant.app.ui.theme.AppCodeFontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for the markdown subset in [MessageText]: fenced code block
 * detection, inline code spans, clickable link annotations, bold/italic emphasis,
 * blockquotes, and horizontal rules. The helpers are pure, so these run
 * without composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageTextTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun codeBlockIsDetectedAndRenderedVerbatim() {
        val blocks = messageBlocks("Before\n```kotlin\nval x = 1\n```\nAfter")

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Paragraph)
        assertTrue(blocks[1] is MessageBlock.Code)
        assertTrue(blocks[2] is MessageBlock.Paragraph)
        assertEquals("val x = 1", (blocks[1] as MessageBlock.Code).code)
        // The language tag on the fence line is not part of the code.
        val code = (blocks[1] as MessageBlock.Code).code
        assertTrue(!code.contains("kotlin"))
        assertEquals("Before", (blocks[0] as MessageBlock.Paragraph).text.text)
        assertEquals("After", (blocks[2] as MessageBlock.Paragraph).text.text)
    }

    @Test
    fun headingsBecomeTheirOwnBlocks() {
        val blocks = messageBlocks("# Title\n## Section\n### Sub")

        assertEquals(3, blocks.size)
        val h1 = blocks[0] as MessageBlock.Heading
        val h2 = blocks[1] as MessageBlock.Heading
        val h3 = blocks[2] as MessageBlock.Heading
        assertEquals(1, h1.level)
        assertEquals("Title", h1.text.text)
        assertEquals(2, h2.level)
        assertEquals(3, h3.level)
    }

    @Test
    fun listItemsGetMarkersAndKeepInlineMarkdown() {
        val blocks = messageBlocks("- plain item\n* **bold item**\n1. first\n2) second")

        assertEquals(4, blocks.size)
        val first = blocks[0] as MessageBlock.ListItem
        assertEquals("\u2022", first.marker)
        assertEquals("plain item", first.text.text)
        val bold = blocks[1] as MessageBlock.ListItem
        assertEquals("bold item", bold.text.text)
        assertTrue(bold.text.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
        assertEquals("1.", (blocks[2] as MessageBlock.ListItem).marker)
        assertEquals("2.", (blocks[3] as MessageBlock.ListItem).marker)
    }

    @Test
    fun headingAndListLookalikesWithoutProperSyntaxStayProse() {
        val blocks = messageBlocks("#hashtag\n####Example\n1.Wed\n-\n4. ")

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MessageBlock.Paragraph)
        assertTrue((blocks.single() as MessageBlock.Paragraph).text.text.contains("#hashtag"))
        assertTrue((blocks.single() as MessageBlock.Paragraph).text.text.contains("####Example"))
    }

    @Test
    fun headingsAndListsSplitMergedParagraphs() {
        val blocks = messageBlocks("Intro line\n- item one\nBack to prose")

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Paragraph)
        assertTrue(blocks[1] is MessageBlock.ListItem)
        assertTrue(blocks[2] is MessageBlock.Paragraph)
        assertEquals("item one", (blocks[1] as MessageBlock.ListItem).text.text)
        assertEquals("Back to prose", (blocks[2] as MessageBlock.Paragraph).text.text)
    }

    @Test
    fun unterminatedFenceRendersTheRestAsCode() {
        val blocks = messageBlocks("Start\n```python\nprint(1)")

        assertEquals(2, blocks.size)
        assertEquals("print(1)", (blocks[1] as MessageBlock.Code).code)
    }

    @Test
    fun midSentenceBackticksRenderAsPlainText() {
        val text = "Wrap it in ```json blocks``` for clarity"
        val blocks = messageBlocks(text)

        // The fence is not at a line start, so nothing becomes a Code block
        // and no prose is swallowed.
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MessageBlock.Paragraph
        assertEquals(text, paragraph.text.text)
        assertTrue(paragraph.text.spanStyles.isEmpty())
    }

    @Test
    fun fenceEndingExactlyAtLanguageTagYieldsNoEmptyCodeBlock() {
        // An unterminated fence with a language tag and no content yet (the
        // mid-stream shape) yields no empty Code block — only the prose.
        val blocks = messageBlocks("Code:\n```kotlin")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Paragraph)
        assertEquals("Code:", (blocks[0] as MessageBlock.Paragraph).text.text)

        // A closed empty fence also renders nothing.
        assertEquals(0, messageBlocks("```\n```").size)
    }

    @Test
    fun plainTextStaysASingleParagraph() {
        val blocks = messageBlocks("just words, no markers")

        assertEquals(1, blocks.size)
        assertEquals("just words, no markers", (blocks[0] as MessageBlock.Paragraph).text.text)
    }

    @Test
    fun inlineCodeGetsMonospaceSpanAndDropsBackticks() {
        val text = richText("Run `ls -la` now", codeBackground = Color.Red)

        assertEquals("Run ls -la now", text.text)
        val codeRange = text.spanStyles.single { it.item.fontFamily == AppCodeFontFamily }
        assertEquals(4, codeRange.start)
        assertEquals(10, codeRange.end)
        assertEquals(Color.Red, codeRange.item.background)
    }

    @Test
    fun unclosedInlineCodeRendersLiterally() {
        val text = richText("Backtick ` never closed")

        assertEquals("Backtick ` never closed", text.text)
    }

    @Test
    fun urlBecomesLinkAnnotation() {
        val text = richText("See https://example.com/docs for more")

        assertEquals("See https://example.com/docs for more", text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        val link = links.single().item as LinkAnnotation.Url
        assertEquals("https://example.com/docs", link.url)
        assertEquals(4, links.single().start)
        assertEquals(28, links.single().end)
    }

    @Test
    fun boldAndItalicGetSpansAndDropMarkers() {
        val text = richText("**bold** and *slanted*")

        assertEquals("bold and slanted", text.text)
        val bold = text.spanStyles.single { it.item.fontWeight == FontWeight.SemiBold }
        assertEquals(0, bold.start)
        assertEquals(4, bold.end)
        val italic = text.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals(9, italic.start)
        assertEquals(16, italic.end)
    }

    @Test
    fun unclosedEmphasisRendersLiterally() {
        val text = richText("one * two ** three")

        assertEquals("one * two ** three", text.text)
    }

    @Test
    fun asterisksBetweenWordCharactersRenderLiterally() {
        val text = richText("2*3*4")

        assertEquals("2*3*4", text.text)
        assertTrue(text.spanStyles.isEmpty())
    }

    @Test
    fun linkTrailingPunctuationStaysOutsideTheUrl() {
        val text = richText("See https://example.com. Also (https://x.com/path) done")

        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(2, links.size)
        assertEquals(
            listOf("https://example.com", "https://x.com/path"),
            links.map { (it.item as LinkAnnotation.Url).url },
        )
        // The sentence punctuation remains part of the rendered text.
        assertEquals("See https://example.com. Also (https://x.com/path) done", text.text)
    }

    @Test
    fun linkGetsAVisualAffordanceSpan() {
        val text = richText(
            "Go to https://example.com now",
            linkColor = Color.Red,
        )

        val link = text.getLinkAnnotations(0, text.length).single()
        val style = (link.item as LinkAnnotation.Url).styles?.style
        assertEquals(Color.Red, style?.color)
        assertEquals(TextDecoration.Underline, style?.textDecoration)
    }

    @Test
    fun streamingContentRendersWithoutCaret() {
        composeRule.setContent {
            MaterialTheme { MessageText(text = "Answer with `code` and") }
        }

        composeRule.onNodeWithText("Answer with code and").assertExists()
    }

    @Test
    fun streamingTextIsShownAfterTheParseCadence() {
        var text by mutableStateOf("Growing ans")
        composeRule.setContent {
            MaterialTheme { MessageText(text = text, streaming = true) }
        }
        composeRule.onNodeWithText("Growing ans", substring = true).assertExists()

        text = "Growing answer text"
        composeRule.onNodeWithText("Growing ans", substring = true).assertExists()

        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithText("Growing answer text", substring = true).assertExists()
    }

    @Test
    fun finishedAnswerRendersAsText() {
        composeRule.setContent {
            MaterialTheme { MessageText(text = "Finished answer") }
        }

        composeRule.onNodeWithText("Finished answer").assertExists()
    }

    @Test
    fun emptyContentRendersNothing() {
        composeRule.setContent {
            MaterialTheme { MessageText(text = "") }
        }

        composeRule.onNodeWithText("▌").assertDoesNotExist()
    }

    @Test
    fun basicPipeTableParsesToATableBlock() {
        val blocks = messageBlocks("| A | B |\n| --- | --- |\n| 1 | 2 |")

        assertEquals(1, blocks.size)
        val table = blocks[0] as MessageBlock.Table
        assertEquals(listOf("A", "B"), table.headers.map { it.text })
        assertEquals(listOf(listOf("1", "2")), table.rows.map { row -> row.map { it.text } })
    }

    @Test
    fun tableCellInlineMarkdownIsRendered() {
        val blocks = messageBlocks("| **H** | x |\n| --- | --- |\n| a | b |")

        val table = blocks.single() as MessageBlock.Table
        assertEquals("H", table.headers[0].text)
        assertTrue(table.headers[0].spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
    }

    @Test
    fun escapedPipeDoesNotSplitACell() {
        val blocks = messageBlocks("| a\\|b | c |\n| --- | --- |\n| d | e |")

        val table = blocks.single() as MessageBlock.Table
        assertEquals(listOf("a|b", "c"), table.headers.map { it.text })
    }

    @Test
    fun tableWithoutSeparatorStaysAParagraph() {
        val blocks = messageBlocks("| a | b |\n| c | d |")

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MessageBlock.Paragraph)
    }

    @Test
    fun raggedRowsArePaddedAndTruncatedToHeaderWidth() {
        val blocks = messageBlocks("| A | B |\n| --- | --- |\n| 1 |\n| 1 | 2 | 3 |")

        val table = blocks.single() as MessageBlock.Table
        assertEquals(listOf(listOf("1", ""), listOf("1", "2")), table.rows.map { row -> row.map { it.text } })
    }

    @Test
    fun tableWithoutLeadingOrTrailingPipesParsesCorrectly() {
        val blocks = messageBlocks("A | B\n--- | ---\n1 | 2")

        val table = blocks.single() as MessageBlock.Table
        assertEquals(listOf("A", "B"), table.headers.map { it.text })
        assertEquals(listOf(listOf("1", "2")), table.rows.map { row -> row.map { it.text } })
    }

    @Test
    fun tableRendersWithHeaderAndBodyCells() {
        composeRule.setContent {
            MaterialTheme { MessageText(text = "| A | B |\n| --- | --- |\n| 1 | 2 |") }
        }

        composeRule.onNodeWithText("A").assertExists()
        composeRule.onNodeWithText("2").assertExists()
    }

    @Test
    fun consecutiveQuoteLinesMergeIntoOneBlock() {
        val blocks = messageBlocks("> first line\n> second line\n> third")

        assertEquals(1, blocks.size)
        val quote = blocks.single() as MessageBlock.Blockquote
        assertEquals("first line\nsecond line\nthird", quote.text.text)
    }

    @Test
    fun quoteTextKeepsInlineMarkdownAndNewlines() {
        val blocks = messageBlocks("> **bold** line\n> `code` line")

        val quote = blocks.single() as MessageBlock.Blockquote
        assertEquals("bold line\ncode line", quote.text.text)
        assertTrue(quote.text.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
        assertTrue(quote.text.spanStyles.any { it.item.fontFamily == AppCodeFontFamily })
    }

    @Test
    fun quoteMarkerWithoutSpaceStillQuotes() {
        val blocks = messageBlocks(">tight\n> loose")

        val quote = blocks.single() as MessageBlock.Blockquote
        assertEquals("tight\nloose", quote.text.text)
    }

    @Test
    fun bareGreaterThanLineContinuesTheQuote() {
        val blocks = messageBlocks("> above\n>\n> below")

        val quote = blocks.single() as MessageBlock.Blockquote
        assertEquals("above\n\nbelow", quote.text.text)
    }

    @Test
    fun quoteEndsAtUnmarkedLinesAndProseResumes() {
        val blocks = messageBlocks("Intro\n> quoted\nOutro")

        assertEquals(3, blocks.size)
        assertEquals("Intro", (blocks[0] as MessageBlock.Paragraph).text.text)
        assertEquals("quoted", (blocks[1] as MessageBlock.Blockquote).text.text)
        assertEquals("Outro", (blocks[2] as MessageBlock.Paragraph).text.text)
    }

    @Test
    fun quoteLineWithPipesIsNotATableHeader() {
        val blocks = messageBlocks("> | a | b |\nmore prose")

        assertTrue(blocks[0] is MessageBlock.Blockquote)
        assertTrue(blocks[1] is MessageBlock.Paragraph)
    }

    @Test
    fun horizontalRulesOfAllMarkersAreDetected() {
        assertTrue(messageBlocks("---").single() is MessageBlock.HorizontalRule)
        assertTrue(messageBlocks("***").single() is MessageBlock.HorizontalRule)
        assertTrue(messageBlocks("* * *").single() is MessageBlock.HorizontalRule)
        assertTrue(messageBlocks("_ _ _").single() is MessageBlock.HorizontalRule)
    }

    @Test
    fun ruleLinesSplitMergedParagraphs() {
        val blocks = messageBlocks("Above\n---\nBelow")

        assertEquals(3, blocks.size)
        assertEquals("Above", (blocks[0] as MessageBlock.Paragraph).text.text)
        assertTrue(blocks[1] is MessageBlock.HorizontalRule)
        assertEquals("Below", (blocks[2] as MessageBlock.Paragraph).text.text)
    }

    @Test
    fun dashBulletListLineIsNotARule() {
        val blocks = messageBlocks("- item")

        assertTrue(blocks.single() is MessageBlock.ListItem)
    }

    @Test
    fun pipeTableSeparatorIsNotARule() {
        val blocks = messageBlocks("| A | B |\n| --- | --- |\n| 1 | 2 |")

        assertTrue(blocks.single() is MessageBlock.Table)
    }

    @Test
    fun dashesAfterPipelessRowBecomeARuleNotATable() {
        val blocks = messageBlocks("a | b\n---\nc | d")

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Paragraph)
        assertTrue(blocks[1] is MessageBlock.HorizontalRule)
        assertTrue(blocks[2] is MessageBlock.Paragraph)
    }

    @Test
    fun blockquoteRendersItsText() {
        composeRule.setContent {
            MaterialTheme { MessageText(text = "> quoted words") }
        }

        composeRule.onNodeWithText("quoted words").assertExists()
    }

    @Test
    fun codeBlockShowsTheCopyAffordance() {
        composeRule.setContent {
            MaterialTheme { MessageText(text = "```kotlin\nval x = 1\n```") }
        }

        composeRule.onNodeWithText("Copy").assertExists()
    }



    @Test
    fun paragraphBeforeAndAfterTable() {
        val blocks = messageBlocks("Intro paragraph\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n\nAfter table paragraph")

        assertEquals(3, blocks.size)
        assertEquals("Intro paragraph", (blocks[0] as MessageBlock.Paragraph).text.text)
        assertTrue(blocks[1] is MessageBlock.Table)
        assertEquals("After table paragraph", (blocks[2] as MessageBlock.Paragraph).text.text)
    }

    @Test
    fun headingThenParagraphThenTable() {
        val blocks = messageBlocks("# Heading\n\nSome context.\n\n| X | Y |\n| --- | --- |\n| a | b |")

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Heading)
        assertTrue(blocks[1] is MessageBlock.Paragraph)
        assertTrue(blocks[2] is MessageBlock.Table)
    }

    @Test
    fun tableWithLongContentDoesNotCrash() {
        val longCell = "A".repeat(200)
        val blocks = messageBlocks("| $longCell | B |\n| --- | --- |\n| $longCell | D |")

        assertTrue(blocks.single() is MessageBlock.Table)
        val table = blocks.single() as MessageBlock.Table
        assertEquals(200, table.headers[0].text.length)
        assertEquals(200, table.rows[0][0].text.length)
    }

    @Test
    fun tableWithEmptyCells() {
        val blocks = messageBlocks("| A | B | C |\n| --- | --- | --- |\n| | 2 | |")

        val table = blocks.single() as MessageBlock.Table
        assertEquals(listOf("A", "B", "C"), table.headers.map { it.text })
        assertEquals(listOf("", "2", ""), table.rows[0].map { it.text })
    }

    @Test
    fun tableWithInlineCodeInCells() {
        val blocks = messageBlocks("| `code` | normal |\n| --- | --- |\n| a | `x` |")

        val table = blocks.single() as MessageBlock.Table
        assertTrue(table.headers[0].spanStyles.any { it.item.fontFamily == AppCodeFontFamily })
        assertTrue(table.rows[0][1].spanStyles.any { it.item.fontFamily == AppCodeFontFamily })
    }

    @Test
    fun tableWithBoldTextInCells() {
        val blocks = messageBlocks("| **Bold** | normal |\n| --- | --- |\n| a | *italic* |")

        val table = blocks.single() as MessageBlock.Table
        assertTrue(table.headers[0].spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
        assertTrue(table.rows[0][1].spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun tableWithLinksInCells() {
        val blocks = messageBlocks("| [link](https://example.com) | text |\n| --- | --- |\n| a | https://x.com |")

        val table = blocks.single() as MessageBlock.Table
        val headerLinks = table.headers[0].getLinkAnnotations(0, table.headers[0].length)
        assertEquals(1, headerLinks.size)
        val bodyLinks = table.rows[0][1].getLinkAnnotations(0, table.rows[0][1].length)
        assertEquals(1, bodyLinks.size)
    }

    @Test
    fun malformedTableWithUnevenRowsDoesNotCrash() {
        // More cells in a body row than header: pads with empty strings.
        val blocks = messageBlocks("| A | B |\n| --- | --- |\n| 1 | 2 | 3 |")

        assertTrue(blocks.single() is MessageBlock.Table)
        val table = blocks.single() as MessageBlock.Table
        // header has 2 columns, body row has 3 cells → padded to header width
        assertEquals(2, table.headers.size)
        assertEquals(listOf("1", "2"), table.rows[0].map { it.text })
    }

    @Test
    fun tableWithoutSeparatorStaysParagraph() {
        val blocks = messageBlocks("| a | b |\n| c | d |")

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is MessageBlock.Paragraph)
    }

    @Test
    fun streamedTableWithTrailingIncompleteLine() {
        // Mid-stream table: header and separator but no body yet — treated as table.
        val blocks = messageBlocks("| A | B |\n| --- | --- |")

        assertTrue(blocks.single() is MessageBlock.Table)
        val table = blocks.single() as MessageBlock.Table
        assertEquals(listOf("A", "B"), table.headers.map { it.text })
        assertTrue(table.rows.isEmpty())
    }

    @Test
    fun codeBlockFollowedByTable() {
        val blocks = messageBlocks("```kotlin\nval x = 1\n```\n| A | B |\n| --- | --- |\n| 1 | 2 |")

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Code)
        assertTrue(blocks[1] is MessageBlock.Table)
    }

    @Test
    fun listFollowedByTable() {
        val blocks = messageBlocks("- item one\n- item two\n| A | B |\n| --- | --- |\n| 1 | 2 |")

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessageBlock.ListItem)
        assertTrue(blocks[1] is MessageBlock.ListItem)
        assertTrue(blocks[2] is MessageBlock.Table)
    }

    @Test
    fun blockquoteFollowedByTable() {
        val blocks = messageBlocks("> quoted text\n| A | B |\n| --- | --- |\n| 1 | 2 |")

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Blockquote)
        assertTrue(blocks[1] is MessageBlock.Table)
    }

    @Test
    fun multipleTablesInOneResponse() {
        val blocks = messageBlocks(
            "| A |\n| --- |\n| 1 |\n\n" +
                "Some paragraph\n\n" +
                "| X | Y |\n| --- | --- |\n| a | b |",
        )

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Table)
        assertTrue(blocks[1] is MessageBlock.Paragraph)
        assertTrue(blocks[2] is MessageBlock.Table)
        val table1 = blocks[0] as MessageBlock.Table
        val table2 = blocks[2] as MessageBlock.Table
        assertEquals(listOf("A"), table1.headers.map { it.text })
        assertEquals(listOf("X", "Y"), table2.headers.map { it.text })
    }

    @Test
    fun tableWithEscapedPipesInCells() {
        val blocks = messageBlocks("| a\\|b | c |\n| --- | --- |\n| d | e\\|f |")

        val table = blocks.single() as MessageBlock.Table
        assertEquals(listOf("a|b", "c"), table.headers.map { it.text })
        assertEquals(listOf("d", "e|f"), table.rows[0].map { it.text })
    }

    @Test
    fun latexBlockParsesCorrectly() {
        val blocks = messageBlocks("Prose before\n$$\nE = mc^2\n$$\nProse after")

        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Paragraph)
        assertTrue(blocks[1] is MessageBlock.LaTeXBlock)
        assertTrue(blocks[2] is MessageBlock.Paragraph)
        assertEquals("E = mc^2", (blocks[1] as MessageBlock.LaTeXBlock).formula)
    }

    @Test
    fun inlineMathAndCurrencySafety() {
        // Correct inline math
        val mathText = richText("Solution is \$x^2 + y^2 = r^2\$ now.")
        assertEquals("Solution is x^2 + y^2 = r^2 now.", mathText.text)

        // Financial false positives must stay literal
        val priceText = richText("It costs \$10 and then another \$20 dollars.")
        assertEquals("It costs \$10 and then another \$20 dollars.", priceText.text)
    }

    @Test
    fun mermaidDiagramBlockParsesCorrectly() {
        val blocks = messageBlocks("```mermaid\ngraph TD\n  A --> B\n```")
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is MessageBlock.Diagram)
        assertEquals("graph TD\n  A --> B", (blocks[0] as MessageBlock.Diagram).code)
    }

    @Test
    fun diagramDocumentLoadsTheBundledRendererAndEscapesModelOutput() {
        val document = diagramDocument("graph TD\n  A[\"<img src=x onerror=alert(1)>\"] --> B")

        assertTrue(document.contains("file:///android_asset/diagram/mermaid.min.js"))
        assertTrue(document.contains("mermaid.initialize("))
        assertFalse(document.contains("<img src=x"))
        assertTrue(document.contains("&lt;img src=x onerror=alert(1)&gt;"))
    }

    @Test
    fun diagramDocumentDisablesHtmlLabelsForTheFullScreenViewer() {
        assertTrue(diagramDocument("graph TD\n A --> B", htmlLabels = true).contains("htmlLabels:false").not())
        assertTrue(diagramDocument("graph TD\n A --> B", htmlLabels = false).contains("htmlLabels:false"))
    }

    @Test
    fun performanceWithVeryLongMessagesIsLinearlyBounded() {
        val sb = StringBuilder()
        repeat(500) {
            sb.append("This is an extremely long line number \$it that goes on and on.\n")
            sb.append("- bullet point list item number \$it\n")
            sb.append("| Header A | Header B |\n| --- | --- |\n| Cell A \$it | Cell B \$it |\n")
        }
        val start = System.currentTimeMillis()
        val blocks = messageBlocks(sb.toString())
        val duration = System.currentTimeMillis() - start
        
        assertTrue(blocks.isNotEmpty())
        // Should parse 500 blocks of tables, lists and paragraphs in sub-second (usually < 100ms)
        assertTrue("Parsing extremely long text must be extremely fast", duration < 1000)
    }
}

