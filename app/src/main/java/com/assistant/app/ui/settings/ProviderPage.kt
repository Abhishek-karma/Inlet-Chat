package com.assistant.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.data.local.ReasoningSupport
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.theme.AppCodeFontFamily
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing

internal const val SettingsNameFieldTag = "settings_name_field"
internal const val SettingsBaseUrlFieldTag = "settings_base_url_field"
internal const val SettingsApiKeyFieldTag = "settings_api_key_field"
internal const val SettingsModelFieldTag = "settings_model_field"
internal const val SettingsReasoningFieldTag = "settings_reasoning_field"

@Composable
internal fun ProviderPage(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    if (state.isEditing) {
        ProviderEditor(state = state, viewModel = viewModel)
        return
    }

    val activeProvider = state.providers.firstOrNull { it.isActive }
    val otherProviders = state.providers.filter { !it.isActive }

    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (state.providers.isEmpty()) {
            SettingsCard {
                Text(
                    text = stringResource(R.string.settings_provider_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(AppSpacing.lg),
                )
                SettingsDivider()
                SettingsActionRow(
                    label = stringResource(R.string.settings_add_provider),
                    onClick = viewModel::startAdd,
                )
            }
        } else {
            // [ Active provider ]
            Text(
                text = stringResource(R.string.settings_active_provider_section),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = AppSpacing.xs, bottom = AppSpacing.xs),
            )
            SettingsCard {
                if (activeProvider != null) {
                    ProviderRow(
                        provider = activeProvider,
                        onEdit = { viewModel.edit(activeProvider.id) },
                        onActivate = {},
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_provider_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(AppSpacing.lg),
                    )
                }
            }

            // [ Other configured providers ]
            if (otherProviders.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AppSpacing.md))
                Text(
                    text = stringResource(R.string.settings_other_providers_section),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = AppSpacing.xs, bottom = AppSpacing.xs),
                )
                SettingsCard {
                    otherProviders.forEachIndexed { index, provider ->
                        if (index > 0) SettingsDivider()
                        ProviderRow(
                            provider = provider,
                            onEdit = { viewModel.edit(provider.id) },
                            onActivate = { viewModel.activateProvider(provider.id) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.md))
            SettingsCard {
                SettingsActionRow(
                    label = stringResource(R.string.settings_add_provider),
                    onClick = viewModel::startAdd,
                )
            }
        }
    }
}

