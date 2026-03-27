@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.material3.Divider
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import ai.appdna.sdk.Log
import ai.appdna.sdk.LogLevel
import ai.appdna.sdk.core.StyleEngine
import ai.appdna.sdk.core.TextStyleConfig
import ai.appdna.sdk.core.LottieBlock
import ai.appdna.sdk.core.LottieBlockView
import ai.appdna.sdk.core.RiveBlock
import ai.appdna.sdk.core.RiveBlockView
import ai.appdna.sdk.core.VideoBlock as CoreVideoBlock
import ai.appdna.sdk.core.VideoBlockView as CoreVideoBlockView
import ai.appdna.sdk.core.IconView
import ai.appdna.sdk.core.resolveIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.text.ClickableText
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.zIndex
// mutableIntStateOf, derivedStateOf, etc. covered by runtime.* import
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.DisposableEffect
import ai.appdna.sdk.AppDNA
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.spring
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image

// MARK: - Block Style Design Tokens (SPEC-089d §6.1)

/** Per-block styling: background, border, shadow, padding, margin, opacity. */
data class BlockStyle(
    val background_color: String? = null,
    val background_gradient: BlockGradientStyle? = null,
    val border_color: String? = null,
    val border_width: Double? = null,
    val border_style: String? = null,  // solid, dashed, dotted
    val border_radius: Double? = null,
    val shadow: BlockShadowStyle? = null,
    val padding_top: Double? = null,
    val padding_right: Double? = null,
    val padding_bottom: Double? = null,
    val padding_left: Double? = null,
    val margin_top: Double? = null,
    val margin_bottom: Double? = null,
    val margin_left: Double? = null,
    val margin_right: Double? = null,
    val opacity: Double? = null,
)

/** Shadow definition for block_style. */
data class BlockShadowStyle(
    val x: Double = 0.0,
    val y: Double = 2.0,
    val blur: Double = 8.0,
    val spread: Double = 0.0,
    val color: String = "#1A000000",  // ~10% black
)

/** Gradient definition for block_style background. */
data class BlockGradientStyle(
    val angle: Double = 135.0,
    val start: String = "#6366f1",
    val end: String = "#a855f7",
)

// MARK: - Block Style Modifier Extension (SPEC-089d §6.1)

/**
 * Applies `block_style` design tokens to any Composable's Modifier.
 * Order: inner padding → background → clip/border → shadow → opacity → outer margin.
 */
fun Modifier.applyBlockStyle(style: BlockStyle?): Modifier {
    if (style == null) return this

    var mod = this

    // Outer margin (top/bottom/left/right)
    val mTop = style.margin_top ?: 0.0
    val mBottom = style.margin_bottom ?: 0.0
    val mLeft = style.margin_left ?: 0.0
    val mRight = style.margin_right ?: 0.0
    if (mTop > 0 || mBottom > 0 || mLeft > 0 || mRight > 0) {
        mod = mod.then(Modifier.padding(top = mTop.dp, bottom = mBottom.dp, start = mLeft.dp, end = mRight.dp))
    }

    // Opacity
    if (style.opacity != null) mod = mod.then(Modifier.alpha(style.opacity.toFloat()))

    // Shadow (must come before clip/background to be visible)
    if (style.shadow != null) {
        val s = style.shadow
        val shape = RoundedCornerShape((style.border_radius ?: 0.0).dp)
        mod = mod.then(
            Modifier.shadow(
                elevation = (s.blur / 2).dp,
                shape = shape,
                ambientColor = StyleEngine.parseColor(s.color),
                spotColor = StyleEngine.parseColor(s.color),
            )
        )
    }

    // Clip shape
    val shape = RoundedCornerShape((style.border_radius ?: 0.0).dp)
    mod = mod.then(Modifier.clip(shape))

    // Background (gradient takes precedence over solid color)
    if (style.background_gradient != null) {
        val g = style.background_gradient
        val rads = g.angle * Math.PI / 180.0
        val startX = (0.5 - sin(rads) / 2).toFloat()
        val startY = (0.5 + cos(rads) / 2).toFloat()
        val endX = (0.5 + sin(rads) / 2).toFloat()
        val endY = (0.5 - cos(rads) / 2).toFloat()
        mod = mod.then(
            Modifier.background(
                Brush.linearGradient(
                    colors = listOf(
                        StyleEngine.parseColor(g.start),
                        StyleEngine.parseColor(g.end),
                    ),
                    start = androidx.compose.ui.geometry.Offset(startX * 1000f, startY * 1000f),
                    end = androidx.compose.ui.geometry.Offset(endX * 1000f, endY * 1000f),
                ),
                shape,
            )
        )
    } else if (style.background_color != null) {
        mod = mod.then(Modifier.background(StyleEngine.parseColor(style.background_color), shape))
    }

    // Border
    if (style.border_width != null && style.border_width > 0) {
        val borderColor = StyleEngine.parseColor(style.border_color ?: "#000000")
        // Note: dashed/dotted borders require Canvas — for now we render solid only.
        // border_style is parsed but not yet differentiated visually.
        mod = mod.then(Modifier.border(style.border_width.dp, borderColor, shape))
    }

    // Inner padding
    mod = mod.then(
        Modifier.padding(
            top = (style.padding_top ?: 0.0).dp,
            end = (style.padding_right ?: 0.0).dp,
            bottom = (style.padding_bottom ?: 0.0).dp,
            start = (style.padding_left ?: 0.0).dp,
        )
    )

    return mod
}

// MARK: - 2D Positioning Modifier (SPEC-089d §6.2)

/**
 * Applies vertical/horizontal alignment + offset positioning to a content block.
 */
fun Modifier.applyBlockPosition(
    verticalAlign: String?,
    horizontalAlign: String?,
    verticalOffset: Double?,
    horizontalOffset: Double?,
): Modifier {
    val hasPositioning = verticalAlign != null || horizontalAlign != null
        || verticalOffset != null || horizontalOffset != null
    if (!hasPositioning) return this

    var mod = this

    // Offset
    if (verticalOffset != null || horizontalOffset != null) {
        mod = mod.then(
            Modifier.offset(
                x = (horizontalOffset ?: 0.0).dp,
                y = (verticalOffset ?: 0.0).dp,
            )
        )
    }

    return mod
}

/**
 * Maps horizontal/vertical alignment strings to Compose Alignment.
 */
internal fun mapBlockAlignment(horizontal: String?, vertical: String?): Alignment {
    val h = when (horizontal) {
        "left" -> Alignment.Start
        "right" -> Alignment.End
        else -> Alignment.CenterHorizontally
    }
    val v = when (vertical) {
        "top" -> Alignment.Top
        "bottom" -> Alignment.Bottom
        else -> Alignment.CenterVertically
    }
    return when {
        h == Alignment.Start && v == Alignment.Top -> Alignment.TopStart
        h == Alignment.CenterHorizontally && v == Alignment.Top -> Alignment.TopCenter
        h == Alignment.End && v == Alignment.Top -> Alignment.TopEnd
        h == Alignment.Start && v == Alignment.CenterVertically -> Alignment.CenterStart
        h == Alignment.End && v == Alignment.CenterVertically -> Alignment.CenterEnd
        h == Alignment.Start && v == Alignment.Bottom -> Alignment.BottomStart
        h == Alignment.CenterHorizontally && v == Alignment.Bottom -> Alignment.BottomCenter
        h == Alignment.End && v == Alignment.Bottom -> Alignment.BottomEnd
        else -> Alignment.Center
    }
}

// MARK: - Content Block data class

data class ContentBlock(
    val id: String,
    val type: String,  // heading, text, image, button, spacer, list, divider, badge, icon, toggle, video, ...
    val text: String? = null,
    val level: Int? = null,
    val image_url: String? = null,
    val alt: String? = null,
    val corner_radius: Double? = null,
    val height: Double? = null,
    val variant: String? = null,
    val action: String? = null,
    val action_value: String? = null,
    val bg_color: String? = null,
    val text_color: String? = null,
    val button_corner_radius: Double? = null,
    val spacer_height: Double? = null,
    val items: List<String>? = null,
    val list_style: String? = null,
    val divider_color: String? = null,
    val divider_thickness: Double? = null,
    val divider_margin_y: Double? = null,
    val badge_text: String? = null,
    val badge_bg_color: String? = null,
    val badge_text_color: String? = null,
    val badge_corner_radius: Double? = null,
    val icon_emoji: String? = null,
    val icon_size: Double? = null,
    val icon_alignment: String? = null,
    val toggle_label: String? = null,
    val toggle_description: String? = null,
    val toggle_default: Boolean? = null,
    val video_thumbnail_url: String? = null,
    // SPEC-084: per-block text style override
    val style: TextStyleConfig? = null,
    // SPEC-084: video source URL, height, and corner radius
    val video_url: String? = null,
    val video_height: Double? = null,
    val video_corner_radius: Double? = null,
    // SPEC-085: Rich media fields
    val lottie_url: String? = null,
    val lottie_json: Map<String, Any>? = null,
    val lottie_autoplay: Boolean? = null,
    val lottie_loop: Boolean? = null,
    val lottie_speed: Float? = null,
    val rive_url: String? = null,
    val rive_artboard: String? = null,
    val rive_state_machine: String? = null,
    val icon_ref: Any? = null,  // IconReference map or emoji string
    val video_autoplay: Boolean? = null,
    val video_loop: Boolean? = null,
    val video_muted: Boolean? = null,
    // SPEC-089d §6.1: Per-block style design tokens
    val block_style: BlockStyle? = null,
    // SPEC-089d §6.2: 2D positioning
    val vertical_align: String? = null,
    val horizontal_align: String? = null,
    val vertical_offset: Double? = null,
    val horizontal_offset: Double? = null,
    // SPEC-089d: page_indicator fields
    val dot_count: Int? = null,
    val active_index: Int? = null,
    val active_color: String? = null,
    val inactive_color: String? = null,
    val dot_size: Double? = null,
    val dot_spacing: Double? = null,
    val active_dot_width: Double? = null,
    // SPEC-089d: social_login fields
    val providers: List<SocialProvider>? = null,
    val button_style: String? = null,
    val button_height: Double? = null,
    val spacing: Double? = null,
    val show_divider: Boolean? = null,
    val divider_text: String? = null,
    // SPEC-089d: countdown_timer fields
    val target_type: String? = null,
    val duration_seconds: Int? = null,
    val target_datetime: String? = null,
    val show_days: Boolean? = null,
    val show_hours: Boolean? = null,
    val show_minutes: Boolean? = null,
    val show_seconds: Boolean? = null,
    val labels: CountdownLabels? = null,
    val on_expire_action: String? = null,
    val expired_text: String? = null,
    val accent_color: String? = null,
    val font_size: Double? = null,
    val alignment: String? = null,
    // SPEC-089d: rating fields
    val field_id: String? = null,
    val max_stars: Int? = null,
    val default_value: Double? = null,
    val star_size: Double? = null,
    val active_rating_color: String? = null,
    val inactive_rating_color: String? = null,
    val allow_half: Boolean? = null,
    val label: String? = null,
    // SPEC-089d: rich_text fields
    val content: String? = null,
    val base_style: TextStyleConfig? = null,
    val link_color: String? = null,
    val max_lines: Int? = null,
    // SPEC-089d: progress_bar fields
    val segment_count: Int? = null,
    val active_segments: Int? = null,
    val fill_color: String? = null,
    val track_color: String? = null,
    val segment_gap: Double? = null,
    val show_label: Boolean? = null,
    val label_style: TextStyleConfig? = null,
    // SPEC-089d: timeline fields
    val timeline_items: List<TimelineItem>? = null,
    val line_color: String? = null,
    val completed_color: String? = null,
    val current_color: String? = null,
    val upcoming_color: String? = null,
    val show_line: Boolean? = null,
    val compact: Boolean? = null,
    val title_style: TextStyleConfig? = null,
    val subtitle_style: TextStyleConfig? = null,
    // SPEC-089d: animated_loading fields
    val loading_items: List<LoadingItem>? = null,
    val progress_color: String? = null,
    val check_color: String? = null,
    val total_duration_ms: Int? = null,
    val auto_advance: Boolean? = null,
    val show_percentage: Boolean? = null,
    // SPEC-089d Phase F: circular_gauge fields
    val gauge_value: Double? = null,
    val max_gauge_value: Double? = null,
    val sublabel: String? = null,
    val stroke_width: Double? = null,
    val label_color: String? = null,
    val label_font_size: Double? = null,
    val animate: Boolean? = null,
    val animation_duration_ms: Int? = null,
    // SPEC-089d Phase F: date_wheel_picker fields
    val columns: List<DateWheelColumn>? = null,
    val default_date_value: String? = null,
    val min_date: String? = null,
    val max_date: String? = null,
    val highlight_color: String? = null,
    val haptic_on_scroll: Boolean? = null,
    // SPEC-089d Phase F: stack / row fields (container blocks)
    val children: List<ContentBlock>? = null,
    val z_index: Double? = null,
    val gap: Double? = null,
    val wrap: Boolean? = null,
    val justify: String? = null,
    val align_items: String? = null,
    // Row direction and distribution (alignment gap fix)
    val row_direction: String? = null,       // horizontal (default), vertical
    val row_distribution: String? = null,    // start, center, end, space-between, space-around, space-evenly
    val row_child_fill: Boolean? = null,     // true (default) — each child gets weight(1f)
    // SPEC-089d Phase F: custom_view fields
    val view_key: String? = null,
    val custom_config: Map<String, Any>? = null,
    val placeholder_image_url: String? = null,
    val placeholder_text: String? = null,
    // SPEC-089d Phase F: star_background fields
    val particle_type: String? = null,
    val density: String? = null,
    val speed: String? = null,
    val secondary_color: String? = null,
    val size_range: List<Double>? = null,
    val fullscreen: Boolean? = null,
    // SPEC-089d Phase F: wheel_picker fields
    val min_value: Double? = null,
    val max_value_picker: Double? = null,
    val step_value: Double? = null,
    val default_picker_value: Double? = null,
    val unit: String? = null,
    val unit_position: String? = null,
    val visible_items: Int? = null,
    // SPEC-089d Phase F: pulsing_avatar fields
    val pulse_color: String? = null,
    val pulse_ring_count: Int? = null,
    val pulse_speed: Double? = null,
    val border_width: Double? = null,
    val border_color: String? = null,
    // SPEC-089d Nurrai: pricing_card fields
    val pricing_plans: List<PricingPlan>? = null,
    val pricing_layout: String? = null,
    // SPEC-089d Phase 3: Form input common fields
    val field_label: String? = null,
    val field_placeholder: String? = null,
    val field_required: Boolean? = null,
    val field_style: FormFieldBlockStyle? = null,
    val field_options: List<InputOption>? = null,
    val field_config: Map<String, Any>? = null,
    // SPEC-089d §6.3: Visibility condition
    val visibility_condition: VisibilityCondition? = null,
    // SPEC-089d §6.4: Entrance animation
    val entrance_animation: EntranceAnimationConfig? = null,
    // SPEC-089d §6.5: Press/tap state
    val pressed_style: PressedStyleConfig? = null,
    // SPEC-089d §6.6: Dynamic bindings
    val bindings: Map<String, String>? = null,
    // SPEC-089d §6.7: Relative sizing
    val element_width: String? = null,
    val element_height: String? = null,
    // Overflow control (visible = no clipping)
    val overflow: String? = null,
)

