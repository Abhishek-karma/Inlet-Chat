package com.assistant.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.settings.AppPreferences
import com.assistant.app.ui.chat.ChatScreen
import com.assistant.app.ui.chat.ChatViewModel
import com.assistant.app.ui.components.AppDrawer
import com.assistant.app.ui.history.ConversationSummary
import com.assistant.app.ui.history.HistoryScreen
import com.assistant.app.ui.history.SearchScreen
import com.assistant.app.ui.onboarding.OnboardingScreen
import androidx.compose.ui.unit.IntOffset
import com.assistant.app.ui.settings.SettingsScreen
import com.assistant.app.ui.settings.SettingsViewModel
import com.assistant.app.ui.theme.AppMotion
import com.assistant.app.ui.theme.appTween
import kotlinx.coroutines.launch

private const val CHAT_ROUTE_BASE = "chat"
private const val CONVERSATION_ID_ARG = "conversationId"
private const val CHAT_ROUTE = "$CHAT_ROUTE_BASE?$CONVERSATION_ID_ARG={$CONVERSATION_ID_ARG}"
private const val HISTORY_ROUTE = "history"
private const val SEARCH_ROUTE = "search"
private const val SETTINGS_ROUTE = "settings"

/**
 * App navigation: chat is the start destination, and history, search,
 * settings, and provider setup push on top of it. Onboarding shows once
 * before the chat. History opens a conversation by navigating back to the
 * chat route with a [CONVERSATION_ID_ARG] argument.
 */
@Composable
fun AssistantNavHost(
    chatViewModelFactory: ViewModelProvider.Factory,
    settingsViewModelFactory: ViewModelProvider.Factory,
    appPreferences: AppPreferences,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val chatViewModel: ChatViewModel = viewModel(factory = chatViewModelFactory)

    // null until the persisted flag has actually been read. The gate must not
    // resolve against a placeholder: with `initial = true` the first frame
    // latched "already done", so a fresh install skipped onboarding forever.
    val onboardingDone by remember { appPreferences.onboardingDone }
        .collectAsState(initial = null)

    // Dismissed locally so the screen leaves on tap, rather than waiting for
    // the write to come back round.
    var finishedOnboarding by rememberSaveable { mutableStateOf(false) }

    if (onboardingDone == null) return

    if (!onboardingDone!! && !finishedOnboarding) {
        OnboardingScreen(
            onDone = {
                finishedOnboarding = true
                scope.launch { appPreferences.setOnboardingDone(true) }
            },
            modifier = modifier,
        )
        return
    }

    // Hoisted because the transition lambdas are not composable scopes, so they
    // cannot call appTween themselves. Alpha and offset need separate types.
    val navFade = appTween<Float>(AppMotion.MEDIUM)
    val navFadeFast = appTween<Float>(AppMotion.FAST)
    val navSlide = appTween<IntOffset>(AppMotion.MEDIUM)
    NavHost(
        navController = navController,
        startDestination = CHAT_ROUTE,
        modifier = modifier,
        // Subtle motion communicates hierarchy, not style.
        enterTransition = {
            fadeIn(navFade) + slideInVertically(navSlide) { it / 12 }
        },
        exitTransition = { fadeOut(navFadeFast) },
        popEnterTransition = { fadeIn(navFade) },
        popExitTransition = {
            fadeOut(navFadeFast) + slideOutVertically(navSlide) { it / 12 }
        },
    ) {
        composable(
            route = CHAT_ROUTE,
            arguments = listOf(
                navArgument(CONVERSATION_ID_ARG) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { entry ->
            val chatLlm by chatViewModel.chatLlm.collectAsState()
            val chatState by chatViewModel.uiState.collectAsState()
            val activeModel = (chatLlm as? ChatLlmState.Ready)?.model
            AppDrawer(
                drawerState = drawerState,
                activeModel = activeModel,
                isNewChat = entry.arguments?.getString(CONVERSATION_ID_ARG) == null &&
                    chatState.conversationId == null,
                onNewChat = {
                    scope.launch { drawerState.close() }
                    chatViewModel.newConversation()
                    navController.popBackStack(CHAT_ROUTE, inclusive = false)
                },
                onHistory = {
                    scope.launch { drawerState.close() }
                    navController.navigate(HISTORY_ROUTE)
                },
                onSettings = {
                    scope.launch { drawerState.close() }
                    navController.navigate(SETTINGS_ROUTE)
                },
            ) {
                ChatScreen(
                    onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenProviderSetup = { navController.navigate(SETTINGS_ROUTE) },
                    viewModelFactory = chatViewModelFactory,
                    pendingConversationId = entry.arguments?.getString(CONVERSATION_ID_ARG),
                )
            }
        }
        composable(HISTORY_ROUTE) {
            val conversations by chatViewModel.conversations.collectAsState(initial = emptyList())
            HistoryScreen(
                conversations = conversations.map {
                    ConversationSummary(it.id, it.title, it.updatedAt, it.pinned)
                },
                onOpen = { id ->
                    navController.navigate("$CHAT_ROUTE_BASE?$CONVERSATION_ID_ARG=$id") {
                        popUpTo(HISTORY_ROUTE) { inclusive = true }
                    }
                },
                onSearch = { navController.navigate(SEARCH_ROUTE) },
                onDelete = chatViewModel::deleteConversation,
                onTogglePin = chatViewModel::setConversationPinned,
                onRename = chatViewModel::renameConversation,
                shareText = chatViewModel::shareConversationText,
                onBack = { navController.popBackStack() },
            )
        }
        composable(SEARCH_ROUTE) {
            val conversations by chatViewModel.conversations.collectAsState(initial = emptyList())
            SearchScreen(
                conversations = conversations.map {
                    ConversationSummary(it.id, it.title, it.updatedAt, it.pinned)
                },
                onOpen = { id ->
                    navController.navigate("$CHAT_ROUTE_BASE?$CONVERSATION_ID_ARG=$id") {
                        popUpTo(SEARCH_ROUTE) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(SETTINGS_ROUTE) {
            SettingsScreen(
                viewModelFactory = settingsViewModelFactory,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