@Composable
internal fun ProviderRow(
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
                maxLines = 1,
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
        if (provider.isActive) {
            Text(
                text = stringResource(R.string.settings_provider_active),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = AppSpacing.xs),
            )
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(AppIcons.ChevronRight),
                    contentDescription = stringResource(R.string.settings_edit_provider),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Text(
                text = stringResource(R.string.settings_provider_inactive),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = AppSpacing.xs),
            )
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(AppIcons.ChevronRight),
                    contentDescription = stringResource(R.string.settings_edit_provider),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderEditor(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val isGeminiProvider = isGemini(state.baseUrl, state.name)
    var providerDropdownExpanded by remember { mutableStateOf(false) }

    val presetNames = listOf(
        "Google Gemini",
        "OpenAI",
        "OpenRouter",
        "Groq",
        "Naga",
        "OpenAI-compatible",
    )

    val currentProviderLabel = when {
        isGeminiProvider -> "Google Gemini"
        state.name.equals("OpenAI", ignoreCase = true) -> "OpenAI"
        state.name.equals("OpenRouter", ignoreCase = true) -> "OpenRouter"
        state.name.equals("Groq", ignoreCase = true) -> "Groq"
        state.name.equals("Naga", ignoreCase = true) -> "Naga"
        state.baseUrl.isNotBlank() && state.name.isNotBlank() -> state.name
        else -> "OpenAI-compatible"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AppSpacing.xs, bottom = AppSpacing.xl),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        // Provider Type Dropdown
        ExposedDropdownMenuBox(
            expanded = providerDropdownExpanded,
            onExpandedChange = { if (!state.isSaving) providerDropdownExpanded = !providerDropdownExpanded },
        ) {
            OutlinedTextField(
                value = currentProviderLabel,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.settings_provider_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerDropdownExpanded) },
                shape = AppShape.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                enabled = !state.isSaving,
            )
            ExposedDropdownMenu(
                expanded = providerDropdownExpanded,
                onDismissRequest = { providerDropdownExpanded = false },
            ) {
                presetNames.forEach { label ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (label == currentProviderLabel) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            providerDropdownExpanded = false
                            when (label) {
                                "Google Gemini" -> {
                                    viewModel.fillPreset(
                                        name = GEMINI_NAME,
                                        baseUrl = GEMINI_BASE_URL,
                                        defaultModel = GEMINI_DEFAULT_MODEL,
                                    )
                                }
                                "OpenAI" -> {
                                    viewModel.fillPreset(
                                        name = "OpenAI",
                                        baseUrl = "https://api.openai.com/v1",
                                        defaultModel = "gpt-4o-mini",
                                    )
                                }
                                "OpenRouter" -> {
                                    viewModel.fillPreset(
                                        name = "OpenRouter",
                                        baseUrl = "https://openrouter.ai/api/v1",
                                        defaultModel = "openai/gpt-4o-mini",
                                    )
                                }
                                "Groq" -> {
                                    viewModel.fillPreset(
                                        name = "Groq",
                                        baseUrl = "https://api.groq.com/openai/v1",
                                        defaultModel = "llama-3.3-70b-versatile",
                                    )
                                }
                                "Naga" -> {
                                    viewModel.fillPreset(
                                        name = "Naga",
                                        baseUrl = "https://api.naga.ac/v1",
                                        defaultModel = "dots-3-note-preview:free",
                                    )
                                }
                                else -> {
                                    // Custom OpenAI-compatible
                                    viewModel.fillPreset(
                                        name = "",
                                        baseUrl = "",
                                        defaultModel = "",
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }

        // Only show Name field for custom/OpenAI-compatible providers when needed
        if (!isGeminiProvider) {
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

            // Endpoint field (hidden for Gemini)
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::setBaseUrl,
                label = { Text(stringResource(R.string.settings_field_endpoint)) },
                singleLine = true,
                shape = AppShape.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SettingsBaseUrlFieldTag),
                enabled = !state.isSaving,
            )
        }

        // API Key field
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

        // API Key helper link
        val keyUrl = if (isGeminiProvider) {
            GEMINI_KEY_URL
        } else {
            presetForBaseUrl(state.baseUrl)?.keyUrl
        }
        if (keyUrl != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl)))
                        }
                    },
                ) {
                    Text(
                        text = stringResource(R.string.settings_get_api_key),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        // Model field
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

        // Reasoning capability: declared by the user, never inferred from the model name.
        var reasoningOpen by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = reasoningOpen,
            onExpandedChange = { reasoningOpen = it },
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = reasoningLabel(state.reasoningSupport),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.settings_field_reasoning)) },
                singleLine = true,
                shape = AppShape.small,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = reasoningOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor()
                    .testTag(SettingsReasoningFieldTag),
                enabled = !state.isSaving,
            )
            ExposedDropdownMenu(
                expanded = reasoningOpen,
                onDismissRequest = { reasoningOpen = false },
            ) {
                ReasoningSupport.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(reasoningLabel(option)) },
                        onClick = {
                            reasoningOpen = false
                            viewModel.setReasoningSupport(option)
                        },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.settings_reasoning_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = AppSpacing.xs),
        )

        // Test connection button
        OutlinedButton(
            onClick = viewModel::testConnection,
            enabled = state.isLoaded && !state.isTesting && state.formError == null,
            shape = AppShape.pill,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
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

        // Connection outcome feedback
        state.connectionOutcome?.let { outcome ->
            val color = when (outcome) {
                is ConnectionOutcome.Success -> MaterialTheme.colorScheme.primary
                is ConnectionOutcome.Failure -> MaterialTheme.colorScheme.error
            }
            val text = when (outcome) {
                is ConnectionOutcome.Success -> {
                    if (isGeminiProvider) stringResource(R.string.settings_gemini_connected)
                    else stringResource(R.string.settings_connection_success)
                }
                is ConnectionOutcome.Failure -> "${stringResource(R.string.settings_connection_failed)}: ${outcome.message}"
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

        // Form error
        state.formError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // Save button
        Button(
            onClick = viewModel::save,
            enabled = state.isLoaded && !state.isSaving && state.formError == null,
            shape = AppShape.pill,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
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

        // Cancel / Delete actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(
                onClick = viewModel::cancelEdit,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.settings_cancel))
            }
            if (state.editingId != null) {
                TextButton(
                    onClick = { viewModel.delete(state.editingId) },
                    enabled = !state.isSaving,
                    modifier = Modifier.heightIn(min = 48.dp),
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

/** User-facing label for a declared provider reasoning capability. */
@Composable
private fun reasoningLabel(support: ReasoningSupport): String = stringResource(
    when (support) {
        ReasoningSupport.UNSPECIFIED -> R.string.settings_reasoning_unspecified
        ReasoningSupport.EFFORT -> R.string.settings_reasoning_effort
        ReasoningSupport.BUDGET -> R.string.settings_reasoning_budget
    },
)
