package com.assistant.app.ui.settings

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assistant.app.R
import com.assistant.app.data.settings.AppTheme
import com.assistant.app.data.settings.TextSize
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.components.AssistantTopBar
import com.assistant.app.ui.theme.AppCodeFontFamily
import com.assistant.app.ui.theme.AppMotion
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing
import com.assistant.app.ui.theme.appTween
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

internal enum class SettingsGroup(val titleRes: Int) {
    Ai(R.string.settings_group_ai),
    Chat(R.string.settings_group_chat),
    Data(R.string.settings_group_data),
    Support(R.string.settings_group_support),
    About(R.string.settings_group_about),
}

internal enum class SettingsPage(val titleRes: Int, val icon: Int, val group: SettingsGroup) {
    Provider(R.string.settings_section_provider, AppIcons.Sparkle, SettingsGroup.Ai),
    Voice(R.string.settings_section_voice, AppIcons.Speak, SettingsGroup.Chat),
    Appearance(R.string.settings_section_appearance, AppIcons.Palette, SettingsGroup.Chat),
    Privacy(R.string.settings_section_privacy, AppIcons.Info, SettingsGroup.Data),
    Help(R.string.settings_section_help, AppIcons.Chat, SettingsGroup.Support),
    Terms(R.string.settings_section_terms, AppIcons.Flag, SettingsGroup.About),
    Licenses(R.string.settings_section_licenses, AppIcons.Flag, SettingsGroup.About),
    About(R.string.settings_section_about, AppIcons.Info, SettingsGroup.About),
}

/**
 * Inlet Settings: Clear, calm configuration for providers, voice, and experience.
 */
@Composable
fun SettingsScreen(
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: PackageManager.NameNotFoundException) {
            ""
        }
    }
    var pageStack by rememberSaveable(
        stateSaver = listSaver<List<SettingsPage>, String>(
            save = { list -> list.map { it.name } },
            restore = { saved -> saved.mapNotNull { name -> runCatching { SettingsPage.valueOf(name) }.getOrNull() } },
        ),
    ) { mutableStateOf<List<SettingsPage>>(emptyList()) }

    val page = pageStack.lastOrNull()

    val handleBack: () -> Unit = {
        if (pageStack.isNotEmpty()) {
            pageStack = pageStack.dropLast(1)
        } else {
            onBack?.invoke()
        }
    }

    BackHandler(enabled = pageStack.isNotEmpty()) {
        handleBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AssistantTopBar(
                title = stringResource(page?.titleRes ?: R.string.settings_title),
                onBack = if (pageStack.isNotEmpty() || onBack != null) handleBack else null,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            val descending = page != null
            val settingsPageIn = fadeIn(appTween(AppMotion.MEDIUM)) +
                slideInHorizontally(appTween(AppMotion.MEDIUM)) { width ->
                    if (descending) width / 10 else -width / 10
                }
            val settingsPageOut = fadeOut(appTween(AppMotion.FAST)) +
                slideOutHorizontally(appTween(AppMotion.FAST)) { width ->
                    if (descending) -width / 10 else width / 10
                }

            AnimatedContent(
                targetState = page,
                transitionSpec = { settingsPageIn togetherWith settingsPageOut },
                modifier = Modifier.fillMaxSize(),
                label = "settingsPage",
            ) { current ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
                ) {
                    when (current) {
                        null -> SettingsMenu(
                            state = state,
                            versionName = versionName,
                            onOpen = { pageStack = pageStack + it },
                        )
                        SettingsPage.Provider -> ProviderPage(state = state, viewModel = viewModel)
                        SettingsPage.Voice -> VoicePage(state = state, viewModel = viewModel)
                        SettingsPage.Appearance -> AppearancePage(state = state, viewModel = viewModel)
                        SettingsPage.About -> AboutPage(
                            versionName = versionName,
                            state = state,
                            onCheckForUpdates = viewModel::checkForUpdates,
                            onToggleAutoCheckUpdates = viewModel::setAutoCheckUpdates,
                            onDismissUpdateDialog = viewModel::dismissUpdateDialog,
                        ) { pageStack = pageStack + it }
                        SettingsPage.Privacy -> LegalPage(R.string.privacy_intro, PRIVACY_SECTIONS)
                        SettingsPage.Help -> HelpPage()
                        SettingsPage.Terms -> LegalPage(R.string.terms_intro, TERMS_SECTIONS)
                        SettingsPage.Licenses -> LicensesPage()
                    }
                    Spacer(Modifier.height(AppSpacing.xxl))
                }
            }
        }
    }
}

@Composable
private fun SettingsMenu(
    state: SettingsUiState,
    versionName: String,
    onOpen: (SettingsPage) -> Unit,
) {
    SettingsGroup.entries.forEachIndexed { groupIndex, group ->
        val pages = SettingsPage.entries.filter { it.group == group }
        if (pages.isEmpty()) return@forEachIndexed
        if (groupIndex > 0) Spacer(Modifier.height(AppSpacing.lg))
        SettingsSectionHeader(text = stringResource(group.titleRes), isFirst = groupIndex == 0)
        SettingsCard {
            pages.forEachIndexed { index, page ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    label = stringResource(page.titleRes),
                    icon = page.icon,
                    value = settingsPageSummary(page, state, versionName),
                    onClick = { onOpen(page) },
                )
            }
        }
    }
}

