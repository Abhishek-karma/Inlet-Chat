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
 * Inlet Chat design tokens: spacing, ergonomic shapes, and layout constants.
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

object AppShape {
    val small = RoundedCornerShape(12.dp)
    val medium = RoundedCornerShape(18.dp)
    val large = RoundedCornerShape(26.dp)
    val userBubble = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp)
    val assistantBubble = RoundedCornerShape(6.dp, 22.dp, 22.dp, 22.dp)
    val composer = RoundedCornerShape(28.dp)
    val bubble = RoundedCornerShape(20.dp)
    val card = RoundedCornerShape(20.dp)
    val pill = RoundedCornerShape(50)
}

object AppDimens {
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