/** Social login provider config (SPEC-089d §3.4). */
data class SocialProvider(
    val type: String,
    val label: String? = null,
    val enabled: Boolean = true,
)

/** Countdown labels config (SPEC-089d §3.7). */
data class CountdownLabels(
    val days: String? = null,
    val hours: String? = null,
    val minutes: String? = null,
    val seconds: String? = null,
)

/** Timeline item config (SPEC-089d §3.5). */
data class TimelineItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: String? = null,
    val status: String = "upcoming",  // completed | current | upcoming
)

/** Animated loading item config (SPEC-089d §3.6). */
data class LoadingItem(
    val label: String,
    val duration_ms: Int = 1000,
    val icon: String? = null,
)

/** Pricing plan config for pricing_card block (SPEC-089d §3.17). */
data class PricingPlan(
    val id: String,
    val label: String,
    val price: String,
    val period: String,
    val badge: String? = null,
    val is_highlighted: Boolean = false,
)

/** Date wheel column config (SPEC-089d §3.12). */
data class DateWheelColumn(
    val type: String,    // day | month | year | custom
    val label: String? = null,
    val values: List<String>? = null,
)

/** Star particle state for star_background animation. */
data class StarParticleState(
    var x: Float,
    var y: Float,
    var size: Float,
    var opacity: Float,
    var speed: Float,
)

/** Form field styling config (SPEC-089d §5.2). */
data class FormFieldBlockStyle(
    val background_color: String? = null,
    val border_color: String? = null,
    val border_width: Double? = null,
    val corner_radius: Double? = null,
    val text_color: String? = null,
    val placeholder_color: String? = null,
    val font_size: Double? = null,
    val focused_border_color: String? = null,
    val label_color: String? = null,
    val label_font_size: Double? = null,
    val error_border_color: String? = null,
    val error_text_color: String? = null,
    val track_color: String? = null,
    val fill_color: String? = null,
    val thumb_color: String? = null,
    val toggle_on_color: String? = null,
    val toggle_off_color: String? = null,
)

/** Option for select, chips, segmented inputs. */
data class InputOption(
    val value: String,
    val label: String,
    val image_url: String? = null,
)

/** Visibility condition (SPEC-089d §6.3). */
data class VisibilityCondition(
    val type: String,        // always, when_equals, when_not_equals, when_not_empty, when_empty, when_gt, when_lt
    val variable: String? = null,
    val value: Any? = null,
    val expression: String? = null,
)

/** Entrance animation config (SPEC-089d §6.4). */
data class EntranceAnimationConfig(
    val type: String = "none",    // none, fade_in, slide_up, slide_down, slide_left, slide_right, scale_up, scale_down, bounce, flip
    val duration_ms: Int = 300,
    val delay_ms: Int = 0,
    val easing: String = "ease_out",
    val spring_damping: Double? = null,
)

/** Pressed/tap style config (SPEC-089d §6.5). */
data class PressedStyleConfig(
    val bg_color: String? = null,
    val text_color: String? = null,
    val scale: Double? = null,     // 0.85-1.0
    val opacity: Double? = null,   // 0.5-1.0
)

// MARK: - Visibility Condition Evaluator (SPEC-089d §6.3)

/**
 * Evaluates a visibility condition against the current data context.
 * Returns true if the block should be rendered.
 */
fun evaluateVisibilityCondition(
    condition: VisibilityCondition?,
    responses: Map<String, Any> = emptyMap(),
    hookData: Map<String, Any>? = null,
    userTraits: Map<String, Any>? = null,
    sessionData: Map<String, Any>? = null,
): Boolean {
    if (condition == null) return true

    return when (condition.type) {
        "always" -> true
        "when_equals" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            resolved?.toString() == condition.value?.toString()
        }
        "when_not_equals" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            resolved?.toString() != condition.value?.toString()
        }
        "when_not_empty" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            resolved != null && resolved.toString().isNotEmpty()
        }
        "when_empty" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            resolved == null || resolved.toString().isEmpty()
        }
        "when_gt" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            val numA = resolved?.toString()?.toDoubleOrNull() ?: return false
            val numB = condition.value?.toString()?.toDoubleOrNull() ?: return false
            numA > numB
        }
        "when_lt" -> {
            val resolved = resolveDotPath(condition.variable, responses, hookData, userTraits, sessionData)
            val numA = resolved?.toString()?.toDoubleOrNull() ?: return false
            val numB = condition.value?.toString()?.toDoubleOrNull() ?: return false
            numA < numB
        }
        else -> true
    }
}

/**
 * Resolves a dot-path variable from the evaluation context.
 */
private fun resolveDotPath(
    path: String?,
    responses: Map<String, Any>,
    hookData: Map<String, Any>?,
    userTraits: Map<String, Any>?,
    sessionData: Map<String, Any>?,
): Any? {
    if (path.isNullOrEmpty()) return null
    val parts = path.split(".")
    if (parts.size < 2) return null

    val root: Map<String, Any>? = when (parts[0]) {
        "responses" -> responses
        "hook_data" -> hookData
        "user" -> userTraits
        "session" -> sessionData
        else -> null
    }

    var current: Any? = root ?: return null
    for (part in parts.drop(1)) {
        current = (current as? Map<*, *>)?.get(part) ?: return null
    }
    return current
}

/**
 * Resolves `{{variable}}` template strings in text (SPEC-089d §6.6).
 */
fun resolveTemplateString(
    text: String,
    hookData: Map<String, Any>?,
    responses: Map<String, Any>,
    sessionData: Map<String, Any>? = null,
    userTraits: Map<String, Any>? = null,
): String {
    val pattern = Regex("\\{\\{\\s*([a-zA-Z0-9_.]+)\\s*\\}\\}")
    return pattern.replace(text) { matchResult ->
        val path = matchResult.groupValues[1]
        val resolved = resolveDotPath(path, responses, hookData, userTraits, sessionData)
        resolved?.toString() ?: matchResult.value
    }
}

/**
 * AC-064/065/066: Resolves dynamic bindings and template strings on a block.
 * Returns a new ContentBlock with resolved text fields and binding property overrides.
 */
private fun resolveBlockBindings(
    block: ContentBlock,
    hookData: Map<String, Any>?,
    responses: Map<String, Any>,
): ContentBlock {
    val hasBindings = !block.bindings.isNullOrEmpty()
    val hasTemplates = (block.text?.contains("{{") == true)
        || (block.field_label?.contains("{{") == true)
        || (block.field_placeholder?.contains("{{") == true)
        || (block.badge_text?.contains("{{") == true)
        || (block.toggle_label?.contains("{{") == true)
        || (block.label?.contains("{{") == true)
    if (!hasBindings && !hasTemplates) return block

    var resolved = block

    // AC-066: Resolve bindings map — override block properties from data context
    if (hasBindings) {
        block.bindings?.forEach { (property, path) ->
            val value = resolveDotPath(path, responses, hookData, null, null)
            if (value != null) {
                resolved = applyBindingProperty(resolved, property, value)
            }
        }
    }

    // AC-064/065: Resolve template strings in text fields
    if (hasTemplates) {
        resolved = resolved.copy(
            text = resolved.text?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            field_label = resolved.field_label?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            field_placeholder = resolved.field_placeholder?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            badge_text = resolved.badge_text?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            toggle_label = resolved.toggle_label?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
            label = resolved.label?.let { if (it.contains("{{")) resolveTemplateString(it, hookData, responses) else it },
        )
    }

    return resolved
}

/** Apply a single binding property override to a ContentBlock. */
private fun applyBindingProperty(block: ContentBlock, property: String, value: Any): ContentBlock {
    val strValue = value.toString()
    return when (property) {
        "text" -> block.copy(text = strValue)
        "field_label" -> block.copy(field_label = strValue)
        "field_placeholder" -> block.copy(field_placeholder = strValue)
        "badge_text" -> block.copy(badge_text = strValue)
        "toggle_label" -> block.copy(toggle_label = strValue)
        "label" -> block.copy(label = strValue)
        "image_url" -> block.copy(image_url = strValue)
        "bg_color" -> block.copy(bg_color = strValue)
        "text_color" -> block.copy(text_color = strValue)
        "active_color" -> block.copy(active_color = strValue)
        "inactive_color" -> block.copy(inactive_color = strValue)
        "fill_color" -> block.copy(fill_color = strValue)
        "icon_emoji" -> block.copy(icon_emoji = strValue)
        "active_index" -> (value as? Number)?.toInt()?.let { block.copy(active_index = it) } ?: block
        "dot_count" -> (value as? Number)?.toInt()?.let { block.copy(dot_count = it) } ?: block
        "segment_count" -> (value as? Number)?.toInt()?.let { block.copy(segment_count = it) } ?: block
        "active_segments" -> (value as? Number)?.toInt()?.let { block.copy(active_segments = it) } ?: block
        else -> block // Unknown property — no-op
    }
}

/**
 * Parses relative size strings (SPEC-089d §6.7).
 */
fun Modifier.applyRelativeSizing(width: String?, height: String?): Modifier {
    var mod = this
    when {
        width == "fill" -> mod = mod.then(Modifier.fillMaxWidth())
        width == "auto" -> { /* no-op, content-sized */ }
        width?.endsWith("%") == true -> {
            val fraction = width.dropLast(1).toFloatOrNull()?.div(100f)
            if (fraction != null) mod = mod.then(Modifier.fillMaxWidth(fraction))
        }
        width?.endsWith("px") == true -> {
            val px = width.dropLast(2).toFloatOrNull()
            if (px != null) mod = mod.then(Modifier.width(px.dp))
        }
    }
    when {
        height == "fill" -> mod = mod.then(Modifier.fillMaxHeight())
        height == "auto" -> { /* no-op */ }
        height?.endsWith("%") == true -> {
            val fraction = height.dropLast(1).toFloatOrNull()?.div(100f)
            if (fraction != null) mod = mod.then(Modifier.fillMaxHeight(fraction))
        }
        height?.endsWith("px") == true -> {
            val px = height.dropLast(2).toFloatOrNull()
            if (px != null) mod = mod.then(Modifier.height(px.dp))
        }
    }
    return mod
}

// MARK: - Content Block Renderer

@Composable
fun ContentBlockRendererView(
    blocks: List<ContentBlock>,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any> = mutableMapOf(),
    loc: ((String, String) -> String)? = null,
    responses: Map<String, Any> = emptyMap(),
    hookData: Map<String, Any>? = null,
    currentStepIndex: Int = 0,
    totalSteps: Int = 1,
) {
    // SPEC-089d §6.3: Filter blocks by visibility condition
    val visibleBlocks = blocks.filter { block ->
        evaluateVisibilityCondition(
            block.visibility_condition,
            responses = responses,
            hookData = hookData,
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        var animationCount = 0
        visibleBlocks.forEach { rawBlock ->
            // AC-064/065/066: Resolve dynamic bindings and template strings
            val block = resolveBlockBindings(rawBlock, hookData = hookData, responses = responses)

            val shouldAnimate = block.entrance_animation != null
                && block.entrance_animation.type != "none"
                && animationCount < 10  // Max 10 animated blocks per step
            if (shouldAnimate) animationCount++

            // SPEC-089d §6.7: Apply relative sizing
            val sizingModifier = Modifier.applyRelativeSizing(block.element_width, block.element_height)

            if (shouldAnimate) {
                block.entrance_animation?.let { anim ->
                    EntranceAnimationWrapper(animation = anim) {
                        Box(modifier = sizingModifier) {
                            RenderBlock(block = block, onAction = onAction, toggleValues = toggleValues, inputValues = inputValues, loc = loc, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
                        }
                    }
                }
            } else {
                Box(modifier = sizingModifier) {
                    RenderBlock(block = block, onAction = onAction, toggleValues = toggleValues, inputValues = inputValues, loc = loc, currentStepIndex = currentStepIndex, totalSteps = totalSteps)
                }
            }
        }
    }
}

@Composable
internal fun RenderBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any> = mutableMapOf(),
    loc: ((String, String) -> String)? = null,
    currentStepIndex: Int = 0,
    totalSteps: Int = 1,
) {
    // SPEC-089d: Wrap every block with block_style + 2D positioning modifiers
    val blockAlignment = if (block.horizontal_align != null || block.vertical_align != null) {
        mapBlockAlignment(block.horizontal_align, block.vertical_align)
    } else null

    val contentModifier = Modifier
        .applyBlockStyle(block.block_style)
        .applyBlockPosition(
            verticalAlign = block.vertical_align,
            horizontalAlign = block.horizontal_align,
            verticalOffset = block.vertical_offset,
            horizontalOffset = block.horizontal_offset,
        )

    if (blockAlignment != null) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = blockAlignment,
        ) {
            Box(modifier = contentModifier) {
                RenderBlockContent(block, onAction, toggleValues, inputValues, loc, currentStepIndex, totalSteps)
            }
        }
    } else {
        Box(modifier = contentModifier) {
            RenderBlockContent(block, onAction, toggleValues, inputValues, loc, currentStepIndex, totalSteps)
        }
    }
}

