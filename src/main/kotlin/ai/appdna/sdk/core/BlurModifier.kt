package ai.appdna.sdk.core

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

data class BlurConfig(
    val radius: Float,
    val tint: String? = null,
    val saturation: Float? = null,
)

fun Modifier.applyBlurBackdrop(config: BlurConfig?, cornerRadius: Float = 0f): Modifier {
    if (config == null || config.radius <= 0) return this

    return this
        .graphicsLayer {
            // API 31+ supports RenderEffect blur
            if (Build.VERSION.SDK_INT >= 31) {
                val blurPx = config.radius * density
                renderEffect = android.graphics.RenderEffect.createBlurEffect(
                    blurPx, blurPx,
                    android.graphics.Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
            }
        }
        .background(
            // Tint overlay for glassmorphism
            config.tint?.let { StyleEngine.parseColor(it) }
                ?: Color.White.copy(alpha = 0.15f),
            shape = RoundedCornerShape(cornerRadius.dp)
        )
        .clip(RoundedCornerShape(cornerRadius.dp))
}
