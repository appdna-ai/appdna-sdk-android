@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
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
import ai.appdna.sdk.core.applyTransform
import ai.appdna.sdk.core.LottieBlock
import ai.appdna.sdk.core.LottieBlockView
import ai.appdna.sdk.core.RiveBlock
import ai.appdna.sdk.core.RiveBlockView
import ai.appdna.sdk.core.VideoBlock as CoreVideoBlock
import ai.appdna.sdk.core.VideoBlockView as CoreVideoBlockView
import ai.appdna.sdk.core.IconView
import ai.appdna.sdk.core.resolveIcon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarHalf
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image

// MARK: - Block Style Design Tokens (SPEC-089d §6.1)

/**
 * Per-block styling: background, border, shadow, padding, margin, opacity.
 *
 * SPEC-070-A J.10 — `@Immutable`: every field is a primitive / nullable
 * primitive / nested fully-immutable holder; no iterables to migrate.
 */
@androidx.compose.runtime.Immutable
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
@androidx.compose.runtime.Immutable
data class BlockShadowStyle(
    val x: Double = 0.0,
    val y: Double = 2.0,
    val blur: Double = 8.0,
    val spread: Double = 0.0,
    // SPEC-401-A R26 — match iOS ContentBlockTypes.swift:60. iOS treats
    // a missing `shadow.color` as transparent (invisible shadow); Android
    // was hardcoding #1A000000 (10% black) which made the same JSON
    // `shadow: { x: 4, y: 4, blur: 12 }` (no color) render visibly on
    // Android but invisibly on iOS.
    val color: String = "transparent",
)

/** Gradient definition for block_style background. */
@androidx.compose.runtime.Immutable
data class BlockGradientStyle(
    val angle: Double = 135.0,
    // SPEC-401-A R26 — match iOS ContentBlockTypes.swift:67-68. iOS
    // gradient endpoint defaults are black (#000000) and white
    // (#FFFFFF) — applied only when console publishes a gradient
    // without start/end. Android was injecting brand indigo→purple
    // (#6366f1 → #a855f7) which made the same JSON `gradient: { angle:
    // 90 }` render as a brand-tinted gradient on Android while iOS
    // showed a black→white gradient.
    val start: String = "#000000",
    val end: String = "#FFFFFF",
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
        val borderWidthDp = style.border_width.dp
        val cornerRadiusVal = (style.border_radius ?: 0.0).toFloat()
        // SPEC-401-A R26 — match iOS ContentBlockTypes.swift:132-141 which
        // implements dashed `[8, 4]` and dotted `[2, 4]` strokes via
        // SwiftUI `StrokeStyle`. Android previously rendered all variants
        // as solid via `Modifier.border` because Compose's border modifier
        // doesn't natively support dash patterns. Now uses `drawBehind`
        // with `PathEffect.dashPathEffect` for dashed/dotted; keeps the
        // existing `Modifier.border` for the solid case (faster path).
        mod = when (style.border_style?.lowercase()) {
            "dashed", "dotted" -> {
                val pattern = if (style.border_style.lowercase() == "dotted") {
                    floatArrayOf(2f, 4f)
                } else {
                    floatArrayOf(8f, 4f)
                }
                mod.then(
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = borderColor,
                            size = size,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                cornerRadiusVal * density,
                                cornerRadiusVal * density,
                            ),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = borderWidthDp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(pattern[0] * density, pattern[1] * density),
                                    0f,
                                ),
                            ),
                        )
                    }
                )
            }
            else -> mod.then(Modifier.border(borderWidthDp, borderColor, shape))
        }
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

    // SPEC-401-A R65 (Lens A P1 #2) — positive vertical_offset participates
    // in layout via top padding (siblings shift down + scrollable parents
    // can scroll the full content). Negative offset stays as Modifier.offset
    // (no negative padding). Mirrors iOS BlockPositionModifier at
    // ContentBlockTypes.swift:469-498 — comment there: "Positive
    // vertical_offset participates in layout (top padding) so scrollable
    // zones can still scroll the full content. Negative offsets stay as
    // `.offset` since there's no negative padding."
    val yOffset = verticalOffset ?: 0.0
    val xOffset = horizontalOffset ?: 0.0
    val topPaddingDp = if (yOffset > 0.0) yOffset.dp else 0.dp
    val residualYDp = if (yOffset < 0.0) yOffset.dp else 0.dp

    if (topPaddingDp.value > 0f) {
        mod = mod.then(Modifier.padding(top = topPaddingDp))
    }
    if (residualYDp.value != 0f || xOffset != 0.0) {
        mod = mod.then(Modifier.offset(x = xOffset.dp, y = residualYDp))
    }

    return mod
}

/**
 * Maps horizontal/vertical alignment strings to Compose Alignment.
 */
/**
 * SPEC-401-A — parse iOS combined alignment tokens (e.g. `top_left`,
 * `topLeading`, `bottomTrailing`). Returns null when the token isn't
 * recognised so callers can fall back to per-axis tokens.
 */
internal fun parseStackAlignment(token: String?): Alignment? {
    if (token.isNullOrBlank()) return null
    return when (token.lowercase()) {
        "top_left", "topleft", "topleading", "top_leading" -> Alignment.TopStart
        "top", "topcenter", "top_center" -> Alignment.TopCenter
        "top_right", "topright", "toptrailing", "top_trailing" -> Alignment.TopEnd
        "left", "leading", "centerleading", "center_leading" -> Alignment.CenterStart
        "center", "middle" -> Alignment.Center
        "right", "trailing", "centertrailing", "center_trailing" -> Alignment.CenterEnd
        "bottom_left", "bottomleft", "bottomleading", "bottom_leading" -> Alignment.BottomStart
        "bottom", "bottomcenter", "bottom_center" -> Alignment.BottomCenter
        "bottom_right", "bottomright", "bottomtrailing", "bottom_trailing" -> Alignment.BottomEnd
        else -> null
    }
}

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

/**
 * SPEC-070-A J.10 — `@Stable`, not `@Immutable`: ContentBlock still carries
 * passthrough JSON Map/Any? fields per J.22 EXCLUDE rules (`lottie_json`,
 * `custom_config`, `field_config`, `icon_ref: Any?`, `bindings`) that we
 * deliberately don't migrate — they're caller-supplied JSON bags consumed by
 * specialized renderers, not Compose-iterated lists.
 *
 * SPEC-070-A J.22 — typed iterable fields (`children`, `items`, `providers`,
 * `timeline_items`, `loading_items`, `field_options`, `pricing_plans`,
 * `columns`, `size_range`) are migrated to ImmutableList<T> below so Compose
 * can short-circuit recompositions when contents are structurally equal.
 */
@androidx.compose.runtime.Stable
data class ContentBlock(
    val id: String,
    val type: String,  // heading, text, image, button, spacer, list, divider, badge, icon, toggle, video, ...
    val text: String? = null,
    val level: Int? = null,
    val image_url: String? = null,
    val alt: String? = null,
    // SPEC-401-A — `cover` (default) / `contain` / `fit` / `inside`
    // / `none`. iOS toggles `.fill`/`.fit` on this token.
    val image_fit: String? = null,
    val corner_radius: Double? = null,
    val height: Double? = null,
    val variant: String? = null,
    val action: String? = null,
    val action_value: String? = null,
    val bg_color: String? = null,
    val text_color: String? = null,
    val button_corner_radius: Double? = null,
    val spacer_height: Double? = null,
    // SPEC-070-A J.22 — ImmutableList for Compose stability (list block items).
    val items: kotlinx.collections.immutable.ImmutableList<String>? = null,
    val list_style: String? = null,
    val divider_color: String? = null,
    val divider_thickness: Double? = null,
    val divider_margin_y: Double? = null,
    val badge_text: String? = null,
    val badge_bg_color: String? = null,
    val badge_text_color: String? = null,
    val badge_corner_radius: Double? = null,
    val badge_position: String? = null,        // "top_trailing" (default) | "top_leading" | "bottom_trailing" | "bottom_leading"
    val badge_size: Double? = null,            // Scale factor (default 1.0 — matches native defaults)
    // Pulsing avatar — image shape override (legacy default = circle).
    val image_shape: String? = null,           // "circle" (default) | "square" | "rounded"
    val image_corner_radius: Double? = null,   // Used when image_shape == "rounded" (default 16)
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
    // SPEC-401-A — extra Lottie controls iOS forwards but Android
    // never declared (and so silently dropped).
    val play_on_scroll: Boolean? = null,
    val play_on_tap: Boolean? = null,
    val color_overrides: Map<String, String>? = null,
    val lottie_width: Double? = null,
    /**
     * SPEC-401-A R10 — iOS canonical Lottie-specific height field
     * (ContentBlockRendererView.swift:599 `block.lottie_height ?? block.height ?? 160`).
     * When both `lottie_height` and a generic `height` are authored,
     * lottie_height takes precedence so the same payload renders the
     * same vertical dimension on both natives.
     */
    val lottie_height: Double? = null,
    val rive_url: String? = null,
    val rive_artboard: String? = null,
    val rive_state_machine: String? = null,
    // SPEC-401-A — iOS canonical Rive field names (`artboard`,
    // `state_machine`) on the top-level ContentBlock plus the inputs
    // and trigger_on_step_complete fields iOS already forwards.
    val artboard: String? = null,
    val state_machine: String? = null,
    val rive_inputs: Map<String, Any>? = null,
    val trigger_on_step_complete: String? = null,
    val icon_ref: Any? = null,  // IconReference map or emoji string
    val video_autoplay: Boolean? = null,
    val video_loop: Boolean? = null,
    val video_muted: Boolean? = null,
    // SPEC-401-A — iOS canonical field names. Console writes both
    // legacy `video_*` and the unprefixed forms; honour both.
    val autoplay: Boolean? = null,
    val loop: Boolean? = null,
    val muted: Boolean? = null,
    val controls: Boolean? = null,
    val inline_playback: Boolean? = null,
    // SPEC-089d §6.1: Per-block style design tokens
    val block_style: BlockStyle? = null,
    // SPEC-089d §6.2: 2D positioning
    val vertical_align: String? = null,
    val horizontal_align: String? = null,
    val vertical_offset: Double? = null,
    val horizontal_offset: Double? = null,
    // SPEC-070-A I.17 — explicit "top" / "center" / "bottom" zone for the
    // ThreeZoneLayout step variant. Falls back to vertical_align when unset
    // so legacy authored content lands in the right zone without re-saving.
    val zone: String? = null,
    // SPEC-089d: page_indicator fields
    val dot_count: Int? = null,
    val active_index: Int? = null,
    val active_color: String? = null,
    val inactive_color: String? = null,
    val dot_size: Double? = null,
    val dot_spacing: Double? = null,
    val active_dot_width: Double? = null,
    // SPEC-089d: social_login fields
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val providers: kotlinx.collections.immutable.ImmutableList<SocialProvider>? = null,
    val button_style: String? = null,
    val button_height: Double? = null,
    val spacing: Double? = null,
    val show_divider: Boolean? = null,
    val divider_text: String? = null,
    // SPEC-401-A R13 — match iOS ContentBlockTypes.swift:964-965.
    // `with_providers` (default) renders providers in author order;
    // `below_inputs` extracts the email provider, renders it FIRST,
    // then a clear spacer of `email_cta_spacing_below` (px), then
    // the remaining providers below.
    val email_login_placement: String? = null,
    val email_cta_spacing_below: Double? = null,
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
    // SPEC-401-A R3 — iOS canonical field names for the rating
    // standalone block. Console writes these; Android only knew the
    // `*_rating_color` form and `default_value`/`label` so authored
    // values silently dropped. Honour both with iOS canon first.
    val filled_color: String? = null,
    val empty_color: String? = null,
    val default_rating: Double? = null,
    val rating_label: String? = null,
    // SPEC-089d: rich_text fields
    val content: String? = null,
    // SPEC-401-A R4 — iOS canonical name `markdown_content`; the
    // console writes this. Previously Android only knew `content` so
    // any console-authored markdown body silently dropped to plain
    // `text` fallback.
    val markdown_content: String? = null,
    val base_style: TextStyleConfig? = null,
    // SPEC-401-A — `legal` variant centres + uses caption font +
    // secondary colour fallback (consent / privacy boilerplate).
    val rich_text_variant: String? = null,
    val link_color: String? = null,
    val max_lines: Int? = null,
    // SPEC-089d: progress_bar fields
    val segment_count: Int? = null,
    val active_segments: Int? = null,
    // SPEC-401-A — iOS canon `filled_segments` field-name parity.
    // Android historically only knew `active_segments`; backend writes
    // both. Honour `filled_segments` first, fall back to `active_segments`.
    val filled_segments: Int? = null,
    val total_segments: Int? = null,
    val progress_value: Double? = null,
    val progress_variant: String? = null,
    val bar_color: String? = null,
    val bar_height: Double? = null,
    val fill_color: String? = null,
    val track_color: String? = null,
    val segment_gap: Double? = null,
    val show_label: Boolean? = null,
    val label_style: TextStyleConfig? = null,
    // SPEC-089d: timeline fields
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val timeline_items: kotlinx.collections.immutable.ImmutableList<TimelineItem>? = null,
    val line_color: String? = null,
    val completed_color: String? = null,
    val current_color: String? = null,
    val upcoming_color: String? = null,
    val show_line: Boolean? = null,
    val compact: Boolean? = null,
    val title_style: TextStyleConfig? = null,
    val subtitle_style: TextStyleConfig? = null,
    // SPEC-089d: animated_loading fields
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val loading_items: kotlinx.collections.immutable.ImmutableList<LoadingItem>? = null,
    val progress_color: String? = null,
    val check_color: String? = null,
    val total_duration_ms: Int? = null,
    val auto_advance: Boolean? = null,
    val show_percentage: Boolean? = null,
    // SPEC-401-A R3 — iOS canonical `loading_variant` (circular, linear,
    // checklist). Android historically only read top-level `variant`.
    val loading_variant: String? = null,
    // SPEC-089d Phase F: circular_gauge fields
    val gauge_value: Double? = null,
    val max_gauge_value: Double? = null,
    // SPEC-401-A — iOS canonical name `max_value`. Console writes
    // this; Android historically only knew `max_gauge_value` and so
    // any console-authored max was silently dropped to default 100.
    val max_value: Double? = null,
    val gauge_variant: String? = null,
    val gradient_start_color: String? = null,
    val gradient_end_color: String? = null,
    val arrow_color: String? = null,
    val arrow_stroke_width: Double? = null,
    val min_label: String? = null,
    val max_label: String? = null,
    val min_max_font_size: Double? = null,
    val min_max_color: String? = null,
    val percentage_location: String? = null,
    val sublabel: String? = null,
    val stroke_width: Double? = null,
    val label_color: String? = null,
    val label_font_size: Double? = null,
    val animate: Boolean? = null,
    val animation_duration_ms: Int? = null,
    // SPEC-089d Phase F: date_wheel_picker fields
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val columns: kotlinx.collections.immutable.ImmutableList<DateWheelColumn>? = null,
    val default_date_value: String? = null,
    val min_date: String? = null,
    val max_date: String? = null,
    val highlight_color: String? = null,
    val haptic_on_scroll: Boolean? = null,
    // SPEC-089d Phase F: stack / row fields (container blocks)
    // SPEC-070-A J.22 — ImmutableList for Compose stability of recursive children.
    val children: kotlinx.collections.immutable.ImmutableList<ContentBlock>? = null,
    // SPEC-401-A R61 (Lens A N1, P1) — accept iOS canonical `stack_children`
    // alongside `children`. Console editor (StepContentEditor.tsx) writes
    // `stack_children` on row-block creation; without this field Row blocks
    // shipped from console rendered an empty Row on Android. iOS already
    // reads both (`block.children ?? block.stack_children ?? []` at
    // ContentBlockRendererView.swift:1129).
    val stack_children: kotlinx.collections.immutable.ImmutableList<ContentBlock>? = null,
    val z_index: Double? = null,
    val gap: Double? = null,
    val wrap: Boolean? = null,
    val justify: String? = null,
    val align_items: String? = null,
    // Row direction and distribution (alignment gap fix)
    val row_direction: String? = null,       // horizontal (default), vertical
    val row_distribution: String? = null,    // start, center, end, space-between, space-around, space-evenly
    val row_child_fill: Boolean? = null,     // true (default) — each child gets weight(1f)
    // SPEC-401-A — proportional column widths. iOS reads
    // `field_config["column_ratios"]` first then falls back to
    // top-level `column_ratios` (e.g. "1:2"). Android historically
    // ignored both → equal-weight rows even when authoring asks for
    // 1/3-2/3 splits. Honour both paths.
    val column_ratios: String? = null,
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
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val size_range: kotlinx.collections.immutable.ImmutableList<Double>? = null,
    val fullscreen: Boolean? = null,
    // SPEC-089d Phase F: wheel_picker fields
    val min_value: Double? = null,
    val max_value_picker: Double? = null,
    val step_value: Double? = null,
    val default_picker_value: Double? = null,
    val unit: String? = null,
    val unit_position: String? = null,
    val visible_items: Int? = null,
    // SPEC-401-A R35 — wheel_picker orientation per iOS (modernHorizontalWheel
    // at ContentBlockStandaloneViews.swift:1521-1612). When unset or "vertical"
    // a vertical LazyColumn renders. "horizontal" → center-snap LazyRow.
    val wheel_orientation: String? = null,
    val orientation: String? = null,
    // SPEC-089d Phase F: pulsing_avatar fields
    val pulse_color: String? = null,
    val pulse_ring_count: Int? = null,
    val pulse_speed: Double? = null,
    val border_width: Double? = null,
    val border_color: String? = null,
    // SPEC-089d Nurrai: pricing_card fields
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val pricing_plans: kotlinx.collections.immutable.ImmutableList<PricingPlan>? = null,
    val pricing_layout: String? = null,
    // SPEC-089d Phase 3: Form input common fields
    val field_label: String? = null,
    val field_placeholder: String? = null,
    val field_required: Boolean? = null,
    // SPEC-401-A R49 (Lens A #1) — multi_select on input_select content block
    // (iOS ContentBlockTypes.swift:1120 + FormInputBlockViews.swift:403-406).
    // Without this Android always renders single-select even when authored
    // multi_select=true.
    val multi_select: Boolean? = null,
    // SPEC-401-A R49 (Lens A #3-#5) — date picker constraints + chrome.
    // iOS ContentBlockStandaloneViews.swift:966,970,980,984,1009,1012,
    // 1160,1174-1175,1193-1196,1239.
    val allow_future: Boolean? = null,
    val allow_past: Boolean? = null,
    val date_validation_message: String? = null,
    val picker_presentation: String? = null,
    val picker_mode: String? = null,
    val picker_spacing: Double? = null,
    val wheel_bg_color: String? = null,
    val wheel_height: Double? = null,
    val calendar_bg_color: String? = null,
    // SPEC-401-A R49 (Lens A #2) — Sprint 7 scroll-collapse behaviour.
    // iOS ContentBlockTypes.swift:1143 + ContentBlockRendererView.swift:51,68
    // + ThreeZoneStepLayout.swift:19. Without this header images don't
    // collapse on scroll on Android (visual desync).
    val collapse_on_scroll: Boolean? = null,
    val field_style: FormFieldBlockStyle? = null,
    // SPEC-070-A J.22 — ImmutableList for Compose stability.
    val field_options: kotlinx.collections.immutable.ImmutableList<InputOption>? = null,
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
@androidx.compose.runtime.Immutable
data class SocialProvider(
    val type: String,
    val label: String? = null,
    val enabled: Boolean = true,
    // SPEC-070-A finalization OB-2 — per-provider color/style overrides.
    // iOS resolves these in the renderer with brand-default fallbacks
    // (`ContentBlockRendererView.swift:724-771`). Without these fields,
    // every social-login provider rendered with hardcoded brand colors
    // and console-authored overrides were silently dropped (user
    // reported as "colors on button text are not the same as on iOS
    // and set in console").
    val bg_color: String? = null,
    val text_color: String? = null,
    val border_color: String? = null,
    val border_width: Float? = null,
    val corner_radius: Float? = null,
    val icon_style: String? = null, // "logo" | "monochrome" | "filled"
)

/** Countdown labels config (SPEC-089d §3.7). */
@androidx.compose.runtime.Immutable
data class CountdownLabels(
    val days: String? = null,
    val hours: String? = null,
    val minutes: String? = null,
    val seconds: String? = null,
)

/** Timeline item config (SPEC-089d §3.5). */
@androidx.compose.runtime.Immutable
data class TimelineItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: String? = null,
    // SPEC-401-A R18 — match iOS ContentBlockTypes.swift:796 — `String?`
    // (no default). When console publishes a timeline_item without `status`,
    // iOS evaluates `item.status == "upcoming"` as false → solid-black title;
    // Android previously defaulted to "upcoming" → ALWAYS greyed-out title
    // for omitted-status items. Switching to nullable aligns the title
    // foreground while keeping the status circle's `default → upcoming`
    // fallback in `timelineStatusColor` (line 2554) intact.
    val status: String? = null,  // completed | current | upcoming
)

