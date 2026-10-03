package com.assistant.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.data.VoiceStatus
import com.assistant.app.llm.model.ReasoningConfig
import com.assistant.app.llm.model.ReasoningEffort
import com.assistant.app.llm.model.ThinkCapability
import com.assistant.app.llm.model.budgetPresets
import com.assistant.app.llm.model.isReasoningSupported
import com.assistant.app.ui.theme.AppDimens
import com.assistant.app.ui.theme.AppMotion
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing
import com.assistant.app.ui.theme.appTween
import com.assistant.app.ui.theme.rememberHaptics

internal const val ComposerInputTag = "composer_input"

object AppIcons {
    val Add = R.drawable.ic_plus
    val ArrowLeft = R.drawable.ic_arrow_left
    val Brain = R.drawable.ic_brain
    val Camera = R.drawable.ic_camera
    val Chat = R.drawable.ic_chat
    val Check = R.drawable.ic_check
    val ChevronRight = R.drawable.ic_chevron_right
    val ChevronLeft = R.drawable.ic_chevron_left
    val ChevronDown = R.drawable.ic_chevron_down
    val Close = R.drawable.ic_close
    val Copy = R.drawable.ic_copy
    val Edit = R.drawable.ic_edit
    val Error = R.drawable.ic_error
    val File = R.drawable.ic_attach_file
    val Flag = R.drawable.ic_flag
    val Globe = R.drawable.ic_globe
    val History = R.drawable.ic_list
    val Image = R.drawable.ic_image
    val Info = R.drawable.ic_info
    val Mic = R.drawable.ic_mic
    val Menu = R.drawable.ic_menu
    val More = R.drawable.ic_more
    val Palette = R.drawable.ic_palette
    val Pin = R.drawable.ic_pin
    val Refresh = R.drawable.ic_refresh
    val Renew = R.drawable.ic_renew
    val Search = R.drawable.ic_search
    val Send = R.drawable.ic_send
    val Settings = R.drawable.ic_settings
    val Share = R.drawable.ic_share
    val Speak = R.drawable.ic_speak
    val Sparkle = R.drawable.ic_sparkle
    val SpeakerOff = R.drawable.ic_speaker_off
    val SpeakerOn = R.drawable.ic_speaker_on
    val Stop = R.drawable.ic_stop
    val TextSize = R.drawable.ic_text_size
    val Trash = R.drawable.ic_trash
    val Wave = R.drawable.ic_wave
}

data class ComposerAttachAction(
    val label: String,
    val contentDescription: String,
    val icon: Int,
    val onClick: () -> Unit,
)

private const val COMPOSER_MAX_LINES = 8

