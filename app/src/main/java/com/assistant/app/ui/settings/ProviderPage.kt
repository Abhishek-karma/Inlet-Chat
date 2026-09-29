package com.assistant.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.theme.AppCodeFontFamily
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing

internal const val SettingsNameFieldTag = "settings_name_field"
internal const val SettingsBaseUrlFieldTag = "settings_base_url_field"
internal const val SettingsApiKeyFieldTag = "settings_api_key_field"
internal const val SettingsModelFieldTag = "settings_model_field"

@Composable
internal fun ProviderPage(
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
internal fun ProviderEditor(
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
internal fun ProviderGuideDialog(preset: ProviderPreset?, onDismiss: () -> Unit) {
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
