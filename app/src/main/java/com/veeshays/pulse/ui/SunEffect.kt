package com.veeshays.pulse.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin

/**
 * The PC "Sun effects" identity, re-expressed for mobile GPUs.
 *
 * Desktop uses layered CSS light/stained-glass treatments; porting those
 * shaders 1:1 would burn battery and jank on phones. This is the visually
 * equivalent optimized implementation the spec asks for:
 *  - radial sun glow in the theme accent (PC --glow / blI-blSoft language)
 *  - slow rotating rays (PC light-model language), ≤1 animation clock
 *  - optional stained tint wash (PC stained-glass language, no texture maps)
 *  - static frame when [animated] is false (lists, backgrounded, battery saver)
 *
 * Nothing here touches playback or input: pure decoration behind controls.
 */
@Composable
fun SunEffect(
    accent: Color,
    modifier: Modifier = Modifier,
    stained: Boolean = false,
    animated: Boolean = true,
    rayCount: Int = 12,
    intensity: Float = 0.22f, // PC blI≈22 default
) {
    if (!animated) {
        Canvas(modifier) {
            val c = Offset(size.width / 2f, size.height * 0.32f)
            val r = size.minDimension * 0.75f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to accent.copy(alpha = intensity + 0.10f),
                    1f to Color.Transparent,
                    center = c, radius = r,
                ),
                radius = r, center = c,
            )
        }
        return
    }
    val t by rememberInfiniteTransition(label = "sun").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(28_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ray-rotation",
    )
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height * 0.32f)
        val r = size.minDimension * 0.75f
        // Core glow.
        drawCircle(
            brush = Brush.radialGradient(
                0f to accent.copy(alpha = (intensity + 0.10f).coerceAtMost(0.5f)),
                0.55f to accent.copy(alpha = intensity * 0.45f),
                1f to Color.Transparent,
                center = c, radius = r,
            ),
            radius = r, center = c,
        )
        // Rays: cheap lines, rotated as one batch — no per-ray clock, no shader.
        rotate(t, pivot = c) {
            repeat(rayCount) { i ->
                val a = (i * 2f * Math.PI / rayCount).toFloat()
                val r0 = r * 0.18f
                drawLine(
                    color = accent.copy(alpha = intensity * 0.5f),
                    start = Offset(c.x + cos(a) * r0, c.y + sin(a) * r0),
                    end = Offset(c.x + cos(a) * r * 0.62f, c.y + sin(a) * r * 0.62f),
                    strokeWidth = size.minDimension * 0.012f,
                )
            }
        }
        // Stained-glass wash: diagonal tint bands (identity hint, ~free).
        if (stained) {
            drawRect(
                brush = Brush.linearGradient(
                    0f to accent.copy(alpha = 0.05f),
                    0.5f to Color.Transparent,
                    1f to accent.copy(alpha = 0.08f),
                ),
                size = size,
            )
        }
    }
}
