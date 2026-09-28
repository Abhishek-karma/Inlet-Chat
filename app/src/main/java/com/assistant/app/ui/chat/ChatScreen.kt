package com.assistant.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.assistant.app.R
import com.assistant.app.data.AttachmentIngester
import com.assistant.app.data.ChatLlmState
import com.assistant.app.data.ChatStatus
import com.assistant.app.data.VoiceStatus
import com.assistant.app.llm.model.Role
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.components.AssistantTopBar
import com.assistant.app.ui.components.AttachmentSheet
import com.assistant.app.ui.components.Composer
import com.assistant.app.ui.components.ComposerAttachAction
import com.assistant.app.ui.components.MessageList
import com.assistant.app.ui.theme.AppDimens
import com.assistant.app.ui.theme.AppMotion
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing
import com.assistant.app.ui.theme.appTween
import kotlinx.coroutines.launch
import java.io.File

/**
 * Nara Chat Screen: Main conversation canvas, model switching, and writing flow.
 */
@Composable
fun ChatScreen(
    onOpenSettings: () -> Unit,
    viewModelFactory: ViewModelProvider.Factory,
    modifier: Modifier = Modifier,
    pendingConversationId: String? = null,
    onOpenDrawer: (() -> Unit)? = null,
    onOpenProviderSetup: (() -> Unit)? = null,
) {
    val viewModel: ChatViewModel = viewModel(factory = viewModelFactory)
    val state by viewModel.uiState.collectAsState()
    val chatLlmState by viewModel.chatLlm.collectAsState()
    val savedProviders by viewModel.providers.collectAsState(initial = emptyList())
    val reasoningVisible by viewModel.reasoningVisible.collectAsState()
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var showMicRationale by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showMicRationale = false
            viewModel.onMicClick()
        } else {
            showMicRationale = true
        }
    }
    val startVoiceInput: () -> Unit = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.onMicClick()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(AttachmentIngester.MAX_IMAGES_PER_MESSAGE),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addImageAttachments(uris)
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.addTextAttachment(uri)
    }
    var cameraTarget by remember { mutableStateOf<Uri?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { captured ->
        val target = cameraTarget
        val file = cameraFile
        cameraTarget = null
        cameraFile = null
        if (captured && target != null) {
            viewModel.addImageAttachments(listOf(target))
        } else {
            file?.delete()
        }
    }
    val launchCamera: () -> Unit = {
        cameraFile?.delete()
        val file = File(
            context.cacheDir,
            "camera/IMG_${System.currentTimeMillis()}.jpg",
        ).apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        cameraTarget = uri
        cameraFile = file
        cameraLauncher.launch(uri)
    }
    DisposableEffect(Unit) {
        onDispose { cameraFile?.delete() }
    }

    val attachActions = if (viewModel.attachmentSupport) {
        listOf(
            ComposerAttachAction(
                label = stringResource(R.string.attach_photos),
                contentDescription = stringResource(R.string.cd_attach_photos),
                icon = R.drawable.ic_image,
                onClick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            ),
            ComposerAttachAction(
                label = stringResource(R.string.attach_camera),
                contentDescription = stringResource(R.string.cd_attach_camera),
                icon = R.drawable.ic_camera,
                onClick = launchCamera,
            ),
            ComposerAttachAction(
                label = stringResource(R.string.attach_file),
                contentDescription = stringResource(R.string.cd_attach_file),
                icon = R.drawable.ic_attach_file,
                onClick = { filePicker.launch(arrayOf("*/*")) },
            ),
        )
    } else {
        emptyList()
    }
    var showAttachSheet by remember { mutableStateOf(false) }
    val searchAvailable by viewModel.searchAvailable.collectAsState()
    if (showAttachSheet && (attachActions.isNotEmpty() || searchAvailable)) {
        AttachmentSheet(
            actions = attachActions,
            subtitles = mapOf(
                stringResource(R.string.attach_photos) to R.string.attach_photos_subtitle,
                stringResource(R.string.attach_camera) to R.string.attach_camera_subtitle,
                stringResource(R.string.attach_file) to R.string.attach_file_subtitle,
            ),
            onDismiss = { showAttachSheet = false },
            searchEnabled = state.searchEnabled,
            onToggleSearch = if (searchAvailable) viewModel::toggleSearch else null,
        )
    }

    LaunchedEffect(pendingConversationId) {
        if (pendingConversationId != null) {
            viewModel.openConversation(pendingConversationId)
        }
    }

    val error = state.status as? ChatStatus.Error
    val isGenerating = state.status is ChatStatus.Generating
    val voiceActive = state.voiceStatus == VoiceStatus.Listening ||
        state.voiceStatus == VoiceStatus.Transcribing
    val showSetupPrompt = state.needsSetup && state.messages.isEmpty()
    val suggestions = state.messages.lastOrNull()
        ?.takeIf { !isGenerating && it.role == Role.ASSISTANT }
        ?.followUps
        .orEmpty()
    val composerHint: () -> String? = {
        when {
            state.voiceStatus == VoiceStatus.Speaking ->
                context.getString(R.string.voice_speaking_hint)
            showMicRationale -> context.getString(R.string.voice_mic_rationale)
            state.voiceHint -> context.getString(R.string.voice_no_match_hint)
            else -> state.searchNotice
        }
    }

    val listState = rememberLazyListState()
    val atBottom = listState.firstVisibleItemIndex == 0 &&
        listState.firstVisibleItemScrollOffset == 0
    val lastContentLength = state.messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(state.messages.size, lastContentLength) {
        if (!atBottom) return@LaunchedEffect
        if (isGenerating) {
            listState.scrollToItem(0)
        } else {
            listState.animateScrollToItem(0)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    val composer: @Composable () -> Unit = {
        Column(modifier = Modifier.readingColumn()) {
            if (state.pendingAttachments.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.md, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    state.pendingAttachments.forEach { attachment ->
                        AssistChip(
                            onClick = { viewModel.removePendingAttachment(attachment.id) },
                            label = {
                                Text(
                                    text = attachment.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cd_remove_attachment),
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        )
                    }
                }
            }
            state.attachmentError?.let { errorText -> InlineHint(text = errorText) }
            Composer(
                value = state.draft,
                onValueChange = {
                    viewModel.setDraft(it)
                    if (state.voiceHint) viewModel.dismissVoiceHint()
                    if (state.attachmentError != null) viewModel.dismissAttachmentError()
                    if (state.searchNotice != null) viewModel.dismissSearchNotice()
                },
                onSend = {
                    val editing = editingMessageId
                    if (editing != null) {
                        viewModel.editAndResend(editing, state.draft)
                    } else {
                        viewModel.send(state.draft)
                    }
                    editingMessageId = null
                },
                onStop = viewModel::stop,
                isGenerating = isGenerating,
                onMicClick = if (viewModel.isVoiceInputAvailable) startVoiceInput else null,
                voiceActive = voiceActive,
                onAttachClick = if (attachActions.isNotEmpty() || searchAvailable) {
                    { showAttachSheet = true }
                } else {
                    null
                },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            val activeProvider = (chatLlmState as? ChatLlmState.Ready)
                ?.let { ready -> savedProviders.firstOrNull { it.id == ready.providerId } }
            val voiceOut by viewModel.voiceOutputEnabled.collectAsState()
            AssistantTopBar(
                title = stringResource(R.string.app_name),
                onOpenDrawer = onOpenDrawer,
                onToggleVoiceOutput = if (viewModel.ttsAvailable) viewModel::toggleVoiceOutput else null,
                voiceOutputEnabled = voiceOut,
                activeProvider = activeProvider,
                savedProviders = savedProviders,
                onProviderSelected = viewModel::activateProvider,
                onNewChat = if (state.conversationId != null) {
                    {
                        editingMessageId = null
                        viewModel.newConversation()
                    }
                } else {
                    null
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            val regionEnter = fadeIn(appTween(AppMotion.MEDIUM))
            val regionExit = fadeOut(appTween(AppMotion.FAST))
            AnimatedContent(
                targetState = if (showSetupPrompt || state.messages.isEmpty()) 0 else 1,
                transitionSpec = {
                    regionEnter togetherWith regionExit using SizeTransform(clip = false)
                },
                modifier = Modifier.weight(1f),
                label = "chatRegion",
            ) { region ->
                when (region) {
                    0 -> if (showSetupPrompt) {
                        SetupRequired(
                            onOpenSettings = onOpenSettings,
                            onOpenProviderSetup = onOpenProviderSetup,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        EmptyHome(
                            onPromptSelected = viewModel::setDraft,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            MessageList(
                                messages = state.messages,
                                status = state.status,
                                onRegenerate = viewModel::regenerate,
                                onEditAndResend = { messageId, content ->
                                    editingMessageId = messageId
                                    viewModel.setDraft(content)
                                },
                                onSwitchVersion = viewModel::switchVersion,
                                listState = listState,
                                showReasoning = reasoningVisible,
                                onSpeakMessage = if (viewModel.speakAvailable) viewModel::speakMessage else null,
                                modifier = Modifier.fillMaxWidth(),
                            )

                            val showScrollAffordance = isGenerating && !atBottom
                            val fabAlpha by animateFloatAsState(
                                targetValue = if (showScrollAffordance) 1f else 0f,
                                animationSpec = appTween(AppMotion.FAST),
                                label = "scrollAffordance",
                            )
                            if (fabAlpha > 0.01f) {
                                val scrollLabel = stringResource(R.string.cd_scroll_to_latest)
                                val listScope = rememberCoroutineScope()
                                Surface(
                                    onClick = { listScope.launch { listState.animateScrollToItem(0) } },
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    shadowElevation = 2.dp,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(AppSpacing.lg)
                                        .size(44.dp)
                                        .graphicsLayer {
                                            alpha = fabAlpha
                                            val scale = 0.85f + 0.15f * fabAlpha
                                            scaleX = scale
                                            scaleY = scale
                                        }
                                        .semantics { contentDescription = scrollLabel },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = error != null,
                            enter = fadeIn(appTween(AppMotion.MEDIUM)) +
                                expandVertically(appTween(AppMotion.MEDIUM)),
                            exit = fadeOut(appTween(AppMotion.FAST)) +
                                shrinkVertically(appTween(AppMotion.FAST)),
                        ) {
                            error?.let {
                                ErrorBanner(
                                    message = it.message,
                                    onRetry = viewModel::retry,
                                    onDismiss = viewModel::dismissError,
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = editingMessageId != null,
                            enter = fadeIn(appTween(AppMotion.MEDIUM)) +
                                expandVertically(appTween(AppMotion.MEDIUM)),
                            exit = fadeOut(appTween(AppMotion.FAST)) +
                                shrinkVertically(appTween(AppMotion.FAST)),
                        ) {
                            if (editingMessageId != null) {
                                EditBanner(
                                    onCancel = {
                                        editingMessageId = null
                                        viewModel.setDraft("")
                                    },
                                )
                            }
                        }

                        composerHint()?.let { InlineHint(text = it) }
                        if (suggestions.isNotEmpty()) {
                            SuggestionsRow(
                                suggestions = suggestions,
                                onSelect = viewModel::setDraft,
                            )
                        }
                    }
                }
            }

            if (!showSetupPrompt) {
                composer()
            }
        }
    }
}

private fun Modifier.readingColumn(): Modifier =
    fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = AppDimens.maxContentWidth)

@Composable
private fun InlineHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .readingColumn()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
    )
}

/**
 * Nara Empty State: Calm, inspiring editorial prompt canvas.
 */
@Composable
private fun EmptyHome(
    onPromptSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val greeting = remember {
        timeOfDayGreeting(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // Radiant Inlet brand mark
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_inlet_logo),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }

            Spacer(Modifier.height(AppSpacing.lg))

            Text(
                text = stringResource(
                    when (greeting) {
                        Greeting.MORNING -> R.string.greeting_morning
                        Greeting.AFTERNOON -> R.string.greeting_afternoon
                        Greeting.EVENING -> R.string.greeting_evening
                    },
                ),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                text = stringResource(R.string.chat_empty_statement),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(AppSpacing.xxl))

            val examples: List<Pair<ImageVector, String>> = listOf(
                Icons.Filled.Search to stringResource(R.string.chat_prompt_1),
                Icons.Filled.Edit to stringResource(R.string.chat_prompt_2),
                Icons.Filled.Star to stringResource(R.string.chat_prompt_3),
            )
            examples.forEachIndexed { index, (icon, prompt) ->
                if (index > 0) Spacer(Modifier.height(AppSpacing.sm))
                Surface(
                    onClick = { onPromptSelected(prompt) },
                    shape = AppShape.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 360.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = AppSpacing.lg, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 14.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(AppSpacing.md))
    }
}

@Composable
private fun SuggestionsRow(
    suggestions: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .readingColumn()
            .padding(bottom = AppSpacing.xs),
        contentPadding = PaddingValues(horizontal = AppSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
    ) {
        items(suggestions) { suggestion ->
            Surface(
                onClick = { onSelect(suggestion) },
                shape = AppShape.pill,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 6.dp),
                )
            }
        }
    }
}

/** Shown instead of the conversation while no provider is configured. */
@Composable
private fun SetupRequired(
    onOpenSettings: () -> Unit,
    onOpenProviderSetup: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(AppSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(AppIcons.Sparkle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(AppSpacing.lg))
        Text(
            text = stringResource(R.string.chat_setup_required),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(AppSpacing.lg))
        if (onOpenProviderSetup != null) {
            Button(
                onClick = onOpenProviderSetup,
                shape = AppShape.pill,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(stringResource(R.string.settings_add_provider))
            }
        } else {
            Button(
                onClick = onOpenSettings,
                shape = AppShape.pill,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text(stringResource(R.string.chat_open_settings))
            }
        }
    }
}

/** Error banner with clear recovery affordance */
@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .readingColumn()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
        shape = AppShape.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(AppIcons.Error),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = AppSpacing.md),
            ) {
                Text(
                    text = stringResource(R.string.error_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Surface(
                    onClick = onRetry,
                    shape = AppShape.pill,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                    modifier = Modifier.padding(top = AppSpacing.sm),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(AppIcons.Refresh),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(R.string.error_retry),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = AppSpacing.xs),
                        )
                    }
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_dismiss),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun EditBanner(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .readingColumn()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
        shape = AppShape.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppSpacing.md, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(AppIcons.Edit),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.edit_banner_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = AppSpacing.sm),
            )
            IconButton(
                onClick = onCancel,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_cancel_edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