@Composable
private fun RenderBlockContent(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any> = mutableMapOf(),
    loc: ((String, String) -> String)? = null,
    currentStepIndex: Int = 0,
    totalSteps: Int = 1,
) {
    when (block.type) {
        "heading" -> HeadingBlock(block, loc)
        "text" -> TextBlock(block, loc)
        "image" -> ImageBlock(block)
        "button" -> ButtonBlock(block, onAction, loc)
        "spacer" -> Spacer(modifier = Modifier.height((block.spacer_height ?: 16.0).dp))
        "list" -> ListBlock(block, loc)
        "divider" -> DividerBlock(block)
        "badge" -> BadgeBlock(block, loc)
        "icon" -> IconBlock(block)
        "toggle" -> ToggleBlock(block, toggleValues, loc)
        "video" -> VideoBlock(block)
        // SPEC-085: Rich media block types
        "lottie" -> LottieContentBlock(block)
        "rive" -> RiveContentBlock(block)
        // SPEC-089d Phase A: Implemented onboarding block types
        "page_indicator" -> PageIndicatorBlock(block, currentStepIndex, totalSteps)
        "social_login" -> SocialLoginBlock(block, onAction, loc)
        "countdown_timer" -> CountdownTimerBlock(block, onAction)
        "rating" -> RatingBlock(block, inputValues, loc)
        "rich_text" -> RichTextBlock(block, loc)
        "progress_bar" -> ProgressBarBlock(block, loc, currentStepIndex, totalSteps)
        "timeline" -> TimelineBlock(block, loc)
        "animated_loading" -> AnimatedLoadingBlock(block, onAction)
        // SPEC-089d Phase A: Implemented blocks
        "wheel_picker" -> WheelPickerBlock(block, inputValues)
        "pulsing_avatar" -> PulsingAvatarBlock(block)
        "star_background" -> StarBackgroundBlock(block)
        // SPEC-089d Phase F: Container & advanced block types
        "stack" -> StackBlock(block, onAction, toggleValues, inputValues, loc)
        "custom_view" -> CustomViewBlock(block)
        "date_wheel_picker" -> DateWheelPickerBlock(block, inputValues)
        "circular_gauge" -> CircularGaugeBlock(block)
        "row" -> RowBlock(block, onAction, toggleValues, inputValues, loc)
        // SPEC-089d Nurrai: Pricing card
        "pricing_card" -> PricingCardBlock(block, onAction, inputValues)
        // SPEC-089d Phase 3: Form input block renderers (22 types)
        "input_text" -> FormInputTextBlock(block, inputValues, keyboardType = android.text.InputType.TYPE_CLASS_TEXT)
        "input_textarea" -> FormInputTextAreaBlock(block, inputValues)
        "input_number" -> FormInputTextBlock(block, inputValues, keyboardType = android.text.InputType.TYPE_CLASS_NUMBER)
        "input_email" -> FormInputTextBlock(block, inputValues, keyboardType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        "input_phone" -> FormInputTextBlock(block, inputValues, keyboardType = android.text.InputType.TYPE_CLASS_PHONE)
        "input_url" -> FormInputTextBlock(block, inputValues, keyboardType = android.text.InputType.TYPE_TEXT_VARIATION_URI)
        "input_password" -> FormInputPasswordBlock(block, inputValues)
        "input_date" -> FormInputDateBlock(block, inputValues, mode = "date")
        "input_time" -> FormInputDateBlock(block, inputValues, mode = "time")
        "input_datetime" -> FormInputDateBlock(block, inputValues, mode = "datetime")
        "input_select" -> FormInputSelectBlock(block, inputValues)
        "input_slider" -> FormInputSliderBlock(block, inputValues)
        "input_toggle" -> FormInputToggleBlock(block, inputValues)
        "input_stepper" -> FormInputStepperBlock(block, inputValues)
        "input_segmented" -> FormInputSegmentedBlock(block, inputValues)
        "input_rating" -> FormInputRatingBlock(block, inputValues)
        "input_range_slider" -> FormInputRangeSliderBlock(block, inputValues)
        "input_chips" -> FormInputChipsBlock(block, inputValues)
        "input_color" -> FormInputColorBlock(block, inputValues)
        "input_location" -> FormInputLocationPlaceholder(block, inputValues)
        "input_image_picker" -> FormInputImagePickerPlaceholder(block, inputValues)
        "input_signature" -> FormInputSignatureBlock(block, inputValues)
        // SPEC-089d AC-002: Backward compatibility — unknown types render as empty
        else -> {
            // Unknown block types silently render nothing.
            // This prevents crashes when the backend sends new block types
            // that this SDK version does not yet implement.
        }
    }
}

@Composable
private fun HeadingBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val text = block.text ?: ""
    val baseStyle = TextStyle(
        fontSize = when (block.level ?: 1) { 1 -> 28.sp; 2 -> 22.sp; else -> 18.sp },
        fontWeight = FontWeight.Bold,
        color = Color.Unspecified,
    )
    val effectiveStyle = if (block.style != null) StyleEngine.applyTextStyle(baseStyle, block.style) else baseStyle
    Text(
        text = loc?.invoke("block.${block.id}.text", text) ?: text,
        style = effectiveStyle,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TextBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val text = block.text ?: ""
    val baseStyle = TextStyle(fontSize = 16.sp, color = Color.Unspecified)
    val effectiveStyle = if (block.style != null) StyleEngine.applyTextStyle(baseStyle, block.style) else baseStyle
    Text(
        text = loc?.invoke("block.${block.id}.text", text) ?: text,
        style = effectiveStyle,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ImageBlock(block: ContentBlock) {
    val cr = (block.corner_radius ?: 0.0)
    val isCircle = cr >= 9999

    val shapeModifier = if (block.overflow == "visible") {
        Modifier.graphicsLayer {
            clip = false
            shape = if (isCircle) CircleShape else RoundedCornerShape(cr.dp)
        }
    } else {
        if (isCircle) Modifier.clip(CircleShape) else Modifier.clip(RoundedCornerShape(cr.dp))
    }

    ai.appdna.sdk.core.NetworkImage(
        url = block.image_url,
        modifier = Modifier
            .fillMaxWidth()
            .height((block.height ?: 200.0).dp)
            .then(shapeModifier),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        contentDescription = block.alt,
    )
}

@Composable
private fun ButtonBlock(block: ContentBlock, onAction: (String) -> Unit, loc: ((String, String) -> String)? = null) {
    val text = block.text ?: "Continue"
    val baseStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    val effectiveStyle = if (block.style != null) StyleEngine.applyTextStyle(baseStyle, block.style) else baseStyle
    val context = LocalContext.current
    val btnVariant = block.variant ?: "primary"
    val bgColor = StyleEngine.parseColor(block.bg_color ?: "#6366F1")
    val txtColor = StyleEngine.parseColor(block.text_color ?: "#FFFFFF")
    val cornerRadius = (block.button_corner_radius ?: 12.0).dp
    val displayText = loc?.invoke("block.${block.id}.text", text) ?: text

    val onClick: () -> Unit = {
        val action = block.action ?: "next"
        when (action) {
            "link" -> {
                block.action_value?.let { url ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
                onAction("next")
            }
            "permission" -> onAction("next")
            else -> onAction(action)
        }
    }

    // SPEC-089d §6.5: Pressed style — collect interaction source for scale/opacity
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedScale = if (isPressed) (block.pressed_style?.scale ?: 0.97).toFloat() else 1f
    val pressedAlpha = if (isPressed) (block.pressed_style?.opacity ?: 0.9).toFloat() else 1f
    val pressedModifier = if (block.pressed_style != null) {
        Modifier
            .graphicsLayer(scaleX = pressedScale, scaleY = pressedScale, alpha = pressedAlpha)
    } else Modifier

    // Gap 5: Gradient background support
    val shape = RoundedCornerShape(cornerRadius)
    val gradient = block.block_style?.background_gradient
    val gradientModifier = if (gradient != null) {
        val rads = gradient.angle * Math.PI / 180.0
        val startX = (0.5 - sin(rads) / 2).toFloat()
        val startY = (0.5 + cos(rads) / 2).toFloat()
        val endX = (0.5 + sin(rads) / 2).toFloat()
        val endY = (0.5 - cos(rads) / 2).toFloat()
        Modifier.background(
            Brush.linearGradient(
                colors = listOf(StyleEngine.parseColor(gradient.start), StyleEngine.parseColor(gradient.end)),
                start = Offset(startX * 1000f, startY * 1000f),
                end = Offset(endX * 1000f, endY * 1000f),
            ),
            shape,
        )
    } else null

    // Gap 6: Button content with optional icon_emoji / image_url
    @Composable
    fun ButtonContent(textColor: Color) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            block.icon_emoji?.let { emoji ->
                if (emoji.isNotEmpty()) {
                    Text(emoji, modifier = Modifier.padding(end = 8.dp))
                }
            }
            if (block.icon_emoji.isNullOrEmpty() && !block.image_url.isNullOrEmpty()) {
                ai.appdna.sdk.core.NetworkImage(
                    url = block.image_url,
                    modifier = Modifier.size(20.dp).padding(end = 8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            Text(text = displayText, style = effectiveStyle, color = textColor)
        }
    }

    when (btnVariant) {
        "outline" -> {
            // SPEC-089d §3.18: Outline variant — transparent bg, colored border + text
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().height(52.dp).then(pressedModifier),
                shape = shape,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, bgColor),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = bgColor),
                interactionSource = interactionSource,
            ) {
                ButtonContent(textColor = bgColor)
            }
        }
        "text" -> {
            TextButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().height(52.dp).then(pressedModifier),
                shape = shape,
                interactionSource = interactionSource,
            ) {
                ButtonContent(textColor = bgColor)
            }
        }
        else -> {
            // primary / secondary — filled button
            if (gradientModifier != null) {
                // Gradient button: use Box with gradient background + clickable
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .then(pressedModifier)
                        .clip(shape)
                        .then(gradientModifier)
                        .clickable { onClick() },
                    contentAlignment = Alignment.Center,
                ) {
                    ButtonContent(textColor = txtColor)
                }
            } else {
                Button(
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth().height(52.dp).then(pressedModifier),
                    shape = shape,
                    colors = ButtonDefaults.buttonColors(containerColor = bgColor),
                    interactionSource = interactionSource,
                ) {
                    ButtonContent(textColor = txtColor)
                }
            }
        }
    }
}

@Composable
private fun ListBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.items?.forEachIndexed { index, item ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (block.list_style) {
                    "numbered" -> Text("${index + 1}.", fontWeight = FontWeight.SemiBold, color = Color.Gray)
                    "check" -> Text("\u2713", color = Color(0xFF22C55E), fontSize = 16.sp)
                    else -> Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.Gray),
                    )
                }
                Text(
                    text = loc?.invoke("block.${block.id}.item.$index", item) ?: item,
                    style = if (block.style != null) StyleEngine.applyTextStyle(TextStyle(fontSize = 16.sp), block.style) else TextStyle(fontSize = 16.sp),
                )
            }
        }
    }
}

