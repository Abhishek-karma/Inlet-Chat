package com.assistant.app.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.assistant.app.voice.VoiceOption

internal const val SettingsNameFieldTag = "settings_name_field"
internal const val SettingsBaseUrlFieldTag = "settings_base_url_field"
internal const val SettingsApiKeyFieldTag = "settings_api_key_field"
internal const val SettingsModelFieldTag = "settings_model_field"

private const val LICENSES_ASSET = "licenses.txt"

internal data class LegalSection(val titleRes: Int, val bodyRes: Int)

private val PRIVACY_SECTIONS = listOf(
    LegalSection(R.string.privacy_local_title, R.string.privacy_local_body),
    LegalSection(R.string.privacy_provider_title, R.string.privacy_provider_body),
    LegalSection(R.string.privacy_search_title, R.string.privacy_search_body),
    LegalSection(R.string.privacy_voice_title, R.string.privacy_voice_body),
    LegalSection(R.string.privacy_analytics_title, R.string.privacy_analytics_body),
    LegalSection(R.string.privacy_deletion_title, R.string.privacy_deletion_body),
)

private val TERMS_SECTIONS = listOf(
    LegalSection(R.string.terms_byok_title, R.string.terms_byok_body),
    LegalSection(R.string.terms_providers_title, R.string.terms_providers_body),
    LegalSection(R.string.terms_cost_title, R.string.terms_cost_body),
    LegalSection(R.string.terms_content_title, R.string.terms_content_body),
    LegalSection(R.string.terms_search_title, R.string.terms_search_body),
    LegalSection(R.string.terms_availability_title, R.string.terms_availability_body),
    LegalSection(R.string.terms_conduct_title, R.string.terms_conduct_body),
)

private val HELP_SECTIONS = listOf(
    LegalSection(R.string.help_add_provider_title, R.string.help_add_provider_body),
    LegalSection(R.string.help_api_key_title, R.string.help_api_key_body),
    LegalSection(R.string.help_test_title, R.string.help_test_body),
    LegalSection(R.string.help_invalid_key_title, R.string.help_invalid_key_body),
    LegalSection(R.string.help_endpoint_title, R.string.help_endpoint_body),
    LegalSection(R.string.help_model_title, R.string.help_model_body),
    LegalSection(R.string.help_voice_title, R.string.help_voice_body),
    LegalSection(R.string.help_attachments_title, R.string.help_attachments_body),
    LegalSection(R.string.help_search_title, R.string.help_search_body),
    LegalSection(R.string.help_contact_title, R.string.help_contact_body),
)

private enum class SettingsGroup(val titleRes: Int) {
    Ai(R.string.settings_group_ai),
    Chat(R.string.settings_group_chat),
    Search(R.string.settings_group_search),
    Data(R.string.settings_group_data),
    Support(R.string.settings_group_support),
    About(R.string.settings_group_about),
}

private enum class SettingsPage(val titleRes: Int, val icon: Int, val group: SettingsGroup) {
    Provider(R.string.settings_section_provider, AppIcons.Sparkle, SettingsGroup.Ai),
    Voice(R.string.settings_section_voice, AppIcons.Speak, SettingsGroup.Chat),
    Appearance(R.string.settings_section_appearance, AppIcons.Palette, SettingsGroup.Chat),
    Search(R.string.settings_section_search, AppIcons.Globe, SettingsGroup.Search),
    Privacy(R.string.settings_section_privacy, AppIcons.Info, SettingsGroup.Data),
    Help(R.string.settings_section_help, AppIcons.Chat, SettingsGroup.Support),
    Terms(R.string.settings_section_terms, AppIcons.Flag, SettingsGroup.About),
    Licenses(R.string.settings_section_licenses, AppIcons.Flag, SettingsGroup.About),
    About(R.string.settings_section_about, AppIcons.Info, SettingsGroup.About),
}

