package com.assistant.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.ui.theme.AppCodeFontFamily
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing
import com.assistant.app.ui.theme.rememberHaptics

/**
 * An action in an [InletActionSheet].
 */
data class InletAction(
    val label: String,
    val icon: Int,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
    val trailing: String? = null,
    val selected: Boolean? = null,
    val onClickLabel: String? = null,
)

typealias NaraAction = InletAction
typealias InletChatAction = InletAction

@Composable
fun NaraActionSheet(
    actions: List<InletAction>,
    onDismiss: () -> Unit,
) = InletActionSheet(actions, onDismiss)

/**
 * Inlet Chat Action Sheet: Ergonomic thumb-reachable bottom menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InletActionSheet(
    actions: List<InletAction>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        val haptics = rememberHaptics()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
        ) {
            actions.forEachIndexed { index, action ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 48.dp),
                    )
                }
                NaraActionRow(
                    action = action,
                    onClick = {
                        haptics(HapticFeedbackType.TextHandleMove)
                        action.onClick()
                        onDismiss()
                    },
                )
            }
            Spacer(Modifier.height(AppSpacing.lg))
        }
    }
}

@Composable
private fun NaraActionRow(
    action: NaraAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labelColor = if (action.destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(AppShape.medium)
            .clickable(
                enabled = action.selected != false,
                onClickLabel = action.onClickLabel ?: action.label,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = AppSpacing.sm, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (action.destructive) {
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(action.icon),
                contentDescription = null,
                tint = if (action.destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(18.dp),
            )
        }

        Text(
            text = action.label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = AppSpacing.md),
        )

        action.trailing?.let { trailing ->
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = AppCodeFontFamily),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 160.dp)
                    .padding(start = AppSpacing.sm),
            )
        }

        if (action.selected == true) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.cd_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = AppSpacing.sm)
                    .size(18.dp),
            )
        }
    }
}