@Composable
private fun DividerBlock(block: ContentBlock) {
    Spacer(modifier = Modifier.height((block.divider_margin_y ?: 8.0).dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((block.divider_thickness ?: 1.0).dp)
            .background(StyleEngine.parseColor(block.divider_color ?: "#E5E7EB")),
    )
    Spacer(modifier = Modifier.height((block.divider_margin_y ?: 8.0).dp))
}

@Composable
private fun BadgeBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val text = block.badge_text ?: ""
    Text(
        text = loc?.invoke("block.${block.id}.badge", text) ?: text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = StyleEngine.parseColor(block.badge_text_color ?: "#FFFFFF"),
        modifier = Modifier
            .background(
                StyleEngine.parseColor(block.badge_bg_color ?: "#6366F1"),
                RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun IconBlock(block: ContentBlock) {
    val alignment = when (block.icon_alignment) {
        "left" -> Alignment.CenterStart
        "right" -> Alignment.CenterEnd
        else -> Alignment.Center
    }
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        // SPEC-085: Support IconReference (structured icon) or plain emoji
        val iconRef = resolveIcon(block.icon_ref) ?: resolveIcon(block.icon_emoji)
        if (iconRef != null) {
            IconView(
                ref = iconRef.copy(size = iconRef.size ?: (block.icon_size ?: 32.0).toFloat()),
            )
        } else {
            Text(
                text = block.icon_emoji ?: "",
                fontSize = (block.icon_size ?: 32.0).sp,
            )
        }
    }
}

@Composable
private fun ToggleBlock(block: ContentBlock, toggleValues: MutableMap<String, Boolean>, loc: ((String, String) -> String)? = null) {
    var checked by remember { mutableStateOf(toggleValues[block.id] ?: (block.toggle_default ?: false)) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val label = block.toggle_label ?: ""
            Text(
                text = loc?.invoke("block.${block.id}.label", label) ?: label,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    toggleValues[block.id] = it
                },
            )
        }
        block.toggle_description?.let {
            Text(text = loc?.invoke("block.${block.id}.description", it) ?: it, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun VideoBlock(block: ContentBlock) {
    val effectiveHeight = block.video_height ?: block.height ?: 200.0
    val effectiveCornerRadius = block.video_corner_radius ?: block.corner_radius ?: 8.0

    // SPEC-085: Use core VideoBlockView if video_url is available
    if (block.video_url != null) {
        CoreVideoBlockView(
            block = CoreVideoBlock(
                video_url = block.video_url,
                video_thumbnail_url = block.video_thumbnail_url ?: block.image_url,
                video_height = effectiveHeight.toFloat(),
                video_corner_radius = effectiveCornerRadius.toFloat(),
                autoplay = block.video_autoplay,
                loop = block.video_loop,
                muted = block.video_muted,
            )
        )
    } else {
        // Fallback: thumbnail with play icon overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(effectiveHeight.dp)
                .clip(RoundedCornerShape(effectiveCornerRadius.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ai.appdna.sdk.core.NetworkImage(
                url = block.video_thumbnail_url ?: block.image_url,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u25B6", fontSize = 24.sp, color = Color.White)
            }
        }
    }
}

// SPEC-085: Lottie content block
@Composable
private fun LottieContentBlock(block: ContentBlock) {
    if (block.lottie_url != null || block.lottie_json != null) {
        LottieBlockView(
            block = LottieBlock(
                lottie_url = block.lottie_url,
                lottie_json = block.lottie_json,
                autoplay = block.lottie_autoplay ?: true,
                loop = block.lottie_loop ?: true,
                speed = block.lottie_speed ?: 1.0f,
                height = (block.height ?: 160.0).toFloat(),
                alignment = block.icon_alignment ?: "center",
            )
        )
    }
}

// SPEC-085: Rive content block
@Composable
private fun RiveContentBlock(block: ContentBlock) {
    if (block.rive_url != null) {
        RiveBlockView(
            block = RiveBlock(
                rive_url = block.rive_url,
                artboard = block.rive_artboard,
                state_machine = block.rive_state_machine,
                height = (block.height ?: 160.0).toFloat(),
                alignment = block.icon_alignment ?: "center",
            )
        )
    }
}

// MARK: - Page Indicator Block (SPEC-089d AC-012)

/**
 * Renders a row of indicator dots. Active dot can be wider (pill) if active_dot_width is set.
 * SDK auto-binds active_index to current step index when inside an onboarding flow.
 */
@Composable
private fun PageIndicatorBlock(block: ContentBlock, currentStepIndex: Int = 0, totalSteps: Int = 1) {
    val dotCount = block.dot_count ?: totalSteps
    // AC-012: Auto-bind active_index to current step index when not explicitly set or 0
    val activeIndex = if ((block.active_index ?: 0) == 0) currentStepIndex else (block.active_index ?: 0)
    val activeColor = StyleEngine.parseColor(block.active_color ?: "#6366F1")
    val inactiveColor = StyleEngine.parseColor(block.inactive_color ?: "#D1D5DB")
    val dotSize = (block.dot_size ?: 8.0).dp
    val dotSpacing = (block.dot_spacing ?: 8.0).dp
    val activeDotWidth = block.active_dot_width?.dp

    val hAlign = when (block.alignment ?: block.icon_alignment) {
        "left" -> Arrangement.Start
        "right" -> Arrangement.End
        else -> Arrangement.Center
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = hAlign,
    ) {
        for (i in 0 until dotCount) {
            if (i > 0) Spacer(modifier = Modifier.width(dotSpacing))
            val isActive = i == activeIndex
            if (isActive && activeDotWidth != null) {
                // Pill shape for active dot
                Box(
                    modifier = Modifier
                        .size(width = activeDotWidth, height = dotSize)
                        .clip(RoundedCornerShape(50))
                        .background(activeColor),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(dotSize)
                        .clip(CircleShape)
                        .background(if (isActive) activeColor else inactiveColor),
                )
            }
        }
    }
}

// MARK: - Social Login Block (SPEC-089d AC-015)

/**
 * Renders social login provider buttons (Apple, Google, Email, Facebook, GitHub).
 * Supports filled, outlined, and minimal button styles.
 * Each tap fires the onAction callback with the provider type.
 */
@Composable
private fun SocialLoginBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    loc: ((String, String) -> String)? = null,
) {
    val providers = block.providers?.filter { it.enabled } ?: return
    val buttonStyle = block.button_style ?: "filled"
    val cornerRadius = (block.button_corner_radius ?: 12.0).dp
    val buttonHeight = (block.button_height ?: 48.0).dp
    val spacing = (block.spacing ?: 12.0).dp
    val showDivider = block.show_divider ?: false
    val dividerText = block.divider_text ?: "or"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        providers.forEachIndexed { index, provider ->
            val label = provider.label ?: when (provider.type) {
                "apple" -> "Continue with Apple"
                "google" -> "Continue with Google"
                "email" -> "Continue with Email"
                "facebook" -> "Continue with Facebook"
                "github" -> "Continue with GitHub"
                else -> "Continue with ${provider.type.replaceFirstChar { it.uppercase() }}"
            }
            val displayLabel = loc?.invoke("block.${block.id}.provider.$index", label) ?: label

            val (bgColor, textColor, borderColor) = when (provider.type) {
                "apple" -> Triple(Color(0xFF000000), Color.White, Color(0xFF000000))
                "google" -> Triple(Color.White, Color(0xFF1F1F1F), Color(0xFFDADCE0))
                "facebook" -> Triple(Color(0xFF1877F2), Color.White, Color(0xFF1877F2))
                "github" -> Triple(Color(0xFF24292F), Color.White, Color(0xFF24292F))
                "email" -> Triple(
                    StyleEngine.parseColor(block.accent_color ?: block.bg_color ?: "#6366F1"),
                    Color.White,
                    StyleEngine.parseColor(block.accent_color ?: block.bg_color ?: "#6366F1"),
                )
                else -> Triple(Color(0xFF6366F1), Color.White, Color(0xFF6366F1))
            }

            val providerIcon = when (provider.type) {
                "apple" -> "\uF8FF"  // Apple logo placeholder
                "google" -> "G"
                "email" -> "\u2709"
                "facebook" -> "f"
                "github" -> "\u2B24"
                else -> ""
            }

            when (buttonStyle) {
                "outlined" -> {
                    OutlinedButton(
                        onClick = { onAction("social_login:${provider.type}") },
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        shape = RoundedCornerShape(cornerRadius),
                        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                    ) {
                        Text(providerIcon, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(displayLabel, fontWeight = FontWeight.SemiBold)
                    }
                }
                "minimal" -> {
                    TextButton(
                        onClick = { onAction("social_login:${provider.type}") },
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        shape = RoundedCornerShape(cornerRadius),
                    ) {
                        Text(providerIcon, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(displayLabel, fontWeight = FontWeight.SemiBold)
                    }
                }
                else -> { // filled
                    Button(
                        onClick = { onAction("social_login:${provider.type}") },
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        shape = RoundedCornerShape(cornerRadius),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = bgColor,
                            contentColor = textColor,
                        ),
                    ) {
                        Text(providerIcon, fontSize = 18.sp, modifier = Modifier.padding(end = 8.dp))
                        Text(displayLabel, fontWeight = FontWeight.SemiBold, color = textColor)
                    }
                }
            }

            // Insert divider between providers if show_divider is true and not after last
            if (showDivider && index < providers.size - 1) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(Color.Gray.copy(alpha = 0.3f)),
                    )
                    Text(
                        text = loc?.invoke("block.${block.id}.divider", dividerText) ?: dividerText,
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(Color.Gray.copy(alpha = 0.3f)),
                    )
                }
            }
        }
    }
}

// MARK: - Countdown Timer Block (SPEC-089d AC-018)

/**
 * Countdown timer with digital or bar variant.
 * Uses LaunchedEffect with delay(1000) loop to decrement remaining seconds.
 * Supports fixed_duration and fixed_datetime target types.
 */
@Composable
private fun CountdownTimerBlock(block: ContentBlock, onAction: (String) -> Unit) {
    val variant = block.variant ?: "digital"
    val initialSeconds = when (block.target_type) {
        "fixed_datetime" -> {
            // Parse ISO datetime and compute remaining seconds
            try {
                val targetMs = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(block.target_datetime ?: "")?.time ?: 0L
                val remaining = ((targetMs - System.currentTimeMillis()) / 1000).toInt()
                if (remaining > 0) remaining else 0
            } catch (_: Exception) { block.duration_seconds ?: 60 }
        }
        else -> block.duration_seconds ?: 60
    }

    var remainingSeconds by remember { mutableIntStateOf(initialSeconds) }
    var expired by remember { mutableStateOf(initialSeconds <= 0) }

    val textColor = StyleEngine.parseColor(block.text_color ?: "#000000")
    val accentColor = StyleEngine.parseColor(block.accent_color ?: "#6366F1")
    val bgColor = block.bg_color?.let { StyleEngine.parseColor(it) }
    val fontSize = (block.font_size ?: 24.0).sp

    val showDays = block.show_days ?: true
    val showHours = block.show_hours ?: true
    val showMinutes = block.show_minutes ?: true
    val showSeconds = block.show_seconds ?: true

    val labels = block.labels ?: CountdownLabels()
    val daysLabel = labels.days ?: "Days"
    val hoursLabel = labels.hours ?: "Hours"
    val minutesLabel = labels.minutes ?: "Min"
    val secondsLabel = labels.seconds ?: "Sec"

    // Countdown tick
    LaunchedEffect(Unit) {
        while (remainingSeconds > 0) {
            kotlinx.coroutines.delay(1000L)
            remainingSeconds--
        }
        expired = true
        when (block.on_expire_action) {
            "auto_advance" -> onAction("next")
            else -> { /* hide or show_expired_text handled below */ }
        }
    }

    // On expire: hide
    if (expired && block.on_expire_action == "hide") return

    // On expire: show expired text
    if (expired && block.on_expire_action == "show_expired_text") {
        Text(
            text = block.expired_text ?: "Expired",
            fontSize = fontSize,
            color = textColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    val days = remainingSeconds / 86400
    val hours = (remainingSeconds % 86400) / 3600
    val minutes = (remainingSeconds % 3600) / 60
    val seconds = remainingSeconds % 60

    val hAlign = when (block.alignment) {
        "left" -> Arrangement.Start
        "right" -> Arrangement.End
        else -> Arrangement.Center
    }

    when (variant) {
        "bar" -> {
            // Shrinking bar variant
            val fraction = if (initialSeconds > 0) remainingSeconds.toFloat() / initialSeconds else 0f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((block.height ?: 8.0).dp)
                    .clip(RoundedCornerShape((block.corner_radius ?: 4.0).dp))
                    .background(bgColor ?: Color.Gray.copy(alpha = 0.2f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(accentColor),
                )
            }
        }
        else -> {
            // Digital variant: columns of time units
            // Build list of (value, label) pairs to display
            val timeUnits = mutableListOf<Triple<Int, String, Boolean>>()
            if (showDays && days > 0) timeUnits.add(Triple(days, daysLabel, true))
            if (showHours) timeUnits.add(Triple(hours, hoursLabel, true))
            if (showMinutes) timeUnits.add(Triple(minutes, minutesLabel, showSeconds))
            if (showSeconds) timeUnits.add(Triple(seconds, secondsLabel, false))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = hAlign,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                timeUnits.forEachIndexed { index, (value, unitLabel, showSep) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = value.toString().padStart(2, '0'),
                            fontSize = fontSize,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                        )
                        Text(
                            text = unitLabel,
                            fontSize = 10.sp,
                            color = textColor.copy(alpha = 0.6f),
                        )
                    }
                    // Show separator between units, but not after last
                    if (showSep && index < timeUnits.size - 1) {
                        Text(
                            text = ":",
                            fontSize = fontSize,
                            fontWeight = FontWeight.Bold,
                            color = textColor.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Rating Block (SPEC-089d AC-019)

/**
 * Star rating input. Renders a row of star icons (filled/outlined).
 * Stores selected rating in inputValues map for response collection.
 */
@Composable
private fun RatingBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)? = null,
) {
    val maxStars = block.max_stars ?: 5
    val starSize = (block.star_size ?: 32.0).dp
    val activeColor = StyleEngine.parseColor(block.active_rating_color ?: block.active_color ?: "#FBBF24")
    val inactiveColor = StyleEngine.parseColor(block.inactive_rating_color ?: block.inactive_color ?: "#D1D5DB")
    val fieldId = block.field_id ?: block.id

    var selectedRating by remember {
        mutableIntStateOf((block.default_value?.toInt() ?: (inputValues[fieldId] as? Number)?.toInt()) ?: 0)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Optional label
        block.label?.let { label ->
            val displayLabel = loc?.invoke("block.${block.id}.label", label) ?: label
            Text(
                text = displayLabel,
                fontSize = 14.sp,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 1..maxStars) {
                val filled = i <= selectedRating
                Icon(
                    imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = "Star $i",
                    tint = if (filled) activeColor else inactiveColor,
                    modifier = Modifier
                        .size(starSize)
                        .clickable {
                            selectedRating = i
                            inputValues[fieldId] = i
                        },
                )
            }
        }
    }
}

// MARK: - Rich Text Block (SPEC-089d AC-020)

/**
 * Rich text with inline markdown-style formatting.
 * Parses **bold**, *italic*, and [link](url) patterns.
 * Uses ClickableText for link tap handling.
 */
@Composable
private fun RichTextBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val rawContent = block.content ?: block.text ?: ""
    val content = loc?.invoke("block.${block.id}.content", rawContent) ?: rawContent
    val linkColor = StyleEngine.parseColor(block.link_color ?: "#6366F1")
    val context = LocalContext.current

    // Apply base_style if present
    val baseTextStyle = if (block.base_style != null) {
        StyleEngine.applyTextStyle(TextStyle(fontSize = 16.sp, color = Color.Unspecified), block.base_style)
    } else if (block.style != null) {
        StyleEngine.applyTextStyle(TextStyle(fontSize = 16.sp, color = Color.Unspecified), block.style)
    } else {
        TextStyle(fontSize = 16.sp, color = Color.Unspecified)
    }

    val annotatedString = parseMarkdownToAnnotatedString(content, baseTextStyle, linkColor)

    ClickableText(
        text = annotatedString,
        style = baseTextStyle,
        maxLines = block.max_lines ?: Int.MAX_VALUE,
        modifier = Modifier.fillMaxWidth(),
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(annotation.item))
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        // Malformed URL or no browser — silently ignore
                    }
                }
        },
    )
}

