package com.assistant.app.ui.settings

import android.content.pm.PackageManager
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

private enum class SettingsPage(val titleRes: Int, val icon: Int) {
    Provider(R.string.settings_section_provider, AppIcons.Sparkle),
    Voice(R.string.settings_section_voice, AppIcons.Speak),
    Search(R.string.settings_section_search, AppIcons.Globe),
    Appearance(R.string.settings_section_appearance, AppIcons.Palette),
    About(R.string.settings_section_about, AppIcons.Info),
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
                        SettingsPage.About -> AboutPage(versionName = versionName)
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
    SettingsCard {
        SettingsPage.entries.forEachIndexed { index, page ->
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

@Composable
private fun settingsPageSummary(
    page: SettingsPage,
    state: SettingsUiState,
    versionName: String,
): String = when (page) {
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
private fun AboutPage(versionName: String) {
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "Fluid intelligence, pure connection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.lg, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_version),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = versionName,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AppCodeFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
                .padding(horizontal = AppSpacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (provider.isActive) {
                    Text(
                        text = " · " + stringResource(R.string.settings_active),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = provider.model,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppCodeFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            painter = painterResource(AppIcons.ChevronRight),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
        )
    }
}

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    listOf(
                        Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
                        Triple("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-4o-mini"),
                        Triple("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
                        Triple("Naga", "https://api.naga.ac/v1", "dots-3-note-preview:free"),
                    ).forEach { (presetName, url, defaultModel) ->
                        Surface(
                            onClick = { viewModel.fillPreset(presetName, url, defaultModel) },
                            shape = AppShape.pill,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        ) {
                            Text(
                                text = presetName,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
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
