package com.assistant.app.ui.components

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.data.ChatStatus
import com.assistant.app.llm.model.Role
import com.assistant.app.llm.model.SearchResult
import com.assistant.app.llm.model.UiAttachment
import com.assistant.app.llm.model.UiMessage
import com.assistant.app.ui.theme.AppCodeFontFamily
import com.assistant.app.ui.theme.AppDimens
import com.assistant.app.ui.theme.AppMotion
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing
import com.assistant.app.ui.theme.AppTypographyStyles
import com.assistant.app.ui.theme.appTween
import kotlinx.coroutines.delay

private val USER_BUBBLE_SHAPE = AppShape.userBubble
private val USER_BUBBLE_MAX_WIDTH = 340.dp
private const val WAITING_DELAY_MILLIS = 350L

internal fun isWebUrl(url: String): Boolean =
    url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

/**
 * Inlet Message Transcript: Content-first conversation stream optimized for reading comfort.
 */
@Composable
fun MessageList(
    messages: List<UiMessage>,
    status: ChatStatus,
    onRegenerate: () -> Unit,
    onEditAndResend: (messageId: String, newContent: String) -> Unit,
    onSwitchVersion: (messageId: String, index: Int) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    showReasoning: Boolean = true,
    onSpeakMessage: ((String) -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = AppDimens.maxContentWidth),
        state = listState,
        reverseLayout = true,
        contentPadding = PaddingValues(
            start = AppSpacing.lg,
            end = AppSpacing.lg,
            top = AppSpacing.lg,
            bottom = AppSpacing.sm,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.xl),
    ) {

        items(messages.asReversed(), key = { it.id }) { message ->
            val isLast = message.id == messages.last().id
            MessageItem(
                message = message,
                streaming = status is ChatStatus.Generating && isLast && message.role == Role.ASSISTANT,
                actionsEnabled = status is ChatStatus.Idle,
                canRegenerate = status is ChatStatus.Idle && isLast && message.role == Role.ASSISTANT,
                onRegenerate = onRegenerate,
                onEditAndResend = onEditAndResend,
                onSwitchVersion = onSwitchVersion,
                showReasoning = showReasoning,
                onSpeakMessage = onSpeakMessage,
            )
        }
    }
}

