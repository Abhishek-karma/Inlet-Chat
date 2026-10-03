package com.assistant.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val ORB_POINTS = 90
private const val ORB_SPIN_MILLIS = 4200
private const val ORB_TILT = 0.45f
private const val ORB_WOBBLE = 0.18f

/**
 * Ambient rotating dotted sphere shown while a response is pending.
 * Depth-shaded points on a slowly spinning, gently wobbling sphere.
 */
@Composable
fun ThinkingOrb(
    modifier: Modifier = Modifier,
    orbSize: Dp = 20.dp,
) {
    val points = remember { fibonacciSphere(ORB_POINTS) }
    val transition = rememberInfiniteTransition(label = "thinkingOrb")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ORB_SPIN_MILLIS, easing = LinearEasing),
        ),
        label = "spin",
    )
    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ORB_SPIN_MILLIS * 2, easing = LinearEasing),
        ),
        label = "breathe",
    )
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier.size(orbSize)) {
        val radius = size.minDimension / 2f
        val angle = spin * 2.0 * PI
        val cosA = cos(angle)
        val sinA = sin(angle)
        val tilt = ORB_TILT + ORB_WOBBLE * sin(breathe * 2.0 * PI)
        val cosT = cos(tilt)
        val sinT = sin(tilt)
        val pulse = 0.92f + 0.08f * sin(breathe * 2.0 * PI).toFloat()

        points.forEach { (x, y, z) ->
            val x1 = x * cosA + z * sinA
            val z1 = -x * sinA + z * cosA
            val y2 = y * cosT - z1 * sinT
            val z2 = y * sinT + z1 * cosT

            val depth = ((z2 + 1.0) / 2.0).toFloat()
            val alpha = 0.12f + 0.88f * depth * depth
            val dotRadius = radius * 0.055f * (0.5f + 0.5f * depth) * pulse
            val cx = center.x + (x1 * radius * 0.82f * pulse).toFloat()
            val cy = center.y + (y2 * radius * 0.82f * pulse).toFloat()

            drawCircle(
                color = color.copy(alpha = alpha),
                radius = dotRadius,
                center = Offset(cx, cy),
            )
        }
    }
}

/** Evenly distributed points on the unit sphere via the Fibonacci lattice. */
private fun fibonacciSphere(count: Int): List<Triple<Double, Double, Double>> {
    val golden = PI * (3.0 - sqrt(5.0))
    return List(count) { i ->
        val y = 1.0 - (i.toDouble() / (count - 1)) * 2.0
        val r = sqrt(1.0 - y * y)
        val theta = golden * i
        Triple(cos(theta) * r, y, sin(theta) * r)
    }
}