/**
 * Parses a subset of Markdown into an AnnotatedString.
 * Supported patterns:
 * - **bold** → SpanStyle(fontWeight = Bold)
 * - *italic* → SpanStyle(fontStyle = Italic)
 * - [text](url) → SpanStyle(color = linkColor, underline) + URL annotation
 */
private fun parseMarkdownToAnnotatedString(
    markdown: String,
    baseStyle: TextStyle,
    linkColor: Color,
): androidx.compose.ui.text.AnnotatedString {
    // Regex patterns (order matters: bold before italic to avoid ambiguity)
    val boldRegex = Regex("""\*\*(.+?)\*\*""")
    val italicRegex = Regex("""\*(.+?)\*""")
    val linkRegex = Regex("""\[(.+?)]\((.+?)\)""")

    data class StyledRange(val start: Int, val end: Int, val style: SpanStyle, val tag: String? = null, val annotation: String? = null)

    // First pass: find all link ranges in the original text and build a clean string
    val linkMatches = linkRegex.findAll(markdown).toList()
    val boldMatches = boldRegex.findAll(markdown).toList()
    val italicMatches = italicRegex.findAll(markdown).toList()

    return buildAnnotatedString {
        // Simple iterative parser: process the string character by character,
        // replacing markdown tokens as we go
        var remaining = markdown
        while (remaining.isNotEmpty()) {
            // Find the earliest markdown match
            val linkMatch = linkRegex.find(remaining)
            val boldMatch = boldRegex.find(remaining)
            // Only match italic if it's not part of a bold marker
            val italicMatch = italicRegex.find(remaining)?.takeIf { m ->
                val idx = m.range.first
                // Ensure this is not a ** bold marker
                !(idx > 0 && remaining.getOrNull(idx - 1) == '*') &&
                    remaining.getOrNull(idx + 1) != '*'
            }

            val matches = listOfNotNull(
                linkMatch?.let { it to "link" },
                boldMatch?.let { it to "bold" },
                italicMatch?.let { it to "italic" },
            ).sortedBy { it.first.range.first }

            if (matches.isEmpty()) {
                // No more markdown — append rest as plain text
                append(remaining)
                break
            }

            val (match, type) = matches.firstOrNull() ?: break

            // Append text before the match
            if (match.range.first > 0) {
                append(remaining.substring(0, match.range.first))
            }

            when (type) {
                "link" -> {
                    val linkText = match.groupValues[1]
                    val url = match.groupValues[2]
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(SpanStyle(
                        color = linkColor,
                        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    )) {
                        append(linkText)
                    }
                    pop()
                }
                "bold" -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(match.groupValues[1])
                    }
                }
                "italic" -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(match.groupValues[1])
                    }
                }
            }

            remaining = remaining.substring(match.range.last + 1)
        }
    }
}

// MARK: - Progress Bar Block (SPEC-089d AC-021)

/**
 * Segmented or continuous progress bar.
 * Continuous: single track + fill bar.
 * Segmented: row of equally-sized segments, some filled, some empty.
 * SDK can auto-bind active_segments to current step index.
 */
@Composable
private fun ProgressBarBlock(block: ContentBlock, loc: ((String, String) -> String)? = null, currentStepIndex: Int = 0, totalSteps: Int = 1) {
    val variant = block.variant ?: "continuous"
    // AC-021: Auto-bind to step index when no explicit values set
    val segmentCount = block.segment_count ?: totalSteps
    val activeSegments = if (block.active_segments != null) block.active_segments
        else if (block.fill_color != null && block.segment_count != null) 1
        else currentStepIndex + 1  // Auto-bind: 1-based fill
    val fillColor = StyleEngine.parseColor(block.fill_color ?: "#6366F1")
    val trackColor = StyleEngine.parseColor(block.track_color ?: "#E5E7EB")
    val barHeight = (block.height ?: 6.0).dp
    val cornerRadius = (block.corner_radius ?: 3.0).dp
    val segmentGap = (block.segment_gap ?: 4.0).dp
    val showLabel = block.show_label ?: false

    Column(modifier = Modifier.fillMaxWidth()) {
        // Optional label
        if (showLabel && segmentCount > 0) {
            val labelText = "Step $activeSegments of $segmentCount"
            val labelStyle = if (block.label_style != null) {
                StyleEngine.applyTextStyle(TextStyle(fontSize = 12.sp, color = Color.Gray), block.label_style)
            } else {
                TextStyle(fontSize = 12.sp, color = Color.Gray)
            }
            Text(
                text = loc?.invoke("block.${block.id}.label", labelText) ?: labelText,
                style = labelStyle,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        when (variant) {
            "segmented" -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(segmentGap),
                ) {
                    for (i in 0 until segmentCount) {
                        val isFilled = i < activeSegments
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(barHeight)
                                .clip(RoundedCornerShape(cornerRadius))
                                .background(if (isFilled) fillColor else trackColor),
                        )
                    }
                }
            }
            else -> {
                // Continuous progress bar
                val fraction = if (segmentCount > 0) {
                    (activeSegments.toFloat() / segmentCount).coerceIn(0f, 1f)
                } else 0f

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(trackColor),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(cornerRadius))
                            .background(fillColor),
                    )
                }
            }
        }
    }
}

// MARK: - Timeline Block (SPEC-089d AC-016)

/**
 * Vertical timeline with status indicators (completed/current/upcoming).
 * Each item shows a status circle, optional connecting line, title, and subtitle.
 */
@Composable
private fun TimelineBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val items = block.timeline_items ?: return
    val lineColor = StyleEngine.parseColor(block.line_color ?: "#E5E7EB")
    val completedColor = StyleEngine.parseColor(block.completed_color ?: "#22C55E")
    val currentColor = StyleEngine.parseColor(block.current_color ?: "#6366F1")
    val upcomingColor = StyleEngine.parseColor(block.upcoming_color ?: "#D1D5DB")
    val showLine = block.show_line ?: true
    val isCompact = block.compact ?: false
    val itemSpacing = if (isCompact) 12.dp else 24.dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        items.forEachIndexed { index, item ->
            val statusColor = when (item.status) {
                "completed" -> completedColor
                "current" -> currentColor
                else -> upcomingColor
            }
            val isLast = index == items.size - 1

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (isLast) 0.dp else itemSpacing),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Left column: status indicator + connecting line
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(24.dp),
                ) {
                    // Status circle
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.status == "completed") {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Completed",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                        } else if (item.status == "current") {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                            )
                        }
                    }

                    // Connecting line
                    if (showLine && !isLast) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(itemSpacing)
                                .background(lineColor),
                        )
                    }
                }

                // Right column: title + subtitle
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val titleText = loc?.invoke("block.${block.id}.item.$index.title", item.title) ?: item.title
                    val titleBaseStyle = TextStyle(
                        fontSize = 16.sp,
                        fontWeight = if (item.status == "current") FontWeight.SemiBold else FontWeight.Normal,
                        color = if (item.status == "upcoming") Color.Gray else Color.Unspecified,
                    )
                    val titleStyle = if (block.title_style != null) {
                        StyleEngine.applyTextStyle(titleBaseStyle, block.title_style)
                    } else titleBaseStyle

                    Text(text = titleText, style = titleStyle)

                    item.subtitle?.let { subtitle ->
                        val subtitleText = loc?.invoke("block.${block.id}.item.$index.subtitle", subtitle) ?: subtitle
                        val subtitleBaseStyle = TextStyle(fontSize = 13.sp, color = Color.Gray)
                        val subtitleEffective = if (block.subtitle_style != null) {
                            StyleEngine.applyTextStyle(subtitleBaseStyle, block.subtitle_style)
                        } else subtitleBaseStyle

                        Text(text = subtitleText, style = subtitleEffective)
                    }
                }
            }
        }
    }
}

// MARK: - Animated Loading Block (SPEC-089d AC-017)

/**
 * Animated loading/progress screen with sequential item completion.
 * Checklist variant: items appear with animated checkmarks.
 * Circular variant: CircularProgressIndicator.
 * Linear variant: LinearProgressIndicator.
 * Supports auto_advance to trigger step advance after completion.
 */
@Composable
private fun AnimatedLoadingBlock(block: ContentBlock, onAction: (String) -> Unit) {
    val variant = block.variant ?: "checklist"
    val items = block.loading_items ?: emptyList()
    val progressColor = StyleEngine.parseColor(block.progress_color ?: "#6366F1")
    val checkColor = StyleEngine.parseColor(block.check_color ?: "#22C55E")
    val textColor = StyleEngine.parseColor(block.text_color ?: "#000000")
    val totalDurationMs = block.total_duration_ms
    val autoAdvance = block.auto_advance ?: false
    val showPercentage = block.show_percentage ?: false

    // Track which items have completed
    var completedCount by remember { mutableIntStateOf(0) }
    var overallProgress by remember { mutableStateOf(0f) }
    var finished by remember { mutableStateOf(false) }

    // Sequential item completion timer
    LaunchedEffect(Unit) {
        if (items.isNotEmpty()) {
            for (i in items.indices) {
                val durationMs = items[i].duration_ms.toLong()
                // Animate progress within this item
                val startProgress = if (items.isNotEmpty()) i.toFloat() / items.size else 0f
                val endProgress = if (items.isNotEmpty()) (i + 1).toFloat() / items.size else 1f

                val steps = (durationMs / 50).toInt().coerceAtLeast(1)
                val stepDelay = durationMs / steps
                for (s in 1..steps) {
                    kotlinx.coroutines.delay(stepDelay)
                    overallProgress = startProgress + (endProgress - startProgress) * (s.toFloat() / steps)
                }
                completedCount = i + 1
            }
        } else if (totalDurationMs != null && totalDurationMs > 0) {
            // No items, just progress over total duration
            val steps = (totalDurationMs / 50).coerceAtLeast(1)
            val stepDelay = totalDurationMs.toLong() / steps
            for (s in 1..steps) {
                kotlinx.coroutines.delay(stepDelay)
                overallProgress = s.toFloat() / steps
            }
        }
        finished = true
        if (autoAdvance) {
            onAction("next")
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (variant) {
            "circular" -> {
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = overallProgress,
                        modifier = Modifier.size(80.dp),
                        color = progressColor,
                        trackColor = progressColor.copy(alpha = 0.2f),
                        strokeWidth = 6.dp,
                    )
                    if (showPercentage) {
                        Text(
                            text = "${(overallProgress * 100).toInt()}%",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                        )
                    }
                }
            }
            "linear" -> {
                LinearProgressIndicator(
                    progress = overallProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = progressColor,
                    trackColor = progressColor.copy(alpha = 0.2f),
                )
                if (showPercentage) {
                    Text(
                        text = "${(overallProgress * 100).toInt()}%",
                        fontSize = 14.sp,
                        color = textColor,
                    )
                }
            }
            else -> { /* checklist is the default, handled below */ }
        }

        // Checklist items (shown for all variants if items exist)
        if (items.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items.forEachIndexed { index, item ->
                    val isCompleted = index < completedCount
                    val isCurrent = index == completedCount && !finished

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // Status icon
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isCompleted) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = "Done",
                                    tint = checkColor,
                                    modifier = Modifier.size(20.dp),
                                )
                            } else if (isCurrent) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = progressColor,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color.Gray.copy(alpha = 0.2f)),
                                )
                            }
                        }

                        Text(
                            text = item.label,
                            fontSize = 15.sp,
                            color = when {
                                isCompleted -> textColor
                                isCurrent -> textColor
                                else -> textColor.copy(alpha = 0.4f)
                            },
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

// MARK: - Circular Gauge Block (SPEC-089d AC-022)

/**
 * Circular arc gauge with center label. Supports animated fill via animateFloatAsState.
 */
@Composable
private fun CircularGaugeBlock(block: ContentBlock) {
    val value = (block.gauge_value ?: block.default_value ?: 0.0).toFloat()
    val maxVal = (block.max_gauge_value ?: 100.0).toFloat()
    val targetProgress = if (maxVal > 0f) (value / maxVal).coerceIn(0f, 1f) else 0f
    val size = (block.height ?: 120.0).dp
    val strokeW = (block.stroke_width ?: 10.0).toFloat()
    val fillColor = StyleEngine.parseColor(block.fill_color ?: block.active_color ?: "#6366F1")
    val trackColor = StyleEngine.parseColor(block.track_color ?: "#E5E7EB")
    val labelColor = StyleEngine.parseColor(block.label_color ?: block.text_color ?: "#000000")
    val labelFontSize = (block.label_font_size ?: block.font_size ?: 20.0).sp
    val shouldAnimate = block.animate ?: true
    val animDurationMs = block.animation_duration_ms ?: 800
    val showPct = block.show_percentage ?: false

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = if (shouldAnimate) tween(durationMillis = animDurationMs) else tween(0),
        label = "gauge_progress",
    )

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Track
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round),
                )
                // Filled arc
                drawArc(
                    color = fillColor,
                    startAngle = -90f,
                    sweepAngle = animatedProgress * 360f,
                    useCenter = false,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round),
                )
            }
            // Center label
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (showPct) "${(animatedProgress * 100).roundToInt()}%" else (block.text ?: "${value.roundToInt()}"),
                    fontSize = labelFontSize,
                    fontWeight = FontWeight.Bold,
                    color = labelColor,
                )
                block.sublabel?.let {
                    Text(text = it, fontSize = 12.sp, color = labelColor.copy(alpha = 0.7f))
                }
            }
        }
    }
}