@Composable
private fun MessageItem(
    message: UiMessage,
    streaming: Boolean,
    actionsEnabled: Boolean,
    canRegenerate: Boolean,
    onRegenerate: () -> Unit,
    onEditAndResend: (String, String) -> Unit,
    onSwitchVersion: (String, Int) -> Unit,
    showReasoning: Boolean,
    onSpeakMessage: ((String) -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var viewerFor by remember { mutableStateOf<UiAttachment?>(null) }
    val clipboard = LocalClipboardManager.current
    val isUser = message.role == Role.USER
    val canSpeak = onSpeakMessage != null && !isUser && message.content.isNotBlank()
    val copyLabel = stringResource(R.string.menu_copy)
    val speakLabel = stringResource(R.string.menu_speak)
    val regenerateLabel = stringResource(R.string.menu_regenerate)
    val editResendLabel = stringResource(R.string.menu_edit_and_resend)

    val enter = remember { Animatable(0f) }
    val enterSpec = appTween<Float>(AppMotion.ENTER)
    LaunchedEffect(Unit) {
        enter.animateTo(1f, animationSpec = enterSpec)
    }
    val enterModifier = Modifier.graphicsLayer {
        alpha = enter.value
        translationY = (1f - enter.value) * 12f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(enterModifier),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier.defaultMinSize(minHeight = AppDimens.minTouchTarget),
            contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            val longPress = Modifier.pointerInput(actionsEnabled) {
                detectTapGestures(onLongPress = { if (actionsEnabled) menuOpen = true })
            }

            if (isUser) {
                Column(horizontalAlignment = Alignment.End) {
                    message.attachments.forEach { attachment ->
                        if (attachment.kind == UiAttachment.Kind.IMAGE) {
                            AttachmentThumbnail(
                                attachment = attachment,
                                onClick = { viewerFor = attachment },
                            )
                        } else {
                            Surface(
                                shape = AppShape.small,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.padding(bottom = AppSpacing.xs),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                                ) {
                                    Icon(
                                        painter = painterResource(AppIcons.File),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = attachment.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = AppSpacing.xs),
                                    )
                                }
                            }
                        }
                    }

                    Surface(
                        modifier = longPress.widthIn(max = USER_BUBBLE_MAX_WIDTH),
                        shape = USER_BUBBLE_SHAPE,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        ),
                    ) {
                        Text(
                            text = message.content.ifBlank {
                                stringResource(R.string.message_attachment_only)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.md),
                        )
                    }
                }
            } else {
                // Assistant Message (No giant card container, prioritization of reading)
                Column(horizontalAlignment = Alignment.Start) {
                    if (showReasoning && message.reasoning.isNotBlank()) {
                        ReasoningSection(
                            reasoning = message.reasoning,
                            autoExpanded = streaming && message.content.isBlank(),
                            modifier = Modifier.padding(bottom = AppSpacing.sm),
                        )
                    }

                    MessageText(
                        text = message.content,
                        streaming = streaming,
                        modifier = longPress,
                    )

                    if (streaming && message.content.isBlank()) {
                        WaitingIndicator(modifier = Modifier.padding(top = AppSpacing.xs))
                    }

                    if (message.sources.isNotEmpty()) {
                        SearchCitationsList(sources = message.sources)
                    }

                    // Message action bar & version switcher
                    if (!streaming && message.content.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = AppSpacing.xs),
                        ) {
                            if (message.versions.size > 1) {
                                VersionSwitcher(
                                    selected = message.selectedVersion,
                                    count = message.versions.size,
                                    enabled = actionsEnabled,
                                    onPrevious = { onSwitchVersion(message.id, message.selectedVersion - 1) },
                                    onNext = { onSwitchVersion(message.id, message.selectedVersion + 1) },
                                )
                                Spacer(Modifier.width(AppSpacing.sm))
                            }

                            IconButton(
                                onClick = {
                                    val data = ClipData.newPlainText("message", message.content)
                                    clipboard.setClip(ClipEntry(data))
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painter = painterResource(AppIcons.Copy),
                                    contentDescription = copyLabel,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }

                            if (canSpeak) {
                                IconButton(
                                    onClick = { onSpeakMessage?.invoke(message.id) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(AppIcons.Speak),
                                        contentDescription = speakLabel,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }

                            if (canRegenerate) {
                                IconButton(
                                    onClick = onRegenerate,
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(AppIcons.Renew),
                                        contentDescription = regenerateLabel,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (menuOpen) {
                NaraActionSheet(
                    actions = buildList {
                        add(
                            NaraAction(
                                label = copyLabel,
                                icon = AppIcons.Copy,
                                onClick = {
                                    val data = ClipData.newPlainText("message", message.content)
                                    clipboard.setClip(ClipEntry(data))
                                },
                            ),
                        )
                        if (canSpeak) {
                            add(
                                NaraAction(
                                    label = speakLabel,
                                    icon = AppIcons.Speak,
                                    onClick = { onSpeakMessage?.invoke(message.id) },
                                ),
                            )
                        }
                        if (canRegenerate) {
                            add(
                                NaraAction(
                                    label = regenerateLabel,
                                    icon = AppIcons.Renew,
                                    onClick = onRegenerate,
                                ),
                            )
                        }
                        if (isUser) {
                            add(
                                NaraAction(
                                    label = editResendLabel,
                                    icon = AppIcons.Edit,
                                    onClick = { onEditAndResend(message.id, message.content) },
                                ),
                            )
                        }
                    },
                    onDismiss = { menuOpen = false },
                )
            }
        }
    }
    viewerFor?.let { attachment ->
        ImageViewerDialog(attachment = attachment, onDismiss = { viewerFor = null })
    }
}

/** Error message banner with retry action */
@Composable
private fun ErrorMessageBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = AppShape.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
        ) {
            Icon(
                painter = painterResource(AppIcons.Error),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = AppSpacing.sm),
            )
            Button(
                onClick = onRetry,
                shape = AppShape.pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                contentPadding = PaddingValues(horizontal = AppSpacing.md, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    text = stringResource(R.string.error_retry),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/** Ambient pulsing indicator while waiting for response tokens */
@Composable
private fun WaitingIndicator(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(WAITING_DELAY_MILLIS)
        visible = true
    }
    if (!visible) return

    val transition = rememberInfiniteTransition(label = "waiting")
    Row(
        modifier = modifier.padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 650, delayMillis = index * 180),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$index",
            )
            val scale = 0.7f + 0.4f * phase
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.3f + 0.7f * phase
                    }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Reasoning section with calm disclosure and sidebar thinking bar */
@Composable
private fun ReasoningSection(
    reasoning: String,
    autoExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val visible = expanded || autoExpanded

    val rotation by animateFloatAsState(
        targetValue = if (visible) 90f else 0f,
        animationSpec = appTween(AppMotion.FAST),
        label = "chevronRotation",
    )

    Surface(
        shape = AppShape.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Elegant vertical primary bar indicating ongoing thought / reasoning
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
            )

            Column(modifier = Modifier.padding(AppSpacing.xs)) {
                Row(
                    modifier = Modifier
                        .clip(AppShape.small)
                        .clickable { expanded = !expanded }
                        .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(AppIcons.ChevronRight),
                        contentDescription = stringResource(R.string.cd_toggle_reasoning),
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = rotation },
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.reasoning_header),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = AppSpacing.xs),
                    )
                }

                AnimatedVisibility(visible = visible) {
                    Text(
                        text = reasoning,
                        style = AppTypographyStyles.reasoning,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchCitationsList(sources: List<SearchResult>) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = Modifier.padding(top = AppSpacing.xs)) {
        sources.forEach { source ->
            Surface(
                onClick = { if (isWebUrl(source.url)) uriHandler.openUri(source.url) },
                shape = AppShape.small,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = AppSpacing.sm, vertical = 6.dp),
                ) {
                    Icon(
                        painter = painterResource(AppIcons.Globe),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = source.title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = AppSpacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
private fun VersionSwitcher(
    selected: Int,
    count: Int,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Surface(
        shape = AppShape.pill,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = enabled && selected > 0,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    painter = painterResource(AppIcons.ChevronLeft),
                    contentDescription = stringResource(R.string.cd_previous_answer),
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = "${selected + 1}/$count",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AppCodeFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.xs),
            )
            IconButton(
                onClick = onNext,
                enabled = enabled && selected < count - 1,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    painter = painterResource(AppIcons.ChevronRight),
                    contentDescription = stringResource(R.string.cd_next_answer),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