/**
 * Nara Settings: Clear, calm configuration for providers, voice, and experience.
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
    var page by remember { mutableStateOf<SettingsPage?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            AssistantTopBar(
                title = stringResource(page?.titleRes ?: R.string.settings_title),
                onBack = if (page != null) {
                    { page = null }
                } else {
                    onBack
                },
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
                            onOpen = { page = it },
                        )
                        SettingsPage.Provider -> ProviderPage(state = state, viewModel = viewModel)
                        SettingsPage.Voice -> VoicePage(state = state, viewModel = viewModel)
                        SettingsPage.Search -> SearchPage(state = state, viewModel = viewModel)
                        SettingsPage.Appearance -> AppearancePage(state = state, viewModel = viewModel)
                        SettingsPage.About -> AboutPage(versionName = versionName) { page = it }
                        SettingsPage.Privacy -> LegalPage(R.string.privacy_intro, PRIVACY_SECTIONS)
                        SettingsPage.Help -> LegalPage(R.string.help_intro, HELP_SECTIONS)
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
    SettingsPage.Search -> stringResource(
        if (state.searchConfigured) {
            R.string.settings_configured
        } else {
            R.string.settings_not_configured
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
private fun ProviderPage(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    if (state.isEditing) {
        ProviderEditor(state = state, viewModel = viewModel)
        return
    }

    SettingsCard {
        if (state.providers.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_provider_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(AppSpacing.lg),
            )
        }
        state.providers.forEachIndexed { index, provider ->
            if (index > 0) SettingsDivider()
            ProviderRow(
                provider = provider,
                onEdit = { viewModel.edit(provider.id) },
                onActivate = { viewModel.activateProvider(provider.id) },
            )
        }
        if (state.providers.isNotEmpty()) SettingsDivider()
        SettingsActionRow(
            label = stringResource(R.string.settings_add_provider),
            onClick = viewModel::startAdd,
        )
    }
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
private fun SearchPage(state: SettingsUiState, viewModel: SettingsViewModel) {
    Text(
        text = stringResource(R.string.settings_search_description),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.searchEndpointInput,
        onValueChange = viewModel::setSearchEndpointInput,
        label = { Text(stringResource(R.string.settings_search_endpoint)) },
        placeholder = { Text(state.storedSearchEndpoint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        shape = AppShape.small,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isSearchSaving && !state.isSearchTesting,
    )
    OutlinedTextField(
        value = state.searchApiKeyInput,
        onValueChange = viewModel::setSearchApiKeyInput,
        label = { Text(stringResource(R.string.settings_search_api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        placeholder = {
            if (state.storedSearchKey != null && state.searchApiKeyInput.isEmpty()) {
                Text(stringResource(R.string.settings_api_key_saved_placeholder))
            }
        },
        shape = AppShape.small,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isSearchSaving && !state.isSearchTesting,
    )
    state.searchFormError?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    OutlinedButton(
        onClick = viewModel::testSearch,
        enabled = state.isLoaded && !state.isSearchTesting && !state.isSearchSaving,
        shape = AppShape.pill,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.isSearchTesting) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = AppSpacing.sm),
                strokeWidth = 2.dp,
            )
        }
        Text(
            stringResource(
                if (state.isSearchTesting) {
                    R.string.settings_search_testing
                } else {
                    R.string.settings_search_test
                },
            ),
        )
    }

    state.searchTestOutcome?.let { outcome ->
        val color = when (outcome) {
            is ConnectionOutcome.Success -> MaterialTheme.colorScheme.primary
            is ConnectionOutcome.Failure -> MaterialTheme.colorScheme.error
        }
        val text = when (outcome) {
            is ConnectionOutcome.Success -> stringResource(R.string.settings_connection_success)
            is ConnectionOutcome.Failure -> outcome.message
        }
        Column(modifier = Modifier.padding(top = AppSpacing.xs)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = color,
            )
            if (outcome is ConnectionOutcome.Failure && outcome.detail != null) {
                Text(
                    text = outcome.detail,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppCodeFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                            shape = AppShape.small,
                        )
                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = viewModel::resetSearchEndpointToDefault,
            enabled = !state.isSearchSaving,
        ) {
            Text(stringResource(R.string.settings_search_reset_default))
        }

        Button(
            onClick = viewModel::saveSearch,
            enabled = state.isLoaded && !state.isSearchSaving && !state.isSearchTesting,
            shape = AppShape.pill,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Text(
                stringResource(
                    if (state.isSearchSaving) R.string.settings_saving else R.string.settings_save,
                ),
            )
        }
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

@Composable
private fun AboutPage(versionName: String, onOpen: (SettingsPage) -> Unit) {
    SettingsSectionHeader(text = stringResource(R.string.app_name), isFirst = true)
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.lg, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_inlet_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(AppSpacing.md))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = stringResource(R.string.settings_about_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.md),
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.settings_version),
            icon = AppIcons.Info,
            value = versionName,
        )
    }

    SettingsSectionHeader(text = stringResource(R.string.settings_group_data))
    SettingsCard {
        SettingsNavRow(
            label = stringResource(R.string.settings_about_privacy),
            icon = AppIcons.Info,
            onClick = { onOpen(SettingsPage.Privacy) },
        )
        SettingsDivider()
        SettingsNavRow(
            label = stringResource(R.string.settings_about_terms),
            icon = AppIcons.Flag,
            onClick = { onOpen(SettingsPage.Terms) },
        )
    }

    SettingsSectionHeader(text = stringResource(R.string.settings_group_support))
    SettingsCard {
        SettingsNavRow(
            label = stringResource(R.string.settings_about_help),
            icon = AppIcons.Chat,
            onClick = { onOpen(SettingsPage.Help) },
        )
        SettingsDivider()
        SettingsNavRow(
            label = stringResource(R.string.settings_about_licenses),
            icon = AppIcons.Flag,
            onClick = { onOpen(SettingsPage.Licenses) },
        )
    }
}

/**
 * One heading, one block of text per [LegalSection]. Sections are plain
 * scrolling text, not cards, so a long policy stays readable.
 */
