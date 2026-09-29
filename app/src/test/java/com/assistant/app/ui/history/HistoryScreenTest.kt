package com.assistant.app.ui.history

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.R
import com.assistant.app.ui.theme.ChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun historyScreenDisplaysEmptyStateWhenListIsEmpty() {
        var backPressed = false
        composeRule.setContent {
            ChatTheme {
                HistoryScreen(
                    conversations = emptyList(),
                    onOpen = {},
                    onDelete = {},
                    onTogglePin = { _, _ -> },
                    onRename = { _, _ -> },
                    shareText = { null },
                    onBack = { backPressed = true },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.history_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.history_empty_hint)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()
        assertTrue("Back callback should be triggered", backPressed)
    }

    @Test
    fun historyScreenRendersConversationsAndHandlesOpen() {
        val now = System.currentTimeMillis()
        val list = listOf(
            ConversationSummary(id = "c1", title = "Pinned Chat", updatedAt = now, pinned = true),
            ConversationSummary(id = "c2", title = "Recent Chat", updatedAt = now, pinned = false),
        )
        var openedId: String? = null

        composeRule.setContent {
            ChatTheme {
                HistoryScreen(
                    conversations = list,
                    onOpen = { openedId = it },
                    onDelete = {},
                    onTogglePin = { _, _ -> },
                    onRename = { _, _ -> },
                    shareText = { null },
                )
            }
        }

        composeRule.onNodeWithText("Pinned Chat").assertIsDisplayed()
        composeRule.onNodeWithText("Recent Chat").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.history_pinned), substring = true).assertIsDisplayed()

        composeRule.onNodeWithText("Recent Chat").performClick()
        assertEquals("c2", openedId)
    }

    @Test
    fun searchScreenFiltersConversationsByQuery() {
        val list = listOf(
            ConversationSummary(id = "c1", title = "Kotlin Coroutines", updatedAt = 1000L),
            ConversationSummary(id = "c2", title = "Compose Animation", updatedAt = 2000L),
        )
        var openedId: String? = null

        composeRule.setContent {
            ChatTheme {
                SearchScreen(
                    conversations = list,
                    onOpen = { openedId = it },
                )
            }
        }

        composeRule.onNodeWithText("Kotlin Coroutines").assertIsDisplayed()
        composeRule.onNodeWithText("Compose Animation").assertIsDisplayed()

        // Filter by typing query
        composeRule.onNodeWithText(context.getString(R.string.search_hint))
            .performTextInput("Coroutines")

        composeRule.onNodeWithText("Kotlin Coroutines").assertIsDisplayed()
        composeRule.onAllNodes(androidx.compose.ui.test.hasText("Compose Animation"))
            .fetchSemanticsNodes().isEmpty().also { assertTrue(it) }

        composeRule.onNodeWithText("Kotlin Coroutines").performClick()
        assertEquals("c1", openedId)
    }
}
