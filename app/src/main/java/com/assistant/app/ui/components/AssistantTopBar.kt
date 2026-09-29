package com.assistant.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.data.local.ProviderEntity
import com.assistant.app.ui.theme.AppCodeFontFamily
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing

/**
 * Inlet Top Bar: Quiet, content-first navigation and model indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onNewChat: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onToggleVoiceOutput: (() -> Unit)? = null,
    voiceOutputEnabled: Boolean = false,
    activeProvider: ProviderEntity? = null,
    savedProviders: List<ProviderEntity> = emptyList(),
    onProviderSelected: ((Long) -> Unit)? = null,
) {
    val barColor = MaterialTheme.colorScheme.background
    val hairline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    TopAppBar(
        modifier = modifier.drawBehind {
            drawLine(
                color = hairline,
                strokeWidth = 0.5.dp.toPx(),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = barColor,
            scrolledContainerColor = barColor,
        ),
        navigationIcon = {
            when {
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(AppIcons.ArrowLeft),
                        contentDescription = stringResource(R.string.cd_back),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                onOpenDrawer != null -> IconButton(onClick = onOpenDrawer) {
                    Icon(
                        painter = painterResource(AppIcons.Menu),
                        contentDescription = stringResource(R.string.cd_open_drawer),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        title = {
            val switchable = activeProvider != null &&
                onProviderSelected != null && savedProviders.size > 1

            if (switchable) {
                var pickerOpen by remember { mutableStateOf(false) }
                val active = activeProvider
                ModelSelectorCapsule(
                    label = title,
                    model = active?.model,
                    onClick = { pickerOpen = true },
                )
                if (pickerOpen) {
                    NaraActionSheet(
                        actions = savedProviders.map { provider ->
                            NaraAction(
                                label = provider.name,
                                icon = AppIcons.Sparkle,
                                trailing = provider.model,
                                selected = if (provider.id == active?.id) true else null,
                                onClick = {
                                    if (provider.id != active?.id) {
                                        onProviderSelected.invoke(provider.id)
                                    }
                                },
                            )
                        },
                        onDismiss = { pickerOpen = false },
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onOpenDrawer != null && title == stringResource(R.string.app_name)) {
                        Icon(
                            painter = painterResource(R.drawable.ic_inlet_logo),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(end = AppSpacing.sm)
                                .size(22.dp),
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        actions = {
            if (onSearch != null) {
                IconButton(onClick = onSearch) {
                    Icon(
                        painter = painterResource(AppIcons.Search),
                        contentDescription = stringResource(R.string.drawer_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (onToggleVoiceOutput != null) {
                IconButton(onClick = onToggleVoiceOutput) {
                    Icon(
                        painter = painterResource(
                            if (voiceOutputEnabled) AppIcons.SpeakerOn else AppIcons.SpeakerOff,
                        ),
                        contentDescription = stringResource(R.string.cd_toggle_speaker),
                        tint = if (voiceOutputEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (onNewChat != null) {
                IconButton(onClick = onNewChat) {
                    Icon(
                        painter = painterResource(AppIcons.Add),
                        contentDescription = stringResource(R.string.cd_new_chat),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
    )
}

/** The refined model indicator capsule with subtle border and status indicator. */
@Composable
private fun ModelSelectorCapsule(
    label: String,
    model: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = AppShape.pill,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = AppSpacing.md,
                end = AppSpacing.sm,
                top = 5.dp,
                bottom = 5.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(AppSpacing.xs))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.cd_switch_provider),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(16.dp),
            )
        }
    }
}
