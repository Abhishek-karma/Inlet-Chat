package com.assistant.app.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.R
import com.assistant.app.data.ProviderDraft
import com.assistant.app.data.ProviderStore
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.settings.InMemorySecureKeyStore
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.llm.ScriptedEvent
import com.assistant.app.ui.theme.ChatTheme
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose tests for the settings screen: the API key field never
 * exposes the stored key until the reveal toggle is on, the connection test is
 * disabled while the form does not validate, and a successful test shows the
 * success message inline.
 *
 * The screen scrolls, so anything below the fold is scrolled into view before
 * it is clicked; touch injection outside the window silently does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyStore = InMemorySecureKeyStore()
    private val appPreferences = AppPreferences(context, Dispatchers.Unconfined)
    private val db = Room
        .inMemoryDatabaseBuilder(context, com.assistant.app.data.local.ChatDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val providerStore = ProviderStore(db, appPreferences, keyStore, Dispatchers.Unconfined)

    @After
    fun tearDown() {
        db.close()
    }

    private fun setContent(seedKey: String? = null) {
        if (seedKey != null) {
            runBlocking {
                providerStore.addProvider(
                    ProviderDraft(name = "P", baseUrl = "https://api.example.com", model = "m"),
                    seedKey,
                )
            }
        }
        composeRule.setContent {
            ChatTheme {
                SettingsScreen(
                    viewModelFactory = SettingsViewModel.Factory(
                        providerStore,
                        appPreferences,
                        keyStore,
                        { _, _, _ -> FakeLlmProvider(listOf(ScriptedEvent.Emit("ok"))) },
                    ),
                )
            }
        }
    }

    private fun buttonIsEnabled(label: String): Boolean =
        composeRule.onAllNodesWithText(label).fetchSemanticsNodes()
            .any { !it.config.contains(SemanticsProperties.Disabled) }

    /**
     * Opens one settings sub-page from the root menu. Settings is a menu of
     * areas, so a test that exercises one area has to navigate to it first.
     */
    private fun openPage(sectionLabel: String) {
        composeRule.onNodeWithText(sectionLabel).performClick()
        composeRule.waitForIdle()
    }

    /** Waits until the add/edit editor's name field is on screen. */
    private fun awaitEditorOpen() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag(SettingsNameFieldTag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Waits until the validation message is shown (form is currently invalid). */
    private fun awaitValidationError() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(ProviderStore.ERROR_NAME_REQUIRED)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    @Test
    fun storedKeyIsHiddenUntilRevealed() {
        setContent(seedKey = "sk-secret-123")
        openPage(context.getString(R.string.settings_section_provider))
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("P").fetchSemanticsNodes().isNotEmpty()
        }

        // Opening the editor hydrates the masked placeholder, not the value.
        composeRule.onNodeWithText("P").performClick()
        awaitEditorOpen()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.settings_show_key))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("sk-secret-123").assertDoesNotExist()

        composeRule.onNodeWithText(context.getString(R.string.settings_show_key)).performClick()
        composeRule.onNodeWithText("sk-secret-123").assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.settings_hide_key)).performClick()
        composeRule.onNodeWithText("sk-secret-123").assertDoesNotExist()
    }

    @Test
    fun connectionTestDisabledWhileFormInvalid() {
        setContent()
        openPage(context.getString(R.string.settings_section_provider))
        composeRule.onNodeWithText(context.getString(R.string.settings_add_provider)).performClick()
        awaitValidationError()
        composeRule.onNodeWithText(context.getString(R.string.settings_test_connection))
            .assertIsNotEnabled()
    }

    @Test
    fun connectionTestShowsSuccess() {
        setContent()
        openPage(context.getString(R.string.settings_section_provider))
        composeRule.onNodeWithText(context.getString(R.string.settings_add_provider)).performClick()
        awaitValidationError()
        composeRule.onNodeWithTag(SettingsNameFieldTag).performTextInput("OpenAI")
        composeRule.onNodeWithTag(SettingsBaseUrlFieldTag).performTextInput("https://api.example.com/v1")
        composeRule.onNodeWithTag(SettingsModelFieldTag).performTextInput("test-model")
        composeRule.onNodeWithTag(SettingsApiKeyFieldTag).performTextInput("sk-1")

        // The connection test becomes usable once the form validates.
        val testLabel = context.getString(R.string.settings_test_connection)
        composeRule.waitUntil(10_000) { buttonIsEnabled(testLabel) }
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(testLabel))
        composeRule.onNodeWithText(testLabel)
            .assertIsEnabled()
            .performClick()

        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.settings_connection_success),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        val successLabel = context.getString(R.string.settings_connection_success)
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(successLabel))
        composeRule.onNodeWithText(successLabel).assertIsDisplayed()
    }

    @Test
    fun menuListsEveryAreaAndOpensItsPage() {
        setContent()
        val provider = context.getString(R.string.settings_section_provider)
        val appearance = context.getString(R.string.settings_section_appearance)

        // The root is a menu: the areas are listed, not expanded.
        composeRule.onNodeWithText(provider).assertIsDisplayed()
        composeRule.onNodeWithText(appearance).assertIsDisplayed()
        // Page content is not on screen until its area is opened.
        composeRule.onNodeWithText(context.getString(R.string.settings_theme))
            .assertDoesNotExist()

        openPage(appearance)
        composeRule.onNodeWithText(context.getString(R.string.settings_theme))
            .assertIsDisplayed()
    }

    @Test
    fun aboutSectionShowsVersion() {
        setContent()
        openPage(context.getString(R.string.settings_section_about))
        val versionLabel = context.getString(R.string.settings_version)
        composeRule.onNodeWithText(versionLabel).assertIsDisplayed()
        composeRule.onNodeWithText("1.0.0").assertIsDisplayed()
    }

    @Test
    fun openingVoicePageLeavesNoMenuRowsBehind() {
        setContent()
        openPage(context.getString(R.string.settings_section_voice))

        composeRule.onNodeWithText(context.getString(R.string.settings_voice_output))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_reasoning_visible))
            .assertIsDisplayed()

        composeRule.onNodeWithText(context.getString(R.string.settings_section_appearance))
            .assertDoesNotExist()
    }

    @Test
    fun returningToMenuDropsTheSubPage() {
        setContent()
        openPage(context.getString(R.string.settings_section_voice))
        composeRule.onNodeWithText(context.getString(R.string.settings_voice_output))
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_back))
            .performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(context.getString(R.string.settings_section_voice))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_voice_output))
            .assertDoesNotExist()
    }
}