/** Animated loading item config (SPEC-089d §3.6). */
@androidx.compose.runtime.Immutable
data class LoadingItem(
    val label: String,
    val duration_ms: Int = 1000,
    val icon: String? = null,
    // SPEC-401-A R35 — match iOS LoadingItemConfig (ContentBlockTypes.swift:816-825).
    // Per-item orbit decoration used by the orbiting_icons variant. Already
    // dropped on JSON parse before this field set was added.
    val icon_url: String? = null,
    val icon_bg_color: String? = null,
    val icon_size: Float? = null,
    val icon_orbit_angle: Float? = null,
)

/** Pricing plan config for pricing_card block (SPEC-089d §3.17). */
@androidx.compose.runtime.Immutable
data class PricingPlan(
    val id: String,
    val label: String,
    val price: String,
    val period: String,
    val badge: String? = null,
    val is_highlighted: Boolean = false,
)

/** Date wheel column config (SPEC-089d §3.12).
 *
 * SPEC-070-A J.22: `values` is migrated to ImmutableList for Compose stability.
 */
@androidx.compose.runtime.Immutable
data class DateWheelColumn(
    val type: String,    // day | month | year | custom
    val label: String? = null,
    val values: kotlinx.collections.immutable.ImmutableList<String>? = null,
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
@androidx.compose.runtime.Immutable
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
    // SPEC-401-A R41 — match iOS ContentBlockTypes.swift:291-312 (3 missing
    // fields). Renderer wiring of focused-state background + size token +
    // weight is a separate ticket; data-class + parser presence prevents
    // future R-round renderer fixes from being shadowed by missing field.
    val height: String? = null,
    val font_weight: String? = null,
    val focused_background_color: String? = null,
)

/**
 * Option for select, chips, segmented inputs.
 *
 * SPEC-070-A finalization P0 audit-11 Drift 2 — expanded from 3 fields
 * to 24 to mirror iOS `InputOption` (ContentBlockTypes.swift:316-406).
 * Console authors who set per-option styling (selected/unselected images,
 * subtitles, per-option colors / fonts / icons / borders / backgrounds /
 * cell alignment / image overlays) now see those values applied on
 * Android instead of being silently dropped.
 */
@androidx.compose.runtime.Immutable
data class InputOption(
    val value: String,
    val label: String,
    val image_url: String? = null,
    /** Stable id (falls back to `value` when null on iOS). */
    val id: String? = null,
    /** SF Symbol / emoji glyph rendered next to the label. */
    val icon: String? = null,
    /** Image swap on selection (falls back to `image_url`). */
    val selected_image_url: String? = null,
    val unselected_image_url: String? = null,
    /** Per-option subtitle (smaller font under label). */
    val subtitle: String? = null,
    /** Per-option text styling — overrides field_config defaults. */
    val title_color: String? = null,
    val subtitle_color: String? = null,
    val title_font_size: Double? = null,
    val subtitle_font_size: Double? = null,
    val title_font_weight: String? = null,
    /** Grid toggle: icon to show when selected/unselected. */
    val selected_icon: String? = null,
    val unselected_icon: String? = null,
    /** Image overlay: colored circle with opacity rendered over image. */
    val image_overlay_color: String? = null,
    val image_overlay_opacity: Double? = null,
    /** Per-option border overrides. */
    val border_color: String? = null,
    val selected_border_color: String? = null,
    /** Per-option backgrounds. */
    val bg_color: String? = null,
    val selected_bg_color: String? = null,
    val selected_text_color: String? = null,
    /** Per-option grid cell alignment ("leading" | "center" | "trailing"). */
    val cell_alignment: String? = null,
) {
    /** Mirrors iOS `resolvedImageURL(isSelected:)` — selected/unselected variant first, default image_url last. */
    fun resolvedImageURL(isSelected: Boolean): String? {
        val variant = if (isSelected) selected_image_url else unselected_image_url
        // SPEC-401-A R61 (Lens A backlog #8, P3) — `isNotEmpty` matches iOS
        // canonical ContentBlockTypes.swift:401-405 `if let v = variant,
        // !v.isEmpty { return v }`. Was `isNotBlank()` which treated
        // whitespace-only strings as empty (more lenient than iOS).
        // Aligning to iOS: a `"  "` URL is returned as-is (iOS shows broken
        // image; Android was silently falling back to image_url).
        return variant?.takeIf { it.isNotEmpty() } ?: image_url
    }
}

/** Visibility condition (SPEC-089d §6.3). */
@androidx.compose.runtime.Stable
data class VisibilityCondition(
    val type: String,        // always, when_equals, when_not_equals, when_not_empty, when_empty, when_gt, when_lt
    val variable: String? = null,
    val value: Any? = null,
    val expression: String? = null,
)

/** Entrance animation config (SPEC-089d §6.4). */
@androidx.compose.runtime.Immutable
data class EntranceAnimationConfig(
    val type: String = "none",    // none, fade_in, slide_up, slide_down, slide_left, slide_right, scale_up, scale_down, bounce, flip
    val duration_ms: Int = 300,
    val delay_ms: Int = 0,
    val easing: String = "ease_out",
    val spring_damping: Double? = null,
)

/** Pressed/tap style config (SPEC-089d §6.5). */
@androidx.compose.runtime.Immutable
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

            // SPEC-089d §6.7: Apply relative sizing.
            // SPEC-401-A R9 — skip `element_height` for input_* blocks,
            // mirroring iOS ContentBlockRendererView.swift:58-62 which
            // explicitly passes `nil` height for inputs so e.g. a
            // 60dp text field inside a `element_height: 280dp` row
            // doesn't render a tall empty wrapper around it. iOS also
            // sets `useMinHeight=true` for `input_select` so the
            // dropdown can grow; Compose's relative sizing equivalent
            // already lets dropdowns expand, so we only need the skip.
            val isInputBlock = block.type.startsWith("input_")
            val effectiveHeight = if (isInputBlock) null else block.element_height
            val sizingModifier = Modifier.applyRelativeSizing(block.element_width, effectiveHeight)

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

    // SPEC-401-A R9 — universal block-container styling. Mirrors
    // iOS `ContentBlockRendererView.swift:67` `.applyBlockContainerStyle(block)`
    // applied to EVERY block type. Previously only the `row` block read
    // these field_config keys; every other block silently dropped
    // authored `blur_background` / `container_bg_color` / `container_*`.
    // The hook is opt-in: it no-ops when the field_config has no
    // visible container values.
    val contentModifier = with(StyleEngine) {
        Modifier
            .applyBlockStyle(block.block_style)
            .applyBlockPosition(
                verticalAlign = block.vertical_align,
                horizontalAlign = block.horizontal_align,
                verticalOffset = block.vertical_offset,
                horizontalOffset = block.horizontal_offset,
            )
            .applyBlockContainerStyle(block.field_config)
    }

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

/**
 * SPEC-401-A — translate `horizontal_align` token into Compose
 * [TextAlign]. Mirrors iOS `multilineTextAlignment(...)` on text/
 * heading blocks. iOS accepts `leading`/`start`/`left`,
 * `trailing`/`end`/`right`, `center`. Unknown values fall through
 * to platform default (Start).
 */
private fun horizontalTextAlign(token: String?): androidx.compose.ui.text.style.TextAlign? = when (token) {
    "left", "start", "leading" -> androidx.compose.ui.text.style.TextAlign.Start
    "right", "end", "trailing" -> androidx.compose.ui.text.style.TextAlign.End
    "center" -> androidx.compose.ui.text.style.TextAlign.Center
    "justify" -> androidx.compose.ui.text.style.TextAlign.Justify
    else -> null
}

@Composable
private fun HeadingBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val text = block.text ?: ""
    val baseStyle = TextStyle(
        // SPEC-401-A R61 (Lens A backlog #1, P2) — match iOS canonical default
        // at ContentBlockRendererView.swift:240-246: level 4-6 (and unknown)
        // falls to 28pt, NOT 18pt. Was rendering 18sp on Android vs 28pt on
        // iOS for the same `{level: 4}` payload — 10pt narrower.
        fontSize = when (block.level ?: 1) { 1 -> 28.sp; 2 -> 22.sp; 3 -> 18.sp; else -> 28.sp },
        fontWeight = FontWeight.Bold,
        color = Color.Unspecified,
    )
    val effectiveStyle = if (block.style != null) StyleEngine.applyTextStyle(baseStyle, block.style) else baseStyle
    val styleWithAlign = horizontalTextAlign(block.horizontal_align)
        ?.let { effectiveStyle.copy(textAlign = it) } ?: effectiveStyle
    val resolved = loc?.invoke("block.${block.id}.text", text) ?: text
    Text(
        // SPEC-401-A R10 — apply `style.text_transform` (uppercase/lowercase).
        // iOS does this via `.textCase` in StyleEngine.swift:194-198. Compose
        // has no equivalent TextStyle modifier so we transform the string.
        text = block.style.applyTransform(resolved),
        style = styleWithAlign,
        // SPEC-070-A J.11 — heading content blocks announce as a heading
        // to screen readers, matching iOS `accessibilityAddTraits(.isHeader)`.
        modifier = Modifier
            .fillMaxWidth()
            .semantics { heading() },
    )
}

@Composable
private fun TextBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val text = block.text ?: ""
    val baseStyle = TextStyle(fontSize = 16.sp, color = Color.Unspecified)
    val effectiveStyle = if (block.style != null) StyleEngine.applyTextStyle(baseStyle, block.style) else baseStyle
    val styleWithAlign = horizontalTextAlign(block.horizontal_align)
        ?.let { effectiveStyle.copy(textAlign = it) } ?: effectiveStyle
    val resolved = loc?.invoke("block.${block.id}.text", text) ?: text
    Text(
        // SPEC-401-A R10 — apply `style.text_transform` (uppercase/lowercase).
        text = block.style.applyTransform(resolved),
        style = styleWithAlign,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ImageBlock(block: ContentBlock) {
    // SPEC-401-A — iOS no-render guard. iOS draws nothing when the
    // url is missing/empty; Android previously fell into NetworkImage
    // with a null URL which still rendered a fixed-height placeholder
    // box. Match iOS behaviour.
    if (block.image_url.isNullOrBlank()) return

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

    // SPEC-401-A — image_fit. iOS toggles `.fit`/`.fill` when fit is
    // "contain"/"fit"; default is `.fill` (Crop). Match the toggle so
    // authored portrait photos are no longer distorted by the fixed
    // height + Crop combo.
    // SPEC-401-A R64 (Lens A P2 #1) — collapse to iOS's two-arm semantics
    // at ContentBlockRendererView.swift:327: `(image_fit == "contain" ||
    // image_fit == "fit") ? .fit : .fill`. Console schema's `"none"`
    // and `"cover"` both fall through to .fill on iOS; Android was
    // mapping them to `ContentScale.None` (intrinsic-pixel) and
    // `ContentScale.Inside`, producing visibly different layouts for
    // the same payload.
    val fit = block.image_fit ?: ""
    val contentScale = when (fit) {
        "contain", "fit" -> androidx.compose.ui.layout.ContentScale.Fit
        else -> androidx.compose.ui.layout.ContentScale.Crop
    }

    // SPEC-401-A R9 — iOS uses `.frame(maxHeight: imgHeight)` at
    // ContentBlockRendererView.swift:338,344. Android previously hard-set
    // `.height(...)` so portrait/landscape assets with `image_fit="contain"`
    // were forced to that exact pixel height instead of shrinking to
    // intrinsic size. `heightIn(max=)` matches iOS behaviour.
    ai.appdna.sdk.core.NetworkImage(
        url = block.image_url,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = (block.height ?: 200.0).dp)
            .then(shapeModifier),
        contentScale = contentScale,
        // SPEC-401-A R57 (Lens A R57 #15, P3) — fall back to "Image" when
        // alt unset, matching iOS accessibilityLabel(block.alt ?? "Image")
        // at ContentBlockRendererView.swift:340. Was passing null — TalkBack
        // treats null contentDescription on an Image as "decorative" so the
        // image is silently skipped.
        contentDescription = block.alt ?: "Image",
    )
}

@Composable
private fun ButtonBlock(block: ContentBlock, onAction: (String) -> Unit, loc: ((String, String) -> String)? = null) {
    val text = block.text ?: "Continue"
    // SPEC-401-A R54 (Lens A R54 #4, P2) — 16→17sp matching iOS
    // .body.weight(.semibold) at ContentBlockRendererView.swift:395-396.
    val baseStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
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
                // SPEC-070-A finalization Phase E — `link` action divergence.
                // iOS InAppBrowser.present(url) does NOT auto-advance —
                // user returns to the same step after closing the browser.
                // Android previously called `onAction("next")` after
                // launching the browser, double-jumping the user away from
                // the step. Removed the auto-advance to match iOS behavior.
                // Browser launch path uses ACTION_VIEW for now (Chrome Custom
                // Tabs would be a polish followup once androidx.browser dep
                // is added).
                block.action_value?.let { url ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        context.startActivity(intent)
                    } catch (_: Exception) {}
                }
                // No auto-advance — mirrors iOS InAppBrowser.present semantics.
            }
            // SPEC-401-A R14 — was `onAction("next")` which rewrote the
            // action before reaching the canonical handleAction switch.
            // R13 added a `"permission" -> onNext(null)` branch in
            // OnboardingActivity (matching iOS OnboardingRenderer.swift
            // :1525-1529), but block-authored permission buttons (the
            // only path the console emits today) never reached it
            // because of this rewrite. Forwarding the original action
            // keeps the iOS-canonical behavior as the single source.
            "permission" -> onAction("permission")
            else -> onAction(action)
        }
    }

    // SPEC-089d §6.5: Pressed style — collect interaction source for scale/opacity
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // SPEC-401-A R26 — match iOS ContentBlockTypes.swift:650 which wraps
    // pressed-style scale/opacity in `.animation(.easeInOut(duration: 0.1),
    // value: isPressed)` — smooth 100ms transition. Android was reading raw
    // Float values directly into graphicsLayer, producing a one-frame snap
    // (visible "pop" on press/release). animateFloatAsState(tween(100))
    // gives the same easeInOut interpolation.
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) (block.pressed_style?.scale ?: 0.97).toFloat() else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "pressedScale",
    )
    val pressedAlpha by animateFloatAsState(
        targetValue = if (isPressed) (block.pressed_style?.opacity ?: 0.9).toFloat() else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "pressedAlpha",
    )
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
            // SPEC-401-A — iOS shows BOTH icon_emoji AND image_url
            // when both are set (ContentBlockRendererView.swift:383-394).
            // Android previously gated image_url on icon_emoji being
            // null/empty, so authored buttons with both lost the image.
            block.icon_emoji?.let { emoji ->
                if (emoji.isNotEmpty()) {
                    Text(emoji, modifier = Modifier.padding(end = 8.dp))
                }
            }
            if (!block.image_url.isNullOrEmpty()) {
                ai.appdna.sdk.core.NetworkImage(
                    url = block.image_url,
                    modifier = Modifier.size(20.dp).padding(end = 8.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            // SPEC-070-A finalization OB-3 — button text color precedence.
            // iOS: when `block.style.color` is set, it WINS over `text_color`
            // (`StyleEngine.swift:148-153` `applyTextStyle` foregroundColor
            // resolves to `s.color ?? .primary` — `.primary` then falls back
            // to the parent button's `text_color` via SwiftUI's foregroundColor
            // inheritance). Compose's contract is the opposite: an explicit
            // `color` parameter overrides `style.color`, so we previously
            // forced `text_color` even when console authored a `style.color`
            // override. Bake the resolved color into `effectiveStyle.color`
            // (priority: block.style.color → block.text_color), and drop the
            // explicit `color` param so style wins.
            val resolvedTextColor = block.style?.color
                ?.let { StyleEngine.parseColor(it) }
                ?: textColor
            Text(
                // SPEC-401-A R63 (Lens A Gap 3 Site A, P2) — apply
                // style.text_transform (uppercase/lowercase) matching iOS
                // ContentBlockRendererView.swift:395-397 `.applyTextStyle(
                // block.style)`. Compose TextStyle has no equivalent;
                // applyTransform helper at StyleEngine.kt:52 wraps the
                // string. R17 covered Heading/Text/Timeline; ButtonBlock
                // was missed.
                text = block.style.applyTransform(displayText),
                style = effectiveStyle.copy(color = resolvedTextColor),
            )
        }
    }

    when (btnVariant) {
        "outline" -> {
            // SPEC-089d §3.18: Outline variant — transparent bg, colored border + text
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).then(pressedModifier),
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
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).then(pressedModifier),
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
                        .defaultMinSize(minHeight = 52.dp)
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
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).then(pressedModifier),
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
                    // SPEC-401-A R44 — theme-adaptive secondary color
                    // (was hardcoded Color.Gray). iOS uses .secondary
                    // (ContentBlockRendererView.swift:459).
                    // SPEC-401-A R52 (Lens A R51 #19, P3) — explicit 15sp matching
                    // iOS .subheadline.weight(.semibold) at
                    // ContentBlockRendererView.swift:457-459.
                    "numbered" -> Text("${index + 1}.", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    // SPEC-401-A \u2014 render `check` marker as Material
                    // CheckCircle icon (mirrors iOS `checkmark.circle.fill`).
                    // Was a plain Unicode glyph at 16sp which looked like
                    // a typo on Android.
                    // SPEC-401-A R6 — match iOS hardcoded #6366F1
                    // indigo (ContentBlockRendererView.swift:460-463).
                    // iOS ignores text_color on the check marker;
                    // Android previously defaulted to #22C55E green
                    // and accepted text_color, so the same authored
                    // payload rendered different colors across
                    // platforms.
                    "check" -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = StyleEngine.parseColor("#6366F1"),
                        modifier = Modifier.size(16.dp),
                    )
                    // SPEC-401-A R44 — theme-adaptive bullet (was Color.Gray).
                    // iOS uses Color.primary.opacity(0.5)
                    // (ContentBlockRendererView.swift:466).
                    else -> Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)),
                    )
                }
                Text(
                    // SPEC-401-A R63 (Lens A Gap 3 Site B, P3) — apply
                    // style.text_transform matching iOS
                    // ContentBlockRendererView.swift:447-448 list item
                    // `.applyTextStyle(block.style)`.
                    text = block.style.applyTransform(loc?.invoke("block.${block.id}.item.$index", item) ?: item),
                    // SPEC-401-A R56→R57 (Lens A R56 #8, P3) — list item base 17sp
                    // matches iOS .body which inherits ambient default at
                    // ContentBlockRendererView.swift:447 list item Text. Was 16sp.
                    style = if (block.style != null) StyleEngine.applyTextStyle(TextStyle(fontSize = 17.sp), block.style) else TextStyle(fontSize = 17.sp),
                )
            }
        }
    }
}

@Composable
private fun DividerBlock(block: ContentBlock) {
    // SPEC-401-A R65 (Lens A P1) — port iOS ContentBlockRendererView.swift
    // :473-478 single `Rectangle().padding(.vertical, divider_margin_y)`.
    // Was emitting 3 children (Spacer/Box/Spacer) into the parent
    // `RenderBlock`'s `Box(modifier = contentModifier) { … }` — Compose
    // Box stacks children at the same z-position (NOT a Column), so the
    // two zero-width Spacers rendered no pixels and the line ended up
    // pinned to the top of an 8dp-tall Box. `divider_margin_y` was
    // functionally dead since written. Use single Box with vertical
    // padding to mirror iOS exactly.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = (block.divider_margin_y ?: 8.0).dp)
            .height((block.divider_thickness ?: 1.0).dp)
            .background(StyleEngine.parseColor(block.divider_color ?: "#E5E7EB")),
    )
}