// MARK: - Date Wheel Picker Block (SPEC-089d AC-023)

/**
 * Date picker using Material3 DatePickerDialog or simplified column picker.
 * For simplicity, renders three side-by-side LazyColumns for day/month/year.
 */
@Composable
private fun DateWheelPickerBlock(block: ContentBlock, inputValues: MutableMap<String, Any>) {
    val fieldId = block.field_id ?: block.id
    val highlightColor = StyleEngine.parseColor(block.highlight_color ?: "#6366F1")

    // Simple day/month/year selectors
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    var selectedDay by remember { mutableIntStateOf(1) }
    var selectedMonth by remember { mutableIntStateOf(1) }
    var selectedYear by remember { mutableIntStateOf(2000) }

    val dayListState = rememberLazyListState()
    val monthListState = rememberLazyListState()
    val yearListState = rememberLazyListState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Month column
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = monthListState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = monthListState),
            ) {
                items(12) { index ->
                    val isCenter = monthListState.firstVisibleItemIndex == index
                    Text(
                        text = months[index],
                        fontSize = if (isCenter) 18.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCenter) highlightColor else Color.Gray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedMonth = index + 1
                                inputValues[fieldId] = "%04d-%02d-%02d".format(selectedYear, selectedMonth, selectedDay)
                            },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Day column
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = dayListState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = dayListState),
            ) {
                items(31) { index ->
                    val day = index + 1
                    val isCenter = dayListState.firstVisibleItemIndex == index
                    Text(
                        text = "%02d".format(day),
                        fontSize = if (isCenter) 18.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCenter) highlightColor else Color.Gray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedDay = day
                                inputValues[fieldId] = "%04d-%02d-%02d".format(selectedYear, selectedMonth, selectedDay)
                            },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // Year column
        Box(modifier = Modifier.weight(1f)) {
            val years = (1950..2030).toList()
            LazyColumn(
                state = yearListState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = yearListState),
            ) {
                items(years.size) { index ->
                    val year = years[index]
                    val isCenter = yearListState.firstVisibleItemIndex == index
                    Text(
                        text = year.toString(),
                        fontSize = if (isCenter) 18.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCenter) highlightColor else Color.Gray,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedYear = year
                                inputValues[fieldId] = "%04d-%02d-%02d".format(selectedYear, selectedMonth, selectedDay)
                            },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// MARK: - Stack Block (ZStack container — SPEC-089d AC-024)

@Composable
private fun StackBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)?,
) {
    val childBlocks = (block.children ?: emptyList()).sortedBy { it.z_index ?: 0.0 }
    val stackAlignment = mapBlockAlignment(block.alignment, null)

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = stackAlignment,
    ) {
        childBlocks.forEach { child ->
            Box(modifier = Modifier.zIndex((child.z_index ?: 0.0).toFloat())) {
                RenderBlock(
                    block = child,
                    onAction = onAction,
                    toggleValues = toggleValues,
                    inputValues = inputValues,
                    loc = loc,
                )
            }
        }
    }
}

// MARK: - Row Block (HStack container — SPEC-089d AC-025)

@Composable
private fun RowBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any>,
    loc: ((String, String) -> String)?,
) {
    val childBlocks = block.children ?: emptyList()
    val rowGap = (block.gap ?: 8.0).dp
    val direction = block.row_direction ?: "horizontal"
    val distribution = block.row_distribution ?: "start"
    val childFill = block.row_child_fill ?: true

    val hasOverflowChild = childBlocks.any { it.overflow == "visible" }

    if (direction == "vertical") {
        // Vertical layout
        val vArrangement = when (distribution) {
            "center" -> Arrangement.Center
            "end" -> Arrangement.Bottom
            "space-between", "space_between" -> Arrangement.SpaceBetween
            "space-around", "space_around" -> Arrangement.SpaceAround
            "space-evenly", "space_evenly" -> Arrangement.SpaceEvenly
            else -> Arrangement.Top
        }
        val columnModifier = if (hasOverflowChild) {
            Modifier.fillMaxWidth().graphicsLayer { clip = false }
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            modifier = columnModifier,
            verticalArrangement = if (rowGap.value > 0 && distribution == "start") Arrangement.spacedBy(rowGap) else vArrangement,
        ) {
            childBlocks.forEach { child ->
                val childMod = if (childFill) Modifier.fillMaxWidth() else Modifier
                val overflowMod = if (child.overflow == "visible") childMod.zIndex(1f) else childMod
                Box(modifier = overflowMod) {
                    RenderBlock(
                        block = child,
                        onAction = onAction,
                        toggleValues = toggleValues,
                        inputValues = inputValues,
                        loc = loc,
                    )
                }
            }
        }
    } else {
        // Horizontal layout (default)
        val hArrangement = when (distribution) {
            "center" -> Arrangement.Center
            "end" -> Arrangement.End
            "space-between", "space_between" -> Arrangement.SpaceBetween
            "space-around", "space_around" -> Arrangement.SpaceAround
            "space-evenly", "space_evenly" -> Arrangement.SpaceEvenly
            else -> Arrangement.spacedBy(rowGap)
        }
        val vAlignment = when (block.align_items) {
            "top" -> Alignment.Top
            "bottom" -> Alignment.Bottom
            else -> Alignment.CenterVertically
        }
        val rowModifier = if (hasOverflowChild) {
            Modifier.fillMaxWidth().graphicsLayer { clip = false }
        } else {
            Modifier.fillMaxWidth()
        }
        Row(
            modifier = rowModifier,
            horizontalArrangement = hArrangement,
            verticalAlignment = vAlignment,
        ) {
            childBlocks.forEach { child ->
                val childMod = if (childFill) Modifier.weight(1f) else Modifier
                val overflowMod = if (child.overflow == "visible") childMod.zIndex(1f) else childMod
                Box(modifier = overflowMod) {
                    RenderBlock(
                        block = child,
                        onAction = onAction,
                        toggleValues = toggleValues,
                        inputValues = inputValues,
                        loc = loc,
                    )
                }
            }
        }
    }
}

// MARK: - Custom View Block (SPEC-089d AC-026)

@Composable
private fun CustomViewBlock(block: ContentBlock) {
    val key = block.view_key ?: ""
    val factory = AppDNA.registeredCustomViews[key]

    if (factory != null) {
        val heightMod = if (block.height != null) Modifier.height(block.height.dp) else Modifier
        Box(modifier = Modifier.fillMaxWidth().then(heightMod)) {
            factory(block.custom_config ?: emptyMap())
        }
    } else if (block.placeholder_image_url != null) {
        ai.appdna.sdk.core.NetworkImage(
            url = block.placeholder_image_url,
            modifier = Modifier
                .fillMaxWidth()
                .height((block.height ?: 100.0).dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(
            text = block.placeholder_text ?: "[$key]",
            fontSize = 12.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
        )
    }
}

// MARK: - Star Background Block (SPEC-089d AC-027)

@Composable
private fun StarBackgroundBlock(block: ContentBlock) {
    val particleColor = StyleEngine.parseColor(block.active_color ?: block.text_color ?: "#FFFFFF")
    val baseOpacity = (block.block_style?.opacity ?: 0.8).toFloat()
    val particleCount = when (block.density) {
        "sparse" -> 20; "dense" -> 100; else -> 50
    }
    val speedFactor = when (block.speed) {
        "slow" -> 0.3f; "fast" -> 1.5f; else -> 0.8f
    }
    val minSize = (block.size_range?.firstOrNull() ?: 1.0).toFloat()
    val maxSize = (block.size_range?.lastOrNull() ?: 3.0).toFloat()
    val isFullscreen = block.fullscreen ?: false
    val blockHeight = (block.height ?: 200.0).dp

    val particles = remember {
        mutableStateOf(
            (0 until particleCount).map {
                StarParticleState(
                    x = (Math.random() * 1000).toFloat(),
                    y = (Math.random() * 1000).toFloat(),
                    size = (minSize + Math.random().toFloat() * (maxSize - minSize)),
                    opacity = (0.2f + Math.random().toFloat() * 0.8f),
                    speed = (0.2f + Math.random().toFloat() * 0.8f),
                )
            }
        )
    }

    // Battery safety — stop animation when composable leaves composition
    var isActive by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        isActive = true
        onDispose { isActive = false }
    }

    // Animation loop
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(33L) // ~30fps
            if (!isActive) continue
            particles.value = particles.value.map { p ->
                var newY = p.y + p.speed * speedFactor
                var newOpacity = p.opacity + (-0.02f + Math.random().toFloat() * 0.04f)
                newOpacity = newOpacity.coerceIn(0.1f, 1f)
                if (newY > 1000f) {
                    p.copy(y = -p.size, x = (Math.random() * 1000).toFloat(), opacity = newOpacity)
                } else {
                    p.copy(y = newY, opacity = newOpacity)
                }
            }
        }
    }

    Canvas(
        modifier = if (isFullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(blockHeight),
    ) {
        val scaleX = size.width / 1000f
        val scaleY = size.height / 1000f
        particles.value.forEach { p ->
            drawCircle(
                color = particleColor.copy(alpha = p.opacity * baseOpacity),
                radius = p.size * scaleX,
                center = Offset(p.x * scaleX, p.y * scaleY),
            )
        }
    }
}

// MARK: - Wheel Picker Block (SPEC-089d AC-013)

