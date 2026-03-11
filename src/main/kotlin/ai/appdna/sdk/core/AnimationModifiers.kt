package ai.appdna.sdk.core

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// MARK: - Entry Animation

@Composable
fun Modifier.entryAnimation(animation: String?, durationMs: Int? = null): Modifier {
    val anim = animation ?: "none"
    if (anim == "none") return this

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val duration = durationMs ?: 400
    val animSpec = tween<Float>(durationMillis = duration, easing = FastOutSlowInEasing)

    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = animSpec,
        label = "entry_alpha",
    )
    val scaleVal by animateFloatAsState(
        targetValue = if (appeared || anim != "scale_in") 1f else 0.8f,
        animationSpec = animSpec,
        label = "entry_scale",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (appeared || anim != "slide_up") 0f else 800f,
        animationSpec = animSpec,
        label = "entry_offset",
    )

    return this
        .alpha(alpha)
        .scale(scaleVal)
        .offset { IntOffset(0, offsetY.toInt()) }
}

// MARK: - Section Stagger Animation

@Composable
fun Modifier.sectionStagger(animation: String?, delayMs: Int? = null): Modifier {
    val anim = animation ?: "none"
    if (anim == "none") return this

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((delayMs ?: 0).toLong())
        appeared = true
    }

    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(400),
        label = "stagger_alpha",
    )
    val offsetX by animateFloatAsState(
        targetValue = if (appeared) 0f else when (anim) {
            "slide_in_left" -> -300f
            "slide_in_right" -> 300f
            else -> 0f
        },
        animationSpec = if (anim == "bounce") spring(dampingRatio = 0.6f) else tween(400),
        label = "stagger_offset",
    )
    val scaleVal by animateFloatAsState(
        targetValue = if (appeared || anim != "bounce") 1f else 0.5f,
        animationSpec = spring(dampingRatio = 0.6f),
        label = "stagger_scale",
    )

    return this
        .alpha(alpha)
        .scale(scaleVal)
        .offset { IntOffset(offsetX.toInt(), 0) }
}

// MARK: - CTA Animations

@Composable
fun Modifier.ctaAnimation(animation: String?): Modifier {
    return when (animation) {
        "pulse" -> this.pulseEffect()
        "glow" -> this.glowEffect()
        "bounce" -> this.bounceEffect()
        else -> this
    }
}

@Composable
private fun Modifier.pulseEffect(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "pulse_scale",
    )
    return this.scale(scale)
}

@Composable
private fun Modifier.glowEffect(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val elevation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 15f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "glow_elevation",
    )
    return this.shadow(elevation.dp)
}

@Composable
private fun Modifier.bounceEffect(): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -15f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "bounce_offset",
    )
    return this.offset { IntOffset(0, offsetY.toInt()) }
}

// MARK: - Plan Selection Animation

@Composable
fun Modifier.planSelectionAnimation(animation: String?, isSelected: Boolean): Modifier {
    val anim = animation ?: "none"
    if (anim == "none") return this

    val scale by animateFloatAsState(
        targetValue = if (anim == "scale" && isSelected) 1.03f else 1f,
        animationSpec = spring(dampingRatio = 0.7f),
        label = "plan_scale",
    )

    return this.scale(scale)
}
