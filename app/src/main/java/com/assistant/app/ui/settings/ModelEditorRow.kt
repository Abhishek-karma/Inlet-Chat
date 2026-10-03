package com.assistant.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.data.ModelDraft
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing

/** One saved model row: its model id and whether it is the active model. */
@Composable
internal fun ModelEditorRow(
    draft: ModelDraft,
    modelCount: Int,
    enabled: Boolean,
    onOpenSelector: () -> Unit,
    onModelChange: (String) -> Unit,
    onSetActive: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.xs)
            .clip(AppShape.card)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(AppSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = draft.isActive, onClick = onSetActive, enabled = enabled)
            OutlinedTextField(
                value = draft.model,
                onValueChange = onModelChange,
                label = { Text(stringResource(R.string.settings_field_model)) },
                singleLine = true,
                shape = AppShape.small,
                modifier = Modifier
                    .weight(1f)
                    .testTag(SettingsModelFieldTag),
                enabled = enabled,
                trailingIcon = {
                    TextButton(onClick = onOpenSelector, enabled = enabled) {
                        Text(
                            stringResource(R.string.model_selector_title),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
            IconButton(onClick = onRemove, enabled = enabled && modelCount > 1) {
                Icon(
                    painter = painterResource(AppIcons.Trash),
                    contentDescription = stringResource(R.string.settings_remove_model),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}