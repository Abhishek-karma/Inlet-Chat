package com.assistant.app.ui.theme

import android.animation.ValueAnimator
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * Predictable, 4dp-base spacing scale for the entire application.
 */
object AppSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

/**
 * Unified shape design tokens. Avoid random radii across composables.
 */
object AppShape {
    val extraSmall = RoundedCornerShape(8.dp)
    val small = RoundedCornerShape(12.dp)
    val medium = RoundedCornerShape(16.dp)
    val large = RoundedCornerShape(24.dp)
    val extraLarge = RoundedCornerShape(32.dp)
    val pill = RoundedCornerShape(50)

    // Specific conversational components mapped to standard tokens
    val userBubble = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    val assistantBubble = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
    val composer = RoundedCornerShape(28.dp)
    val card = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

/**
 * Dimensions and touch target standards.
 */
object AppDimens {
    val minTouchTarget = 48.dp
    val prominentTouchTarget = 56.dp
    val iconButtonSize = 48.dp
    val maxContentWidth = 720.dp
}

/**
 * Motion tokens: swift, restrained, respectful of system accessibility settings.
 */
object AppMotion {
    const val FAST = 120
    const val MEDIUM = 200
    const val ENTER = 240
}

@Composable
fun <T> appTween(durationMillis: Int): FiniteAnimationSpec<T> {
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    return if (animationsEnabled) tween(durationMillis) else snap()
}

@Composable
fun rememberHaptics(): (HapticFeedbackType) -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics) { { type -> haptics.performHapticFeedback(type) } }
}