@Composable
private fun WheelPickerBlock(block: ContentBlock, inputValues: MutableMap<String, Any>) {
    val minVal = block.min_value ?: 0.0
    val maxVal = block.max_value_picker ?: 100.0
    val step = block.step_value ?: 1.0
    val defaultVal = block.default_picker_value ?: minVal
    val unitStr = block.unit ?: ""
    val unitPos = block.unit_position ?: "after"
    val highlightColor = StyleEngine.parseColor(block.highlight_color ?: block.active_color ?: "#6366F1")
    val fieldId = block.field_id ?: block.id

    val values = remember {
        val vals = mutableListOf<Double>()
        var current = minVal
        while (current <= maxVal) {
            vals.add(current)
            current += step
        }
        if (vals.isEmpty()) vals.add(0.0)
        vals
    }

    val initialIndex = values.indexOfFirst { it >= defaultVal }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)

    // Persist selected value
    val centeredIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(centeredIndex) {
        if (centeredIndex in values.indices) {
            inputValues[fieldId] = values[centeredIndex]
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        block.label?.let { label ->
            Text(text = label, fontSize = 14.sp, color = Color.Gray, modifier = Modifier.padding(bottom = 8.dp))
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Highlight strip at center
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(highlightColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
            ) {
                items(values.size) { index ->
                    val v = values[index]
                    val formatted = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
                    val display = if (unitPos == "before") "$unitStr$formatted" else "$formatted$unitStr"
                    val isCenter = index == centeredIndex

                    Text(
                        text = display,
                        fontSize = if (isCenter) 22.sp else 16.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCenter) highlightColor else Color.Gray,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// MARK: - Pulsing Avatar Block (SPEC-089d AC-014)

@Composable
private fun PulsingAvatarBlock(block: ContentBlock) {
    val avatarSize = (block.icon_size ?: block.height ?: 80.0).dp
    val pulseColor = StyleEngine.parseColor(block.pulse_color ?: "#6366F1")
    val ringCount = block.pulse_ring_count ?: 3
    val pulseDurationMs = ((block.pulse_speed ?: 1.5) * 1000).toInt()
    val borderW = (block.border_width ?: 0.0).dp
    val borderCol = StyleEngine.parseColor(block.border_color ?: "#FFFFFF")
    val hAlign = when (block.alignment) {
        "left" -> Alignment.CenterStart; "right" -> Alignment.CenterEnd; else -> Alignment.Center
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = hAlign,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Pulse rings
            for (i in 0 until ringCount) {
                val ringSize = avatarSize + (i + 1).dp * 20
                val scale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = pulseDurationMs),
                        repeatMode = RepeatMode.Restart,
                        initialStartOffset = androidx.compose.animation.core.StartOffset(
                            offsetMillis = (pulseDurationMs / ringCount) * i,
                        ),
                    ),
                    label = "ring_scale_$i",
                )
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = pulseDurationMs),
                        repeatMode = RepeatMode.Restart,
                        initialStartOffset = androidx.compose.animation.core.StartOffset(
                            offsetMillis = (pulseDurationMs / ringCount) * i,
                        ),
                    ),
                    label = "ring_alpha_$i",
                )
                Box(
                    modifier = Modifier
                        .size(ringSize)
                        .alpha(alpha)
                        .border(2.dp, pulseColor.copy(alpha = 0.3f), CircleShape)
                        .then(Modifier.size(ringSize * scale.coerceIn(1f, 1.5f))),
                )
            }

            // Avatar image
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(CircleShape)
                    .then(
                        if (borderW.value > 0) Modifier.border(borderW, borderCol, CircleShape) else Modifier
                    ),
            ) {
                if (block.image_url != null) {
                    ai.appdna.sdk.core.NetworkImage(
                        url = block.image_url,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Gray.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("\uD83D\uDC64", fontSize = (avatarSize.value * 0.4).sp)
                    }
                }
            }

            // Badge
            if (!block.badge_text.isNullOrEmpty()) {
                Text(
                    text = block.badge_text,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StyleEngine.parseColor(block.badge_text_color ?: "#FFFFFF"),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(
                            StyleEngine.parseColor(block.badge_bg_color ?: "#EF4444"),
                            RoundedCornerShape(999.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// MARK: - Pricing Card Block (SPEC-089d Nurrai)

@Composable
private fun PricingCardBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    inputValues: MutableMap<String, Any>,
) {
    val plans = block.pricing_plans ?: emptyList()
    val isSideBySide = block.pricing_layout == "side_by_side"
    val accentColor = StyleEngine.parseColor(block.active_color ?: block.bg_color ?: "#6366F1")

    var selectedPlanId by remember { mutableStateOf<String?>(null) }

    @Composable
    fun PlanCardContent(plan: PricingPlan, modifier: Modifier = Modifier) {
        val isHighlighted = plan.is_highlighted
        val isSelected = selectedPlanId == plan.id

        Card(
            onClick = {
                selectedPlanId = plan.id
                inputValues["selected_plan_id"] = plan.id
                inputValues["selected_plan_label"] = plan.label
                onAction("select_plan:${plan.id}")
            },
            modifier = modifier
                .border(
                    width = if (isSelected || isHighlighted) 2.dp else 1.dp,
                    color = if (isSelected) accentColor else if (isHighlighted) accentColor else Color.Gray.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp),
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) accentColor.copy(alpha = 0.05f) else Color.Transparent,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!plan.badge.isNullOrEmpty()) {
                    Text(
                        text = plan.badge,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .background(accentColor, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Text(text = plan.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(text = plan.price, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(text = plan.period, fontSize = 12.sp, color = Color.Gray)
            }
        }
    }

    if (isSideBySide) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            plans.forEach { plan ->
                PlanCardContent(plan, modifier = Modifier.weight(1f))
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            plans.forEach { plan ->
                PlanCardContent(plan, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// MARK: - Entrance Animation Wrapper (SPEC-089d §6.4)

/**
 * Wraps content with entrance animation.
 * Uses AnimatedVisibility with appropriate EnterTransition.
 */
@Composable
fun EntranceAnimationWrapper(
    animation: EntranceAnimationConfig,
    content: @Composable () -> Unit,
) {
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(animation.delay_ms.toLong())
        isVisible = true
    }

    val durationMs = animation.duration_ms
    val easingSpec: androidx.compose.animation.core.FiniteAnimationSpec<Float> = when (animation.easing) {
        "spring" -> androidx.compose.animation.core.spring(
            dampingRatio = (animation.spring_damping ?: 0.7).toFloat()
        )
        else -> tween(durationMillis = durationMs)
    }

    val enterTransition: androidx.compose.animation.EnterTransition = when (animation.type) {
        "fade_in" -> androidx.compose.animation.fadeIn(tween(durationMs))
        "slide_up" -> androidx.compose.animation.slideInVertically(tween(durationMs)) { it }
        "slide_down" -> androidx.compose.animation.slideInVertically(tween(durationMs)) { -it }
        "slide_left" -> androidx.compose.animation.slideInHorizontally(tween(durationMs)) { -it }
        "slide_right" -> androidx.compose.animation.slideInHorizontally(tween(durationMs)) { it }
        "scale_up" -> androidx.compose.animation.scaleIn(tween(durationMs), initialScale = 0.5f)
        "scale_down" -> androidx.compose.animation.scaleIn(tween(durationMs), initialScale = 1.5f)
        "bounce" -> androidx.compose.animation.scaleIn(
            androidx.compose.animation.core.spring(dampingRatio = (animation.spring_damping ?: 0.7).toFloat()),
            initialScale = 0.3f,
        )
        "flip" -> androidx.compose.animation.fadeIn(tween(durationMs)) +
            androidx.compose.animation.scaleIn(tween(durationMs), initialScale = 0.0f)
        else -> androidx.compose.animation.EnterTransition.None
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = isVisible,
        enter = enterTransition,
    ) {
        content()
    }
}

// MARK: - Form Input Block Composables (SPEC-089d Phase 3: AC-040 through AC-053)

/** Label composable for form field blocks. */
@Composable
private fun FormFieldLabel(block: ContentBlock) {
    val label = block.field_label ?: block.label ?: block.text
    if (!label.isNullOrEmpty()) {
        val required = block.field_required ?: false
        Row {
            Text(
                text = label,
                fontSize = (block.field_style?.label_font_size ?: 14.0).sp,
                fontWeight = FontWeight.Medium,
                color = StyleEngine.parseColor(block.field_style?.label_color ?: "#374151"),
            )
            if (required) {
                Text(text = "*", color = Color.Red, fontSize = 14.sp)
            }
        }
    }
}

/** Generic text-based input (text, number, email, phone, url). */
@Composable
private fun FormInputTextBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
    keyboardType: Int,
) {
    val fieldId = block.field_id ?: block.id
    var text by remember { mutableStateOf("") }
    val borderColor = StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB")
    val cornerRadius = (block.field_style?.corner_radius ?: 8.0).dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        val kbType = when (keyboardType) {
            android.text.InputType.TYPE_CLASS_NUMBER -> androidx.compose.ui.text.input.KeyboardType.Number
            android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> androidx.compose.ui.text.input.KeyboardType.Email
            android.text.InputType.TYPE_CLASS_PHONE -> androidx.compose.ui.text.input.KeyboardType.Phone
            android.text.InputType.TYPE_TEXT_VARIATION_URI -> androidx.compose.ui.text.input.KeyboardType.Uri
            else -> androidx.compose.ui.text.input.KeyboardType.Text
        }

        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                inputValues[fieldId] = it
            },
            placeholder = {
                Text(
                    text = block.field_placeholder ?: "",
                    color = StyleEngine.parseColor(block.field_style?.placeholder_color ?: "#9CA3AF"),
                )
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = kbType),
            shape = RoundedCornerShape(cornerRadius),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
    }
}

/** Multi-line textarea input. */
@Composable
private fun FormInputTextAreaBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    var text by remember { mutableStateOf("") }
    val minLines = (block.field_config?.get("min_lines") as? Number)?.toInt() ?: 3

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                inputValues[fieldId] = it
            },
            placeholder = { Text(block.field_placeholder ?: "") },
            shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = minLines,
        )
    }
}

/** Password input with show/hide toggle. */
@Composable
private fun FormInputPasswordBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    var text by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        androidx.compose.material3.OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                inputValues[fieldId] = it
            },
            placeholder = { Text(block.field_placeholder ?: "Password") },
            shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (passwordVisible)
                androidx.compose.ui.text.input.VisualTransformation.None
            else
                androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(if (passwordVisible) "\uD83D\uDE48" else "\uD83D\uDC41", fontSize = 18.sp)
                }
            },
        )
    }
}

/** Date / Time / DateTime picker input. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormInputDateBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
    mode: String,
) {
    val fieldId = block.field_id ?: block.id
    var displayText by remember { mutableStateOf(block.field_placeholder ?: "Select ${mode}...") }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    // For datetime mode: track which sub-picker is active
    var pendingDate by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        // AC-042: Tappable button opens actual Material3 date/time picker
        OutlinedButton(
            onClick = {
                when (mode) {
                    "date", "datetime" -> showDatePicker = true
                    "time" -> showTimePicker = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
            ),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.DarkGray),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = displayText, fontSize = 14.sp)
                Text(text = when (mode) {
                    "date" -> "\uD83D\uDCC5"
                    "time" -> "\u23F0"
                    else -> "\uD83D\uDCC5"
                }, fontSize = 16.sp)
            }
        }
    }

    // Material3 DatePickerDialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val formatted = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(millis))
                        if (mode == "datetime") {
                            pendingDate = formatted
                            showTimePicker = true
                        } else {
                            displayText = formatted
                            inputValues[fieldId] = formatted
                        }
                    }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Material3 TimePickerDialog (using AlertDialog wrapper)
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState()
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showTimePicker = false
                    val timeStr = String.format(java.util.Locale.US, "%02d:%02d", timePickerState.hour, timePickerState.minute)
                    if (mode == "datetime" && pendingDate.isNotEmpty()) {
                        val combined = "$pendingDate $timeStr"
                        displayText = combined
                        inputValues[fieldId] = combined
                        pendingDate = ""
                    } else {
                        displayText = timeStr
                        inputValues[fieldId] = timeStr
                    }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
            },
            title = { Text("Select time") },
            text = { TimePicker(state = timePickerState) },
        )
    }
}

/** Dropdown/stacked/grid select input with display_style support. */
@Composable
private fun FormInputSelectBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val options = block.field_options ?: emptyList()
    // Gap 1: Read display_style from field_config; defaults to "dropdown".
    val displayStyle = (block.field_config?.get("display_style") as? String) ?: "dropdown"
    // Gap 8: Gracefully handle dynamic options (use_variable / use_webhook) — parse but ignore
    val useVariable = block.field_config?.get("use_variable") as? String
    val useWebhook = block.field_config?.get("use_webhook") as? String
    // For now, dynamic options require server round-trip. Display static options if present.
    var selectedValue by remember { mutableStateOf(inputValues[fieldId] as? String ?: "") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        when (displayStyle) {
            "stacked" -> {
                // Stacked: Column of cards with RadioButton
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    options.forEach { option ->
                        val isSelected = selectedValue == option.value
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedValue = option.value
                                    inputValues[fieldId] = option.value
                                },
                            shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) StyleEngine.parseColor(block.field_style?.fill_color ?: "#6366F1").copy(alpha = 0.1f) else Color.Transparent,
                            ),
                            border = if (isSelected) {
                                androidx.compose.foundation.BorderStroke(2.dp, StyleEngine.parseColor(block.field_style?.fill_color ?: "#6366F1"))
                            } else {
                                androidx.compose.foundation.BorderStroke(1.dp, StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"))
                            },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selectedValue = option.value
                                        inputValues[fieldId] = option.value
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = StyleEngine.parseColor(block.field_style?.fill_color ?: "#6366F1"),
                                    ),
                                )
                                // Gap 7: Option image_url
                                option.image_url?.let { url ->
                                    if (url.isNotEmpty()) {
                                        Spacer(Modifier.width(8.dp))
                                        ai.appdna.sdk.core.NetworkImage(
                                            url = url,
                                            modifier = Modifier.size(24.dp).clip(CircleShape),
                                            contentScale = ContentScale.Crop,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(text = option.label, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            "grid" -> {
                // Grid: 2-column layout
                val chunked = options.chunked(2)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    chunked.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            row.forEach { option ->
                                val isSelected = selectedValue == option.value
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            selectedValue = option.value
                                            inputValues[fieldId] = option.value
                                        },
                                    shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) StyleEngine.parseColor(block.field_style?.fill_color ?: "#6366F1").copy(alpha = 0.1f) else Color.Transparent,
                                    ),
                                    border = if (isSelected) {
                                        androidx.compose.foundation.BorderStroke(2.dp, StyleEngine.parseColor(block.field_style?.fill_color ?: "#6366F1"))
                                    } else {
                                        androidx.compose.foundation.BorderStroke(1.dp, StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"))
                                    },
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        // Gap 7: Option image_url
                                        option.image_url?.let { url ->
                                            if (url.isNotEmpty()) {
                                                ai.appdna.sdk.core.NetworkImage(
                                                    url = url,
                                                    modifier = Modifier.size(32.dp).clip(CircleShape),
                                                    contentScale = ContentScale.Crop,
                                                )
                                                Spacer(Modifier.height(4.dp))
                                            }
                                        }
                                        Text(text = option.label, fontSize = 14.sp, textAlign = TextAlign.Center)
                                    }
                                }
                            }
                            // Fill empty slot in last row
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            else -> {
                // "dropdown" (default): Spinner/dropdown
                var expanded by remember { mutableStateOf(false) }
                var selectedLabel by remember { mutableStateOf(
                    options.firstOrNull { it.value == selectedValue }?.label ?: block.field_placeholder ?: "Select..."
                ) }

                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                        ),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.DarkGray),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(text = selectedLabel, fontSize = 14.sp)
                            Text(text = "\u25BC", fontSize = 12.sp)
                        }
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        options.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Gap 7: Option image_url in dropdown
                                        option.image_url?.let { url ->
                                            if (url.isNotEmpty()) {
                                                ai.appdna.sdk.core.NetworkImage(
                                                    url = url,
                                                    modifier = Modifier.size(24.dp).clip(CircleShape),
                                                    contentScale = ContentScale.Crop,
                                                )
                                                Spacer(Modifier.width(8.dp))
                                            }
                                        }
                                        Text(option.label)
                                    }
                                },
                                onClick = {
                                    selectedLabel = option.label
                                    selectedValue = option.value
                                    inputValues[fieldId] = option.value
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Slider input for single numeric value. */
@Composable
private fun FormInputSliderBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val minVal = (block.min_value ?: 0.0).toFloat()
    val maxVal = (block.max_value_picker ?: 100.0).toFloat()
    val unitStr = block.unit ?: ""
    val fillCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_color ?: "#6366F1")
    var value by remember { mutableStateOf((block.default_picker_value ?: minVal.toDouble()).toFloat()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FormFieldLabel(block)
            Text(
                text = "${value.roundToInt()}$unitStr",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = fillCol,
            )
        }

        Slider(
            value = value,
            onValueChange = {
                value = it
                inputValues[fieldId] = it.toDouble()
            },
            valueRange = minVal..maxVal,
            colors = SliderDefaults.colors(
                thumbColor = fillCol,
                activeTrackColor = fillCol,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    LaunchedEffect(Unit) {
        inputValues[fieldId] = value.toDouble()
    }
}

/** Toggle (switch) input. */
@Composable
private fun FormInputToggleBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val onColor = StyleEngine.parseColor(block.field_style?.toggle_on_color ?: "#6366F1")
    val label = block.field_label ?: block.toggle_label ?: ""
    var checked by remember { mutableStateOf(block.toggle_default ?: false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Switch(
            checked = checked,
            onCheckedChange = {
                checked = it
                inputValues[fieldId] = it
            },
            colors = SwitchDefaults.colors(checkedTrackColor = onColor),
        )
    }

    LaunchedEffect(Unit) {
        inputValues[fieldId] = checked
    }
}

/** Stepper input (increment/decrement). */
@Composable
private fun FormInputStepperBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val minVal = (block.min_value ?: 0.0).toInt()
    val maxVal = (block.max_value_picker ?: 100.0).toInt()
    val stepVal = (block.step_value ?: 1.0).toInt()
    val unitStr = block.unit ?: ""
    var value by remember { mutableStateOf((block.default_picker_value ?: minVal.toDouble()).toInt()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { if (value - stepVal >= minVal) { value -= stepVal; inputValues[fieldId] = value } },
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "$value$unitStr",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = { if (value + stepVal <= maxVal) { value += stepVal; inputValues[fieldId] = value } },
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("+", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    LaunchedEffect(Unit) {
        inputValues[fieldId] = value
    }
}

/** Segmented picker input. */
@Composable
private fun FormInputSegmentedBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val options = block.field_options ?: emptyList()
    var selectedValue by remember { mutableStateOf(options.firstOrNull()?.value ?: "") }
    val fillCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_color ?: "#6366F1")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color.Gray.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
        ) {
            options.forEach { option ->
                val isSelected = selectedValue == option.value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (isSelected) fillCol else Color.Transparent)
                        .clickable {
                            selectedValue = option.value
                            inputValues[fieldId] = option.value
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        color = if (isSelected) Color.White else Color.DarkGray,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (selectedValue.isNotEmpty()) inputValues[fieldId] = selectedValue
    }
}

/** Star rating input (form variant). */
@Composable
private fun FormInputRatingBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val maxStars = block.max_stars ?: 5
    val starSize = (block.star_size ?: 32.0).sp
    val filledCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_rating_color ?: "#FBBF24")
    val emptyCol = StyleEngine.parseColor(block.inactive_rating_color ?: "#D1D5DB")
    var selectedRating by remember { mutableStateOf((block.default_value ?: 0.0).toInt()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..maxStars) {
                val isFilled = i <= selectedRating
                Icon(
                    imageVector = if (isFilled) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = "$i stars",
                    tint = if (isFilled) filledCol else emptyCol,
                    modifier = Modifier
                        .size(starSize.value.dp)
                        .clickable {
                            selectedRating = i
                            inputValues[fieldId] = i.toDouble()
                        },
                )
            }
        }
    }
}

