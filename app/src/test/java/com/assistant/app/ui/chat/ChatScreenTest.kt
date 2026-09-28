package com.assistant.app.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.assistant.app.R
import com.assistant.app.data.ChatLlmState
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.llm.model.ProviderError
import com.assistant.app.ui.ScriptedChatFixture
import com.assistant.app.ui.components.ComposerInputTag
import com.assistant.app.ui.theme.ChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val WAIT_MS = 5_000L

/**
 * Robolectric Compose tests for the conversation screen, driven end-to-end
 * against the scripted fake provider: send + streaming, stop, error/retry,
 * and the new-chat action.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(
        fixture: ScriptedChatFixture,
        onOpenSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            ChatTheme {
                ChatScreen(
                    onOpenSettings = onOpenSettings,
                    viewModelFactory = fixture.factory,
                )
            }
        }
    }

    private fun string(resId: Int): String = composeRule.activity.getString(resId)

    private fun typeAndSend(text: String) {
        composeRule.onNodeWithTag(ComposerInputTag).performTextInput(text)
        composeRule.onNodeWithContentDescription(string(R.string.cd_send)).performClick()
    }

    @Test
    fun editAndResendTruncatesHistoryAndStartsNewReply() {
        val fixture = ScriptedChatFixture(listOf(ScriptedEvent.Emit("First reply")))
        setContent(fixture)
        typeAndSend("First")
        waitUntilText("First reply")

        // Long-press the user message and choose Edit and resend.
        composeRule.onNodeWithText("First").performTouchInput { longClick() }
        composeRule.onNodeWithText(string(R.string.menu_edit_and_resend)).performClick()

        // Edit mode routes the message content through the composer.
        composeRule.onNodeWithText(string(R.string.edit_banner_label)).assertIsDisplayed()
        composeRule.onNodeWithTag(ComposerInputTag).performTextReplacement("Edited")

        fixture.provider.script = listOf(ScriptedEvent.Emit("Edited reply"))
        composeRule.onNodeWithContentDescription(string(R.string.cd_send)).performClick()

        waitUntilText("Edited reply")
        composeRule.onNodeWithText("Edited").assertIsDisplayed()
        composeRule.onNodeWithText("Edited reply").assertIsDisplayed()
        // Edit mode ended with the send.
        composeRule.onNodeWithText(string(R.string.edit_banner_label)).assertDoesNotExist()
        // Everything after the edited user message was truncated.
        assertTrue(composeRule.onAllNodesWithText("First reply").fetchSemanticsNodes().isEmpty())
    }

    private fun waitUntilText(text: String, substring: Boolean = false) {
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            composeRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitUntilContentDescription(description: String) {
        composeRule.waitUntil(timeoutMillis = WAIT_MS) {
            composeRule.onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun sendShowsUserMessageAndStreamedAssistantReply() {
        val fixture = ScriptedChatFixture(
            listOf(ScriptedEvent.Emit("Hel"), ScriptedEvent.Emit("lo")),
        )
        setContent(fixture)

        // Send is disabled while the draft is blank.
        composeRule.onNodeWithContentDescription(string(R.string.cd_send)).assertIsNotEnabled()

        typeAndSend("Hi")

        waitUntilText("Hello")
        composeRule.onNodeWithText("Hi").assertIsDisplayed()
        composeRule.onNodeWithText("Hello").assertIsDisplayed()

        // An open conversation gains the new-chat action.
        composeRule.onNodeWithContentDescription(string(R.string.cd_new_chat)).assertIsDisplayed()
    }

    @Test
    fun newChatClearsTheConversationAndHidesTheAction() {
        val fixture = ScriptedChatFixture(listOf(ScriptedEvent.Emit("Hello")))
        setContent(fixture)
        typeAndSend("Hi")
        waitUntilText("Hello")

        composeRule.onNodeWithContentDescription(string(R.string.cd_new_chat)).performClick()

        composeRule.onNodeWithText(string(R.string.chat_empty_statement)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.cd_new_chat)).assertDoesNotExist()

        // The draft belonged to the old conversation: the fresh composer is empty.
        val editableText = composeRule.onNodeWithTag(ComposerInputTag)
            .fetchSemanticsNode().config[SemanticsProperties.EditableText]
        assertEquals("", editableText.text)
    }

    @Test
    fun stopDuringGenerationKeepsPartialTextAndReenablesSend() {
        val fixture = ScriptedChatFixture(
            listOf(ScriptedEvent.Emit("Hel"), ScriptedEvent.Delay(60_000)),
        )
        setContent(fixture)
        typeAndSend("Hi")

        // The caret is appended while streaming, so match by substring.
        waitUntilText("Hel", substring = true)
        composeRule.onNodeWithContentDescription(string(R.string.cd_stop)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(string(R.string.cd_send)).assertDoesNotExist()

        composeRule.onNodeWithContentDescription(string(R.string.cd_stop)).performClick()

        waitUntilContentDescription(string(R.string.cd_send))
        composeRule.onNodeWithText("Hel").assertIsDisplayed()

        // The composer is live again: it accepts input, which re-enables send.
        composeRule.onNodeWithTag(ComposerInputTag).performTextInput(" and more")
        composeRule.onNodeWithContentDescription(string(R.string.cd_send)).assertIsEnabled()
    }

    @Test
    fun errorBannerShowsUserMessageAndRetryRestartsGeneration() {
        val fixture = ScriptedChatFixture(
            listOf(ScriptedEvent.Delay(50), ScriptedEvent.Fail(ProviderError.InvalidCredentials)),
        )
        setContent(fixture)
        typeAndSend("Hi")

        // The banner text is exactly the provider's mapped user message.
        waitUntilText(ProviderError.InvalidCredentials.userMessage)
        composeRule.onNodeWithText(string(R.string.error_retry)).assertIsDisplayed()

        fixture.provider.script = listOf(ScriptedEvent.Emit("Recovered"))
        composeRule.onNodeWithText(string(R.string.error_retry)).performClick()

        waitUntilText("Recovered")
        composeRule.onNodeWithText("Recovered").assertIsDisplayed()
    }

    @Test
    fun needsSetupShowsCalmSetupStateAndOpensSettings() {
        var settingsOpened = false
        setContent(ScriptedChatFixture(emptyList(), ChatLlmState.NeedsSetup)) {
            settingsOpened = true
        }

        // The calm setup state replaces the conversation area and the composer.
        composeRule.onNodeWithText(string(R.string.chat_setup_required)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.chat_open_settings)).assertIsDisplayed()
        composeRule.onNodeWithTag(ComposerInputTag).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.chat_empty_statement)).assertDoesNotExist()

        composeRule.onNodeWithText(string(R.string.chat_open_settings)).performClick()
        assertTrue(settingsOpened)
    }
}
