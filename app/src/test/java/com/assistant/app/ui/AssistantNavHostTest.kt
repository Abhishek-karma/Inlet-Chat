package com.assistant.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.assistant.app.R
import com.assistant.app.data.ProviderStore
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.data.settings.InMemorySecureKeyStore
import com.assistant.app.firstBounded
import com.assistant.app.llm.FakeLlmProvider
import com.assistant.app.ui.settings.SettingsViewModel
import com.assistant.app.ui.theme.ChatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric Compose test for the app navigation: chat is the start
 * destination, history/settings are reachable from the navigation drawer,
 * and system back returns to chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssistantNavHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Settings wiring over in-memory storage and a scripted provider. */
    private val settingsDb = androidx.room.Room
        .inMemoryDatabaseBuilder(context, com.assistant.app.data.local.ChatDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private val settingsPreferences = AppPreferences(context, Dispatchers.Unconfined)

    private val settingsFactory = SettingsViewModel.Factory(
        ProviderStore(settingsDb, settingsPreferences, InMemorySecureKeyStore()),
        settingsPreferences,
        InMemorySecureKeyStore(),
        { _, _, _ -> FakeLlmProvider(emptyList()) },
    )

    @After
    fun tearDown() {
        settingsDb.close()
    }

    /** Marks onboarding as already completed so a test reaches the chat. */
    private fun skipOnboarding() {
        runBlocking { settingsPreferences.setOnboardingDone(true) }
        // waitUntil takes a non-suspend lambda, so the read has to be blocking.
        runBlocking { settingsPreferences.onboardingDone.firstBounded { it } }
    }

    @Test
    fun onboardingShowsForAFreshInstall() {
        composeRule.setContent {
            ChatTheme {
                AssistantNavHost(
                    chatViewModelFactory = ScriptedChatFixture(emptyList()).factory,
                    settingsViewModelFactory = settingsFactory,
                    appPreferences = settingsPreferences,
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.onboarding_skip))
            .assertIsDisplayed()
    }

    @Test
    fun skipFinishesOnboardingAndPersistsIt() {
        composeRule.setContent {
            ChatTheme {
                AssistantNavHost(
                    chatViewModelFactory = ScriptedChatFixture(emptyList()).factory,
                    settingsViewModelFactory = settingsFactory,
                    appPreferences = settingsPreferences,
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.onboarding_skip)).performClick()

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.chat_empty_statement))
            .assertIsDisplayed()
        runBlocking {
            settingsPreferences.onboardingDone.firstBounded("onboarding persisted") { it }
        }
    }

    @Test
    fun onboardingIsSkippedWhenAlreadyDone() {
        skipOnboarding()

        composeRule.setContent {
            ChatTheme {
                AssistantNavHost(
                    chatViewModelFactory = ScriptedChatFixture(emptyList()).factory,
                    settingsViewModelFactory = settingsFactory,
                    appPreferences = settingsPreferences,
                )
            }
        }

        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.onboarding_skip))
            .assertCountEquals(0)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.chat_empty_statement))
            .assertIsDisplayed()
    }

    @Test
    fun startsOnChatEmptyState() {
        skipOnboarding()

        composeRule.setContent {
            ChatTheme {
                AssistantNavHost(
                    chatViewModelFactory = ScriptedChatFixture(emptyList()).factory,
                    settingsViewModelFactory = settingsFactory,
                    appPreferences = settingsPreferences,
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.chat_empty_statement)).assertIsDisplayed()
    }

    @Test
    fun settingsIsReachableAndBackReturnsToChat() {
        skipOnboarding()

        composeRule.setContent {
            ChatTheme {
                AssistantNavHost(
                    chatViewModelFactory = ScriptedChatFixture(emptyList()).factory,
                    settingsViewModelFactory = settingsFactory,
                    appPreferences = settingsPreferences,
                )
            }
        }

        openDrawer()
        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.drawer_settings))
            .onFirst().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                composeRule.activity.getString(R.string.settings_title),
            ).fetchSemanticsNodes().isNotEmpty()
        }

        pressSystemBack()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.chat_empty_statement)).assertIsDisplayed()
    }

    @Test
    fun historyIsReachableAndBackReturnsToChat() {
        skipOnboarding()

        composeRule.setContent {
            ChatTheme {
                AssistantNavHost(
                    chatViewModelFactory = ScriptedChatFixture(emptyList()).factory,
                    settingsViewModelFactory = settingsFactory,
                    appPreferences = settingsPreferences,
                )
            }
        }

        openDrawer()
        composeRule.onAllNodesWithText(composeRule.activity.getString(R.string.drawer_history))
            .onFirst().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                composeRule.activity.getString(R.string.history_empty),
            ).fetchSemanticsNodes().isNotEmpty()
        }

        pressSystemBack()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.chat_empty_statement)).assertIsDisplayed()
    }

    /**
     * Exercises the same path as the system back gesture: the activity's
     * back dispatcher, which the NavHost hooks into. (Espresso.pressBack
     * is not on the Robolectric unit-test classpath.)
     */
    private fun pressSystemBack() {
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
    }

    /** Opens the navigation drawer from the chat top bar. */
    private fun openDrawer() {
        composeRule.onAllNodesWithContentDescription(
            composeRule.activity.getString(R.string.cd_open_drawer),
        ).onFirst().performClick()
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(
                composeRule.activity.getString(R.string.drawer_new_chat),
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }
}