/** Range slider (dual-thumb) input. */
@Composable
private fun FormInputRangeSliderBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val minVal = (block.min_value ?: 0.0).toFloat()
    val maxVal = (block.max_value_picker ?: 100.0).toFloat()
    val unitStr = block.unit ?: ""
    val fillCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_color ?: "#6366F1")
    var lowValue by remember { mutableStateOf(minVal) }
    var highValue by remember { mutableStateOf(maxVal) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FormFieldLabel(block)
            Text(
                text = "${lowValue.roundToInt()}$unitStr - ${highValue.roundToInt()}$unitStr",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = fillCol,
            )
        }

        // Min slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Min", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.width(30.dp))
            Slider(
                value = lowValue,
                onValueChange = {
                    lowValue = it
                    if (lowValue > highValue) highValue = lowValue
                    inputValues[fieldId] = mapOf("min" to lowValue.toDouble(), "max" to highValue.toDouble())
                },
                valueRange = minVal..maxVal,
                colors = SliderDefaults.colors(thumbColor = fillCol, activeTrackColor = fillCol),
                modifier = Modifier.weight(1f),
            )
        }
        // Max slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Max", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.width(30.dp))
            Slider(
                value = highValue,
                onValueChange = {
                    highValue = it
                    if (highValue < lowValue) lowValue = highValue
                    inputValues[fieldId] = mapOf("min" to lowValue.toDouble(), "max" to highValue.toDouble())
                },
                valueRange = minVal..maxVal,
                colors = SliderDefaults.colors(thumbColor = fillCol, activeTrackColor = fillCol),
                modifier = Modifier.weight(1f),
            )
        }
    }

    LaunchedEffect(Unit) {
        inputValues[fieldId] = mapOf("min" to lowValue.toDouble(), "max" to highValue.toDouble())
    }
}

/** Chips / tag selection input. */
@Composable
private fun FormInputChipsBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val options = block.field_options ?: emptyList()
    val fillCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_color ?: "#6366F1")
    val maxSelections = (block.field_config?.get("max_selections") as? Number)?.toInt()
    var selectedValues by remember { mutableStateOf(setOf<String>()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        // Wrapping flow layout using FlowRow (Material3)
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                val isSelected = selectedValues.contains(option.value)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .border(
                            1.dp,
                            if (isSelected) fillCol else Color.Gray.copy(alpha = 0.3f),
                            RoundedCornerShape(999.dp),
                        )
                        .background(if (isSelected) fillCol else Color.Gray.copy(alpha = 0.05f))
                        .clickable {
                            selectedValues = if (isSelected) {
                                selectedValues - option.value
                            } else {
                                if (maxSelections != null && selectedValues.size >= maxSelections) {
                                    selectedValues // at max
                                } else {
                                    selectedValues + option.value
                                }
                            }
                            inputValues[fieldId] = selectedValues.toList()
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = option.label,
                        fontSize = 14.sp,
                        color = if (isSelected) Color.White else Color.DarkGray,
                    )
                }
            }
        }
    }
}

/** Color picker — grid of preset color swatches. */
@Composable
private fun FormInputColorBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    @Suppress("UNCHECKED_CAST")
    val presetColors: List<String> = (block.field_config?.get("preset_colors") as? List<String>)
        ?: listOf("#EF4444", "#F97316", "#EAB308", "#22C55E", "#3B82F6", "#6366F1", "#A855F7", "#EC4899", "#000000", "#6B7280")
    var selectedColor by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presetColors.forEach { color ->
                val isSelected = selectedColor == color
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(StyleEngine.parseColor(color))
                        .border(
                            width = if (isSelected) 3.dp else 0.dp,
                            color = if (isSelected) Color.DarkGray else Color.Transparent,
                            shape = CircleShape,
                        )
                        .clickable {
                            selectedColor = color
                            inputValues[fieldId] = color
                        },
                )
            }
        }
    }
}

/** Placeholder for complex inputs (location, image_picker, signature). */
@Composable
private fun FormInputPlaceholderBlock(
    block: ContentBlock,
    icon: String,
    label: String,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                .background(Color(0xFFF9FAFB))
                .border(
                    1.dp,
                    StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                    RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = icon, fontSize = 16.sp)
                Text(text = label, fontSize = 14.sp, color = Color.Gray)
            }
        }
    }
}

// MARK: - AC-046: Location Input with backend geocoding autocomplete

/** Location input with debounced autocomplete via AppDNA backend geocoding API. */
@Composable
private fun FormInputLocationPlaceholder(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    var text by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<LocationSuggestion>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        OutlinedTextField(
            value = text,
            onValueChange = { newText ->
                text = newText
                inputValues[fieldId] = newText
                // Debounce API calls — wait 300ms after last keystroke
                debounceJob?.cancel()
                if (newText.length >= 3) {
                    debounceJob = coroutineScope.launch {
                        kotlinx.coroutines.delay(300)
                        isSearching = true
                        suggestions = fetchLocationSuggestions(newText)
                        showSuggestions = suggestions.isNotEmpty()
                        isSearching = false
                    }
                } else {
                    suggestions = emptyList()
                    showSuggestions = false
                }
            },
            placeholder = { Text(block.field_placeholder ?: "Search location...", color = Color.Gray) },
            leadingIcon = { Text("\uD83D\uDCCD", fontSize = 16.sp) },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = StyleEngine.parseColor(block.field_style?.focused_border_color ?: "#6366F1"),
                unfocusedBorderColor = StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
            ),
        )

        // Autocomplete results dropdown
        if (showSuggestions) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                    .background(Color(0xFFF9FAFB))
                    .border(
                        1.dp,
                        StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                        RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                    ),
            ) {
                suggestions.forEachIndexed { index, suggestion ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                text = suggestion.address
                                showSuggestions = false
                                inputValues[fieldId] = mapOf(
                                    "address" to suggestion.address,
                                    "latitude" to suggestion.latitude,
                                    "longitude" to suggestion.longitude,
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(text = suggestion.address, fontSize = 14.sp, color = Color.Black)
                    }
                    if (index < suggestions.size - 1) {
                        Divider(color = Color.LightGray.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

/** Data class for geocoding autocomplete suggestion. */
private data class LocationSuggestion(
    val address: String,
    val latitude: Double,
    val longitude: Double,
)

/** Fetch location suggestions from the AppDNA backend geocoding API. */
private suspend fun fetchLocationSuggestions(query: String): List<LocationSuggestion> {
    return try {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = java.net.URL("https://appdna-app-156101819099.us-east1.run.app/api/v1/geocoding/autocomplete?q=$encodedQuery")
        val connection = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            (url.openConnection() as? java.net.HttpURLConnection)?.apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
        }
        if (connection == null || connection.responseCode != 200) return emptyList()

        val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            connection.inputStream.bufferedReader().readText()
        }
        val json = org.json.JSONObject(body)
        val results = json.optJSONArray("data") ?: return emptyList()

        (0 until minOf(results.length(), 5)).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            LocationSuggestion(
                address = item.optString("formatted_address", ""),
                latitude = item.optDouble("latitude", 0.0),
                longitude = item.optDouble("longitude", 0.0),
            )
        }
    } catch (e: Exception) {
        ai.appdna.sdk.Log.debug("Location autocomplete failed: ${e.message}")
        emptyList()
    }
}

// MARK: - AC-048: Image Picker with PickVisualMedia (Android 13+, backported via Activity Result API)

/** Image picker using PickVisualMedia. Stores selected image data as base64 in inputValues. */
@Composable
private fun FormInputImagePickerPlaceholder(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val context = LocalContext.current
    var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var thumbnailBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes()
                inputStream?.close()
                if (bytes != null) {
                    thumbnailBitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    inputValues[fieldId] = mapOf(
                        "data" to base64,
                        "size" to bytes.size,
                        "mime_type" to (context.contentResolver.getType(uri) ?: "image/jpeg"),
                    )
                }
            } catch (e: Exception) {
                ai.appdna.sdk.Log.debug("Image picker load failed: ${e.message}")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        if (thumbnailBitmap != null) {
            // Show selected image thumbnail
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                    .border(
                        1.dp,
                        StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                        RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                    )
                    .clickable {
                        launcher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
            ) {
                thumbnailBitmap?.asImageBitmap()?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = "Selected image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                    )
                }
                // Edit overlay icon
                Text(
                    text = "\u270F\uFE0F",
                    fontSize = 20.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        } else {
            // Empty state — dashed border tap target
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                    .background(Color(0xFFF9FAFB))
                    .border(
                        1.dp,
                        StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                        RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                    )
                    .clickable {
                        launcher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "\uD83D\uDDBC", fontSize = 16.sp)
                    Text(text = "Tap to pick image", fontSize = 14.sp, color = Color.Gray)
                }
            }
        }
    }
}

// MARK: - AC-051: Signature Input (interactive Canvas with touch drawing)

/** Interactive signature pad with basic touch/drag drawing. */
@Composable
private fun FormInputSignatureBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    val lines = remember { mutableStateListOf<List<Offset>>() }
    var currentLine by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                .background(Color(0xFFF9FAFB))
                .border(
                    1.dp,
                    StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB"),
                    RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp),
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            currentLine = listOf(offset)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            currentLine = currentLine + change.position
                        },
                        onDragEnd = {
                            if (currentLine.isNotEmpty()) {
                                lines.add(currentLine)
                                currentLine = emptyList()
                                inputValues[fieldId] = "signed"
                            }
                        },
                    )
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val allLines = lines.toList() + listOf(currentLine)
                allLines.forEach { line ->
                    if (line.size > 1) {
                        for (i in 0 until line.size - 1) {
                            drawLine(
                                color = Color.Black,
                                start = line[i],
                                end = line[i + 1],
                                strokeWidth = 4f,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }

            // Clear button
            if (lines.isNotEmpty()) {
                Text(
                    text = "\u2715 Clear",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clickable {
                            lines.clear()
                            currentLine = emptyList()
                            inputValues.remove(fieldId)
                        },
                )
            } else {
                Text(
                    text = "Draw your signature here",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

// MARK: - Stub Placeholder (SPEC-089d)

/**
 * Placeholder view for new block types whose full renderers are not yet implemented.
 * Renders a subtle debug label when SDK log level is DEBUG; nothing otherwise.
 */
@Composable
private fun StubBlockPlaceholder(typeName: String) {
    if (Log.level.level >= LogLevel.DEBUG.level) {
        Text(
            text = "[$typeName]",
            fontSize = 10.sp,
            color = Color.Gray.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }
    // In non-debug mode: renders nothing (empty composable)
}