@Composable
private fun LegalPage(introRes: Int, sections: List<LegalSection>) {
    Text(
        text = stringResource(introRes),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    sections.forEach { section ->
        SettingsSectionHeader(text = stringResource(section.titleRes))
        Text(
            text = stringResource(section.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsSectionHeader(text = stringResource(R.string.legal_todo_header))
    SettingsCard {
        SettingsRow(
            label = stringResource(R.string.legal_todo_label),
            icon = AppIcons.Flag,
            description = stringResource(
                if (sections === TERMS_SECTIONS) R.string.legal_todo_terms else R.string.legal_todo_privacy,
            ),
        )
    }
}

@Composable
private fun LicensesPage() {
    val context = LocalContext.current
    val text = remember {
        runCatching {
            context.assets.open(LICENSES_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull()
    }
    Text(
        text = stringResource(R.string.licenses_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (text == null) {
        Text(
            text = stringResource(R.string.licenses_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    val entries = remember(text) { LibraryLicenses.parse(text) }
    Text(
        text = stringResource(R.string.licenses_count, entries.size),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    entries.forEach { entry ->
        SettingsSectionHeader(text = entry.name)
        if (entry.license.isNotEmpty()) {
            Text(
                text = entry.license,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppCodeFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How to get an API key for the endpoint in the form. A known preset links to
 * that provider's own key page; an unknown endpoint says so instead of
 * guessing a URL.
 */
@Composable
private fun ProviderGuideDialog(preset: ProviderPreset?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_provider_setup_guide)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                listOf(
                    R.string.settings_guide_step_1,
                    R.string.settings_guide_step_2,
                    R.string.settings_guide_step_3,
                    R.string.settings_guide_step_4,
                    R.string.settings_guide_step_5,
                ).forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(20.dp),
                        )
                        Text(
                            text = stringResource(step),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.settings_guide_key_use),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preset == null) {
                    Text(
                        text = stringResource(R.string.settings_guide_own_endpoint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (preset != null) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(preset.keyUrl)),
                            )
                        }
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.settings_guide_open_page))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_guide_close))
                }
            }
        },
        dismissButton = if (preset != null) {
            {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_guide_close))
                }
            }
        } else {
            null
        },
        shape = AppShape.medium,
    )
}

@Composable
private fun ProviderRow(
    provider: ProviderSummary,
    onEdit: () -> Unit,
    onActivate: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClickLabel = provider.name) {
                if (provider.isActive) onEdit() else onActivate()
            }
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (provider.isActive) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = provider.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = if (provider.isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = AppSpacing.md, end = AppSpacing.md),
        ) {
            Text(
                text = provider.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = provider.model.ifBlank {
                    stringResource(R.string.settings_provider_no_model)
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppCodeFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(
                if (provider.isActive) R.string.settings_provider_active else R.string.settings_provider_inactive,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = if (provider.isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Icon(
            painter = painterResource(AppIcons.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditor(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        if (state.editingId == null) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = AppSpacing.xs)) {
                Text(
                    text = stringResource(R.string.settings_quick_presets),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = AppSpacing.xs),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    ProviderPresets.forEach { preset ->
                        Surface(
                            onClick = {
                                viewModel.fillPreset(preset.name, preset.baseUrl, preset.defaultModel)
                            },
                            shape = AppShape.pill,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            ),
                        ) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::setName,
            label = { Text(stringResource(R.string.settings_field_name)) },
            singleLine = true,
            shape = AppShape.small,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsNameFieldTag),
            enabled = !state.isSaving,
        )
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = viewModel::setBaseUrl,
            label = { Text(stringResource(R.string.settings_field_base_url)) },
            singleLine = true,
            shape = AppShape.small,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsBaseUrlFieldTag),
            enabled = !state.isSaving,
        )
        OutlinedTextField(
            value = state.apiKeyInput,
            onValueChange = viewModel::setApiKeyInput,
            label = { Text(stringResource(R.string.settings_field_api_key)) },
            singleLine = true,
            visualTransformation = if (state.revealKey) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            placeholder = {
                if (state.storedKey != null && state.apiKeyInput.isEmpty()) {
                    Text(stringResource(R.string.settings_api_key_saved_placeholder))
                }
            },
            trailingIcon = {
                TextButton(
                    onClick = { viewModel.setRevealKey(!state.revealKey) },
                    enabled = !state.isSaving,
                ) {
                    Text(
                        stringResource(
                            if (state.revealKey) R.string.settings_hide_key else R.string.settings_show_key,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
            shape = AppShape.small,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsApiKeyFieldTag),
            enabled = !state.isSaving,
        )
        var guideOpen by remember { mutableStateOf(false) }
        SettingsNavRow(
            label = stringResource(R.string.settings_provider_setup_guide),
            icon = AppIcons.Info,
            onClick = { guideOpen = true },
        )
        if (guideOpen) {
            ProviderGuideDialog(
                preset = presetForBaseUrl(state.baseUrl),
                onDismiss = { guideOpen = false },
            )
        }
        var modelSelectorOpen by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = state.model,
            onValueChange = viewModel::setModel,
            label = { Text(stringResource(R.string.settings_field_model)) },
            singleLine = true,
            shape = AppShape.small,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SettingsModelFieldTag),
            enabled = !state.isSaving,
            trailingIcon = {
                TextButton(
                    onClick = { modelSelectorOpen = true },
                    enabled = !state.isSaving,
                ) {
                    Text(
                        stringResource(R.string.model_selector_title),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
        )
        if (modelSelectorOpen) {
            LaunchedEffect(state.baseUrl, state.storedKey, state.apiKeyInput) {
                viewModel.loadModels()
            }
            ModelSelectorDialog(
                currentModel = state.model,
                availableModels = state.availableModels,
                isLoading = state.isLoadingModels,
                loadFailed = state.modelsError,
                onRetry = viewModel::loadModels,
                onSelect = viewModel::setModel,
                onDismiss = { modelSelectorOpen = false },
            )
        }
        state.formError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(
            onClick = viewModel::save,
            enabled = state.isLoaded && !state.isSaving && state.formError == null,
            shape = AppShape.pill,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = AppSpacing.sm),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            Text(
                stringResource(
                    if (state.isSaving) R.string.settings_saving else R.string.settings_save,
                ),
            )
        }
        OutlinedButton(
            onClick = viewModel::testConnection,
            enabled = state.isLoaded && !state.isTesting && state.formError == null,
            shape = AppShape.pill,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(18.dp)
                        .padding(end = AppSpacing.sm),
                    strokeWidth = 2.dp,
                )
            }
            Text(
                stringResource(
                    if (state.isTesting) {
                        R.string.settings_testing_connection
                    } else {
                        R.string.settings_test_connection
                    },
                ),
            )
        }
        state.connectionOutcome?.let { outcome ->
            val color = when (outcome) {
                is ConnectionOutcome.Success -> MaterialTheme.colorScheme.primary
                is ConnectionOutcome.Failure -> MaterialTheme.colorScheme.error
            }
            val text = when (outcome) {
                is ConnectionOutcome.Success -> stringResource(R.string.settings_connection_success)
                is ConnectionOutcome.Failure -> outcome.message
            }
            Column(modifier = Modifier.padding(top = AppSpacing.xs)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = color,
                )
                if (outcome is ConnectionOutcome.Failure && outcome.detail != null) {
                    Text(
                        text = outcome.detail,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppCodeFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f),
                                shape = AppShape.small
                            )
                            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs)
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = viewModel::cancelEdit) {
                Text(stringResource(R.string.settings_cancel))
            }
            if (state.editingId != null) {
                TextButton(
                    onClick = { viewModel.delete(state.editingId) },
                    enabled = !state.isSaving,
                ) {
                    Text(
                        text = stringResource(R.string.settings_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = AppShape.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 52.dp),
    )
}

@Composable
private fun SettingsRow(
    label: String,
    icon: Int,
    modifier: Modifier = Modifier,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    toggleable: Pair<Boolean, (Boolean) -> Unit>? = null,
    value: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                when {
                    toggleable != null -> Modifier.toggleable(
                        value = toggleable.first,
                        role = Role.Switch,
                        enabled = enabled,
                        onValueChange = toggleable.second,
                    )
                    onClick != null -> Modifier.clickable(
                        enabled = enabled,
                        onClickLabel = label,
                        onClick = onClick,
                    )
                    else -> Modifier
                },
            )
            .padding(horizontal = AppSpacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = AppSpacing.md),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        value?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (toggleable != null) {
            Switch(
                checked = toggleable.first,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
        if (onClick != null && toggleable == null) {
            Icon(
                painter = painterResource(AppIcons.ChevronRight),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** A row that opens another settings page; mirrors [SettingsRow]'s look. */
@Composable
private fun SettingsNavRow(
    label: String,
    icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = AppSpacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            modifier = Modifier
                .weight(1f)
                .padding(start = AppSpacing.md),
        )
        Icon(
            painter = painterResource(AppIcons.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(enabled = true, onClickLabel = label, onClick = onClick)
            .padding(horizontal = AppSpacing.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .weight(1f)
                .padding(start = AppSpacing.md),
        )
        Icon(
            painter = painterResource(AppIcons.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SettingsSectionHeader(text: String, isFirst: Boolean = false) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = AppSpacing.xs, top = if (isFirst) AppSpacing.xs else AppSpacing.sm, bottom = AppSpacing.xs),
    )
}

@Composable
private fun SettingsDialogRow(
    label: String,
    icon: Int,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dialog: @Composable ((onDismiss: () -> Unit) -> Unit),
) {
    var open by remember { mutableStateOf(false) }
    SettingsRow(
        label = label,
        icon = icon,
        modifier = modifier,
        value = value,
        enabled = enabled,
        onClick = { open = true },
    )
    if (open) {
        dialog { open = false }
    }
}

@Composable
private fun VoicePickerDialog(
    options: List<VoiceOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_voice_choice)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                VoicePickerRow(
                    label = stringResource(R.string.settings_voice_default),
                    selected = selected == null,
                    onClick = { onSelect(null) },
                )
                options.forEach { option ->
                    VoicePickerRow(
                        label = if (option.isNatural) {
                            "${option.label} · ${stringResource(R.string.settings_voice_natural)}"
                        } else {
                            option.label
                        },
                        selected = option.id == selected,
                        onClick = { onSelect(option.id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun VoicePickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = AppSpacing.sm),
        )
    }
}

@Composable
private fun <T> SettingsChoiceDialog(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .selectable(
                                selected = value == selected,
                                role = Role.RadioButton,
                                onClick = { onSelect(value) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = AppSpacing.sm),
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}
