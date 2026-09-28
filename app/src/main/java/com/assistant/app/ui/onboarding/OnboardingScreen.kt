package com.assistant.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.assistant.app.R
import com.assistant.app.ui.components.AppIcons
import com.assistant.app.ui.theme.AppDimens
import com.assistant.app.ui.theme.AppMotion
import com.assistant.app.ui.theme.AppShape
import com.assistant.app.ui.theme.AppSpacing
import com.assistant.app.ui.theme.appTween
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 3

/**
 * Premium, spacious onboarding flow introducing the core product values:
 * What this app is -> What you control -> How to start quickly.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val isLast = pagerState.currentPage == PAGE_COUNT - 1

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .displayCutoutPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = AppDimens.maxContentWidth)
                .padding(horizontal = AppSpacing.xl, vertical = AppSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = AppSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_inlet_logo),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(AppSpacing.sm))
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                TextButton(
                    onClick = onDone,
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Pager Canvas
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                OnboardingPage(page = page)
            }

            Spacer(Modifier.height(AppSpacing.md))

            // Progress Indicator Dots
            Row(
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = AppSpacing.sm),
            ) {
                repeat(PAGE_COUNT) { index ->
                    val active = pagerState.currentPage == index
                    val animatedWidth by animateDpAsState(
                        targetValue = if (active) 24.dp else 8.dp,
                        animationSpec = appTween(AppMotion.MEDIUM),
                        label = "dotWidth",
                    )
                    Box(
                        modifier = Modifier
                            .size(width = animatedWidth, height = 8.dp)
                            .clip(AppShape.pill)
                            .background(
                                if (active) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                                },
                            )
                            .semantics { contentDescription = "Page ${index + 1} of $PAGE_COUNT" },
                    )
                }
            }

            Spacer(Modifier.height(AppSpacing.md))

            // Prominent Primary CTA Button
            Button(
                onClick = {
                    if (isLast) {
                        onDone()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
                shape = AppShape.pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 420.dp)
                    .height(56.dp),
            ) {
                Text(
                    text = stringResource(
                        if (isLast) R.string.onboarding_get_started else R.string.onboarding_next,
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
            }

            Spacer(Modifier.height(AppSpacing.xs))
        }
    }
}

@Composable
private fun OnboardingPage(page: Int) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (page) {
            0 -> PageWhatThisAppIs()
            1 -> PageWhatYouControl()
            else -> PageHowToStartQuickly()
        }
    }
}

@Composable
private fun PageWhatThisAppIs() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_inlet_logo),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
        }

        Spacer(Modifier.height(AppSpacing.xl))

        Text(
            text = stringResource(R.string.onboarding_title_1),
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(AppSpacing.md))

        Text(
            text = stringResource(R.string.onboarding_body_1),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )

        Spacer(Modifier.height(AppSpacing.xxl))

        Column(
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            modifier = Modifier.widthIn(max = 380.dp),
        ) {
            FeatureRow(
                icon = AppIcons.Sparkle,
                title = "OpenAI-Compatible",
                subtitle = "Works with standard AI providers and endpoints",
            )
            FeatureRow(
                icon = AppIcons.Info,
                title = "On-Device Keys",
                subtitle = "API keys stay encrypted on your phone",
            )
            FeatureRow(
                icon = AppIcons.Globe,
                title = "Direct API Calls",
                subtitle = "No intermediary servers or hidden tracking",
            )
        }
    }
}

@Composable
private fun PageWhatYouControl() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(AppIcons.Sparkle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(36.dp),
            )
        }

        Spacer(Modifier.height(AppSpacing.xl))

        Text(
            text = stringResource(R.string.onboarding_title_2),
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(AppSpacing.md))

        Text(
            text = stringResource(R.string.onboarding_body_2),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )

        Spacer(Modifier.height(AppSpacing.xxl))

        Column(
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            modifier = Modifier.widthIn(max = 380.dp),
        ) {
            FeatureRow(
                icon = AppIcons.Palette,
                title = "Custom Providers",
                subtitle = "Configure base URLs, custom headers, and models",
            )
            FeatureRow(
                icon = AppIcons.Brain,
                title = "Reasoning Visibility",
                subtitle = "View structured thinking and model search steps",
            )
            FeatureRow(
                icon = AppIcons.Globe,
                title = "Optional Web Search",
                subtitle = "Ground answers with live web research when enabled",
            )
        }
    }
}

@Composable
private fun PageHowToStartQuickly() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.tertiaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(AppIcons.Chat),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(36.dp),
            )
        }

        Spacer(Modifier.height(AppSpacing.xl))

        Text(
            text = stringResource(R.string.onboarding_title_3),
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(AppSpacing.md))

        Text(
            text = stringResource(R.string.onboarding_body_3),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 460.dp),
        )

        Spacer(Modifier.height(AppSpacing.xxl))

        Column(
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
            modifier = Modifier.widthIn(max = 380.dp),
        ) {
            FeatureRow(
                icon = AppIcons.Chat,
                title = stringResource(R.string.onboarding_cap_chat),
                subtitle = "Fast streaming answers with version comparison",
            )
            FeatureRow(
                icon = AppIcons.Mic,
                title = stringResource(R.string.onboarding_cap_voice),
                subtitle = "Hands-free voice input & spoken answers",
            )
            FeatureRow(
                icon = AppIcons.Image,
                title = stringResource(R.string.onboarding_cap_images),
                subtitle = "Camera capture & photo analysis context",
            )
            FeatureRow(
                icon = AppIcons.File,
                title = stringResource(R.string.onboarding_cap_files),
                subtitle = "Plain text & document file ingestion",
            )
        }
    }
}

@Composable
private fun FeatureRow(
    icon: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(AppSpacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
