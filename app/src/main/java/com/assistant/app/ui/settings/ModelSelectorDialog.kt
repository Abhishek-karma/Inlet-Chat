package com.assistant.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing

internal const val ModelSelectorSearchTag = "model_selector_search_field"
internal const val ModelSelectorRowTag = "model_selector_row"

/**
 * The models the edited provider reported, filtered by a search box, plus the
 * typed value for anything not listed. The list comes from the provider's own
 * `GET /models`, so a self-hosted endpoint shows what it actually serves; when
 * it cannot be asked, the field stays free-text.
 *
 * A bottom sheet, like the other full-width overlays: the list can be long, and
 * a sheet keeps every row thumb-reachable instead of trapping it in a dialog.
 * A tap commits and closes, matching the chat's own switcher, so choosing takes
 * one gesture and dismissing the sheet leaves the model untouched.
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
    val matches = remember(query, availableModels) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            availableModels
        } else {
            availableModels.filter { it.contains(trimmed, ignoreCase = true) }
        }
    }
    val commit: (String) -> Unit = {
        onSelect(it)
        onDismiss()
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
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ModelSelectorSearchTag),
            )
            if (isLoading) {
                Box(
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
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .padding(top = AppSpacing.sm),
                ) {
                    val typed = query.trim()
                    if (typed.isNotEmpty()) {
                        item(key = "typed") {
                            ModelRow(
                                label = typed,
                                selected = typed == currentModel,
                                onClick = { commit(typed) },
                            )
                        }
                    }
                    items(matches, key = { it }) { model ->
                        ModelRow(
                            label = model,
                            selected = model == currentModel,
                            onClick = { commit(model) },
                        )
                    }
                    if (loadFailed) {
                        item(key = "error") {
                            Column(
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
                        }
                    } else if (matches.isEmpty()) {
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
            Spacer(modifier = Modifier.height(AppSpacing.lg))
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
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .testTag(ModelSelectorRowTag)
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