/**
 * Modern, spacious AI chat composer.
 * Focuses on comfortable typing and contextual actions without permanent toolbar clutter.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    onMicClick: (() -> Unit)? = null,
    voiceActive: Boolean = false,
    voiceStatus: VoiceStatus = VoiceStatus.Idle,
    onAttachClick: (() -> Unit)? = null,
    searchActive: Boolean = false,
    onToggleSearch: (() -> Unit)? = null,
    thinkCapability: ThinkCapability = ThinkCapability.Unsupported,
    thinkConfig: ReasoningConfig = ReasoningConfig.Auto,
    onThinkSelect: (ReasoningConfig) -> Unit = {},
    topPadding: androidx.compose.ui.unit.Dp = AppSpacing.sm,
) {
    val haptics = rememberHaptics()
    val isNotBlank = value.isNotBlank()
    val borderCol by animateColorAsState(
        targetValue = if (isNotBlank || isGenerating) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        },
        animationSpec = appTween(AppMotion.MEDIUM),
        label = "composerBorder",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = AppSpacing.md,
                end = AppSpacing.md,
                top = topPadding,
                bottom = AppSpacing.sm,
            ),
        shape = AppShape.composer,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, borderCol),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = AppSpacing.sm,
                vertical = AppSpacing.xs,
            ),
        ) {
            // Main Text Input Field
            TextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ComposerInputTag),
                placeholder = {
                    val placeholderText = when (voiceStatus) {
                        VoiceStatus.Listening -> stringResource(R.string.voice_listening_hint)
                        VoiceStatus.Processing -> stringResource(R.string.voice_processing_hint)
                        VoiceStatus.Speaking -> stringResource(R.string.voice_speaking_hint)
                        VoiceStatus.Error -> stringResource(R.string.voice_error_hint)
                        VoiceStatus.Idle -> stringResource(
                            if (voiceActive) R.string.composer_listening else R.string.composer_hint,
                        )
                    }
                    Text(
                        text = placeholderText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                },
                maxLines = COMPOSER_MAX_LINES,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
            )

            // Contextual Actions Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.xs, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left Contextual Tools (Attach & Web Search)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    if (onAttachClick != null) {
                        IconButton(
                            onClick = {
                                haptics(HapticFeedbackType.TextHandleMove)
                                onAttachClick.invoke()
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painter = painterResource(AppIcons.Add),
                                contentDescription = stringResource(R.string.cd_attach_files),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    if (onToggleSearch != null) {
                        Surface(
                            onClick = {
                                haptics(HapticFeedbackType.TextHandleMove)
                                onToggleSearch.invoke()
                            },
                            shape = AppShape.pill,
                            color = if (searchActive) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            modifier = Modifier.height(36.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = AppSpacing.md),
                            ) {
                                Icon(
                                    painter = painterResource(AppIcons.Globe),
                                    contentDescription = stringResource(R.string.cd_toggle_search),
                                    tint = if (searchActive) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(AppSpacing.xs))
                                Text(
                                    text = "Search",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = if (searchActive) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    ),
                                )
                            }
                        }
                    }

                    if (thinkCapability.isReasoningSupported()) {
                        ThinkControl(
                            capability = thinkCapability,
                            selected = thinkConfig,
                            onSelect = {
                                haptics(HapticFeedbackType.TextHandleMove)
                                onThinkSelect(it)
                            },
                        )
                    }
                }

                // Right Contextual Controls (Mic & Send/Stop)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                ) {
                    val showMic = onMicClick != null &&
                        (!isGenerating || voiceStatus == VoiceStatus.Speaking) &&
                        (value.isBlank() || voiceStatus == VoiceStatus.Speaking || voiceStatus == VoiceStatus.Listening)
                    if (showMic) {
                        val micBg = when (voiceStatus) {
                            VoiceStatus.Listening -> MaterialTheme.colorScheme.primaryContainer
                            VoiceStatus.Processing -> MaterialTheme.colorScheme.tertiaryContainer
                            VoiceStatus.Speaking -> MaterialTheme.colorScheme.secondaryContainer
                            VoiceStatus.Error -> MaterialTheme.colorScheme.errorContainer
                            VoiceStatus.Idle -> if (voiceActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
                        }
                        val micTint = when (voiceStatus) {
                            VoiceStatus.Listening -> MaterialTheme.colorScheme.primary
                            VoiceStatus.Processing -> MaterialTheme.colorScheme.tertiary
                            VoiceStatus.Speaking -> MaterialTheme.colorScheme.secondary
                            VoiceStatus.Error -> MaterialTheme.colorScheme.error
                            VoiceStatus.Idle -> if (voiceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val micCd = when (voiceStatus) {
                            VoiceStatus.Listening -> stringResource(R.string.cd_voice_listening)
                            VoiceStatus.Processing -> stringResource(R.string.cd_voice_processing)
                            VoiceStatus.Speaking -> stringResource(R.string.cd_stop_speaking)
                            VoiceStatus.Error -> stringResource(R.string.cd_voice_error)
                            VoiceStatus.Idle -> stringResource(R.string.cd_use_microphone)
                        }
                        val micIcon = when (voiceStatus) {
                            VoiceStatus.Speaking -> AppIcons.SpeakerOn
                            VoiceStatus.Error -> AppIcons.Refresh
                            else -> AppIcons.Mic
                        }

                        IconButton(
                            onClick = {
                                haptics(HapticFeedbackType.TextHandleMove)
                                onMicClick()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(micBg),
                        ) {
                            Icon(
                                painter = painterResource(micIcon),
                                contentDescription = micCd,
                                tint = micTint,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }

                    SendButton(
                        isGenerating = isGenerating,
                        enabled = isGenerating || isNotBlank,
                        onSend = onSend,
                        onStop = onStop,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkControl(
    capability: ThinkCapability,
    selected: ReasoningConfig,
    onSelect: (ReasoningConfig) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val active = selected !is ReasoningConfig.Auto
    val tint = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box {
        Surface(
            onClick = { open = true },
            shape = AppShape.pill,
            color = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
            modifier = Modifier.height(36.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = AppSpacing.md),
            ) {
                Icon(
                    painter = painterResource(AppIcons.Brain),
                    contentDescription = stringResource(R.string.cd_toggle_think),
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(AppSpacing.xs))
                Text(
                    text = stringResource(R.string.think_label),
                    style = MaterialTheme.typography.labelMedium.copy(color = tint),
                )
            }
        }

        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            thinkOptions(capability).forEach { option ->
                DropdownMenuItem(
                    text = { Text(thinkOptionLabel(option)) },
                    trailingIcon = {
                        RadioButton(selected = option == selected, onClick = null)
                    },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

/** The reasoning choices the active model supports, in menu order. */
private fun thinkOptions(capability: ThinkCapability): List<ReasoningConfig> = when (capability) {
    ThinkCapability.Unsupported, ThinkCapability.Unknown -> emptyList()
    is ThinkCapability.Effort -> buildList {
        add(ReasoningConfig.Auto)
        capability.levels.forEach { add(ReasoningConfig.Effort(it)) }
    }
    is ThinkCapability.Budget -> buildList {
        if (capability.allowAuto) add(ReasoningConfig.Auto)
        if (capability.allowOff) add(ReasoningConfig.Off)
        capability.budgetPresets().forEach { add(ReasoningConfig.Budget(it)) }
    }
}

@Composable
private fun thinkOptionLabel(option: ReasoningConfig): String = when (option) {
    ReasoningConfig.Auto -> stringResource(R.string.think_auto)
    ReasoningConfig.Off -> stringResource(R.string.think_off)
    is ReasoningConfig.Effort -> stringResource(
        when (option.level) {
            ReasoningEffort.LOW -> R.string.think_low
            ReasoningEffort.MEDIUM -> R.string.think_medium
            ReasoningEffort.HIGH -> R.string.think_high
        },
    )
    is ReasoningConfig.Budget -> stringResource(R.string.think_budget_option, option.tokens)
}

@Composable
private fun SendButton(
    isGenerating: Boolean,
    enabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val haptics = rememberHaptics()
    val actionLabel = stringResource(if (isGenerating) R.string.cd_stop else R.string.cd_send)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = appTween(AppMotion.FAST),
        label = "send_press",
    )

    FilledIconButton(
        onClick = {
            haptics(HapticFeedbackType.TextHandleMove)
            if (isGenerating) onStop() else onSend()
        },
        enabled = enabled,
        interactionSource = interaction,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isGenerating) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = actionLabel }
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Crossfade(targetState = isGenerating, animationSpec = tween(150), label = "send_morph") { generating ->
            if (generating) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.onError),
                )
            } else {
                Icon(
                    painter = painterResource(AppIcons.Send),
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
