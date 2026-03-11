package ai.appdna.sdk.core

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// MARK: - Config data classes

data class TextStyleConfig(
    val font_family: String? = null,
    val font_size: Double? = null,
    val font_weight: Int? = null,
    val color: String? = null,
    val alignment: String? = null,
    val line_height: Double? = null,
    val letter_spacing: Double? = null,
    val opacity: Double? = null,
)

data class BackgroundStyleConfig(
    val type: String? = null,
    val color: String? = null,
    val gradient: GradientConfig? = null,
    val image_url: String? = null,
    val image_fit: String? = null,
    val overlay: String? = null,
)

data class GradientConfig(
    val type: String? = null,
    val angle: Double? = null,
    val stops: List<GradientStopConfig>? = null,
)

data class GradientStopConfig(
    val color: String,
    val position: Double,
)

data class BorderStyleConfig(
    val width: Double? = null,
    val color: String? = null,
    val style: String? = null,
    val radius: Double? = null,
    val radius_top_left: Double? = null,
    val radius_top_right: Double? = null,
    val radius_bottom_left: Double? = null,
    val radius_bottom_right: Double? = null,
)

data class ShadowStyleConfig(
    val x: Double? = null,
    val y: Double? = null,
    val blur: Double? = null,
    val spread: Double? = null,
    val color: String? = null,
)

data class SpacingConfig(
    val top: Double? = null,
    val right: Double? = null,
    val bottom: Double? = null,
    val left: Double? = null,
)

data class ElementStyleConfig(
    val background: BackgroundStyleConfig? = null,
    val border: BorderStyleConfig? = null,
    val shadow: ShadowStyleConfig? = null,
    val padding: SpacingConfig? = null,
    val corner_radius: Double? = null,
    val opacity: Double? = null,
)

data class SectionStyleConfig(
    val container: ElementStyleConfig? = null,
    val elements: Map<String, ElementStyleConfig>? = null,
)

data class AnimationConfig(
    val entry_animation: String? = null,
    val entry_duration_ms: Int? = null,
    val section_stagger: String? = null,
    val section_stagger_delay_ms: Int? = null,
    val cta_animation: String? = null,
    val plan_selection_animation: String? = null,
    val dismiss_animation: String? = null,
)

// MARK: - Style engine

object StyleEngine {

    /**
     * Apply TextStyleConfig to a Compose TextStyle.
     */
    fun applyTextStyle(base: TextStyle, config: TextStyleConfig?): TextStyle {
        if (config == null) return base
        return base.copy(
            fontFamily = FontResolver.resolve(config.font_family),
            fontSize = (config.font_size ?: 16.0).sp,
            fontWeight = FontResolver.fontWeight(config.font_weight),
            color = config.color?.let { parseColor(it) } ?: Color.Unspecified,
            textAlign = when (config.alignment) {
                "center" -> TextAlign.Center
                "right" -> TextAlign.End
                else -> TextAlign.Start
            },
            lineHeight = ((config.line_height ?: 1.4) * (config.font_size ?: 16.0)).sp,
            letterSpacing = (config.letter_spacing ?: 0.0).sp,
        )
    }

    /**
     * Apply ElementStyleConfig as Modifier extensions.
     */
    fun Modifier.applyContainerStyle(style: ElementStyleConfig?): Modifier {
        if (style == null) return this

        // Per-corner radius support
        val shape = resolveShape(style)

        var mod = this
        // Padding
        style.padding?.let { p ->
            mod = mod.padding(
                start = (p.left ?: 0.0).dp,
                top = (p.top ?: 0.0).dp,
                end = (p.right ?: 0.0).dp,
                bottom = (p.bottom ?: 0.0).dp,
            )
        }
        // Background
        style.background?.let { bg ->
            mod = when (bg.type) {
                "color" -> mod.background(parseColor(bg.color ?: "#FFFFFF"), shape)
                "gradient" -> {
                    val stops = bg.gradient?.stops
                    if (stops != null && stops.size >= 2) {
                        val colors = stops.map { parseColor(it.color) }
                        val brush = when (bg.gradient.type) {
                            "radial" -> Brush.radialGradient(colors)
                            else -> {
                                val angle = bg.gradient.angle ?: 180.0
                                val rads = Math.toRadians(angle)
                                val dx = sin(rads).toFloat()
                                val dy = -cos(rads).toFloat()
                                Brush.linearGradient(
                                    colors = colors,
                                    start = androidx.compose.ui.geometry.Offset(
                                        (0.5f - dx / 2f) * 1000f,
                                        (0.5f - dy / 2f) * 1000f,
                                    ),
                                    end = androidx.compose.ui.geometry.Offset(
                                        (0.5f + dx / 2f) * 1000f,
                                        (0.5f + dy / 2f) * 1000f,
                                    ),
                                )
                            }
                        }
                        mod.background(brush, shape)
                    } else mod
                }
                "image" -> {
                    // Image backgrounds are handled at the view level (AsyncImage),
                    // but we apply the overlay color if present
                    bg.overlay?.let { overlay ->
                        mod.background(parseColor(overlay), shape)
                    } ?: mod
                }
                else -> mod
            }
        }
        // Clip
        mod = mod.clip(shape)
        // Border
        style.border?.let { b ->
            if ((b.width ?: 0.0) > 0) {
                mod = mod.border(
                    (b.width ?: 0.0).dp,
                    parseColor(b.color ?: "transparent"),
                    shape,
                )
            }
        }
        // Shadow
        style.shadow?.let { s ->
            mod = mod.shadow(
                elevation = ((s.blur ?: 0.0) / 2).dp,
                shape = shape,
            )
        }
        return mod
    }

    /**
     * Resolve per-corner or uniform corner radius into a Shape.
     */
    private fun resolveShape(style: ElementStyleConfig): RoundedCornerShape {
        val border = style.border
        val hasPerCorner = border != null && listOf(
            border.radius_top_left, border.radius_top_right,
            border.radius_bottom_left, border.radius_bottom_right
        ).any { it != null }

        return if (hasPerCorner) {
            RoundedCornerShape(
                topStart = (border?.radius_top_left ?: style.corner_radius ?: 0.0).dp,
                topEnd = (border?.radius_top_right ?: style.corner_radius ?: 0.0).dp,
                bottomStart = (border?.radius_bottom_left ?: style.corner_radius ?: 0.0).dp,
                bottomEnd = (border?.radius_bottom_right ?: style.corner_radius ?: 0.0).dp,
            )
        } else {
            RoundedCornerShape((style.corner_radius ?: 0.0).dp)
        }
    }

    /**
     * Parse a hex color string to Compose Color.
     */
    fun parseColor(hex: String): Color {
        return try {
            val cleaned = hex.removePrefix("#")
            val colorLong = cleaned.toLong(16)
            when (cleaned.length) {
                6 -> Color(0xFF000000 or colorLong)
                8 -> Color(colorLong)
                else -> Color.Black
            }
        } catch (_: Exception) {
            Color.Black
        }
    }
}

// MARK: - Localization helper

object LocalizationEngine {
    fun resolve(
        key: String,
        localizations: Map<String, Map<String, String>>?,
        defaultLocale: String?,
        fallback: String,
    ): String {
        val deviceLocale = java.util.Locale.getDefault().language
        localizations?.get(deviceLocale)?.get(key)?.let { return it }
        defaultLocale?.let { localizations?.get(it)?.get(key)?.let { v -> return v } }
        return fallback
    }
}