@Composable
private fun settingsPageSummary(
    page: SettingsPage,
    state: SettingsUiState,
    versionName: String,
): String? = when (page) {
    SettingsPage.Provider ->
        state.providers.firstOrNull { it.isActive }?.name
            ?: stringResource(R.string.settings_not_configured)
    SettingsPage.Voice -> stringResource(
        when {
            !state.ttsAvailable -> R.string.settings_not_available
            state.voiceSpeed < 1.0f -> R.string.settings_speed_slow
            state.voiceSpeed > 1.0f -> R.string.settings_speed_fast
            else -> R.string.settings_speed_normal
        },
    )
    SettingsPage.Appearance -> stringResource(
        when (state.appearance) {
            AppTheme.SYSTEM -> R.string.settings_appearance_system
            AppTheme.LIGHT -> R.string.settings_appearance_light
            AppTheme.DARK -> R.string.settings_appearance_dark
        },
    )
    SettingsPage.About -> versionName
    SettingsPage.Privacy,
    SettingsPage.Help,
    SettingsPage.Terms,
    SettingsPage.Licenses -> null
}

@Composable
private fun VoicePage(state: SettingsUiState, viewModel: SettingsViewModel) {
    LaunchedEffect(Unit) { viewModel.loadVoices() }
    SettingsCard {
        SettingsRow(
            label = stringResource(R.string.settings_voice_output),
            icon = AppIcons.Speak,
            enabled = state.ttsAvailable && state.isLoaded,
            toggleable = state.voiceOutputEnabled to viewModel::setVoiceOutputEnabled,
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.settings_voice_auto_play),
            icon = AppIcons.Wave,
            enabled = state.ttsAvailable && state.isLoaded,
            toggleable = state.voiceAutoPlay to viewModel::setVoiceAutoPlay,
        )
        SettingsDivider()
        SettingsDialogRow(
            label = stringResource(R.string.settings_voice_speed),
            icon = AppIcons.Wave,
            value = stringResource(
                when {
                    state.voiceSpeed < 1.0f -> R.string.settings_speed_slow
                    state.voiceSpeed > 1.0f -> R.string.settings_speed_fast
                    else -> R.string.settings_speed_normal
                },
            ),
            enabled = state.ttsAvailable && state.isLoaded,
        ) { onDismiss ->
            SettingsChoiceDialog(
                title = stringResource(R.string.settings_voice_speed),
                options = listOf(
                    0.75f to stringResource(R.string.settings_speed_slow),
                    1.0f to stringResource(R.string.settings_speed_normal),
                    1.25f to stringResource(R.string.settings_speed_fast),
                ),
                selected = state.voiceSpeed,
                onSelect = {
                    viewModel.setVoiceSpeed(it)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        if (!state.ttsAvailable) {
            Text(
                text = stringResource(R.string.settings_voice_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
            )
        } else if (!state.voicesLoaded) {
            Text(
                text = stringResource(R.string.settings_voice_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
            )
        } else if (state.voiceOptions.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_voice_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
            )
        } else {
            SettingsDivider()
            SettingsDialogRow(
                label = stringResource(R.string.settings_voice_choice),
                icon = AppIcons.Speak,
                value = state.voiceOptions
                    .firstOrNull { it.id == state.voiceId }
                    ?.let { option ->
                        if (option.isNatural) {
                            "${option.label} · ${stringResource(R.string.settings_voice_natural)}"
                        } else {
                            option.label
                        }
                    }
                    ?: stringResource(R.string.settings_voice_default),
                enabled = state.isLoaded,
            ) { onDismiss ->
                VoicePickerDialog(
                    options = state.voiceOptions,
                    selected = state.voiceId,
                    onSelect = {
                        viewModel.setVoiceId(it)
                        onDismiss()
                    },
                    onDismiss = onDismiss,
                )
            }
        }
    }

    SettingsSectionHeader(text = stringResource(R.string.settings_section_reasoning))
    SettingsCard {
        SettingsRow(
            label = stringResource(R.string.settings_reasoning_visible),
            icon = AppIcons.Brain,
            description = stringResource(R.string.settings_reasoning_description),
            enabled = state.isLoaded,
            toggleable = state.reasoningVisible to viewModel::setReasoningVisible,
        )
    }
}

@Composable
private fun AppearancePage(state: SettingsUiState, viewModel: SettingsViewModel) {
    SettingsCard {
        SettingsDialogRow(
            label = stringResource(R.string.settings_theme),
            icon = AppIcons.Palette,
            value = stringResource(
                when (state.appearance) {
                    AppTheme.SYSTEM -> R.string.settings_appearance_system
                    AppTheme.LIGHT -> R.string.settings_appearance_light
                    AppTheme.DARK -> R.string.settings_appearance_dark
                },
            ),
        ) { onDismiss ->
            SettingsChoiceDialog(
                title = stringResource(R.string.settings_theme),
                options = listOf(
                    AppTheme.SYSTEM to stringResource(R.string.settings_appearance_system),
                    AppTheme.LIGHT to stringResource(R.string.settings_appearance_light),
                    AppTheme.DARK to stringResource(R.string.settings_appearance_dark),
                ),
                selected = state.appearance,
                onSelect = {
                    viewModel.setAppearance(it)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        SettingsDivider()
        SettingsDialogRow(
            label = stringResource(R.string.settings_text_size),
            icon = AppIcons.TextSize,
            value = stringResource(
                when (state.textSize) {
                    TextSize.SMALL -> R.string.settings_text_size_small
                    TextSize.NORMAL -> R.string.settings_text_size_normal
                    TextSize.LARGE -> R.string.settings_text_size_large
                },
            ),
        ) { onDismiss ->
            SettingsChoiceDialog(
                title = stringResource(R.string.settings_text_size),
                options = listOf(
                    TextSize.SMALL to stringResource(R.string.settings_text_size_small),
                    TextSize.NORMAL to stringResource(R.string.settings_text_size_normal),
                    TextSize.LARGE to stringResource(R.string.settings_text_size_large),
                ),
                selected = state.textSize,
                onSelect = {
                    viewModel.setTextSize(it)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
    }
}