@Composable
private fun BadgeBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val text = block.badge_text ?: ""
    // SPEC-401-A — read block.badge_corner_radius (defaults to 999 for
    // capsule). iOS FormInputBlockViews.swift:481-490 honours the field
    // similarly; Android was hardcoded to 999, ignoring author overrides.
    val badgeRadius = (block.badge_corner_radius ?: 999.0).dp
    Text(
        text = loc?.invoke("block.${block.id}.badge", text) ?: text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = StyleEngine.parseColor(block.badge_text_color ?: "#FFFFFF"),
        modifier = Modifier
            .background(
                StyleEngine.parseColor(block.badge_bg_color ?: "#6366F1"),
                RoundedCornerShape(badgeRadius),
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

    // SPEC-401-A R56 (Lens A R56 #4, P2) — 4dp inter-row spacing matches iOS
    // VStack(alignment: .leading, spacing: 4) at ContentBlockRendererView.swift
    // :523. Was bare Column → Row + description visually touching.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // SPEC-401-A R60 (Lens C P2 #1) — wrap label+Switch in `toggleable`
        // so TalkBack treats the row as a single switch element matching
        // iOS `Toggle("label", isOn:)` (ContentBlockRendererView.swift:524).
        // Switch becomes presentational (onCheckedChange = null); the Row
        // owns the click + a11y semantics. Without this users had to swipe
        // twice through label then "Switch, On".
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    role = androidx.compose.ui.semantics.Role.Switch,
                    onValueChange = {
                        checked = it
                        toggleValues[block.id] = it
                    },
                ),
        ) {
            val label = block.toggle_label ?: ""
            Text(
                text = loc?.invoke("block.${block.id}.label", label) ?: label,
                modifier = Modifier.weight(1f),
            )
            // SPEC-401-A R9 — match iOS standalone-toggle tint
            // ContentBlockRendererView.swift:525 `.tint(Color(hex: "#6366F1"))`.
            // Compose Switch defaults to Material3 green; without a `colors=`
            // override the same payload renders indigo on iOS and green on
            // Android. Track-thumb pair below mirrors iOS where the entire
            // track + thumb adopts the tint when checked.
            Switch(
                checked = checked,
                // SPEC-401-A R60 (Lens C P2 #1) — null handler so the Row's
                // toggleable owns the click + a11y semantics; Switch is now
                // a presentational visual.
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Color(0xFF6366F1),
                ),
            )
        }
        block.toggle_description?.let {
            // SPEC-401-A R44 — theme-adaptive secondary (was Color.Gray).
            // iOS uses .secondary (ContentBlockRendererView.swift:529).
            Text(text = loc?.invoke("block.${block.id}.description", it) ?: it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
                // SPEC-401-A — iOS canonical field-name parity. Honour
                // unprefixed `autoplay`/`loop`/`muted`/`controls`/
                // `inline_playback` first, fall back to legacy
                // `video_*` prefix for older payloads.
                autoplay = block.autoplay ?: block.video_autoplay,
                loop = block.loop ?: block.video_loop,
                muted = block.muted ?: block.video_muted,
                controls = block.controls ?: true,
                inline_playback = block.inline_playback ?: true,
            )
        )
    } else {
        // Fallback: thumbnail with play icon overlay
        // SPEC-401-A R38 (Lens C #1) — match iOS ContentBlockRendererView.swift:567
        // `.accessibilityLabel(block.alt ?? "Video")` so screen-reader users
        // know this is a video placeholder, not a decorative image.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(effectiveHeight.dp)
                .clip(RoundedCornerShape(effectiveCornerRadius.dp))
                .semantics { contentDescription = block.alt ?: "Video" },
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
                // SPEC-401-A R7 — iOS reads canonical `block.autoplay` /
                // `block.loop` (ContentBlockRendererView.swift:595-597);
                // Android historically only knew the prefixed
                // `lottie_autoplay`/`lottie_loop`. Console writes the
                // canonical names so authored `false` was silently
                // ignored. Honour canonical first, legacy as fallback.
                autoplay = block.autoplay ?: block.lottie_autoplay ?: true,
                loop = block.loop ?: block.lottie_loop ?: true,
                speed = block.lottie_speed ?: 1.0f,
                width = block.lottie_width?.toFloat(),
                // SPEC-401-A R10 — match iOS field-name precedence at
                // ContentBlockRendererView.swift:599. Authored `lottie_height`
                // overrides generic `height`; both fall through to 160 default.
                height = (block.lottie_height ?: block.height ?: 160.0).toFloat(),
                alignment = block.icon_alignment ?: "center",
                play_on_scroll = block.play_on_scroll,
                play_on_tap = block.play_on_tap,
                color_overrides = block.color_overrides,
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
                // SPEC-401-A — honour iOS canonical `artboard`/
                // `state_machine` first, fall back to legacy
                // `rive_*` prefix.
                artboard = block.artboard ?: block.rive_artboard,
                state_machine = block.state_machine ?: block.rive_state_machine,
                // SPEC-401-A R7 — forward canonical `autoplay` so authored
                // `false` actually disables auto-play. iOS reads
                // `block.autoplay` at ContentBlockRendererView.swift:621.
                // Android previously omitted the arg, defaulting to true
                // unconditionally.
                autoplay = block.autoplay ?: true,
                height = (block.height ?: 160.0).toFloat(),
                alignment = block.icon_alignment ?: "center",
                inputs = block.rive_inputs,
                trigger_on_step_complete = block.trigger_on_step_complete,
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
    // SPEC-401-A — explicit `active_index = 0` is a valid first-dot
    // selection. Previously Android auto-rebound to currentStepIndex
    // when the value was 0 (couldn't tell unset from 0); iOS uses
    // nil-coalesce so 0 is honoured. Use null-check to match iOS:
    val activeIndex = block.active_index ?: currentStepIndex
    val activeColor = StyleEngine.parseColor(block.active_color ?: "#6366F1")
    val inactiveColor = StyleEngine.parseColor(block.inactive_color ?: "#D1D5DB")
    val dotSize = (block.dot_size ?: 8.0).dp
    val dotSpacing = (block.dot_spacing ?: 8.0).dp
    val activeDotWidth = block.active_dot_width?.dp

    // SPEC-401-A R45 (Lens A #6) — match iOS PageIndicator alignment
    // resolution: only `block.alignment` is read (no icon_alignment
    // fallback). ContentBlockRendererView.swift:646-652. A block
    // authored with icon_alignment but no alignment laid out wrong
    // on Android.
    val hAlign = when (block.alignment) {
        "left" -> Arrangement.Start
        "right" -> Arrangement.End
        else -> Arrangement.Center
    }

    // SPEC-401-A R8 — TalkBack contentDescription "Page X of Y" for
    // a11y parity with iOS `accessibilityLabel("Page X of Y")` at
    // ContentBlockRendererView.swift:668. Without this the row was
    // silently announced as "container" only.
    val pageOfDesc = "Page ${activeIndex + 1} of $dotCount"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = pageOfDesc },
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
    // SPEC-401-A R45 (Lens A #5) — match iOS social_login default
    // button height 50pt (was 48dp). ContentBlockRendererView.swift:676.
    val buttonHeight = (block.button_height ?: 50.0).dp
    val spacing = (block.spacing ?: 12.0).dp
    val showDivider = block.show_divider ?: false
    val dividerText = block.divider_text ?: "or"

    // SPEC-401-A R13 — match iOS ContentBlockRendererView.swift:684-715
    // `email_login_placement: "below_inputs"`: pull the email provider
    // out of the array, render it FIRST, insert a clear spacer of
    // (email_cta_spacing_below - spacing), then render the remaining
    // providers below. Default `with_providers` keeps author order.
    val placement = block.email_login_placement ?: "with_providers"
    val emailSpacer = (block.email_cta_spacing_below ?: 16.0).dp
    val (topGroup, bottomGroup) = run {
        if (placement == "below_inputs") {
            val emailIdx = providers.indexOfFirst { it.type == "email" }
            if (emailIdx >= 0) {
                val list = providers.toMutableList()
                val email = list.removeAt(emailIdx)
                Pair(listOf(email), list.toList())
            } else Pair(providers, emptyList())
        } else Pair(providers, emptyList())
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        // Local helper closes over block/loc/buttonStyle/etc to keep
        // the per-provider rendering identical in both groups.
        val renderProvider: @androidx.compose.runtime.Composable (Int, SocialProvider) -> Unit = renderer@ { index, provider ->
            val label = provider.label ?: when (provider.type) {
                "apple" -> "Continue with Apple"
                "google" -> "Continue with Google"
                "email" -> "Continue with Email"
                "facebook" -> "Continue with Facebook"
                "github" -> "Continue with GitHub"
                else -> "Continue with ${provider.type.replaceFirstChar { it.uppercase() }}"
            }
            val displayLabel = loc?.invoke("block.${block.id}.provider.$index", label) ?: label

            // SPEC-070-A finalization OB-2 — per-provider color overrides.
            // Brand-default Triple comes first; provider.bg_color / text_color
            // / border_color override piecewise when authored. Mirrors iOS
            // ContentBlockRendererView.swift:724-771 which resolves
            // `provider.bg_color ?? socialLoginBgColor(provider.type)` for
            // each color independently.
            val (defaultBg, defaultText, defaultBorder) = when (provider.type) {
                "apple" -> Triple(Color(0xFF000000), Color.White, Color(0xFF000000))
                // SPEC-401-A R51 (Lens A #7, P1) — Google brand bg `#4285F4`
                // matches iOS ContentBlockRendererView.swift:828-836 (iOS comment
                // at :723 explicitly states "Google=#4285F4"). Was rendering as
                // a white outlined button on Android while iOS shipped the blue
                // filled button — same payload, two different visuals.
                "google" -> Triple(Color(0xFF4285F4), Color.White, Color(0xFF4285F4))
                "facebook" -> Triple(Color(0xFF1877F2), Color.White, Color(0xFF1877F2))
                "github" -> Triple(Color(0xFF24292F), Color.White, Color(0xFF24292F))
                "email" -> Triple(
                    StyleEngine.parseColor(block.accent_color ?: block.bg_color ?: "#6366F1"),
                    Color.White,
                    StyleEngine.parseColor(block.accent_color ?: block.bg_color ?: "#6366F1"),
                )
                else -> Triple(Color(0xFF6366F1), Color.White, Color(0xFF6366F1))
            }
            val bgColor = provider.bg_color?.let { StyleEngine.parseColor(it) } ?: defaultBg
            val textColor = provider.text_color?.let { StyleEngine.parseColor(it) } ?: defaultText
            val borderColor = provider.border_color?.let { StyleEngine.parseColor(it) } ?: defaultBorder
            // OB-2 — per-provider corner_radius + border_width overrides.
            val providerCorner = (provider.corner_radius ?: block.button_corner_radius?.toFloat() ?: 12f).dp
            // SPEC-401-A R27 — match iOS ContentBlockRendererView.swift:738-741
            // outlined-button default 1.5dp stroke; non-outlined defaults 0
            // (border invisible on filled/minimal anyway). Was hardcoded 1f.
            val providerBorderWidth = (provider.border_width ?: if (buttonStyle == "outlined") 1.5f else 0f).dp

            val providerIcon = when (provider.type) {
                // SPEC-401-A R11 \u2014 `\uF8FF` is in Apple's Private-Use range
                // and renders as a tofu box on every Android device (NotoSans
                // has no Apple glyph). iOS uses `Image(systemName:"applelogo")`
                // SF Symbol which Android cannot replicate without an asset
                // import. Pragmatic fallback: red apple emoji `\uD83C\uDF4E` \u2014
                // universally renders on Android, conveys "Apple" in the
                // sign-in row even if it's not the official logo. Replace
                // with a bundled Apple Sign-In branded vector drawable in a
                // follow-up if HIG-strict branding is required.
                "apple" -> "\uD83C\uDF4E"
                "google" -> "G"
                "email" -> "\u2709"
                "facebook" -> "f"
                "github" -> "\u2B24"
                else -> ""
            }
            // SPEC-070-A finalization OB-2 audit-1 CRIT-2 \u2014 icon_style was a
            // dead field. Mirrors iOS ContentBlockRendererView.swift:778-814:
            // "monochrome_light" \u2192 force white, "monochrome_dark" \u2192 force
            // black, default \u2192 use provider-native or button textColor.
            val monoIconColor: Color? = when (provider.icon_style) {
                "monochrome_light" -> Color.White
                "monochrome_dark" -> Color.Black
                else -> null
            }
            // SPEC-401-A R59 (Lens A P1 #2) \u2014 match iOS per-provider icon
            // styling (ContentBlockRendererView.swift:790-805).
            //  * google "G"  \u2192 18sp Bold (iOS uses .system(size:18, weight:.bold,
            //    design:.rounded); Android Compose has no built-in rounded
            //    sans-serif so we keep FontFamily.SansSerif but match size+weight)
            //  * facebook "f" \u2192 20sp Bold + Facebook-brand-blue (#1877F2) when
            //    no monochrome icon_style override (was 18sp regular monochrome,
            //    losing brand identity in light buttons)
            //  * apple emoji / email envelope / github circle keep 18sp regular
            val providerIconFontSize = when (provider.type) {
                "facebook" -> 20.sp
                else -> 18.sp
            }
            val providerIconFontWeight = when (provider.type) {
                "google", "facebook" -> FontWeight.Bold
                else -> FontWeight.Normal
            }
            val providerIconColor: Color = when {
                monoIconColor != null -> monoIconColor
                provider.type == "facebook" -> Color(0xFF1877F2)
                else -> textColor
            }

            // SPEC-070-A C.1 — dual-emit for the `email` provider (parity with
            // iOS v1.0.60 `ContentBlockRendererView.swift:743-754`). The email
            // provider in a social_login block is not actually OAuth — emit
            // `email_login:email` so hosts can branch their auth handler
            // cleanly. Also dual-emit the legacy `social_login:email` action
            // this release so existing handlers that switch on
            // `social_login` + value=="email" keep working. Legacy emit
            // removed in v1.1.0 alongside iOS.
            // Representation: colon-encoded `action:value` (chosen over
            // widening callback to (String, String?) to keep ContentBlockRenderer
            // call sites stable). Documented here so 070-B/C wrappers mirror.
            val socialClick: () -> Unit = {
                if (provider.type == "email") {
                    onAction("email_login:email")
                    onAction("social_login:email") // deprecated; remove in v1.1.0
                } else {
                    onAction("social_login:${provider.type}")
                }
            }
            // SPEC-070-A finalization OB-2 audit follow-up — apply per-provider
            // colors + provider-level corner/border-width across ALL three
            // button styles (filled, outlined, minimal). Audit round 1 caught
            // that only the filled branch read the overrides; outlined+minimal
            // ignored authored colors entirely.
            when (buttonStyle) {
                "outlined" -> {
                    OutlinedButton(
                        onClick = socialClick,
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        shape = RoundedCornerShape(providerCorner),
                        border = androidx.compose.foundation.BorderStroke(providerBorderWidth, borderColor),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = textColor,
                        ),
                    ) {
                        Text(providerIcon, fontSize = providerIconFontSize, fontWeight = providerIconFontWeight, modifier = Modifier.padding(end = 8.dp), color = providerIconColor)
                        // SPEC-401-A R56 (Lens A R56 #2, P1) — explicit 17sp matches
                        // iOS .body.weight(.semibold) (ContentBlockRendererView.swift:759).
                        // Material Button content defaults to labelLarge=14sp.
                        Text(displayLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                    }
                }
                "minimal" -> {
                    TextButton(
                        onClick = socialClick,
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        shape = RoundedCornerShape(providerCorner),
                        colors = ButtonDefaults.textButtonColors(contentColor = textColor),
                    ) {
                        Text(providerIcon, fontSize = providerIconFontSize, fontWeight = providerIconFontWeight, modifier = Modifier.padding(end = 8.dp), color = providerIconColor)
                        // SPEC-401-A R56 (Lens A R56 #2, P1) — explicit 17sp matches
                        // iOS .body.weight(.semibold) (ContentBlockRendererView.swift:759).
                        // Material Button content defaults to labelLarge=14sp.
                        Text(displayLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                    }
                }
                else -> { // filled
                    Button(
                        onClick = socialClick,
                        modifier = Modifier.fillMaxWidth().height(buttonHeight),
                        // SPEC-070-A finalization OB-2 audit-1 CRIT-1 — was
                        // `RoundedCornerShape(cornerRadius)`, dropping the
                        // per-provider `corner_radius` override. Filled is
                        // the default style (block.button_style ?: "filled")
                        // so this affected every social_login block authored
                        // without explicit outlined/minimal opt-in.
                        shape = RoundedCornerShape(providerCorner),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = bgColor,
                            contentColor = textColor,
                        ),
                    ) {
                        Text(providerIcon, fontSize = providerIconFontSize, fontWeight = providerIconFontWeight, modifier = Modifier.padding(end = 8.dp), color = providerIconColor)
                        // SPEC-401-A R56 (Lens A R56 #2, P1) — explicit 17sp matches
                        // iOS .body.weight(.semibold) (ContentBlockRendererView.swift:759).
                        // Material Button content defaults to labelLarge=14sp.
                        Text(displayLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = textColor)
                    }
                }
            }

            // SPEC-401-A — divider moved out of the per-provider loop.
            // iOS renders the "or" divider ONCE at the bottom of the
            // social-login block (ContentBlockRendererView.swift:709-717).
            // The old per-provider divider gave a column of repeating
            // "or" rows which doesn't exist on iOS at all.
        }

        // SPEC-401-A R13 — render top group (email-first when
        // `below_inputs` placement, full author-order list otherwise),
        // then optional spacer, then the remaining providers below.
        topGroup.forEachIndexed { idx, provider -> renderProvider(idx, provider) }
        if (placement == "below_inputs" && bottomGroup.isNotEmpty()) {
            // Column already inserts `spacing` between adjacent items
            // via verticalArrangement, so the additional gap to add is
            // `email_cta_spacing_below - spacing` (matches iOS
            // ContentBlockRendererView.swift:707).
            Spacer(modifier = Modifier.height(maxOf(0.dp, emailSpacer - spacing)))
        }
        bottomGroup.forEachIndexed { idx, provider ->
            renderProvider(topGroup.size + idx, provider)
        }

        // SPEC-401-A — single bottom divider gated on show_divider,
        // matching iOS placement.
        if (showDivider) {
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
                    // SPEC-401-A R55 (Lens A R55 #1, P3) — 14→15sp matching iOS
                    // ContentBlockRendererView.swift:713 .subheadline (~15pt).
                    fontSize = 15.sp,
                    // SPEC-401-A R44 — theme-adaptive secondary (was Color.Gray).
                    // iOS .secondary (ContentBlockRendererView.swift:714).
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
    // SPEC-401-A R32 — match iOS ContentBlockStandaloneViews.swift:112
    // default font_size 28 (was 24).
    val fontSize = (block.font_size ?: 28.0).sp

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

    // On expire: hide (only when explicitly requested).
    if (expired && block.on_expire_action == "hide") return

    // SPEC-401-A R3 — on expire show expired_text by default. iOS
    // always renders `expired_text` once the timer hits zero
    // (ContentBlockStandaloneViews.swift:148-165) regardless of
    // on_expire_action, except for hide/auto_advance. Android
    // previously only rendered it when on_expire_action ==
    // "show_expired_text", so the default-unset case fell through
    // to the digital "00:00" timer indefinitely.
    // SPEC-401-A R32 — match iOS ContentBlockStandaloneViews.swift:112
    // default font_size 28 (was 24 on Android, 4pt smaller for any
    // countdown_timer authored without explicit font_size — most paywall
    // promo timers).
    if (expired && block.on_expire_action != "auto_advance") {
        // SPEC-401-A R23 — match iOS ContentBlockStandaloneViews.swift
        // :154-160. iOS forces `.font(.subheadline.weight(.semibold))` +
        // `.foregroundColor(.secondary)` on the expiry text regardless of
        // the timer's display style; default copy is "Time's up!" not
        // "Expired". Android was reusing the timer's giant `fontSize` +
        // `textColor` and the wrong default string — visually broken on
        // any timer larger than ~15sp (most paywall timers run 32-48sp).
        Text(
            text = block.expired_text ?: "Time's up!",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
                timeUnits.forEach { (value, unitLabel, _) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 8.dp),
                        // SPEC-401-A R46 (Lens A #5) — value→label 4pt gap
                        // matching iOS VStack(spacing: 4)
                        // (ContentBlockStandaloneViews.swift:138).
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = value.toString().padStart(2, '0'),
                            fontSize = fontSize,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            // SPEC-401-A R3 — monospaced digits so the
                            // counter doesn't visibly shift width per
                            // second. iOS uses .system(.monospaced).
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                        Text(
                            text = unitLabel,
                            // SPEC-401-A R48 (Lens C #6) — countdown unit-label
                            // 10→11sp matching iOS .footnote (~11pt).
                            fontSize = 11.sp,
                            // SPEC-401-A R3 — accent_color when authored,
                            // mirrors iOS unit-label colour (was hardcoded
                            // textColor.alpha 0.6 — accent ignored).
                            color = accentColor,
                        )
                    }
                    // SPEC-401-A R3 — drop the inter-column ":" separator.
                    // iOS doesn't render any separator between time-unit
                    // columns; Android's colon broke the visual rhythm.
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
    // SPEC-401-A R3 — iOS canonical field names first, fall back to
    // legacy Android names so old + new payloads both render.
    val activeColor = StyleEngine.parseColor(
        block.filled_color ?: block.active_rating_color ?: block.active_color ?: "#FBBF24"
    )
    val inactiveColor = StyleEngine.parseColor(
        block.empty_color ?: block.inactive_rating_color ?: block.inactive_color ?: "#D1D5DB"
    )
    val fieldId = block.field_id ?: block.id
    val allowHalf = block.allow_half == true

    // SPEC-401-A R3 — `default_rating` (iOS canon) / `default_value`
    // (legacy) honoured.
    val initial = (block.default_rating ?: block.default_value
        ?: (inputValues[fieldId] as? Number)?.toDouble()) ?: 0.0
    var selectedRating by remember { mutableStateOf(initial) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Optional label — iOS reads `rating_label ?? label`.
        // SPEC-401-A R11 — match iOS `Text(label).foregroundColor(.primary)`
        // (ContentBlockStandaloneViews.swift:21-25) — theme-aware text
        // colour, NOT a hardcoded gray. Previous Color.Gray made the
        // label read as dim secondary text on Android while iOS rendered
        // it as primary body text.
        // SPEC-401-A R61 (Lens A N7, P3) — drop `?: block.label` to match
        // iOS canonical ContentBlockStandaloneViews.swift:21 which only
        // reads `block.rating_label`. Same family as backlog #3.
        block.rating_label?.let { label ->
            val displayLabel = loc?.invoke("block.${block.id}.label", label) ?: label
            Text(
                text = displayLabel,
                // SPEC-401-A R56→R57 (Lens A R56 #6, P3) — 15sp matches iOS
                // .subheadline (ContentBlockStandaloneViews.swift:23). Was 14sp.
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            // SPEC-401-A R37 (Lens C #2) — combine per-star semantics into
            // one a11y element + announce current rating as state.
            // iOS ContentBlockStandaloneViews.swift:47,54-55 wraps the
            // HStack with `accessibilityElement(children: .combine)` +
            // `accessibilityValue("X of Y stars")`. Without this TalkBack
            // reads "Star 1, Star 2, ... Star 5" but never the chosen value.
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "Rating"
                // SPEC-401-A R58 (Lens A R58 P3 #4) — match iOS
                // ContentBlockStandaloneViews.swift:55 `Int(selectedRating)`
                // when not allow_half ("3 of 5"), `String(format: "%.1f", …)`
                // when allow_half ("2.5 of 5"). Was `Double.toString` which
                // produced "3.0 of 5 stars" for every full-star rating.
                stateDescription = if (allowHalf) {
                    "${"%.1f".format(selectedRating)} of $maxStars stars"
                } else {
                    "${selectedRating.toInt()} of $maxStars stars"
                }
            },
        ) {
            for (i in 1..maxStars) {
                // SPEC-401-A R3 — half-star rendering when allow_half:
                // value >= i fills, value >= i-0.5 half-fills, else empty.
                val full = selectedRating >= i
                val half = !full && allowHalf && selectedRating >= i - 0.5
                Icon(
                    imageVector = when {
                        full -> Icons.Filled.Star
                        half -> androidx.compose.material.icons.Icons.Filled.StarHalf
                        else -> Icons.Outlined.Star
                    },
                    contentDescription = "Star $i",
                    tint = if (full || half) activeColor else inactiveColor,
                    modifier = Modifier
                        .size(starSize)
                        .clickable {
                            // Toggle half ↔ full when allow_half on
                            // a re-tap of the currently-filled star.
                            val tapped = i.toDouble()
                            selectedRating = when {
                                allowHalf && full && (selectedRating - tapped).let { it >= 0 && it < 0.5 } -> tapped - 0.5
                                else -> tapped
                            }
                            inputValues[fieldId] = selectedRating
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
    // SPEC-401-A R4 — iOS canonical `markdown_content` first, then
    // `content`, then plain `text`. Console writes markdown_content.
    // SPEC-401-A R61 (Lens A N4, P3) — drop `?: block.content` to match iOS
    // canonical ContentBlockRendererView.swift:932 `block.markdown_content ??
    // block.text`. iOS DTO has no `content` field; payloads using only
    // `block.content` rendered on Android, empty on iOS. Aligning to iOS.
    val rawContent = block.markdown_content ?: block.text ?: ""
    val content = loc?.invoke("block.${block.id}.content", rawContent) ?: rawContent
    val linkColor = StyleEngine.parseColor(block.link_color ?: "#6366F1")
    val context = LocalContext.current

    // SPEC-401-A — `legal` variant defaults: caption font + centred
    // alignment + secondary colour. Mirrors iOS
    // ContentBlockRendererView.swift:932-980.
    // SPEC-401-A R10 — `legal` secondary color. iOS uses
    // `.foregroundColor(.secondary)` (theme-aware system label-secondary,
    // ~UIColor.secondaryLabel). Compose's `Color.Unspecified.copy(alpha=…)`
    // is a sentinel-on-sentinel hack that produces an unreliable color in
    // some Compose builds. Use MaterialTheme.colorScheme.onBackground at
    // 60% alpha so the same payload renders dim grey on both platforms,
    // and adapts to dark mode through the Material theme.
    val isLegal = block.rich_text_variant == "legal"
    val secondaryColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
    val legalDefault = if (isLegal) {
        TextStyle(
            fontSize = 12.sp,
            color = secondaryColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    } else {
        // SPEC-401-A R56 (Lens A R56 #5, P2) — non-legal default 17sp matches
        // iOS .body (ContentBlockRendererView.swift:965). Was 16sp — 1pt narrower
        // on every rich_text block authored without explicit base_style.font_size.
        TextStyle(fontSize = 17.sp, color = Color.Unspecified)
    }

    // Apply base_style if present
    val baseTextStyle = if (block.base_style != null) {
        StyleEngine.applyTextStyle(legalDefault, block.base_style)
    } else if (block.style != null) {
        StyleEngine.applyTextStyle(legalDefault, block.style)
    } else {
        legalDefault
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
    // Regex patterns (order matters: bold before italic to avoid ambiguity).
    // SPEC-401-A — `++underline++` is the iOS custom marker mirrored
    // here so the same Markdown source renders identically.
    val boldRegex = Regex("""\*\*(.+?)\*\*""")
    val italicRegex = Regex("""\*(.+?)\*""")
    val underlineRegex = Regex("""\+\+(.+?)\+\+""")
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
            val underlineMatch = underlineRegex.find(remaining)
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
                underlineMatch?.let { it to "underline" },
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
                "underline" -> {
                    withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline)) {
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
    // SPEC-401-A — iOS field-name parity. iOS uses `progress_variant` /
    // `total_segments` / `filled_segments` / `bar_color` / `bar_height`.
    // Older Android-only payloads still set `variant` / `segment_count` /
    // `active_segments` / `fill_color` / `height` so honour both.
    val variant = block.progress_variant ?: block.variant ?: "continuous"
    // AC-021: Auto-bind to step index when no explicit values set
    val segmentCount = block.total_segments ?: block.segment_count ?: totalSteps
    // SPEC-401-A R10 — match iOS auto-bind exactly. iOS only checks
    // `filled_segments`; when null it always falls through to
    // `currentStepIndex+1`. Android previously also short-circuited to
    // `1` whenever `bar_color`/`fill_color` was authored, which froze
    // the bar at 1/total even for multi-step flows where iOS would
    // advance. ContentBlockRendererView.swift:1051-1056.
    val explicitFilled = block.filled_segments ?: block.active_segments
    val activeSegments = explicitFilled ?: (currentStepIndex + 1)
    val fillColor = StyleEngine.parseColor(block.bar_color ?: block.fill_color ?: "#6366F1")
    val trackColor = StyleEngine.parseColor(block.track_color ?: "#E5E7EB")
    val barHeight = (block.bar_height ?: block.height ?: 6.0).dp
    val cornerRadius = (block.corner_radius ?: 3.0).dp
    val segmentGap = (block.segment_gap ?: 4.0).dp
    val showLabel = block.show_label ?: false

    Column(modifier = Modifier.fillMaxWidth()) {
        // Optional label
        if (showLabel && segmentCount > 0) {
            val labelText = "Step $activeSegments of $segmentCount"
            val labelStyle = if (block.label_style != null) {
                // SPEC-401-A R44 — theme-adaptive secondary base (was Color.Gray).
                StyleEngine.applyTextStyle(TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)), block.label_style)
            } else {
                // SPEC-401-A R44 — theme-adaptive secondary fallback (was Color.Gray).
                TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Text(
                text = loc?.invoke("block.${block.id}.label", labelText) ?: labelText,
                style = labelStyle,
                // SPEC-401-A R47 (Lens C #7) — iOS VStack(spacing: 8)
                // (ContentBlockRendererView.swift:1063). Was 4dp.
                modifier = Modifier.padding(bottom = 8.dp),
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
    // SPEC-401-A R16 — match iOS ContentBlockRendererView.swift connector
    // min-height (compact 20pt / regular 32pt). Android was 12/24, making
    // timelines look cramped side-by-side.
    val itemSpacing = if (isCompact) 20.dp else 32.dp

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
                // SPEC-401-A R16 — match iOS ContentBlockRendererView.swift:878-887
                // dimensions: 28pt circle + 12pt checkmark + 10pt current dot
                // (Android was 24/14/8 — visibly tighter side-by-side).
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(28.dp),
                ) {
                    // Status circle
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.status == "completed") {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Completed",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp),
                            )
                        } else if (item.status == "current") {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
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
                // SPEC-401-A R47 (Lens C #5+#6) — title↔subtitle 4dp
                // (iOS VStack(spacing: 4)).
                // SPEC-401-A R52 (Lens A R51 #14, P2) — drop inner bottom
                // padding. Outer Row already supplies `itemSpacing` (20/32dp);
                // the inner duplicate stacked to 28/44dp total inter-row gap
                // vs iOS ~16/20pt, making timeline rows visibly over-spaced.
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val titleText = loc?.invoke("block.${block.id}.item.$index.title", item.title) ?: item.title
                    // SPEC-401-A R16 — match iOS ContentBlockRendererView.swift
                    // :902 — `.subheadline.weight(.semibold)` for ALL items;
                    // status only varies foregroundColor. Android was flipping
                    // to FontWeight.Normal for non-current items, dropping the
                    // semibold weight on completed/upcoming rows.
                    val titleBaseStyle = TextStyle(
                        // SPEC-401-A R18 — match iOS Dynamic Type defaults
                        // (`.subheadline` = 15pt at default Larger Text). Was
                        // 16.sp which diverged from system font scaling.
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        // SPEC-401-A R44 — theme-adaptive upcoming color
                        // (was Color.Gray). iOS uses .secondary on upcoming.
                        color = if (item.status == "upcoming") MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else Color.Unspecified,
                    )
                    val titleStyle = if (block.title_style != null) {
                        StyleEngine.applyTextStyle(titleBaseStyle, block.title_style)
                    } else titleBaseStyle

                    // SPEC-401-A R17 — match iOS ContentBlockRendererView.swift
                    // :903 + :909 chained `.applyTextStyle(block.title_style)` /
                    // `.applyTextStyle(block.subtitle_style)` which apply
                    // `.textCase(.uppercase|.lowercase)` from `text_transform`.
                    // Compose TextStyle has no equivalent of textCase, so we
                    // route the raw string through `applyTransform()` (same
                    // pattern HeadingBlock + TextBlock use).
                    Text(text = block.title_style.applyTransform(titleText), style = titleStyle)

                    item.subtitle?.let { subtitle ->
                        val subtitleText = loc?.invoke("block.${block.id}.item.$index.subtitle", subtitle) ?: subtitle
                        // SPEC-401-A R18 — match iOS `.caption` = 12pt default.
                        // SPEC-401-A R44 — theme-adaptive subtitle (was Color.Gray).
                        val subtitleBaseStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        val subtitleEffective = if (block.subtitle_style != null) {
                            StyleEngine.applyTextStyle(subtitleBaseStyle, block.subtitle_style)
                        } else subtitleBaseStyle

                        Text(text = block.subtitle_style.applyTransform(subtitleText), style = subtitleEffective)
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
    // SPEC-401-A R3 — iOS canonical field name `loading_variant`
    // (ContentBlockTypes.swift:1019); Android historically only read
    // the generic `variant` so console-authored loading_variant was
    // silently dropped. Honour both.
    val variant = block.loading_variant ?: block.variant ?: "checklist"
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
            // SPEC-401-A R9 — match iOS items-mode auto_advance grace
            // delay at ContentBlockStandaloneViews.swift:347-352 — wait
            // 500ms after the final checklist item completes so users
            // see the completed state before the step advances.
            // (iOS timer-only mode at line 317 fires instantly; Android
            //  ProgressBar block path matches that.)
            if (items.isNotEmpty()) {
                kotlinx.coroutines.delay(500)
            }
            onAction("next")
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // SPEC-401-A R4 — `orbiting_icons` variant falls back to
        // circular indicator until the radial-icon renderer is
        // ported. Without this branch Android dropped through to
        // the checklist default, which made the loading state
        // inconsistent with iOS.
        when (if (variant == "orbiting_icons") "circular" else variant) {
            "circular" -> {
                // SPEC-401-A R19 — match iOS ContentBlockStandaloneViews
                // .swift:228-248. iOS rotates the indicator continuously via
                // `spinAngle = 360` repeatForever AND displays the current
                // loading item's label below the circle. Android previously
                // rendered a static circle with no caption.
                val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "loading_spin")
                val spinAngle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(
                            durationMillis = 1500,
                            easing = androidx.compose.animation.core.LinearEasing,
                        ),
                    ),
                    label = "loading_spin_angle",
                )
                Box(contentAlignment = Alignment.Center) {
                    // SPEC-401-A R51 (Lens C #2, P2) — guarantee a visible 5%
                    // arc even at 0% progress. Mirrors iOS
                    // ContentBlockStandaloneViews.swift:226
                    // `Circle().trim(from: 0, to: max(0.05, overallProgress))`.
                    // Without this the rotation animation is invisible (zero-
                    // length arc) for the first ~600ms of every step before
                    // any item ticks complete — user sees a static ring.
                    CircularProgressIndicator(
                        progress = overallProgress.coerceAtLeast(0.05f),
                        modifier = Modifier
                            .size(80.dp)
                            .graphicsLayer { rotationZ = spinAngle },
                        color = progressColor,
                        // SPEC-401-A R46 (Lens A #6) — iOS lineWidth: 5 (was 6dp).
                        // ContentBlockStandaloneViews.swift:224,227.
                        trackColor = progressColor.copy(alpha = 0.2f),
                        strokeWidth = 5.dp,
                    )
                    if (showPercentage) {
                        Text(
                            text = "${(overallProgress * 100).toInt()}%",
                            // SPEC-401-A R19 — match iOS line 232:
                            // `.font(.system(size: 16, weight: .bold))
                            // .foregroundColor(progressCol)`. Was 18.sp +
                            // text_color; now 16.sp + progress fill color.
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = progressColor,
                        )
                    }
                }
                // SPEC-401-A R19 — current loading item caption matching iOS
                // lines 240-248. Renders the item.label of the in-progress
                // item (clamped to last item when finished).
                if (items.isNotEmpty()) {
                    val currentIdx = completedCount.coerceAtMost(items.size - 1)
                    items.getOrNull(currentIdx)?.label?.takeIf { it.isNotBlank() }?.let { label ->
                        // SPEC-401-A R47 (Lens C #3) — fall back to theme-adaptive
                        // .secondary when block.text_color unset (iOS line 244).
                        Text(
                            text = label,
                            // SPEC-401-A R56→R57 (Lens A R56 #7, P3) — 15sp matches
                            // iOS .subheadline (ContentBlockStandaloneViews.swift:243).
                            fontSize = 15.sp,
                            color = if (block.text_color == null)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            else textColor.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            "linear" -> {
                // SPEC-401-A R22 — Material3 1.2 lambda form (see
                // OnboardingActivity.kt:1093 for the same pre-emptive switch).
                LinearProgressIndicator(
                    progress = { overallProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        // SPEC-401-A R19 — match iOS lines 259+262:
                        // `RoundedRectangle(cornerRadius: 4)` filling
                        // `height: 8`. Was 6dp/3dp on Android (visibly
                        // thinner bar).
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = progressColor,
                    // SPEC-401-A R46 (Lens A #9) — neutral track (was tinted
                    // copy of progressColor at 0.2). iOS uses Color.gray.opacity(0.2)
                    // (ContentBlockStandaloneViews.swift:258).
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
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
                            // SPEC-401-A R46 (Lens A #7) — match iOS state
                            // language at ContentBlockStandaloneViews.swift:275-288:
                            // completed = checkmark.circle.fill (check inside
                            // filled circle); pending = hollow Circle().stroke.
                            if (isCompleted) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
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
                                // Hollow ring matching iOS .stroke pending.
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .border(
                                            width = 1.5.dp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                            shape = CircleShape,
                                        ),
                                )
                            }
                        }

                        Text(
                            text = item.label,
                            fontSize = 15.sp,
                            // SPEC-401-A R46 (Lens A #8) — match iOS color logic
                            // at ContentBlockStandaloneViews.swift:294-295:
                            // current and completed both .primary; pending
                            // .secondary. No weight switch (iOS .font(.subheadline)
                            // is regular always; was SemiBold-on-current drift).
                            // SPEC-401-A R47 (Lens C #1) — when block.text_color
                            // unset, fall back to theme-adaptive onSurface (was
                            // raw default #000000 invisible-on-dark).
                            color = when {
                                isCompleted || isCurrent ->
                                    if (block.text_color == null)
                                        MaterialTheme.colorScheme.onSurface
                                    else textColor
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            },
                            // SPEC-401-A R47 (Lens C #2) — drop SemiBold-on-current
                            // weight switch; iOS .font(.subheadline) regular always.
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
    // SPEC-401-A R19 — match iOS ContentBlockStandaloneViews.swift:679
    // which only checks `gauge_value ?? progress_value ?? 0`. Android also
    // fell through to `block.default_value` which iOS ignores — same JSON
    // `{ "default_value": 75 }` rendered as 0 on iOS, 75 on Android.
    val value = (block.gauge_value ?: block.progress_value ?: 0.0).toFloat()
    val minVal = (block.min_value ?: 0.0).toFloat()
    // SPEC-401-A R11 — full port of iOS `CircularGaugeBlockView`
    // (ContentBlockStandaloneViews.swift:673-907). Variants: `arc`
    // (default 200), `radial` (full ring), `speedometer` (220° sweep,
    // min 240). Renders min/max labels, animated needle, gradient
    // fill via Brush.sweepGradient, and per-anchor percentage
    // (above/center/below/none).
    val maxVal = (block.max_value ?: block.max_gauge_value ?: 100.0).toFloat()
    val targetProgress = if ((maxVal - minVal) > 0f) ((value - minVal) / (maxVal - minVal)).coerceIn(0f, 1f) else 0f
    val variant = block.gauge_variant ?: "arc"
    val defaultSize = if (variant == "speedometer") 280.0 else 200.0
    val rawSize = block.height ?: defaultSize
    val sizePx = if (variant == "speedometer") maxOf(rawSize, 240.0) else rawSize
    val size = sizePx.dp
    val strokeW = (block.stroke_width ?: 14.0).toFloat()
    // SPEC-401-A R19 — match iOS line 690 fallback chain `bar_color ??
    // active_color ?? "#6366F1"`. Android also honored `fill_color` which
    // iOS doesn't — console-published `fill_color` rendered only on
    // Android.
    val fillColor = StyleEngine.parseColor(block.bar_color ?: block.active_color ?: "#6366F1")
    val trackColor = StyleEngine.parseColor(block.track_color ?: "#E5E7EB")
    val labelColor = StyleEngine.parseColor(block.label_color ?: block.text_color ?: "#000000")
    val labelFontSize = (block.label_font_size ?: block.font_size ?: 24.0).sp
    val shouldAnimate = block.animate ?: true
    val animDurationMs = block.animation_duration_ms ?: 800
    val showPct = block.show_percentage ?: false
    val pctLocation = block.percentage_location ?: "below"

    // SPEC-401-A R72 (Lens A P1) — gauge gradient_start_color +
    // gradient_end_color live INSIDE field_config, NOT at the top-level
    // ContentBlock. iOS ContentBlockStandaloneViews.swift:703-705 reads
    // `block.field_config?["gradient_start_color"]`. Console editor at
    // `src/.../StepContentEditor.tsx:3814-3815` writes to
    // `field_config.gradient_start_color`. Android was reading the
    // top-level field which the parser never populates from field_config
    // — every console-published gauge gradient rendered as a solid color
    // on Android while sweeping correctly on iOS. Read field_config first,
    // fall back to top-level for back-compat.
    val gradStartHex = (block.field_config?.get("gradient_start_color") as? String)
        ?: block.gradient_start_color
    val gradEndHex = (block.field_config?.get("gradient_end_color") as? String)
        ?: block.gradient_end_color
    val useGradient = !gradStartHex.isNullOrEmpty() && !gradEndHex.isNullOrEmpty()
    val gradColors: List<Color> = if (useGradient) {
        listOf(StyleEngine.parseColor(gradStartHex!!), StyleEngine.parseColor(gradEndHex!!))
    } else {
        listOf(fillColor, fillColor)
    }

    // Needle / min-max styling (speedometer)
    val needleCol = StyleEngine.parseColor(block.arrow_color ?: block.label_color ?: "#1F2937")
    val needleW = (block.arrow_stroke_width ?: 3.0).toFloat()
    val minLabel = block.min_label ?: "${minVal.toInt()}"
    val maxLabel = block.max_label ?: "${maxVal.toInt()}"
    val minMaxFontSz = (block.min_max_font_size ?: 13.0).sp
    val minMaxCol = StyleEngine.parseColor(block.min_max_color ?: block.label_color ?: "#000000")

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = if (shouldAnimate) tween(durationMillis = animDurationMs) else tween(0),
        label = "gauge_progress",
    )

    val hasCenterText = !block.text.isNullOrEmpty()
    val pctExplicitElsewhere = pctLocation == "above" || pctLocation == "below"
    val shouldShowCenter = pctLocation == "center"
        || hasCenterText
        || (showPct && !pctExplicitElsewhere)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (showPct && pctLocation == "above") {
            Text(
                text = "${(animatedProgress * 100).roundToInt()}%",
                fontSize = labelFontSize,
                fontWeight = FontWeight.Bold,
                color = labelColor,
            )
        }

        when (variant) {
            "speedometer" -> {
                // 220° symmetric sweep around top (12 o'clock).
                val sweepDeg = 220.0
                val endpointRad = Math.toRadians(sweepDeg / 2.0)
                val endpointSinX = kotlin.math.sin(endpointRad).toFloat()  // ≈ 0.94
                val endpointCosY = kotlin.math.abs(kotlin.math.cos(endpointRad)).toFloat() // ≈ 0.34
                val canvasH = (sizePx * 0.66).dp + minMaxFontSz.value.dp + 32.dp
                Box(
                    modifier = Modifier
                        .size(width = size, height = canvasH)
                        .padding(strokeW.dp / 2),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val cx = this.size.width / 2f
                        val radius = (this.size.width / 2f) - strokeW / 2f
                        val cy = radius + strokeW / 2f
                        // Speedometer angle math: SwiftUI Path arc uses iOS coordinate system;
                        // Compose drawArc startAngle is measured from 3 o'clock (right) clockwise.
                        // To replicate iOS sweep starting at 270° - sweep/2, we offset by -90.
                        // iOS startAngle: 270° - sweep/2 → Compose: 180° - sweep/2
                        val startAngle = 180f - (sweepDeg / 2.0).toFloat()
                        val arcSize = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f)
                        val arcOffset = androidx.compose.ui.geometry.Offset(cx - radius, cy - radius)
                        // Track
                        drawArc(
                            color = trackColor,
                            startAngle = startAngle,
                            sweepAngle = sweepDeg.toFloat(),
                            useCenter = false,
                            style = Stroke(width = strokeW, cap = StrokeCap.Round),
                            topLeft = arcOffset,
                            size = arcSize,
                        )
                        // Fill
                        val fillSweep = sweepDeg.toFloat() * animatedProgress
                        if (useGradient) {
                            drawArc(
                                brush = Brush.sweepGradient(gradColors, center = androidx.compose.ui.geometry.Offset(cx, cy)),
                                startAngle = startAngle,
                                sweepAngle = fillSweep,
                                useCenter = false,
                                style = Stroke(width = strokeW, cap = StrokeCap.Round),
                                topLeft = arcOffset,
                                size = arcSize,
                            )
                        } else {
                            drawArc(
                                color = fillColor,
                                startAngle = startAngle,
                                sweepAngle = fillSweep,
                                useCenter = false,
                                style = Stroke(width = strokeW, cap = StrokeCap.Round),
                                topLeft = arcOffset,
                                size = arcSize,
                            )
                        }
                        // Needle — rotated around (cx, cy).
                        val needleLen = radius - strokeW / 2f - 12f
                        val needleAngleFromUp = -sweepDeg / 2.0 + sweepDeg * animatedProgress
                        // Convert "up = 0°" to Compose canvas: subtract 90° because Canvas
                        // rotation is from 3 o'clock; so needle_angle_canvas = needleAngleFromUp - 90°
                        val needleAngleRad = Math.toRadians(needleAngleFromUp - 90.0)
                        val tipX = cx + (kotlin.math.cos(needleAngleRad) * needleLen).toFloat()
                        val tipY = cy + (kotlin.math.sin(needleAngleRad) * needleLen).toFloat()
                        drawLine(
                            color = needleCol,
                            start = androidx.compose.ui.geometry.Offset(cx, cy),
                            end = androidx.compose.ui.geometry.Offset(tipX, tipY),
                            strokeWidth = needleW,
                            cap = StrokeCap.Round,
                        )
                        // Hub circle
                        drawCircle(
                            color = needleCol,
                            radius = maxOf(6f, needleW * 1.75f),
                            center = androidx.compose.ui.geometry.Offset(cx, cy),
                        )
                    }
                    // Min/Max labels positioned at endpoint coordinates (relative to box).
                    val density = LocalDensity.current
                    val sizePxFloat = with(density) { size.toPx() }
                    val cxPx = sizePxFloat / 2f
                    val radiusPx = sizePxFloat / 2f - strokeW / 2f
                    val cyPx = radiusPx + strokeW / 2f
                    val endpointX = endpointSinX * radiusPx
                    val endpointY = endpointCosY * radiusPx
                    val labelOffsetXmin = with(density) { (cxPx - endpointX).toDp() }
                    val labelOffsetXmax = with(density) { (cxPx + endpointX).toDp() }
                    val labelOffsetY = with(density) { (cyPx + endpointY + strokeW).toDp() }
                    Text(
                        text = minLabel,
                        fontSize = minMaxFontSz,
                        fontWeight = FontWeight.Medium,
                        color = minMaxCol,
                        modifier = Modifier.absoluteOffset(x = labelOffsetXmin - 12.dp, y = labelOffsetY),
                    )
                    Text(
                        text = maxLabel,
                        fontSize = minMaxFontSz,
                        fontWeight = FontWeight.Medium,
                        color = minMaxCol,
                        modifier = Modifier.absoluteOffset(x = labelOffsetXmax - 12.dp, y = labelOffsetY),
                    )
                    // Center value (positioned in the bowl below center).
                    if (shouldShowCenter) {
                        val centerY = with(density) { (cyPx + radiusPx * 0.5f).toDp() }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .absoluteOffset(y = centerY - labelFontSize.value.dp / 2),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = if (showPct) "${(animatedProgress * 100).roundToInt()}%"
                                    else if (hasCenterText) block.text!!
                                    else "${value.toInt()}",
                                fontSize = labelFontSize,
                                fontWeight = FontWeight.Bold,
                                color = labelColor,
                            )
                            block.sublabel?.takeIf { it.isNotEmpty() }?.let {
                                Text(
                                    text = it,
                                    fontSize = (kotlin.math.min(labelFontSize.value * 0.5f, 13f)).sp,
                                    color = labelColor.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                // arc / radial — full square Box with Canvas + center label.
                val isRadial = variant == "radial"
                val effectiveStroke = if (isRadial) strokeW * 2f else strokeW
                Box(
                    modifier = Modifier.size(size).padding((effectiveStroke / 2).dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Track
                        drawArc(
                            color = trackColor,
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = effectiveStroke, cap = StrokeCap.Round),
                        )
                        // Fill — start at 12 o'clock (-90°)
                        val fillSweep = animatedProgress * 360f
                        if (useGradient) {
                            drawArc(
                                brush = Brush.sweepGradient(gradColors),
                                startAngle = -90f,
                                sweepAngle = fillSweep,
                                useCenter = false,
                                style = Stroke(width = effectiveStroke, cap = StrokeCap.Round),
                            )
                        } else {
                            drawArc(
                                color = fillColor,
                                startAngle = -90f,
                                sweepAngle = fillSweep,
                                useCenter = false,
                                style = Stroke(width = effectiveStroke, cap = StrokeCap.Round),
                            )
                        }
                    }
                    if (shouldShowCenter) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (showPct) "${(animatedProgress * 100).roundToInt()}%"
                                    else if (hasCenterText) block.text!!
                                    else "${value.toInt()}",
                                fontSize = labelFontSize,
                                fontWeight = FontWeight.Bold,
                                color = labelColor,
                            )
                            block.sublabel?.takeIf { it.isNotEmpty() }?.let {
                                Text(
                                    text = it,
                                    fontSize = 12.sp,
                                    color = labelColor.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showPct && pctLocation == "below") {
            Text(
                text = "${(animatedProgress * 100).roundToInt()}%",
                fontSize = labelFontSize,
                fontWeight = FontWeight.Bold,
                color = labelColor,
            )
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
    // SPEC-401-A R57 (Lens C R57 #2, P3) — host View for SELECTION haptic on
    // wheel-column tap, mirroring iOS UIPickerView/UIDatePicker auto-emitting
    // selectionChanged() on every wheel snap (system behavior on iOS 13+).
    // Sibling WheelPickerBlock (line ~3953) already fires SELECTION haptic.
    val view = androidx.compose.ui.platform.LocalView.current

    // Simple day/month/year selectors
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    var selectedDay by remember { mutableIntStateOf(1) }
    var selectedMonth by remember { mutableIntStateOf(1) }
    var selectedYear by remember { mutableIntStateOf(2000) }

    val dayListState = rememberLazyListState()
    val monthListState = rememberLazyListState()
    val yearListState = rememberLazyListState()

    // SPEC-401-A R62 (Lens C P1) — viewport-center math instead of
    // `firstVisibleItemIndex == index`. Without this, only the literal
    // first row was "center" — bold/highlighted text never aligned with
    // the visual selection strip once the user scrolled. Mirrors
    // WheelPickerBlock pattern (line ~4031).
    fun centeredIndexOf(state: androidx.compose.foundation.lazy.LazyListState): Int {
        val info = state.layoutInfo
        if (info.visibleItemsInfo.isEmpty()) return state.firstVisibleItemIndex
        val viewportCenter = info.viewportStartOffset +
            (info.viewportEndOffset - info.viewportStartOffset) / 2
        return info.visibleItemsInfo.minByOrNull {
            kotlin.math.abs((it.offset + it.size / 2) - viewportCenter)
        }?.index ?: state.firstVisibleItemIndex
    }
    val monthCentered by remember { derivedStateOf { centeredIndexOf(monthListState) } }
    val dayCentered by remember { derivedStateOf { centeredIndexOf(dayListState) } }
    val yearCentered by remember { derivedStateOf { centeredIndexOf(yearListState) } }

    Box(modifier = Modifier.fillMaxWidth().height(150.dp)) {
        // SPEC-401-A R62 (Lens C P1) — visible center-strip overlay so
        // users can see WHERE the selection actually lives. Sits behind
        // the LazyColumn Row at viewport-center. Mirrors
        // WheelPickerBlock highlight strip (line 4161-4166).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .align(Alignment.Center)
                .background(highlightColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
        )
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 55.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = monthListState),
            ) {
                items(12) { index ->
                    val isCenter = monthCentered == index
                    Text(
                        text = months[index],
                        fontSize = if (isCenter) 18.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        // SPEC-401-A R50 (Lens C #3, P1) — DateWheelPicker
                        // non-center text uses theme onSurface.alpha(0.6)
                        // matching iOS .secondary (was Color.Gray, illegible
                        // in dark mode).
                        color = if (isCenter) highlightColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedMonth = index + 1
                                inputValues[fieldId] = String.format(java.util.Locale.US, "%04d-%02d-%02d", selectedYear, selectedMonth, selectedDay)
                                // SPEC-401-A R57 (Lens C R57 #2, P3) — SELECTION
                                // haptic mirrors iOS UIPickerView system tick.
                                ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.SELECTION)
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 55.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = dayListState),
            ) {
                items(31) { index ->
                    val day = index + 1
                    val isCenter = dayCentered == index
                    Text(
                        // SPEC-070-A final audit pass D F1 — Locale.US so the
                        // wheel doesn't show mixed-script digits (Persian /
                        // Arabic-Indic) on fa-IR / ar locales.
                        text = String.format(java.util.Locale.US, "%02d", day),
                        fontSize = if (isCenter) 18.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        // SPEC-401-A R50 (Lens C #3, P1) — DateWheelPicker
                        // non-center text uses theme onSurface.alpha(0.6)
                        // matching iOS .secondary (was Color.Gray, illegible
                        // in dark mode).
                        color = if (isCenter) highlightColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedDay = day
                                inputValues[fieldId] = String.format(java.util.Locale.US, "%04d-%02d-%02d", selectedYear, selectedMonth, selectedDay)
                                // SPEC-401-A R57 (Lens C R57 #2, P3) — SELECTION
                                // haptic mirrors iOS UIPickerView system tick.
                                ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.SELECTION)
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
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 55.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = rememberSnapFlingBehavior(lazyListState = yearListState),
            ) {
                items(years.size) { index ->
                    val year = years[index]
                    val isCenter = yearCentered == index
                    Text(
                        text = year.toString(),
                        fontSize = if (isCenter) 18.sp else 14.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        // SPEC-401-A R50 (Lens C #3, P1) — DateWheelPicker
                        // non-center text uses theme onSurface.alpha(0.6)
                        // matching iOS .secondary (was Color.Gray, illegible
                        // in dark mode).
                        color = if (isCenter) highlightColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .clickable {
                                selectedYear = year
                                inputValues[fieldId] = String.format(java.util.Locale.US, "%04d-%02d-%02d", selectedYear, selectedMonth, selectedDay)
                                // SPEC-401-A R57 (Lens C R57 #2, P3) — SELECTION
                                // haptic mirrors iOS UIPickerView system tick.
                                ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.SELECTION)
                            },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    } // SPEC-401-A R62 (Lens C P1) — close Box wrapper added for highlight strip
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
    // SPEC-401-A R61 (Lens A N1, P1) — accept iOS canonical `stack_children`
    // alongside `children`; console editor writes `stack_children` for
    // stack-block creation. iOS reads both at ContentBlockRendererView.swift
    // :1129. Without this fallback Android renders empty Stack on console
    // payloads.
    val childBlocks = (block.children ?: block.stack_children ?: emptyList()).sortedBy { it.z_index ?: 0.0 }
    // SPEC-401-A — iOS Stack alignment supports 9 named tokens
    // (top_left/topLeading/topLeft, top, top_right, leading, center,
    // trailing, bottom_left, bottom, bottom_right). Android previously
    // delegated to mapBlockAlignment which only knew left/right/center
    // + top/bottom split into separate fields, so any combined token
    // fell through to Center. Parse the combined name first, fall
    // back to the per-axis path for legacy authoring.
    val stackAlignment = parseStackAlignment(block.alignment)
        ?: mapBlockAlignment(block.horizontal_align, block.vertical_align)

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
    // SPEC-401-A R61 (Lens A N1, P1) — accept iOS canonical `stack_children`
    // alongside `children` for Row blocks; console editor writes
    // `stack_children` on row-block creation (StepContentEditor.tsx).
    // Mirrors iOS ContentBlockRendererView.swift:1129.
    val childBlocks = block.children ?: block.stack_children ?: emptyList()
    val rowGap = (block.gap ?: 8.0).dp
    val direction = block.row_direction ?: "horizontal"
    val distribution = block.row_distribution ?: "start"
    val childFill = block.row_child_fill ?: true

    // SPEC-401-A — column ratios (e.g. "1:2") for proportional widths.
    // iOS prefers field_config["column_ratios"] then top-level
    // column_ratios; Android matches.
    val ratioStr = (block.field_config?.get("column_ratios") as? String) ?: block.column_ratios
    val ratios: List<Float> = ratioStr
        ?.split(":")
        ?.mapNotNull { it.trim().toFloatOrNull()?.takeIf { v -> v > 0f } }
        ?: emptyList()

    // SPEC-401-A — row container styling (info-card pattern). All
    // values flow through field_config because the row block is reused
    // for many layouts; only when authored do we wrap.
    val cfg = block.field_config
    val rowBgCol = (cfg?.get("bg_color") as? String)?.let { StyleEngine.parseColor(it) }
    val rowBorderW = ((cfg?.get("border_width") as? Number)?.toFloat() ?: 0f).dp
    val rowBorderCol = (cfg?.get("border_color") as? String)?.let { StyleEngine.parseColor(it) }
    val rowCornerR = ((cfg?.get("corner_radius") as? Number)?.toFloat() ?: 0f).dp
    val rowBgOpacity = ((cfg?.get("background_opacity") as? Number)?.toFloat() ?: 1f)
    val rowUseBlur = (cfg?.get("blur_background") as? Boolean) == true

    // SPEC-401-A — leading-icon slot. Mirrors iOS rowLeadingIconView
    // (info-card pattern: circle bg + icon).
    val leadingIcon = cfg?.get("leading_icon") as? String
    val leadingIconSize = ((cfg?.get("leading_icon_size") as? Number)?.toFloat() ?: 24f).dp
    val leadingIconColor = (cfg?.get("leading_icon_color") as? String)?.let { StyleEngine.parseColor(it) }
    val leadingIconBgColor = (cfg?.get("leading_icon_bg_color") as? String)?.let { StyleEngine.parseColor(it) }
    val leadingIconBgSize = ((cfg?.get("leading_icon_bg_size") as? Number)?.toFloat()
        ?: (leadingIconSize.value + 16f)).dp

    val hasOverflowChild = childBlocks.any { it.overflow == "visible" }
    val hasContainerStyling = rowBgCol != null || rowBorderW.value > 0f || rowUseBlur

    @Composable
    fun LeadingIconSlot() {
        if (leadingIcon == null) return
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(leadingIconBgSize)) {
            if (leadingIconBgColor != null) {
                Box(
                    modifier = Modifier
                        .size(leadingIconBgSize)
                        .clip(CircleShape)
                        .background(leadingIconBgColor.copy(alpha = 0.15f)),
                )
            }
            Text(
                text = leadingIcon,
                fontSize = (leadingIconSize.value * 0.6f).sp,
                color = leadingIconColor ?: Color.Unspecified,
            )
        }
    }

    @Composable
    fun RowChildren(modifier: Modifier = Modifier) {
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
            val baseMod = if (hasOverflowChild) {
                Modifier.fillMaxWidth().graphicsLayer { clip = false }
            } else {
                Modifier.fillMaxWidth()
            }
            Column(
                modifier = modifier.then(baseMod),
                verticalArrangement = if (rowGap.value > 0 && distribution == "start") Arrangement.spacedBy(rowGap) else vArrangement,
            ) {
                LeadingIconSlot()
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
            val baseMod = if (hasOverflowChild) {
                Modifier.fillMaxWidth().graphicsLayer { clip = false }
            } else {
                Modifier.fillMaxWidth()
            }
            Row(
                modifier = modifier.then(baseMod),
                horizontalArrangement = hArrangement,
                verticalAlignment = vAlignment,
            ) {
                LeadingIconSlot()
                if (ratios.isNotEmpty()) {
                    // SPEC-401-A — proportional weights from column_ratios.
                    // Children map 1:1 to ratios; extra children get
                    // the average weight (mirrors iOS allocate()).
                    val avg = ratios.sum() / ratios.size
                    childBlocks.forEachIndexed { idx, child ->
                        val w = if (idx < ratios.size) ratios[idx] else avg
                        val childMod = Modifier.weight(w)
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
                } else {
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
    }

    // SPEC-401-A — wrap with container styling when authored. Mirrors
    // iOS `.if(rowBgCol != nil || rowBorderW > 0 || rowUseBlur) { … }`.
    if (hasContainerStyling) {
        val shape = RoundedCornerShape(rowCornerR)
        var containerMod: Modifier = Modifier
        if (rowBgCol != null) {
            containerMod = containerMod.background(rowBgCol.copy(alpha = rowBgOpacity), shape)
        }
        if (rowBorderW.value > 0f && rowBorderCol != null) {
            containerMod = containerMod.border(rowBorderW, rowBorderCol, shape)
        }
        Box(modifier = containerMod.padding(if (rowBorderW.value > 0f) 12.dp else 0.dp)) {
            RowChildren()
        }
    } else {
        RowChildren()
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
            // SPEC-401-A R45 — theme-adaptive secondary (was Color.Gray).
            // CustomViewBlock placeholder fallback. iOS .secondary
            // (ContentBlockRendererView.swift:1361).
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
        // SPEC-401-A R48 (Lens A #5) — particle radius is RAW pixels; do NOT
        // multiply by scaleX. iOS uses size_range as raw point values
        // (StarBackgroundView.swift:88 `circle.size = p.size`). Multiplying
        // by scaleX shrank particles to invisibility on narrow widths and
        // ballooned them into giant blobs in fullscreen mode.
        particles.value.forEach { p ->
            drawCircle(
                color = particleColor.copy(alpha = p.opacity * baseOpacity),
                radius = p.size,
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
    // SPEC-401-A R35 — horizontal mode per iOS modernHorizontalWheel
    // (ContentBlockStandaloneViews.swift:1445,1454-1473,1521-1612).
    val isHorizontal = (block.wheel_orientation ?: block.orientation)?.lowercase() == "horizontal"

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

    // SPEC-401-A R50 (Lens C #1, P0) — derive the truly-centered item from
    // viewport offset rather than `firstVisibleItemIndex`. iOS uses
    // `.scrollPosition(anchor: .center)` (ContentBlockStandaloneViews.swift
    // :1549) which always reports the index nearest the viewport midpoint.
    // Without this fix, the highlight chip + bold/colored text rendered
    // over a half-empty area: chip pinned to viewport center while
    // `firstVisibleItemIndex` still pointed to the leftmost item.
    val centeredIndex by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val viewportCenter = info.viewportStartOffset +
                (info.viewportEndOffset - info.viewportStartOffset) / 2
            info.visibleItemsInfo.minByOrNull {
                kotlin.math.abs((it.offset + it.size / 2) - viewportCenter)
            }?.index ?: listState.firstVisibleItemIndex
        }
    }
    // SPEC-401-A R35 — haptic selection feedback on wheel snap (Lens C #1).
    // iOS pre-warms UISelectionFeedbackGenerator and fires .selectionChanged()
    // after the user starts interacting (ContentBlockStandaloneViews.swift:1500-1510).
    val view = androidx.compose.ui.platform.LocalView.current
    var hasUserInteracted by remember { mutableStateOf(false) }
    var lastHapticIndex by remember { mutableStateOf(initialIndex) }
    LaunchedEffect(centeredIndex) {
        // SPEC-401-A R60 (Lens A P1) — gate persistence behind interaction
        // when the field is required, matching iOS
        // ContentBlockStandaloneViews.swift:1493-1498:
        //   if block.field_required != true { persistValue(...) }
        // Without this, the default wheel value is written to inputValues
        // on first composition; the required-field validator at
        // OnboardingActivity.kt:2503-2508 sees a non-null Double and lets
        // the user advance without ever interacting with the wheel. Also
        // mirror iOS Int/Double type discrimination so server-side
        // analytics joins don't drift.
        val isRequired = block.field_required == true
        if (centeredIndex in values.indices && (!isRequired || hasUserInteracted)) {
            val v = values[centeredIndex]
            inputValues[fieldId] = if (v == v.toLong().toDouble()) v.toLong() else v
        }
        if (!hasUserInteracted && listState.isScrollInProgress) {
            hasUserInteracted = true
        }
        if (hasUserInteracted && centeredIndex != lastHapticIndex && centeredIndex in values.indices) {
            ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.SELECTION)
            lastHapticIndex = centeredIndex
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // SPEC-401-A R3 — wheel_picker label: iOS reads
        // `rating_label ?? text ?? label`; Android only knew `label`
        // so console-authored labels via either field were lost.
        // SPEC-401-A R61 (Lens A N7, P3) — drop `?: block.label` to match
        // iOS canonical ContentBlockStandaloneViews.swift:1448
        // `block.rating_label ?? block.text`. Same family as backlog #3.
        (block.rating_label ?: block.text)?.let { label ->
            // SPEC-401-A R45 — theme-adaptive wheel_picker label (was Color.Gray).
            // iOS .secondary (ContentBlockStandaloneViews.swift:1451).
            // SPEC-401-A R57 (Lens A R57 #6, P2) — 15sp matches iOS .subheadline.
            Text(text = label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 8.dp))
        }

        if (isHorizontal) {
            Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Center-anchored highlight chip
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(60.dp)
                        .background(highlightColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                )

                // SPEC-401-A R50 (Lens C #1, P0) — half-screen-minus-half-item
                // contentPadding so the first/last items can scroll under the
                // center highlight chip. iOS uses `.contentMargins(.horizontal,
                // sidePad, for: .scrollContent)` at line 1547.
                val sidePad = (LocalConfiguration.current.screenWidthDp.dp - 80.dp) / 2
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = sidePad),
                    verticalAlignment = Alignment.CenterVertically,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                ) {
                    items(values.size) { index ->
                        val v = values[index]
                        // SPEC-401-A R59 (Lens A P3 #5) — Locale.US matches
                        // iOS ContentBlockStandaloneViews.swift:1465
                        // `String(format: "%.1f", val)` (POSIX locale).
                        // Default JVM locale would render Arabic-Indic digits
                        // ("٢٫٥") on ar/fa/bn/my locales — sister of R27
                        // which fixed countdown + form date/time pickers.
                        val formatted = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v)
                        val display = if (unitPos == "before") "$unitStr$formatted" else "$formatted$unitStr"
                        val isCenter = index == centeredIndex

                        Text(
                            text = display,
                            fontSize = if (isCenter) 28.sp else 18.sp,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            // SPEC-401-A R48 (Lens A #4) — non-center text uses
                            // theme-adaptive onSurface.alpha(0.7) instead of
                            // hardcoded Color.Gray. Matches iOS .secondary
                            // and renders correctly in dark mode.
                            color = if (isCenter) highlightColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            // SPEC-401-A R59 (Lens C P2 #2) — TalkBack-announce
                            // every selection change on the centered value. iOS
                            // ContentBlockStandaloneViews.swift:1462-1473 uses
                            // native `Picker(.wheel)` which auto-announces wheel
                            // changes to VoiceOver; Compose has no equivalent so
                            // explicit `liveRegion = Polite` is required.
                            modifier = Modifier
                                .width(80.dp)
                                .padding(horizontal = 8.dp)
                                .then(
                                    if (isCenter) Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = display
                                    } else Modifier
                                ),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        } else {
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

                // SPEC-401-A R62 (Lens C P1) — vertical wheel needs
                // top+bottom contentPadding so first/last items can scroll
                // under the center highlight strip. Without this,
                // boundary values (e.g. age 18 / 99 in 18..99 range) are
                // physically unreachable — viewport center math
                // (centeredIndex line ~4031) requires items to be
                // positioned at parent center, which is impossible at
                // index 0 / N-1 when there's no leading/trailing padding.
                // iOS native Picker(.wheel) auto-positions any index.
                // Mirrors the horizontal branch fix at line 4106.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 55.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
                ) {
                    items(values.size) { index ->
                        val v = values[index]
                        // SPEC-401-A R59 (Lens A P3 #5) — Locale.US matches
                        // iOS ContentBlockStandaloneViews.swift:1465
                        // `String(format: "%.1f", val)` (POSIX locale).
                        // Default JVM locale would render Arabic-Indic digits
                        // ("٢٫٥") on ar/fa/bn/my locales — sister of R27
                        // which fixed countdown + form date/time pickers.
                        val formatted = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(java.util.Locale.US, v)
                        val display = if (unitPos == "before") "$unitStr$formatted" else "$formatted$unitStr"
                        val isCenter = index == centeredIndex

                        Text(
                            text = display,
                            fontSize = if (isCenter) 22.sp else 16.sp,
                            fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                            // SPEC-401-A R48 (Lens A #4) — non-center text uses
                            // theme-adaptive onSurface.alpha(0.7) instead of
                            // hardcoded Color.Gray (vertical wheel mode).
                            color = if (isCenter) highlightColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            // SPEC-401-A R59 (Lens C P2 #2) — vertical-wheel
                            // TalkBack live announce; mirrors horizontal branch.
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .then(
                                    if (isCenter) Modifier.semantics {
                                        liveRegion = LiveRegionMode.Polite
                                        contentDescription = display
                                    } else Modifier
                                ),
                            textAlign = TextAlign.Center,
                        )
                    }
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

    // SPEC-401-A R48 (Lens A #3) — reserve a fixed-size frame so the largest
    // pulse ring at peak scale (×1.5) doesn't overlap adjacent content.
    // iOS uses .frame(width: avatarSize + CGFloat(ringCount + 1) * 20 * 1.3,
    // height: same). We mirror the same reservation on Android so layout
    // doesn't collapse adjacent rows on top of the rings during animation.
    val frameSize = (avatarSize.value + (ringCount + 1) * 20f * 1.3f).dp
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = hAlign,
    ) {
        Box(
            modifier = Modifier.size(frameSize),
            contentAlignment = Alignment.Center,
        ) {
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
                // SPEC-401-A R49 (Lens C #1, P1) — Modifier.scale applies a GPU
                // transform that visually grows the already-drawn border.
                // The legacy chained `.then(Modifier.size(ringSize * scale))`
                // changed slot size but didn't grow the rendered ring — rings
                // faded out in place on Android while iOS rings expand outward
                // (iOS ContentBlockStandaloneViews.swift:1664 .scaleEffect).
                Box(
                    modifier = Modifier
                        .size(ringSize)
                        .alpha(alpha)
                        .scale(scale.coerceIn(1f, 1.5f))
                        .border(2.dp, pulseColor.copy(alpha = 0.3f), CircleShape),
                )
            }

            // Avatar image — shape honors block.image_shape ("circle" default |
            // "square" | "rounded"). block.image_corner_radius controls the
            // rounded corner radius (default 16). Missing field preserves
            // previous circle-crop behavior so existing flows look identical.
            val avatarShape = when (block.image_shape) {
                "square" -> RectangleShape
                "rounded" -> RoundedCornerShape((block.image_corner_radius ?: 16.0).dp)
                else -> CircleShape
            }
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .clip(avatarShape)
                    .then(
                        if (borderW.value > 0) Modifier.border(borderW, borderCol, avatarShape) else Modifier
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

            // Badge — honors badge_position (4-corner alignment), badge_size
            // (font + padding scale factor), badge_corner_radius (999 = capsule).
            // Missing fields preserve the previous hardcoded behavior.
            if (!block.badge_text.isNullOrEmpty()) {
                val badgeScale = (block.badge_size ?: 1.0).toFloat()
                // SPEC-401-A R19 — match iOS ContentBlockStandaloneViews.swift
                // :1709 `badgeFontSize: CGFloat = 11 * badgeScale` (caption2
                // ≈ 11pt). Was 10.sp on Android.
                val badgeFontSize = 11.sp * badgeScale
                val badgeHPadding = (6f * badgeScale).dp
                val badgeVPadding = (2f * badgeScale).dp
                val badgeRadius = (block.badge_corner_radius ?: 999.0).dp
                val badgeAlignment = when (block.badge_position) {
                    "top_leading" -> Alignment.TopStart
                    "bottom_trailing" -> Alignment.BottomEnd
                    "bottom_leading" -> Alignment.BottomStart
                    else -> Alignment.TopEnd   // top_trailing is the default
                }
                // SPEC-401-A R19 — match iOS lines 1716-1733 which use
                // `.offset(x: ±avatarSize * 0.35, y: ±avatarSize * 0.35)`
                // to OVERLAP the badge into the avatar's NE corner. Android's
                // `.align(badgeAlignment)` alone pinned to inner Box edge with
                // no overlap, leaving the badge sitting outside the avatar
                // visually. Direction signs derived from corner alignment.
                val overlapPx = (avatarSize.value * 0.35f).dp
                val (offsetX, offsetY) = when (badgeAlignment) {
                    Alignment.TopEnd -> overlapPx to -overlapPx
                    Alignment.TopStart -> -overlapPx to -overlapPx
                    Alignment.BottomEnd -> overlapPx to overlapPx
                    Alignment.BottomStart -> -overlapPx to overlapPx
                    else -> 0.dp to 0.dp
                }
                Text(
                    text = block.badge_text,
                    fontSize = badgeFontSize,
                    fontWeight = FontWeight.SemiBold,
                    color = StyleEngine.parseColor(block.badge_text_color ?: "#FFFFFF"),
                    modifier = Modifier
                        .align(badgeAlignment)
                        .offset(x = offsetX, y = offsetY)
                        .background(
                            StyleEngine.parseColor(block.badge_bg_color ?: "#EF4444"),
                            RoundedCornerShape(badgeRadius),
                        )
                        .padding(horizontal = badgeHPadding, vertical = badgeVPadding),
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
                // SPEC-401-A R3 — match iOS contract:
                // onAction("select_plan", planId) — host parses an
                // ACTION + VALUE, NOT a colon-embedded string. The
                // generic onAction(String) only carries action; we
                // emit a separate "select_plan_id:<planId>" sentinel
                // so existing hosts that don't grok the dual-arg form
                // can still recover the planId. Hosts implementing the
                // canonical contract should listen for "select_plan"
                // first and inspect the planId from the most-recent
                // event sequence (mirrors iOS pattern).
                onAction("select_plan")
                onAction("select_plan_id:${plan.id}")
            },
            modifier = modifier
                // SPEC-401-A R16 — match iOS ContentBlockStandaloneViews.swift
                // :1911 highlighted shadow (`.shadow(color: isHighlighted ?
                // accent.opacity(0.15) : .clear, radius: 4, y: 2)`). Without
                // this the "best value" plan loses its accent-tinted lift on
                // Android; Card's default elevation is 0 because we override
                // containerColor.
                .shadow(
                    elevation = if (isHighlighted) 4.dp else 0.dp,
                    shape = RoundedCornerShape(12.dp),
                    spotColor = accentColor.copy(alpha = 0.15f),
                )
                .border(
                    width = if (isSelected || isHighlighted) 2.dp else 1.dp,
                    color = if (isSelected) accentColor else if (isHighlighted) accentColor else Color.Gray.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp),
                ),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                // SPEC-401-A R16 — match iOS ContentBlockStandaloneViews.swift
                // :1902 unselected uses `Color(.systemBackground)` (opaque
                // surface). Android's `Color.Transparent` left plan cards
                // visibly without a backplate when the page background was
                // tinted. Use MaterialTheme.colorScheme.surface so the card
                // adopts the right opaque color in light/dark themes.
                containerColor = if (isSelected) accentColor.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                // SPEC-401-A R48 (Lens C #8) — plan card row spacing 4→6dp
                // matches iOS PricingTableBlockView VStack(spacing: 6).
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!plan.badge.isNullOrEmpty()) {
                    Text(
                        text = plan.badge,
                        // SPEC-401-A R48 (Lens C #7) — badge font 10→11sp
                        // mirrors iOS .footnote.
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .background(accentColor, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                // SPEC-401-A R48 (Lens C #9) — plan label/price use theme
                // onSurface so dark mode reads as a true on-surface tint, not
                // the default Color.Black inherited from CardDefaults.
                Text(
                    text = plan.label,
                    // SPEC-401-A R57 (Lens A R57 #9, P2) — 15sp matches iOS
                    // .subheadline.weight(.medium) (ContentBlockStandalone
                    // Views.swift:1889).
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = plan.price,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                // SPEC-401-A R44 — theme-adaptive period (was Color.Gray).
                Text(text = plan.period, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
    // SPEC-401-A R13 — match iOS swiftUIAnimation easing map
    // (ContentBlockTypes.swift:618-633). Compose's `tween(d)` defaults
    // to `FastOutSlowInEasing` (≈ ease_in_out), but iOS default is
    // `.linear`. Without explicit mapping, `linear`/`ease_in`/`ease_out`
    // collapse to the same Material curve and look visibly different
    // from iOS in side-by-side comparison.
    val resolvedEasing: androidx.compose.animation.core.Easing = when (animation.easing) {
        "ease_in" -> androidx.compose.animation.core.FastOutLinearInEasing
        "ease_out" -> androidx.compose.animation.core.LinearOutSlowInEasing
        "ease_in_out", "ease" -> androidx.compose.animation.core.FastOutSlowInEasing
        else -> androidx.compose.animation.core.LinearEasing // iOS default
    }
    val tweenSpec = tween<Float>(durationMillis = durationMs, easing = resolvedEasing)
    val tweenIntOffset = tween<androidx.compose.ui.unit.IntOffset>(durationMillis = durationMs, easing = resolvedEasing)
    val springFloatSpec = androidx.compose.animation.core.spring<Float>(
        dampingRatio = (animation.spring_damping ?: 0.7).toFloat()
    )
    val springIntOffsetSpec = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntOffset>(
        dampingRatio = (animation.spring_damping ?: 0.7).toFloat()
    )
    val isSpring = animation.easing == "spring"

    val enterTransition: androidx.compose.animation.EnterTransition = when (animation.type) {
        "fade_in" -> androidx.compose.animation.fadeIn(if (isSpring) springFloatSpec else tweenSpec)
        "slide_up" -> androidx.compose.animation.slideInVertically(if (isSpring) springIntOffsetSpec else tweenIntOffset) { it }
        "slide_down" -> androidx.compose.animation.slideInVertically(if (isSpring) springIntOffsetSpec else tweenIntOffset) { -it }
        "slide_left" -> androidx.compose.animation.slideInHorizontally(if (isSpring) springIntOffsetSpec else tweenIntOffset) { -it }
        "slide_right" -> androidx.compose.animation.slideInHorizontally(if (isSpring) springIntOffsetSpec else tweenIntOffset) { it }
        "scale_up" -> androidx.compose.animation.scaleIn(if (isSpring) springFloatSpec else tweenSpec, initialScale = 0.5f)
        "scale_down" -> androidx.compose.animation.scaleIn(if (isSpring) springFloatSpec else tweenSpec, initialScale = 1.5f)
        // SPEC-401-A R13 — bounce previously always used spring,
        // ignoring `duration_ms`. iOS resolves bounce via swiftUIAnimation
        // which honors `duration_ms` for any non-spring easing — so when
        // `easing != "spring"` use the duration tween instead.
        "bounce" -> androidx.compose.animation.scaleIn(
            if (isSpring) springFloatSpec else tweenSpec,
            initialScale = 0.3f,
        )
        "flip" -> androidx.compose.animation.fadeIn(if (isSpring) springFloatSpec else tweenSpec) +
            androidx.compose.animation.scaleIn(if (isSpring) springFloatSpec else tweenSpec, initialScale = 0.0f)
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
    // SPEC-401-A R55 (Lens A R55 #11, P2) — match iOS canonical fallback chain
    // at FormInputBlockViews.swift:11 `block.field_label ?? block.rating_label
    // ?? block.text`. Was missing rating_label so payloads using only that
    // field on a `rating` block rendered no label on Android.
    // SPEC-401-A R61 (Lens A backlog #3, P2) — drop `?: block.label` from
    // fallback chain to match iOS canonical FormInputBlockViews.swift:11
    // `block.field_label ?? block.rating_label ?? block.text`. Authored
    // payload using only `block.label` rendered the label on Android but
    // not on iOS — Android was masking iOS bug; align to iOS.
    val label = block.field_label ?: block.rating_label ?: block.text
    if (!label.isNullOrEmpty()) {
        val required = block.field_required ?: false
        Row {
            Text(
                text = label,
                // SPEC-401-A R56 (Lens A R56 #3, P2) — default 15sp matches iOS
                // .subheadline (FormInputBlockViews.swift:15). Was 14sp on every
                // FormFieldLabel without explicit label_font_size.
                fontSize = (block.field_style?.label_font_size ?: 15.0).sp,
                fontWeight = FontWeight.Medium,
                color = StyleEngine.parseColor(block.field_style?.label_color ?: "#374151"),
            )
            if (required) {
                // SPEC-401-A R60 (Lens C P3 #3) — match iOS systemRed
                // (#FF3B30 light) which is what SwiftUI `Color.red`
                // resolves to at FormInputBlockViews.swift:19. Was
                // Compose `Color.Red` (#FF0000) — harsher than iOS.
                Text(text = "*", color = Color(0xFFFF3B30), fontSize = 15.sp)
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
    // SPEC-070-A finalization OB-6 — restore saved value on back nav.
    // iOS pre-populates `_inputValues = State(initialValue: savedResponses ?? [:])`
    // (`OnboardingRenderer.swift:1326`); Android previously initialized to ""
    // so every back-press wiped typed answers. Read from inputValues map.
    var text by remember { mutableStateOf((inputValues[fieldId] as? String) ?: "") }
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

        // SPEC-401-A R42 (Lens C #1) — wire field_style.text_color +
        // background_color + focused_background_color + border_color so
        // OutlinedTextField actually applies the console-authored
        // theming. iOS FormInputBlockViews.swift:88-106 reads + applies
        // all of these. Android previously parsed border_color but never
        // threaded it into `colors=`/`border=` modifier — so authored
        // borders were silently invisible. Same payload rendered styled
        // on iOS and Material-default on Android.
        val textColor = block.field_style?.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
        val bgColor = block.field_style?.background_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
        val focusedBgColor = block.field_style?.focused_background_color?.let { StyleEngine.parseColor(it) } ?: bgColor
        val focusedBorderColor = block.field_style?.focused_border_color?.let { StyleEngine.parseColor(it) } ?: borderColor
        // SPEC-401-A R55 (Lens C R54 #3, P2) — honor field_style.border_width
        // (and focused_border_width) by drawing the border on an outer Box
        // and zeroing OutlinedTextField's built-in border colors. Compose's
        // OutlinedTextField doesn't expose border thickness through `colors=`
        // so authored 2/3/4dp widths were silently dropped (always 1dp
        // unfocused / 2dp focused). iOS FormInputBlockViews.swift:103-106
        // reads `block.field_style?.border_width ?? 1`.
        val borderWidth = (block.field_style?.border_width ?: 1.0).dp
        // Focus uses authored width if set, else iOS-equivalent 2dp accent.
        val focusedBorderWidth = (block.field_style?.border_width ?: 2.0).dp
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (isFocused) focusedBorderWidth else borderWidth,
                    color = if (isFocused) focusedBorderColor else borderColor,
                    shape = RoundedCornerShape(cornerRadius),
                ),
        ) {
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
                // SPEC-401-A R11 — match iOS UIKitTextField `returnKeyType: .done`
                // (FormInputBlockViews.swift:93). Without `imeAction = Done` the
                // soft keyboard's return key shows the platform default ("Enter"
                // newline) which (a) doesn't dismiss the keyboard and (b) gives
                // no visual cue that the form is complete.
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = kbType,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                shape = RoundedCornerShape(cornerRadius),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                interactionSource = interactionSource,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedContainerColor = focusedBgColor,
                    unfocusedContainerColor = bgColor,
                    // Built-in border zeroed; outer Box draws the authored width.
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

/** Multi-line textarea input. */
@Composable
private fun FormInputTextAreaBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    // SPEC-070-A finalization OB-6 — restore saved value on back nav.
    // iOS pre-populates `_inputValues = State(initialValue: savedResponses ?? [:])`
    // (`OnboardingRenderer.swift:1326`); Android previously initialized to ""
    // so every back-press wiped typed answers. Read from inputValues map.
    var text by remember { mutableStateOf((inputValues[fieldId] as? String) ?: "") }
    val minLines = (block.field_config?.get("min_lines") as? Number)?.toInt() ?: 3

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        // SPEC-401-A R42 (Lens C #1) — same field_style wiring as
        // FormInputTextBlock above; was ignoring all field_style except
        // corner_radius.
        val textColor = block.field_style?.text_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
        val bgColor = block.field_style?.background_color?.let { StyleEngine.parseColor(it) } ?: Color.Unspecified
        val focusedBgColor = block.field_style?.focused_background_color?.let { StyleEngine.parseColor(it) } ?: bgColor
        val borderColor = StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB")
        val focusedBorderColor = block.field_style?.focused_border_color?.let { StyleEngine.parseColor(it) } ?: borderColor
        // SPEC-401-A R55 (Lens C R54 #3, P2) — same border_width pattern as
        // FormInputTextBlock above. Compose's OutlinedTextField has no border
        // thickness API on `colors=`, so authored field_style.border_width
        // was silently dropped. Outer Box draws the border; inner field uses
        // transparent built-in border colors.
        val cornerRadius = (block.field_style?.corner_radius ?: 8.0).dp
        val borderWidth = (block.field_style?.border_width ?: 1.0).dp
        val focusedBorderWidth = (block.field_style?.border_width ?: 2.0).dp
        val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (isFocused) focusedBorderWidth else borderWidth,
                    color = if (isFocused) focusedBorderColor else borderColor,
                    shape = RoundedCornerShape(cornerRadius),
                ),
        ) {
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
                shape = RoundedCornerShape(cornerRadius),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = minLines,
                interactionSource = interactionSource,
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedContainerColor = focusedBgColor,
                    unfocusedContainerColor = bgColor,
                    // Built-in border zeroed; outer Box draws the authored width.
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
        }
    }
}

/** Password input with show/hide toggle. */
@Composable
private fun FormInputPasswordBlock(
    block: ContentBlock,
    inputValues: MutableMap<String, Any>,
) {
    val fieldId = block.field_id ?: block.id
    // SPEC-070-A finalization OB-6 — restore saved value on back nav.
    // iOS pre-populates `_inputValues = State(initialValue: savedResponses ?? [:])`
    // (`OnboardingRenderer.swift:1326`); Android previously initialized to ""
    // so every back-press wiped typed answers. Read from inputValues map.
    var text by remember { mutableStateOf((inputValues[fieldId] as? String) ?: "") }
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
            // SPEC-401-A R11 \u2014 match iOS Password Autofill at
            // FormInputBlockViews.swift:174 (`textContentType: .password`)
            // and provide imeAction=Done for keyboard return-key parity
            // with FormInputBlockViews.swift:177 `returnKeyType: .done`.
            // `Modifier.semantics { contentType = ContentType.Password }`
            // would be the proper Compose 1.6+ ContentType API but
            // requires consumers on Compose 1.6+; `autofillHints` via
            // the legacy AndroidView Autofill bridge would also work
            // but requires a wrapper. The `keyboardType = Password`
            // option below at least signals AutofillManager that this
            // is a password field on Android 12+ for limited autofill
            // support. A bundled vector icon for show/hide eye is a
            // separate follow-up \u2014 current emoji works on every device.
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            ),
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
    // SPEC-401-A R12 — restore the saved value into the visible button
    // text on back-nav so the user sees their prior answer. Previous
    // impl only restored `pendingDate` (datetime sub-state) and left
    // displayText showing "Select date..." as if no answer existed.
    val savedRaw = (inputValues[fieldId] as? String).orEmpty()

    // SPEC-401-A R35 \u2014 match iOS FormInputBlockViews.swift display formatting.
    // iOS shows a localised `DateFormatter` short string when the user
    // reopens the field; Android was leaking the raw ISO8601 wire format.
    val isoFormatter = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
    }
    // SPEC-401-A R57 (Lens A R57 P1) — derive `<field_id>_age` from picked
    // birth-date in date-only mode. Mirrors iOS FormInputBlockViews.swift
    // :1190-1199 which exposes `inputValues["\(fieldId)_age"]` (whole years
    // from selectedDate to now). Hosts branching on `birth_date_age` got
    // nothing on Android. Date-only mode only — datetime/time skip.
    fun persistAgeIfDate(epochMillis: Long) {
        if (mode != "date" && mode.isNotEmpty() && mode != "input_date") return
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
        val now = java.util.Calendar.getInstance()
        var years = now.get(java.util.Calendar.YEAR) - cal.get(java.util.Calendar.YEAR)
        if (now.get(java.util.Calendar.DAY_OF_YEAR) < cal.get(java.util.Calendar.DAY_OF_YEAR)) years--
        if (years < 0) return
        inputValues["${fieldId}_age"] = years
    }
    val displayFormatter = remember(mode) {
        when (mode) {
            "time" -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, java.util.Locale.getDefault())
            "datetime" -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT, java.util.Locale.getDefault())
            else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale.getDefault())
        }
    }
    fun formatForDisplay(iso: String): String {
        if (iso.isEmpty()) return block.field_placeholder ?: "Select $mode..."
        return try {
            val parsed = isoFormatter.parse(iso)
            if (parsed != null) displayFormatter.format(parsed) else iso
        } catch (_: Exception) { iso }
    }

    var displayText by remember { mutableStateOf(formatForDisplay(savedRaw)) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf(savedRaw) }
    // SPEC-401-A R49 (Lens A #3, P1) \u2014 allow_future/allow_past date validation.
    // iOS ContentBlockStandaloneViews.swift:966,970,1160,1174-1175 enforces
    // these constraints and surfaces an inline error string under the picker.
    // Android was silently writing invalid dates.
    var dateError by remember { mutableStateOf<String?>(null) }
    fun validateDate(millis: Long): Boolean {
        val now = System.currentTimeMillis()
        val msg = block.date_validation_message
        if (block.allow_future == false && millis > now) {
            dateError = msg ?: "Future dates are not allowed"
            return false
        }
        if (block.allow_past == false && millis < now) {
            dateError = msg ?: "Past dates are not allowed"
            return false
        }
        dateError = null
        return true
    }

    // SPEC-401-A R35 \u2014 picker_variant per iOS FormInputBlockViews.swift:208-410.
    // "graphical" \u2192 inline DatePicker; "compact"/null/unknown \u2192 tap-to-open
    // button. "wheel" falls back to compact today (Material3 lacks a wheel
    // date picker out of the box; tracked for follow-up).
    // SPEC-401-A R49 (Lens A #4, P1) \u2014 picker_presentation="field" forces
    // tap-to-open compact; otherwise legacy field_config.picker_variant
    // controls inline graphical vs compact. picker_mode similarly may
    // override the function `mode` parameter.
    val effectiveMode = block.picker_mode ?: mode
    val pickerVariant = when {
        block.picker_presentation == "field" -> "compact"
        block.picker_presentation == "inline" -> "graphical"
        else -> (block.field_config?.get("picker_variant") as? String)?.lowercase() ?: "compact"
    }
    val inlineGraphical = pickerVariant == "graphical" && (effectiveMode == "date" || effectiveMode == "datetime")

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        if (inlineGraphical) {
            val initialMillis = remember(savedRaw) {
                if (savedRaw.isEmpty()) null else try { isoFormatter.parse(savedRaw)?.time } catch (_: Exception) { null }
            }
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
            DatePicker(state = datePickerState, modifier = Modifier.fillMaxWidth())
            LaunchedEffect(datePickerState.selectedDateMillis) {
                val millis = datePickerState.selectedDateMillis
                if (millis != null && validateDate(millis)) {
                    val isoStr = isoFormatter.format(java.util.Date(millis))
                    if (effectiveMode == "datetime") {
                        pendingDate = isoStr
                        showTimePicker = true
                    } else {
                        displayText = formatForDisplay(isoStr)
                        inputValues[fieldId] = isoStr
                        persistAgeIfDate(millis)
                    }
                }
            }
        } else {
            // AC-042: Tappable button opens actual Material3 date/time picker
            OutlinedButton(
                onClick = {
                    // SPEC-401-A R49 (Lens A #4) — use effectiveMode (block.picker_mode override).
                    when (effectiveMode) {
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
                // SPEC-401-A R44 — theme-adaptive content color (was Color.DarkGray
                // — invisible on dark surface in dark mode).
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = displayText, fontSize = 14.sp)
                    // SPEC-401-A R49 (Lens A #4) \u2014 use effectiveMode for icon.
                    Text(text = when (effectiveMode) {
                        "date" -> "\uD83D\uDCC5"
                        "time" -> "\u23F0"
                        else -> "\uD83D\uDCC5"
                    }, fontSize = 16.sp)
                }
            }
        }
        // SPEC-401-A R49 (Lens A #3, P1) \u2014 inline error caption when
        // allow_future / allow_past validation rejects a selection.
        // iOS ContentBlockStandaloneViews.swift:1174-1175 surfaces the
        // same red-tinted caption directly under the picker.
        dateError?.let { msg ->
            Text(
                text = msg,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    // SPEC-401-A R12 — match iOS ISO 8601 serialization at
    // FormInputBlockViews.swift:386-389 (`ISO8601DateFormatter().string(from:)`).
    // Previous Android impl wrote `"yyyy-MM-dd"` strings, so server-side
    // consumers + journey triggers + host analytics saw incompatible
    // payloads from the two SDKs for the same flow. iOS's
    // ISO8601DateFormatter defaults emit `yyyy-MM-dd'T'HH:mm:ssZ` in UTC.
    // (R35 — formatter hoisted earlier in the function next to displayFormatter
    // so both inline-graphical and dialog branches reuse the same instance.)

    // Material3 DatePickerDialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    val millis = datePickerState.selectedDateMillis
                    // SPEC-401-A R49 (Lens A #3) — gate on validateDate.
                    if (millis != null && validateDate(millis)) {
                        // ISO8601 normalised to midnight UTC of the picked day.
                        val isoStr = isoFormatter.format(java.util.Date(millis))
                        if (effectiveMode == "datetime") {
                            pendingDate = isoStr
                            showTimePicker = true
                        } else {
                            displayText = formatForDisplay(isoStr)
                            inputValues[fieldId] = isoStr
                            persistAgeIfDate(millis)
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
                    if (effectiveMode == "datetime" && pendingDate.isNotEmpty()) {
                        // Combine: parse pendingDate (ISO date), apply hour/minute,
                        // re-serialise to ISO8601 with the user's chosen time.
                        try {
                            val baseDate = isoFormatter.parse(pendingDate)
                            if (baseDate != null) {
                                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                                cal.time = baseDate
                                cal.set(java.util.Calendar.HOUR_OF_DAY, timePickerState.hour)
                                cal.set(java.util.Calendar.MINUTE, timePickerState.minute)
                                cal.set(java.util.Calendar.SECOND, 0)
                                val combined = isoFormatter.format(cal.time)
                                displayText = formatForDisplay(combined)
                                inputValues[fieldId] = combined
                            }
                        } catch (_: Exception) {
                            // Fallback: keep pendingDate alone if parse fails.
                            displayText = formatForDisplay(pendingDate)
                            inputValues[fieldId] = pendingDate
                        }
                        pendingDate = ""
                    } else {
                        // Time-only mode: ISO8601 with today's date in UTC.
                        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                        cal.set(java.util.Calendar.HOUR_OF_DAY, timePickerState.hour)
                        cal.set(java.util.Calendar.MINUTE, timePickerState.minute)
                        cal.set(java.util.Calendar.SECOND, 0)
                        val timeStr = isoFormatter.format(cal.time)
                        displayText = formatForDisplay(timeStr)
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
    // SPEC-401-A R49 (Lens A #1) — multi-select branch. iOS
    // FormInputBlockViews.swift:403-406 selects between single + multi
    // based on `block.multi_select == true`. For multi-mode the value
    // written to inputValues is List<String> (list of selected values),
    // for single-mode it remains a single String. Dropdown layout is
    // single-only on both platforms (multi-dropdown is rare UX).
    val isMulti = (block.multi_select == true) ||
        ((block.field_config?.get("multi_select") as? Boolean) == true)
    var selectedValue by remember { mutableStateOf(inputValues[fieldId] as? String ?: "") }
    val initialSelected: Set<String> = remember {
        when (val existing = inputValues[fieldId]) {
            is List<*> -> existing.filterIsInstance<String>().toSet()
            is String -> if (existing.isNotEmpty()) setOf(existing) else emptySet()
            else -> emptySet()
        }
    }
    var selectedValues by remember { mutableStateOf(initialSelected) }
    fun toggleMulti(value: String) {
        selectedValues = if (selectedValues.contains(value)) {
            selectedValues - value
        } else {
            selectedValues + value
        }
        inputValues[fieldId] = selectedValues.toList()
    }
    fun isOptionSelected(value: String): Boolean =
        if (isMulti) selectedValues.contains(value) else selectedValue == value
    fun pickOption(value: String) {
        if (isMulti) {
            toggleMulti(value)
        } else {
            selectedValue = value
            inputValues[fieldId] = value
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        when (displayStyle) {
            "stacked" -> {
                // Stacked: Column of cards with RadioButton (single) or Checkbox (multi).
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    options.forEach { option ->
                        // SPEC-401-A R49 (Lens A #1) — multi-aware selection.
                        val isSelected = isOptionSelected(option.value)
                        // SPEC-401-A R64 (Lens C P1) — single click target so
                        // TalkBack treats Card+Checkbox/RadioButton as ONE
                        // element matching iOS FormInputBlockViews.swift
                        // :559-620 single-Button wrap. Was Card.clickable +
                        // inner Checkbox.onCheckedChange / RadioButton.onClick
                        // → 2 click targets per row, double announcement,
                        // 2× swipes per option to traverse.
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isMulti) {
                                        Modifier.toggleable(
                                            value = isSelected,
                                            role = androidx.compose.ui.semantics.Role.Checkbox,
                                            onValueChange = { pickOption(option.value) },
                                        )
                                    } else {
                                        Modifier.selectable(
                                            selected = isSelected,
                                            role = androidx.compose.ui.semantics.Role.RadioButton,
                                            onClick = { pickOption(option.value) },
                                        )
                                    }
                                ),
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
                                if (isMulti) {
                                    Checkbox(
                                        checked = isSelected,
                                        // SPEC-401-A R64 (Lens C P1) — null
                                        // handler so Card.toggleable owns
                                        // the click + a11y semantics.
                                        onCheckedChange = null,
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = StyleEngine.parseColor(block.field_style?.fill_color ?: "#6366F1"),
                                        ),
                                    )
                                } else {
                                    RadioButton(
                                        selected = isSelected,
                                        // SPEC-401-A R64 (Lens C P1) — null
                                        // handler so Card.selectable owns
                                        // the click + a11y semantics.
                                        onClick = null,
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = StyleEngine.parseColor(block.field_style?.fill_color ?: "#6366F1"),
                                        ),
                                    )
                                }
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
                                // SPEC-401-A R49 (Lens A #1) — multi-aware selection.
                                val isSelected = isOptionSelected(option.value)
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { pickOption(option.value) },
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
                        // SPEC-401-A R44 — theme-adaptive content color (was Color.DarkGray
                // — invisible on dark surface in dark mode).
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
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

        // SPEC-401-A R68 (Lens A P2) — tooltip caption below select grid /
        // stacked / dropdown matching iOS FormInputBlockViews.swift:769-770
        // + 926-937. Reads field_config.tooltip_text + tooltip_icon.
        // Renders 12sp caption with optional 12dp leading icon at 50% alpha.
        val tooltipText = block.field_config?.get("tooltip_text") as? String
        val tooltipIconRef = block.field_config?.get("tooltip_icon") as? String
        if (!tooltipText.isNullOrBlank()) {
            val captionColor = StyleEngine.parseColor(block.field_style?.text_color ?: "#1A1A1A").copy(alpha = 0.5f)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (!tooltipIconRef.isNullOrBlank()) {
                    val iconRef = ai.appdna.sdk.core.resolveIcon(tooltipIconRef)
                    if (iconRef != null) {
                        ai.appdna.sdk.core.IconView(ref = iconRef, defaultSize = 12f)
                    }
                }
                Text(
                    text = tooltipText,
                    fontSize = 12.sp,
                    color = captionColor,
                )
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
    // SPEC-401-A R61 (Lens A N2, P1) — default step_value=1.0 matches iOS
    // FormInputBlockViews.swift:953 `block.step_value ?? 1`. Was 0.0
    // (continuous), out of parity with iOS integer-snap default; also made
    // the R59 step-tick haptic never fire because bucket-change gate was
    // `stepVal > 0f`.
    val stepVal = (block.step_value ?: 1.0).toFloat()
    val unitStr = block.unit ?: ""
    val fillCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_color ?: "#6366F1")
    // SPEC-401-A R55 (Lens C R55 #3, P3) — inactive track color from
    // field_style.track_color (or block.track_color) per iOS
    // FormInputBlockViews.swift slider track. Was Material default purple.
    val trackCol = StyleEngine.parseColor(block.field_style?.track_color ?: block.track_color ?: "#E5E7EB")
    // OB-6 audit follow-up — restore saved value on back nav.
    var value by remember {
        mutableStateOf(
            (inputValues[fieldId] as? Number)?.toFloat()
                ?: (block.default_picker_value ?: minVal.toDouble()).toFloat()
        )
    }
    // SPEC-401-A R55 (Lens C R55 #2, P2) — discrete step gradations matching
    // iOS `Slider(value:in:step:)` at FormInputBlockViews.swift. With step=0.5
    // on 0–10 range Android was continuous; iOS snaps to 21 stops.
    val stepCount = if (stepVal > 0f) {
        ((maxVal - minVal) / stepVal - 1).toInt().coerceAtLeast(0)
    } else 0
    // SPEC-401-A R55 (Lens C R55 #2 cont., P2) — value formatting honors
    // fractional step so 7.5 doesn't display as 8. Matches iOS
    // String(format: "%.1f", value) when step < 1.
    val displayValue = if (stepVal > 0f && stepVal < 1f) {
        "%.1f".format(value)
    } else {
        "${value.roundToInt()}"
    }
    // SPEC-401-A R57 (Lens A R57 #3, P2) — honor field_config.show_value=false
    // matching iOS FormInputBlockViews.swift:954 (`block.field_config?
    // ["show_value"]?.value as? Bool ?? true`). Was always rendered on Android.
    val showValue = (block.field_config?.get("show_value") as? Boolean) ?: true

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FormFieldLabel(block)
            if (showValue) {
                Text(
                    text = "$displayValue$unitStr",
                    // SPEC-401-A R57 (Lens A R57 #2, P2) — 15sp matches iOS
                    // .subheadline.weight(.semibold) at FormInputBlockViews
                    // .swift:966. Was 14sp.
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fillCol,
                )
            }
        }

        // SPEC-401-A R59 (Lens C P2 #1) — discrete-step haptic tick on each
        // step crossing matching iOS native `Slider(value:in:step:)` which
        // auto-emits UISelectionFeedbackGenerator. Compose Slider has no
        // equivalent so we gate on bucket-index change.
        val sliderHapticView = androidx.compose.ui.platform.LocalView.current
        val sliderLastBucket = remember(fieldId) {
            mutableStateOf(if (stepVal > 0f) ((value - minVal) / stepVal).toInt() else 0)
        }
        Slider(
            value = value,
            onValueChange = { v ->
                value = v
                inputValues[fieldId] = v.toDouble()
                if (stepVal > 0f && maxVal > minVal) {
                    val bucket = ((v - minVal) / stepVal).toInt()
                    if (bucket != sliderLastBucket.value) {
                        sliderLastBucket.value = bucket
                        ai.appdna.sdk.core.HapticEngine.trigger(sliderHapticView, ai.appdna.sdk.core.HapticType.SELECTION)
                    }
                }
            },
            valueRange = minVal..maxVal,
            steps = stepCount,
            colors = SliderDefaults.colors(
                thumbColor = fillCol,
                activeTrackColor = fillCol,
                inactiveTrackColor = trackCol,
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
    // OB-6 audit follow-up — restore saved value on back nav.
    var checked by remember {
        mutableStateOf((inputValues[fieldId] as? Boolean) ?: (block.toggle_default ?: false))
    }

    // SPEC-401-A R60 (Lens C P2 #1) — wrap label+Switch in `toggleable` so
    // TalkBack treats the row as a single switch element matching iOS
    // `Toggle("label", isOn:)` (FormInputBlockViews.swift). Switch becomes
    // presentational (onCheckedChange = null); the Row owns the click +
    // a11y semantics. Without this users had to swipe twice through label
    // then "Switch, On".
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = androidx.compose.ui.semantics.Role.Switch,
                onValueChange = {
                    checked = it
                    inputValues[fieldId] = it
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SPEC-401-A R57 (Lens A R57 #5, P2) — 15sp matches iOS .subheadline
        // (FormInputBlockViews.swift:999). Was 14sp.
        Text(text = label, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Switch(
            checked = checked,
            // SPEC-401-A R60 (Lens C P2 #1) — null handler so the Row's
            // toggleable owns the click + a11y semantics; Switch is a
            // presentational visual.
            onCheckedChange = null,
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
    // SPEC-401-A R57 (Lens C R57 #1, P2) — host View for haptic feedback on
    // +/- tap. Mirrors iOS UIStepper auto-emitting click haptic on every
    // increment/decrement (system behavior). Sibling FormStepComposable.kt
    // legacy-form path already fires LIGHT haptic on the same buttons —
    // this content-block path was missing it.
    val view = androidx.compose.ui.platform.LocalView.current
    // OB-6 audit follow-up — restore saved value on back nav.
    var value by remember {
        mutableStateOf(
            (inputValues[fieldId] as? Number)?.toInt()
                ?: (block.default_picker_value ?: minVal.toDouble()).toInt()
        )
    }

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
            // SPEC-401-A R38 (Lens C #3) — a11y labels on +/- buttons.
            // Native iOS Stepper ships built-in "Decrement"/"Increment"
            // labels via VoiceOver; Compose OutlinedButton wrapping a "-"/
            // "+" Text glyph reads as just the punctuation character.
            OutlinedButton(
                onClick = {
                    if (value - stepVal >= minVal) {
                        value -= stepVal
                        inputValues[fieldId] = value
                        // SPEC-401-A R57 (Lens C R57 #1, P2) — LIGHT haptic
                        // mirrors iOS UIStepper system click haptic.
                        ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.LIGHT)
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = "Decrease" },
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("-", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "$value$unitStr",
                // SPEC-401-A R56→R57 (Lens A R56 #9, P3) — 17sp Medium matches iOS
                // .body.weight(.medium) (FormInputBlockViews.swift:1035). Was 18sp
                // SemiBold — 1pt larger, one weight notch heavier.
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = {
                    if (value + stepVal <= maxVal) {
                        value += stepVal
                        inputValues[fieldId] = value
                        // SPEC-401-A R57 (Lens C R57 #1, P2) — LIGHT haptic
                        // mirrors iOS UIStepper system click haptic.
                        ai.appdna.sdk.core.HapticEngine.trigger(view, ai.appdna.sdk.core.HapticType.LIGHT)
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .semantics { contentDescription = "Increase" },
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
    // OB-6 audit follow-up — restore saved value on back nav.
    var selectedValue by remember {
        mutableStateOf(
            (inputValues[fieldId] as? String) ?: (options.firstOrNull()?.value ?: "")
        )
    }
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
                // SPEC-401-A R45 — theme-adaptive segmented border (was Color.Gray).
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
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
                        // SPEC-401-A R38 (Lens C #5) — theme-aware unselected
                        // text. Was Color.DarkGray which is invisible against
                        // MaterialTheme.colorScheme.surface in dark mode.
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
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
    // SPEC-401-A B2 P1 — DTO field-name + priority parity with iOS.
    // iOS reads `filled_color ?? field_style.fill_color`, Android was
    // reversed (field_style.fill_color first). Console can author either
    // — sample author writes `filled_color` so iOS picked it up but
    // Android ignored it whenever both were set.
    // SPEC-401-A R56 (Lens A R56 #1, P1) — restore iOS canonical priority
    // chain `filled_color ?? field_style.fill_color`, `empty_color ?? "#D1D5DB"`
    // (FormInputBlockViews.swift:1095-1096). Sibling content-block RatingBlock
    // already uses this; only the form-input variant was broken — author-set
    // `filled_color` / `empty_color` were silently ignored.
    val filledCol = StyleEngine.parseColor(block.filled_color ?: block.active_rating_color ?: block.field_style?.fill_color ?: "#FBBF24")
    val emptyCol = StyleEngine.parseColor(block.empty_color ?: block.inactive_rating_color ?: "#D1D5DB")
    val allowHalf = block.allow_half == true
    // SPEC-401-A — promote selectedRating to Double for half-star round-trip.
    // Mirrors iOS FormInputBlockViews.swift:1106 which already supports
    // half-stars when block.allow_half is true.
    var selectedRating by remember {
        mutableStateOf(
            (inputValues[fieldId] as? Number)?.toDouble() ?: (block.default_value ?: 0.0)
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 1..maxStars) {
                val starState: Double = when {
                    selectedRating >= i -> 1.0
                    allowHalf && selectedRating >= i - 0.5 -> 0.5
                    else -> 0.0
                }
                // SPEC-401-A — repeat-tap toggle for half-stars. Compose
                // `clickable` doesn't expose tap coordinates; the toggle
                // pattern (full → .5 → full) reaches every 0.5 step iOS
                // reaches via tap-location math, without `pointerInput`.
                Icon(
                    imageVector = when (starState) {
                        1.0 -> Icons.Filled.Star
                        0.5 -> Icons.Filled.StarHalf
                        else -> Icons.Outlined.Star
                    },
                    contentDescription = if (starState >= 1.0) "$i stars" else if (starState > 0.0) "${i - 0.5} stars" else "$i stars (empty)",
                    tint = if (starState > 0.0) filledCol else emptyCol,
                    modifier = Modifier
                        .size(starSize.value.dp)
                        .clickable {
                            val newRating: Double = if (allowHalf && selectedRating == i.toDouble()) {
                                (i - 0.5).coerceAtLeast(0.5)
                            } else {
                                i.toDouble()
                            }
                            selectedRating = if (allowHalf) newRating else newRating.toInt().toDouble()
                            inputValues[fieldId] = selectedRating
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
    // SPEC-401-A R61 (Lens A N2, P1) — default step_value=1.0 matches iOS
    // FormInputBlockViews.swift:953 `block.step_value ?? 1`. Was 0.0
    // (continuous), out of parity with iOS integer-snap default; also made
    // the R59 step-tick haptic never fire because bucket-change gate was
    // `stepVal > 0f`.
    val stepVal = (block.step_value ?: 1.0).toFloat()
    val unitStr = block.unit ?: ""
    val fillCol = StyleEngine.parseColor(block.field_style?.fill_color ?: block.active_color ?: "#6366F1")
    // SPEC-401-A R55 (Lens C R55 #3, P3) — inactive track color from
    // field_style.track_color (or block.track_color) per iOS.
    val trackCol = StyleEngine.parseColor(block.field_style?.track_color ?: block.track_color ?: "#E5E7EB")
    // OB-6 audit follow-up — restore saved range on back nav.
    // Saved range is stored as Map<"min","max"> under fieldId per the
    // write sites below (Slider onValueChange + LaunchedEffect).
    val savedMap = inputValues[fieldId] as? Map<*, *>
    var lowValue by remember {
        mutableStateOf((savedMap?.get("min") as? Number)?.toFloat() ?: minVal)
    }
    var highValue by remember {
        mutableStateOf((savedMap?.get("max") as? Number)?.toFloat() ?: maxVal)
    }
    // SPEC-401-A R55 (Lens C R55 #2, P2) — discrete step gradations + iOS
    // value formatting honoring fractional step.
    val stepCount = if (stepVal > 0f) {
        ((maxVal - minVal) / stepVal - 1).toInt().coerceAtLeast(0)
    } else 0
    val low = if (stepVal > 0f && stepVal < 1f) "%.1f".format(lowValue) else "${lowValue.roundToInt()}"
    val high = if (stepVal > 0f && stepVal < 1f) "%.1f".format(highValue) else "${highValue.roundToInt()}"

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
                text = "$low$unitStr - $high$unitStr",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = fillCol,
            )
        }

        // SPEC-401-A R59 (Lens C P2 #1) — discrete-step haptic tick on each
        // step crossing for both range thumbs (matches iOS native Slider).
        val rangeHapticView = androidx.compose.ui.platform.LocalView.current
        val lowLastBucket = remember(fieldId) {
            mutableStateOf(if (stepVal > 0f) ((lowValue - minVal) / stepVal).toInt() else 0)
        }
        val highLastBucket = remember(fieldId) {
            mutableStateOf(if (stepVal > 0f) ((highValue - minVal) / stepVal).toInt() else 0)
        }

        // Min slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            // SPEC-401-A R44 — theme-adaptive secondary (was Color.Gray).
            Text("Min", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.width(30.dp))
            Slider(
                value = lowValue,
                onValueChange = { v ->
                    lowValue = v
                    if (lowValue > highValue) highValue = lowValue
                    inputValues[fieldId] = mapOf("min" to lowValue.toDouble(), "max" to highValue.toDouble())
                    if (stepVal > 0f && maxVal > minVal) {
                        val bucket = ((v - minVal) / stepVal).toInt()
                        if (bucket != lowLastBucket.value) {
                            lowLastBucket.value = bucket
                            ai.appdna.sdk.core.HapticEngine.trigger(rangeHapticView, ai.appdna.sdk.core.HapticType.SELECTION)
                        }
                    }
                },
                valueRange = minVal..maxVal,
                steps = stepCount,
                colors = SliderDefaults.colors(thumbColor = fillCol, activeTrackColor = fillCol, inactiveTrackColor = trackCol),
                modifier = Modifier.weight(1f),
            )
        }
        // Max slider
        Row(verticalAlignment = Alignment.CenterVertically) {
            // SPEC-401-A R44 — theme-adaptive secondary (was Color.Gray).
            Text("Max", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.width(30.dp))
            Slider(
                value = highValue,
                onValueChange = { v ->
                    highValue = v
                    if (highValue < lowValue) lowValue = highValue
                    inputValues[fieldId] = mapOf("min" to lowValue.toDouble(), "max" to highValue.toDouble())
                    if (stepVal > 0f && maxVal > minVal) {
                        val bucket = ((v - minVal) / stepVal).toInt()
                        if (bucket != highLastBucket.value) {
                            highLastBucket.value = bucket
                            ai.appdna.sdk.core.HapticEngine.trigger(rangeHapticView, ai.appdna.sdk.core.HapticType.SELECTION)
                        }
                    }
                },
                valueRange = minVal..maxVal,
                steps = stepCount,
                colors = SliderDefaults.colors(thumbColor = fillCol, activeTrackColor = fillCol, inactiveTrackColor = trackCol),
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
    // OB-6 audit follow-up — restore saved chips selection on back nav.
    var selectedValues by remember {
        mutableStateOf(
            (inputValues[fieldId] as? List<*>)?.filterIsInstance<String>()?.toSet()
                ?: (inputValues[fieldId] as? Set<*>)?.filterIsInstance<String>()?.toSet()
                ?: setOf()
        )
    }

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
                        // SPEC-401-A R21 — match iOS FormInputBlockViews.swift
                        // :1220 idle background `Color.gray.opacity(0.1)`. Was
                        // 0.05f (half opacity), making idle chips noticeably
                        // fainter on Android for the same JSON.
                        .background(if (isSelected) fillCol else Color.Gray.copy(alpha = 0.1f))
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
                        // SPEC-401-A R57 (Lens A R57 #4, P2) — 15sp matches iOS
                        // .font(.subheadline) at FormInputBlockViews.swift:1217.
                        fontSize = 15.sp,
                        // SPEC-401-A R21 — match iOS FormInputBlockViews.swift
                        // :1221 idle text uses `.primary` (theme-aware: black
                        // in light mode, white in dark mode). Android was
                        // hardcoding Color.DarkGray which renders dark-grey
                        // on dark-grey backgrounds in dark mode (invisible).
                        // MaterialTheme.colorScheme.onSurface gives the
                        // equivalent theme-aware color on Android.
                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
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
    // OB-6 audit follow-up — restore saved color on back nav.
    var selectedColor by remember {
        mutableStateOf((inputValues[fieldId] as? String) ?: "")
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FormFieldLabel(block)

        // SPEC-401-A R73 (Lens A P2 #1) — fixed 5-col grid matches iOS
        // FormInputBlockViews.swift:1257 `LazyVGrid(columns: 5)`. FlowRow
        // packed 7-10+ swatches per row depending on screen width while
        // iOS always renders 2×5 layout for 10 colors. Same authored
        // preset rendered visibly different across phones / iOS.
        val cols = 5
        val chunked = presetColors.chunked(cols)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chunked.forEach { rowColors ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowColors.forEach { color ->
                        val isSelected = selectedColor == color
                        // SPEC-401-A R73 (Lens A P2 #2) — selection-ring inset
                        // mirrors iOS FormInputBlockViews.swift:1264-1266
                        // `.stroke(...).padding(2)`: outer Box draws the ring,
                        // inner Box (the swatch fill) sits with 2dp inset so a
                        // 2dp halo gap separates ring from color. Was drawing
                        // border directly on the swatch — visually thickened
                        // it by 3dp instead of overlaying with a gap.
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    // SPEC-401-A R44 — theme-adaptive selection ring
                                    // (was Color.DarkGray — invisible on dark surface).
                                    // iOS uses Color.primary at FormInputBlockViews.swift:1264.
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                    shape = CircleShape,
                                )
                                .clickable {
                                    selectedColor = color
                                    inputValues[fieldId] = color
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(if (isSelected) 4.dp else 0.dp)
                                    .clip(CircleShape)
                                    .background(StyleEngine.parseColor(color))
                            )
                        }
                    }
                    // Pad shorter last row so swatches stay same width as
                    // full-row swatches (avoids stretching to full width).
                    repeat(cols - rowColors.size) { Spacer(Modifier.weight(1f)) }
                }
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
                // SPEC-401-A R45 — honour field_style.background_color
                // (was hardcoded white #F9FAFB). iOS reads field_style
                // background_color or transparent fallback
                // (FormInputBlockViews.swift:1304).
                .background(
                    block.field_style?.background_color
                        ?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }
                        ?.let { StyleEngine.parseColor(it) }
                        ?: Color.Transparent
                )
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
                // SPEC-401-A R45 — theme-adaptive label (was Color.Gray).
                // iOS .secondary (FormInputBlockViews.swift:1297-1300).
                Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
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
    // SPEC-070-A finalization OB-6 — restore saved value on back nav.
    // iOS pre-populates `_inputValues = State(initialValue: savedResponses ?? [:])`
    // (`OnboardingRenderer.swift:1326`); Android previously initialized to ""
    // so every back-press wiped typed answers. Read from inputValues map.
    var text by remember { mutableStateOf((inputValues[fieldId] as? String) ?: "") }
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
            // SPEC-401-A R44 — drop hardcoded Color.Gray placeholder color so
            // Material3 OutlinedTextFieldDefaults adaptive placeholder color
            // applies (theme-aware in dark mode).
            placeholder = { Text(block.field_placeholder ?: "Search location...") },
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
                    // SPEC-401-A R38 (Lens C #4) — theme-aware surface so the
                    // suggestion list is readable in dark mode (was hardcoded
                    // #F9FAFB white panel + Color.Black text).
                    .background(MaterialTheme.colorScheme.surface)
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
                        Text(text = suggestion.address, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (index < suggestions.size - 1) {
                        // SPEC-401-A R24 — Material3 1.2 deprecated `Divider`
                        // in favor of `HorizontalDivider` (same API surface,
                        // distinct widget). Pre-emptive switch silences runtime
                        // warnings + unblocks the next BOM bump (1.4 removes).
                        // SPEC-401-A R45 — theme-adaptive divider (was Color.LightGray).
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
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

/** Fetch location suggestions from the AppDNA backend geocoding API.
 *
 * SPEC-070-A A.12: Path is `/api/v1/sdk/geocode/autocomplete` (matches the actual
 * SDK-scoped backend route — the previous `/api/v1/geocoding/autocomplete` 404'd).
 * Host is sourced from the configured environment so sandbox builds hit the
 * correct backend; the SDK API key is forwarded via `x-api-key` to match other
 * SDK calls.
 */
private suspend fun fetchLocationSuggestions(query: String): List<LocationSuggestion> {
    return try {
        val baseUrl = ai.appdna.sdk.AppDNA.getApiBaseUrl()
        val apiKey = ai.appdna.sdk.AppDNA.getApiKey()
        val url = java.net.URL("$baseUrl/api/v1/sdk/geocode/autocomplete")
        // SPEC-401-A R28 P0 — backend at `src/app/api/v1/sdk/geocode/
        // autocomplete/route.ts:27` exports ONLY POST. GET returned 405 →
        // silent emptyList → suggestions never appeared. iOS POSTs JSON
        // body via Endpoint.geocodeAutocomplete (LocationFieldView.swift
        // :197-234). Both sites in this file (this `LocationFieldComposable`
        // helper inline path + the canonical helper in
        // LocationFieldComposable.kt) need the POST body shape.
        val requestBody = org.json.JSONObject().apply {
            put("query", query)
            put("limit", 5)
        }.toString()
        val body: String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val connection = (url.openConnection() as? java.net.HttpURLConnection)?.apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey != null) setRequestProperty("x-api-key", apiKey)
            } ?: return@withContext null
            try {
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode != 200) return@withContext null
                connection.inputStream.bufferedReader().readText()
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        if (body == null) return emptyList()
        // Backend wraps in `{data: {suggestions: [...]}}`; iOS reads
        // `json.data.suggestions`. Was reading `json.data` directly →
        // null because data is an object.
        val json = org.json.JSONObject(body)
        val results = json.optJSONObject("data")?.optJSONArray("suggestions") ?: return emptyList()

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
                    // SPEC-401-A R21 \u2014 match iOS FormInputBlockViews.swift
                    // :1687,1704 which reads `field_style.border_width ?? 1`.
                    // Android was hardcoding 1.dp, dropping any console-set
                    // thickness on the populated thumbnail state.
                    .border(
                        ((block.field_style?.border_width ?: 1.0).toFloat()).dp,
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
                // SPEC-401-A R21 \u2014 match iOS FormInputBlockViews.swift
                // :1707-1712 edit overlay: filled white circle + pencil
                // glyph + shadow. Android was rendering a small black emoji
                // that disappeared against dark thumbnails.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .shadow(2.dp, CircleShape)
                        .background(Color.White.copy(alpha = 0.9f), CircleShape)
                        .padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit image",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        } else {
            // Empty state — dashed border tap target
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape((block.field_style?.corner_radius ?: 8.0).dp))
                    // SPEC-401-A R21 — same fixes R20 made for FormInputSignature
                    // also apply here. iOS reads field_style.background_color
                    // (transparent default) AND uses dashed `StrokeStyle
                    // (lineWidth: 1, dash: [6, 3])`. Android was hardcoding
                    // #F9FAFB + solid border.
                    .background(
                        block.field_style?.background_color
                            ?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }
                            ?.let { StyleEngine.parseColor(it) }
                            ?: Color.Transparent
                    )
                    .drawBehind {
                        val cornerR = ((block.field_style?.corner_radius ?: 8.0).toFloat()) * density
                        val borderColorParsed = StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB")
                        val strokeWidthPx = 1f * density
                        drawRoundRect(
                            color = borderColorParsed,
                            size = size,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = strokeWidthPx,
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                    floatArrayOf(6f * density, 3f * density),
                                    0f,
                                ),
                            ),
                        )
                    }
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
                    // SPEC-401-A R45 — theme-adaptive prompt (was Color.Gray).
                    // iOS .secondary (FormInputBlockViews.swift:1720).
                    // SPEC-401-A R57 (Lens A R57 #11, P2) — 15sp matches iOS
                    // .subheadline (FormInputBlockViews.swift:1719).
                    Text(text = "Tap to pick image", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
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
                // SPEC-401-A R20 — match iOS FormInputBlockViews.swift:1791:
                // background reads `field_style.background_color` (default
                // transparent). Android was hardcoding #F9FAFB which silently
                // overrode any console-published field background.
                .background(
                    block.field_style?.background_color
                        ?.takeIf { it.isNotEmpty() && it.lowercase() != "transparent" }
                        ?.let { StyleEngine.parseColor(it) }
                        ?: Color.Transparent
                )
                // SPEC-401-A R20 — match iOS FormInputBlockViews.swift:1797:
                // `.stroke(borderColor, style: StrokeStyle(lineWidth: 1,
                // dash: [6, 3]))`. Compose `Modifier.border` is solid only;
                // use `drawBehind` with `PathEffect.dashPathEffect` for the
                // dotted-stroke convention. Was rendering as solid border on
                // Android for the same JSON.
                .drawBehind {
                    val cornerR = ((block.field_style?.corner_radius ?: 8.0).toFloat()) * density
                    val borderColorParsed = StyleEngine.parseColor(block.field_style?.border_color ?: "#D1D5DB")
                    val strokeWidthPx = 1f * density
                    drawRoundRect(
                        color = borderColorParsed,
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = strokeWidthPx,
                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                                floatArrayOf(6f * density, 3f * density),
                                0f,
                            ),
                        ),
                    )
                }
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
            // SPEC-401-A B2 P1 \u2014 theme-aware stroke colour + 2.dp width
            // matching iOS `FormInputBlockViews.swift:1780-1789` (which uses
            // `.color(.primary)` and `lineWidth: 2`). Was hardcoded
            // `Color.Black` + 4f (twice as thick + invisible in dark mode).
            val strokeColor = MaterialTheme.colorScheme.onSurface
            val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
            Canvas(modifier = Modifier.fillMaxSize()) {
                val allLines = lines.toList() + listOf(currentLine)
                allLines.forEach { line ->
                    if (line.size > 1) {
                        for (i in 0 until line.size - 1) {
                            drawLine(
                                color = strokeColor,
                                start = line[i],
                                end = line[i + 1],
                                strokeWidth = strokePx,
                                cap = StrokeCap.Round,
                            )
                        }
                    }
                }
            }

            // SPEC-401-A B3 P2 \u2014 clear button is now an icon-only Material
            // `IconButton` (Material trailing-action pattern). iOS uses an
            // SF Symbol icon button \u2014 Android equivalent is `Icons.Filled.Close`.
            if (lines.isNotEmpty()) {
                IconButton(
                    onClick = {
                        lines.clear()
                        currentLine = emptyList()
                        inputValues.remove(fieldId)
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Clear signature",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
        // SPEC-401-A B3 P2 \u2014 placeholder caption now sits BELOW the canvas
        // (iOS standard form-field empty-state) rather than centred inside.
        // Inside-canvas placeholder was less discoverable when the user
        // started drawing.
        if (lines.isEmpty() && currentLine.isEmpty()) {
            Text(
                // SPEC-401-A R23 — match iOS FormInputBlockViews.swift:1825
                // ("Draw your signature above"). Android was "...here".
                text = "Draw your signature above",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
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
            // SPEC-401-A R45 — theme-adaptive stub label (was Color.Gray).
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }
    // In non-debug mode: renders nothing (empty composable)
}
