package com.assistant.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.data.update.model.UpdateStatus
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing

@Composable
internal fun AboutPage(
    versionName: String,
    state: SettingsUiState = SettingsUiState(),
    onCheckForUpdates: () -> Unit = {},
    onToggleAutoCheckUpdates: (Boolean) -> Unit = {},
    onDismissUpdateDialog: () -> Unit = {},
    onOpen: (SettingsPage) -> Unit,
) {
    val context = LocalContext.current

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
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.settings_check_for_updates),
            icon = AppIcons.Sparkle,
            description = when (val status = state.updateStatus) {
                is UpdateStatus.Checking -> stringResource(R.string.settings_update_checking)
                is UpdateStatus.UpToDate -> stringResource(R.string.settings_update_up_to_date)
                is UpdateStatus.Available -> stringResource(
                    R.string.settings_update_available_version,
                    status.info.latestVersion,
                )
                is UpdateStatus.Error -> stringResource(R.string.settings_update_failed)
                UpdateStatus.Idle -> stringResource(R.string.settings_check_for_updates_desc)
            },
            onClick = onCheckForUpdates,
        )
        SettingsDivider()
        SettingsRow(
            label = stringResource(R.string.settings_update_auto_check),
            icon = AppIcons.Sparkle,
            description = stringResource(R.string.settings_update_auto_check_desc),
            toggleable = state.autoCheckUpdates to onToggleAutoCheckUpdates,
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

    val available = state.updateStatus as? UpdateStatus.Available
    if (state.showUpdateDialog && available != null) {
        val info = available.info
        AlertDialog(
            onDismissRequest = onDismissUpdateDialog,
            shape = AppShape.large,
            title = {
                Text(
                    text = stringResource(R.string.settings_update_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(
                            R.string.settings_update_dialog_version,
                            info.latestVersion,
                            versionName,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (info.releaseNotes.isNotBlank()) {
                        Spacer(Modifier.height(AppSpacing.md))
                        Text(
                            text = stringResource(R.string.settings_update_dialog_release_notes),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(AppSpacing.xs))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .clip(AppShape.small)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
                                .padding(AppSpacing.sm)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = info.releaseNotes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetUrl = info.downloadUrl.ifBlank { info.htmlUrl }
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(browserIntent)
                        onDismissUpdateDialog()
                    },
                    shape = AppShape.pill,
                ) {
                    Text(stringResource(R.string.settings_update_download))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissUpdateDialog,
                    shape = AppShape.pill,
                ) {
                    Text(stringResource(R.string.settings_update_dismiss))
                }
            },
        )
    }
}
