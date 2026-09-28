package com.assistant.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing

/**
 * The models the edited provider reported, filtered by a search box, plus the
 * typed value for anything not listed. The list comes from the provider's own
 * `GET /models`, so a self-hosted endpoint shows what it actually serves; when
 * it cannot be asked, the field stays free-text.
 *
 * A bottom sheet, like the other full-width overlays: the list can be long, and
 * a sheet keeps every row thumb-reachable instead of trapping it in a dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorDialog(
    currentModel: String,
    availableModels: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    isLoading: Boolean = false,
    loadFailed: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember(currentModel) { mutableStateOf(currentModel) }
    val matches = remember(query, availableModels) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            availableModels
        } else {
            availableModels.filter { it.contains(trimmed, ignoreCase = true) }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = AppSpacing.md),
        ) {
            Text(
                text = stringResource(R.string.model_selector_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = AppSpacing.sm),
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.model_selector_search)) },
                singleLine = true,
                shape = AppShape.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                isLoading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }

                loadFailed -> Column(
                    modifier = Modifier.padding(vertical = AppSpacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.model_selector_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    onRetry?.let { retry ->
                        TextButton(
                            onClick = retry,
                            modifier = Modifier.padding(top = AppSpacing.xs),
                        ) {
                            Text(stringResource(R.string.model_selector_retry))
                        }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .padding(top = AppSpacing.sm),
                ) {
                    if (query.isNotBlank()) {
                        item(key = "typed") {
                            ModelRow(
                                label = query.trim(),
                                selected = selected == query.trim(),
                                onClick = { selected = query.trim() },
                            )
                        }
                    }
                    items(matches, key = { it }) { model ->
                        ModelRow(
                            label = model,
                            selected = selected == model,
                            onClick = { selected = model },
                        )
                    }
                    if (matches.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                text = stringResource(R.string.model_selector_no_match),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(AppSpacing.md),
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.sm, bottom = AppSpacing.md),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.settings_cancel))
                }
                TextButton(
                    onClick = {
                        if (selected.isNotBlank()) onSelect(selected)
                        onDismiss()
                    },
                    enabled = selected.isNotBlank(),
                ) {
                    Text(stringResource(R.string.settings_save))
                }
            }
        }
    }
}

/** The whole row is the target; the selected row is marked with a check. */
@Composable
private fun ModelRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShape.medium)
            .clickable(
                onClickLabel = stringResource(R.string.model_selector_title),
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = AppSpacing.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                painter = painterResource(AppIcons.Check),
                contentDescription = stringResource(R.string.cd_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = AppSpacing.sm)
                    .size(18.dp),
            )
        }
    }
}
