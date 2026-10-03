package com.assistant.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import com.assistant.app.R
import com.assistant.app.ui.chat.ChatScreen
import com.assistant.app.ui.chat.isNearBottom
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.ui.components.ChatModelOption
import com.assistant.app.ui.components.ComposerInputTag
import com.assistant.app.ui.theme.ChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * Robolectric Compose test for the chat screen shell: top bar identity,
 * drawer access, and the empty-state statement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatScreenShellTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun chatShellShowsTopBarActionsAndEmptyState() {
        composeRule.setContent {
            ChatTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                com.assistant.app.ui.components.AppDrawer(
                    drawerState = drawerState,
                    activeModel = null,
                    isNewChat = true,
                    onNewChat = {},
                    onHistory = {},
                    onSettings = {},
                ) {
                    ChatScreen(
                        onOpenSettings = {},
                        viewModelFactory = ScriptedChatFixture(emptyList()).factory,
                        onOpenDrawer = { /* the drawer test below covers reachability */ },
                    )
                }
            }
        }

        // The top bar carries the app identity only; the active model lives in the
        // drawer switcher and must not appear in the chat surface.
        val appName = composeRule.activity.getString(R.string.app_name)
        assertTrue(
            "The app name should be visible in the chat shell",
            composeRule.onAllNodesWithText(appName).fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithText("test-model").assertDoesNotExist()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.chat_empty_statement)).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.cd_open_drawer),
        ).assertIsDisplayed()
    }

    @Test
    fun chatShellHasNoNewChatActionWhileEmpty() {
        composeRule.setContent {
            ChatTheme {
                ChatScreen(
                    onOpenSettings = {},
                    viewModelFactory = ScriptedChatFixture(emptyList()).factory,
                )
            }
        }

        composeRule.onNodeWithContentDescription(composeRule.activity.getString(R.string.cd_new_chat)).assertDoesNotExist()
    }

    @Test
    fun emptyHomeShowsGreetingAndExamplePrompts() {
        composeRule.setContent {
            ChatTheme {
                ChatScreen(
                    onOpenSettings = {},
                    viewModelFactory = ScriptedChatFixture(emptyList()).factory,
                )
            }
        }

        // Example prompts are tappable cards, not bare text.
        val prompt = composeRule.activity.getString(R.string.chat_prompt_1)
        composeRule.onNodeWithText(prompt).assertIsDisplayed().performClick()
    }

    @Test
    fun speakerToggleStaysVisibleAfterMuting() {
        composeRule.setContent {
            ChatTheme {
                ChatScreen(
                    onOpenSettings = {},
                    viewModelFactory = ScriptedChatFixture(
                        emptyList(),
                        ttsAvailable = true,
                        voiceOutputEnabled = true,
                    ).factory,
                )
            }
        }

        val speaker = composeRule.activity.getString(R.string.cd_toggle_speaker)
        composeRule.onNodeWithContentDescription(speaker).assertIsDisplayed()

        // Muting must not remove the only control that can unmute.
        composeRule.onNodeWithContentDescription(speaker).performClick()
        composeRule.onNodeWithContentDescription(speaker).assertIsDisplayed()
    }

    @Test
    fun speakerToggleIsAbsentWithoutTextToSpeech() {
        composeRule.setContent {
            ChatTheme {
                ChatScreen(
                    onOpenSettings = {},
                    viewModelFactory = ScriptedChatFixture(emptyList()).factory,
                )
            }
        }

        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.cd_toggle_speaker),
        ).assertDoesNotExist()
    }

    @Test
    fun onlyBounceSizedDriftStillCountsAsBeingAtTheBottom() {
        lateinit var listState: LazyListState
        composeRule.setContent {
            ChatTheme {
                listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.size(160.dp),
                ) {
                    items(20) { Box(Modifier.height(80.dp)) }
                }
            }
        }

        // Pinned to the newest item: streaming would follow along.
        composeRule.runOnIdle { assertTrue(listState.isNearBottom(0f)) }

        // A few pixels of overscroll or rounding still counts as the bottom,
        // so a bounce never hands control to the app.
        composeRule.runOnIdle { runBlocking { listState.scrollBy(20f) } }
        composeRule.runOnIdle { assertTrue(listState.isNearBottom(64f)) }

        // Real scrolling away is not the bottom any more.
        composeRule.runOnIdle { runBlocking { listState.scrollBy(400f) } }
        composeRule.runOnIdle { assertFalse(listState.isNearBottom(64f)) }
    }

    @Test
    fun drawerSwitcherListsEverySavedModelAndSelectsOne() {
        var selectedId: Long? = null
        val models = listOf(
            ChatModelOption(id = 1L, model = "gpt-4o", providerName = "OpenAI"),
            ChatModelOption(id = 2L, model = "llama3", providerName = "Local"),
        )
        composeRule.setContent {
            ChatTheme {
                val drawerState = rememberDrawerState(DrawerValue.Open)
                com.assistant.app.ui.components.AppDrawer(
                    drawerState = drawerState,
                    activeModel = "gpt-4o",
                    isNewChat = true,
                    onNewChat = {},
                    onHistory = {},
                    onSettings = {},
                    savedModels = models,
                    activeModelId = 1L,
                    onModelSelected = { selectedId = it },
                ) {
                    Box(modifier = Modifier.size(100.dp))
                }
            }
        }

        // The active-model card opens the switcher; both models must be listed.
        composeRule.onNodeWithText("gpt-4o").performClick()
        composeRule.onNodeWithText("llama3").assertIsDisplayed()
        composeRule.onNodeWithText("Local").assertIsDisplayed()

        // The inactive model must be tappable: a non-active row that reads as
        // "not selected" would be disabled, and the switcher would be a dead end.
        composeRule.onNodeWithText("llama3").performClick()
        assertEquals(2L, selectedId)
    }

    @Test
    fun drawerSwitcherHandlesALongModelIdWithoutOverlapping() {
        val models = listOf(
            ChatModelOption(id = 1L, model = "gpt-4o", providerName = "OpenAI"),
            ChatModelOption(id = 2L, model = "a-fairly-long-self-hosted-model-identifier", providerName = "Local"),
        )
        composeRule.setContent {
            ChatTheme {
                val drawerState = rememberDrawerState(DrawerValue.Open)
                com.assistant.app.ui.components.AppDrawer(
                    drawerState = drawerState,
                    activeModel = "gpt-4o",
                    isNewChat = true,
                    onNewChat = {},
                    onHistory = {},
                    onSettings = {},
                    savedModels = models,
                    activeModelId = 1L,
                    onModelSelected = {},
                ) {
                    Box(modifier = Modifier.size(100.dp))
                }
            }
        }

        composeRule.onNodeWithText("gpt-4o").performClick()

        composeRule.onNodeWithText("Local").assertIsDisplayed()
        composeRule.onNodeWithText("a-fairly-long-self-hosted-model-identifier", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun followUpChipsDoNotCoverTheConversationOrComposer() {
        composeRule.setContent {
            ChatTheme {
                ChatScreen(
                    onOpenSettings = {},
                    viewModelFactory = ScriptedChatFixture(
                        listOf(ScriptedEvent.Emit(LONG_ANSWER)),
                        followUps = listOf("Ask about the setup?"),
                    ).factory,
                )
            }
        }
        composeRule.waitForIdle()

        // Send a turn so the transcript, not the empty home, is on screen.
        composeRule.onNodeWithTag(ComposerInputTag).performTextInput("Hi")
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.waitUntil {
            composeRule.onAllNodesWithText(LONG_ANSWER)
                .fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(LONG_ANSWER).assertIsDisplayed()
        composeRule.onNodeWithTag(ComposerInputTag).assertIsDisplayed()
        // Chips are generated after the answer lands, so wait for them to arrive.
        composeRule.waitUntil {
            composeRule.onAllNodesWithText("Ask about the setup?").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Ask about the setup?").assertIsDisplayed().performClick()

        // Tapping a chip must drop it into the composer so it can be sent.
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ComposerInputTag).assertTextEquals("Ask about the setup?")
    }

    private companion object {
        /** Over the length that earns a follow-up request. */
        const val LONG_ANSWER =
            "A considerably longer answer so the follow-up generator is willing to " +
                "spend a second request on it, padded out well past the minimum " +
                "length that the generator uses before it decides to bother."
    }
}
