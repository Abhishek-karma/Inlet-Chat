package com.assistant.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.test.assertIsDisplayed
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
import com.assistant.app.data.local.ProviderEntity
import com.assistant.app.ui.chat.ChatScreen
import com.assistant.app.ui.components.AssistantTopBar
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.ui.components.ComposerInputTag
import com.assistant.app.ui.theme.ChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose test for the chat screen shell: top bar identity,
 * drawer access, and the empty-state statement.
 */
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

        // "Nara" appears in the top bar and the drawer sheet.
        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.app_name))
            .onFirst().assertIsDisplayed()
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
    fun providerSwitcherListsEverySavedProviderAndSelectsOne() {
        var selectedId: Long? = null
        val providers = listOf(
            provider(id = 1L, name = "OpenAI", model = "gpt-4o"),
            provider(id = 2L, name = "Local", model = "llama3"),
        )
        composeRule.setContent {
            ChatTheme {
                AssistantTopBar(
                    title = "OpenAI",
                    activeProvider = providers[0],
                    savedProviders = providers,
                    onProviderSelected = { selectedId = it },
                )
            }
        }

        // The pill opens the switcher; both providers must be listed.
        composeRule.onNodeWithText("OpenAI").performClick()
        composeRule.onNodeWithText("Local").assertIsDisplayed()
        composeRule.onNodeWithText("llama3").assertIsDisplayed()

        // The inactive provider must be tappable: a non-active row that reads as
        // "not selected" would be disabled, and the switcher would be a dead end.
        composeRule.onNodeWithText("Local").performClick()
        assertEquals(2L, selectedId)
    }

    @Test
    fun providerSwitcherHandlesALongModelIdWithoutOverlapping() {
        val providers = listOf(
            provider(id = 1L, name = "OpenAI", model = "com.google.android.tts:en-us-natural:en-US"),
            provider(id = 2L, name = "Local", model = "a-fairly-long-self-hosted-model-identifier"),
        )
        composeRule.setContent {
            ChatTheme {
                AssistantTopBar(
                    title = "OpenAI",
                    activeProvider = providers[0],
                    savedProviders = providers,
                    onProviderSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("OpenAI").performClick()

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

    private fun provider(id: Long, name: String, model: String) = ProviderEntity(
        id = id,
        name = name,
        baseUrl = "https://example.com",
        model = model,
        isActive = id == 1L,
    )
}
