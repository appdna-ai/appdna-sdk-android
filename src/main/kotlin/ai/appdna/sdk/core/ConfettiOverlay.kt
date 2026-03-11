package ai.appdna.sdk.core

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

data class ParticleEffect(
    val type: String = "confetti",       // "confetti", "sparkle", "fireworks", "snow", "hearts"
    val trigger: String = "on_appear",   // "on_appear", "on_step_complete", "on_purchase", "on_flow_complete"
    val duration_ms: Int = 2500,
    val intensity: String = "medium",    // "light", "medium", "heavy"
    val colors: List<String>? = null,
)

@Composable
fun ConfettiOverlay(
    effect: ParticleEffect,
    trigger: Boolean = true,
) {
    var isActive by remember { mutableStateOf(false) }
    val particleCount = when (effect.intensity) {
        "light" -> 30
        "heavy" -> 120
        else -> 60
    }

    val effectColors = if (!effect.colors.isNullOrEmpty()) {
        effect.colors.map { StyleEngine.parseColor(it) }
    } else {
        when (effect.type) {
            "hearts" -> listOf(Color.Red, Color(0xFFFF69B4), Color(0xFFFF6B9D))
            "snow" -> listOf(Color.White, Color(0xFFF0F0F0), Color(0xFFE5E5E5))
            "sparkle" -> listOf(Color.Yellow, Color(0xFFFFA500), Color(0xFFFFD700))
            else -> listOf(Color.Red, Color.Blue, Color.Green, Color.Yellow, Color(0xFF9333EA), Color(0xFFF97316), Color(0xFFEC4899))
        }
    }

    val progress = remember { Animatable(0f) }

    LaunchedEffect(trigger) {
        if (trigger && (effect.trigger == "on_appear" || isActive)) {
            isActive = true
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = effect.duration_ms, easing = LinearEasing)
            )
            isActive = false
        }
    }

    if (isActive) {
        val particles = remember(particleCount) {
            (0 until particleCount).map { i ->
                ConfettiParticle(
                    startX = (Math.random() * 1000).toFloat(),
                    startY = -(Math.random() * 200).toFloat(),
                    color = effectColors[i % effectColors.size],
                    size = (4f + Math.random().toFloat() * 8f),
                    speedX = (-50f + Math.random().toFloat() * 100f),
                    speedY = (200f + Math.random().toFloat() * 400f),
                    rotation = Math.random().toFloat() * 360f,
                )
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = progress.value
            particles.forEach { particle ->
                val x = particle.startX / 1000f * size.width + particle.speedX * t
                val y = particle.startY + particle.speedY * t
                val alpha = (1f - t).coerceIn(0f, 1f)

                if (y < size.height + 50) {
                    drawCircle(
                        color = particle.color.copy(alpha = alpha),
                        radius = particle.size,
                        center = Offset(x.coerceIn(0f, size.width), y),
                    )
                }
            }
        }
    }
}

private data class ConfettiParticle(
    val startX: Float,
    val startY: Float,
    val color: Color,
    val size: Float,
    val speedX: Float,
    val speedY: Float,
    val rotation: Float,
)
