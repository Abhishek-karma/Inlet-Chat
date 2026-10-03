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
import com.assistant.app.data.ModelDraft
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
import org.junit.Assert.assertTrue
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

    private fun setContent(seedKey: String? = null, onBack: (() -> Unit)? = null) {
        if (seedKey != null) {
            runBlocking {
                providerStore.addProvider(
                    ProviderDraft(name = "P", baseUrl = "https://api.example.com"),
                    seedKey,
                    listOf(ModelDraft(model = "m", isActive = true)),
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
                    onBack = onBack,
                )
            }
        }
    }

    private fun buttonIsEnabled(label: String): Boolean =
        composeRule.onAllNodesWithText(label).fetchSemanticsNodes()
            .any { !it.config.contains(SemanticsProperties.Disabled) }

    /**
     * Opens one settings sub-page from the root menu. The menu is grouped and
     * scrolls, so a row below the fold has to be scrolled into view first:
     * touch injection outside the window silently does nothing.
     */
    private fun openPage(sectionLabel: String) {
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText(sectionLabel))
        composeRule.onNodeWithText(sectionLabel).performClick()
        composeRule.waitForIdle()
    }

    /**
     * Scrolls a sub-page to [titleRes] and asserts it. Page section headers are
     * rendered upper case, so the match is case-insensitive.
     */
    private fun assertSectionOnPage(titleRes: Int) {
        val title = context.getString(titleRes)
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText(title, ignoreCase = true))
        composeRule.onNodeWithText(title, ignoreCase = true).assertIsDisplayed()
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
        // Read the version like SettingsScreen does, so the test follows the build config.
        val versionName = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName ?: ""
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText(versionName))
        composeRule.onNodeWithText(versionName).assertIsDisplayed()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText(context.getString(R.string.settings_check_for_updates)))
        composeRule.onNodeWithText(context.getString(R.string.settings_check_for_updates)).assertIsDisplayed()
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

    @Test
    fun everyAreaIsReachableFromTheMenu() {
        setContent()
        listOf(
            R.string.settings_section_provider,
            R.string.settings_section_voice,
            R.string.settings_section_appearance,
            R.string.settings_section_privacy,
            R.string.settings_section_help,
            R.string.settings_section_terms,
            R.string.settings_section_licenses,
            R.string.settings_section_about,
        ).forEach { area ->
            composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(context.getString(area)))
            composeRule.onNodeWithText(context.getString(area)).assertIsDisplayed()
        }
    }

    @Test
    fun privacyPageStatesWhatTheAppActuallyDoes() {
        setContent()
        openPage(context.getString(R.string.settings_section_privacy))

        listOf(
            R.string.privacy_provider_title,
            R.string.privacy_analytics_title,
            R.string.privacy_deletion_title,
        ).forEach { assertSectionOnPage(it) }
        assertSectionOnPage(R.string.privacy_provider_body)
    }

    @Test
    fun privacyPageIncludesLocalAndSearchSections() {
        setContent()
        openPage(context.getString(R.string.settings_section_privacy))
        assertSectionOnPage(R.string.privacy_local_title)
        assertSectionOnPage(R.string.privacy_search_title)
    }

    @Test
    fun termsPageCoversByokAndThirdPartyProviders() {
        setContent()
        openPage(context.getString(R.string.settings_section_terms))
        assertSectionOnPage(R.string.terms_byok_title)
        assertSectionOnPage(R.string.terms_providers_title)
        assertSectionOnPage(R.string.terms_cost_title)
        assertSectionOnPage(R.string.terms_search_title)
    }

    @Test
    fun helpPageExplainsProviderSetupAndFailures() {
        setContent()
        openPage(context.getString(R.string.settings_section_help))
        listOf(
            R.string.help_add_provider_title,
            R.string.help_api_key_title,
            R.string.help_invalid_key_title,
            R.string.help_endpoint_title,
            R.string.help_voice_title,
        ).forEach { assertSectionOnPage(it) }
    }

    @Test
    fun aboutPageLinksToPrivacyTermsHelpAndLicenses() {
        setContent()
        openPage(context.getString(R.string.settings_section_about))
        listOf(
            R.string.settings_about_privacy,
            R.string.settings_about_terms,
            R.string.settings_about_help,
            R.string.settings_about_licenses,
        ).forEach { assertSectionOnPage(it) }
    }

    @Test
    fun licensesPageShowsTheGeneratedList() {
        setContent()
        val asset = ApplicationProvider.getApplicationContext<Context>()
            .assets.open("licenses.txt")
            .bufferedReader()
            .use { it.readText() }
        val expectedCount = LibraryLicenses.parse(asset).size
        assertTrue("The build should generate at least one license entry", expectedCount > 0)

        openPage(context.getString(R.string.settings_section_licenses))
        composeRule.onNodeWithText(context.getString(R.string.licenses_intro)).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.licenses_count, expectedCount),
        ).assertIsDisplayed()
    }

    @Test
    fun providerListMarksTheActiveProviderAndInvitesSwitching() {
        setContent()
        runBlocking {
            providerStore.addProvider(
                ProviderDraft(name = "First", baseUrl = "https://a.example/v1"),
                "sk-1",
                listOf(ModelDraft(model = "m1", isActive = true)),
            )
            providerStore.addProvider(
                ProviderDraft(name = "Second", baseUrl = "https://b.example/v1"),
                "sk-2",
                listOf(ModelDraft(model = "m2", isActive = true)),
            )
        }
        openPage(context.getString(R.string.settings_section_provider))

        composeRule.onNodeWithText("First").assertIsDisplayed()
        composeRule.onNodeWithText("Second").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_provider_active))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_provider_inactive))
            .assertIsDisplayed()
    }

    @Test
    fun providerTypeMenuFillsEndpointFromTheChosenPreset() {
        setContent()
        openPage(context.getString(R.string.settings_section_provider))
        composeRule.onNodeWithText(context.getString(R.string.settings_add_provider)).performClick()
        awaitEditorOpen()

        // The type menu is built from the shipped presets plus the custom entry.
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText(context.getString(R.string.settings_provider_label)))
        composeRule.onNodeWithTag(SettingsProviderFieldTag).performClick()
        composeRule.onNodeWithText("OpenAI").assertIsDisplayed()
        // The custom entry appears in the open menu beside the field's own label.
        assertTrue(
            "Custom entry should be listed in the open menu",
            composeRule
                .onAllNodesWithText(context.getString(R.string.settings_provider_custom))
                .fetchSemanticsNodes()
                .size == 2,
        )

        // Choosing a preset fills the endpoint it stands for.
        composeRule.onNodeWithText("OpenAI").performClick()
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText("https://api.openai.com/v1"))
        composeRule.onNodeWithText("https://api.openai.com/v1").assertIsDisplayed()
    }

    @Test
    fun modelPickerCommitsATypedModelWithOneTap() {
        setContent()
        openPage(context.getString(R.string.settings_section_provider))
        composeRule.onNodeWithText(context.getString(R.string.settings_add_provider)).performClick()
        awaitEditorOpen()

        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText(context.getString(R.string.model_selector_title)))
        composeRule.onNodeWithText(context.getString(R.string.model_selector_title)).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithTag(ModelSelectorSearchTag).fetchSemanticsNodes().isNotEmpty()
        }

        // The search field doubles as free text for endpoints that serve no list.
        composeRule.onNodeWithTag(ModelSelectorSearchTag).performTextInput("typed-model")

        // One tap commits and closes; there is no confirm step anymore.
        composeRule.onNodeWithTag(ModelSelectorRowTag).performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(context.getString(R.string.model_selector_title))
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithText("typed-model").assertIsDisplayed()
    }

    @Test
    fun validationErrorsNameTheProblemAndTheNextStep() {
        listOf(
            ProviderStore.ERROR_NAME_REQUIRED,
            ProviderStore.ERROR_BASE_URL_INVALID,
            ProviderStore.ERROR_MODEL_REQUIRED,
            ProviderStore.ERROR_API_KEY_REQUIRED,
        ).forEach { message ->
            assertTrue(
                "Validation message should be more than a bare field name: $message",
                message.length > 30,
            )
        }
    }

    @Test
    fun backFromSubpageReturnsToSettingsRoot() {
        var chatBackCalled = false
        setContent(onBack = { chatBackCalled = true })

        // Open About page
        openPage(context.getString(R.string.settings_section_about))
        composeRule.onNodeWithText(context.getString(R.string.settings_about_description)).assertIsDisplayed()

        // Tap back in TopBar
        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()
        composeRule.waitForIdle()

        // Verifies returned to Settings root, not Chat
        assertTrue("onBack should not be called when popping subpage", !chatBackCalled)
        composeRule.onNodeWithText(context.getString(R.string.settings_section_provider)).assertIsDisplayed()

        // Tap back on Settings root
        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()
        composeRule.waitForIdle()
        assertTrue("onBack should be called when exiting settings root", chatBackCalled)
    }

    @Test
    fun systemBackFromSubpageReturnsToSettingsRoot() {
        var chatBackCalled = false
        setContent(onBack = { chatBackCalled = true })

        // Open Provider page
        openPage(context.getString(R.string.settings_section_provider))
        composeRule.onNodeWithText(context.getString(R.string.settings_provider_none)).assertIsDisplayed()

        // Dispatch system back
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        // Verifies returned to Settings root
        assertTrue("Chat onBack should not be invoked when subpage was popped", !chatBackCalled)
        composeRule.onNodeWithText(context.getString(R.string.settings_section_provider)).assertIsDisplayed()
    }

    @Test
    fun nestedSubpageNavigationRetainsStack() {
        setContent()

        // Settings -> About
        openPage(context.getString(R.string.settings_section_about))
        composeRule.onNodeWithText(context.getString(R.string.settings_about_description)).assertIsDisplayed()

        // About -> Licenses
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText(context.getString(R.string.settings_about_licenses)))
        composeRule.onNodeWithText(context.getString(R.string.settings_about_licenses)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.licenses_intro)).assertIsDisplayed()

        // Back from Licenses -> About
        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.settings_about_description)).assertIsDisplayed()

        // Back from About -> Settings root
        composeRule.onNodeWithContentDescription(context.getString(R.string.cd_back)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(context.getString(R.string.settings_section_provider)).assertIsDisplayed()
    }
}
