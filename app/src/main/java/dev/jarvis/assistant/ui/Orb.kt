package dev.jarvis.assistant.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import dev.jarvis.assistant.assistant.Phase

private val OrbColors = listOf(Color(0xFF22D3EE), Color(0xFF8B5CF6), Color(0xFFEC4899), Color(0xFF22D3EE))
private val Shine = listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent)

/** Siri-style glowing orb: swells with your voice while listening and breathes while Jarvis talks. */
@Composable
fun Orb(phase: Phase, level: Float, modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "orb")
    val spin by motion.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "spin")
    val breath by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breath")
    val voice by animateFloatAsState(level, spring(stiffness = Spring.StiffnessMediumLow), label = "voice")

    val scale = when (phase) {
        Phase.Listening -> 0.8f + 0.2f * voice
        Phase.Thinking -> 0.78f + 0.04f * breath
        Phase.Speaking -> 0.85f + 0.15f * breath
        Phase.Idle -> 0.75f
    }
    val glow = if (phase == Phase.Idle) 0.55f else 1f

    Canvas(modifier) {
        val radius = size.minDimension / 2f * scale
        rotate(spin) {
            drawCircle(Brush.sweepGradient(OrbColors, center), radius, alpha = glow)
        }
        rotate(-spin * 2f) {
            drawCircle(Brush.sweepGradient(Shine, center), radius * 0.8f, alpha = glow)
        }
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.6f * glow), Color.Transparent), center, radius * 0.7f),
            radius * 0.7f,
        )
    }
}
