package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
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

    // Outer margin (top/bottom only — blocks are full-width in a Column)
    if (style.margin_top != null) mod = mod.then(Modifier.padding(top = style.margin_top.dp))
    if (style.margin_bottom != null) mod = mod.then(Modifier.padding(bottom = style.margin_bottom.dp))

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

// MARK: - Content Block Renderer

@Composable
fun ContentBlockRendererView(
    blocks: List<ContentBlock>,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any> = mutableMapOf(),
    loc: ((String, String) -> String)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        blocks.forEach { block ->
            RenderBlock(block = block, onAction = onAction, toggleValues = toggleValues, inputValues = inputValues, loc = loc)
        }
    }
}

@Composable
private fun RenderBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    inputValues: MutableMap<String, Any> = mutableMapOf(),
    loc: ((String, String) -> String)? = null,
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
                RenderBlockContent(block, onAction, toggleValues, inputValues, loc)
            }
        }
    } else {
        Box(modifier = contentModifier) {
            RenderBlockContent(block, onAction, toggleValues, inputValues, loc)
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
        "page_indicator" -> PageIndicatorBlock(block)
        "social_login" -> SocialLoginBlock(block, onAction, loc)
        "countdown_timer" -> CountdownTimerBlock(block, onAction)
        "rating" -> RatingBlock(block, inputValues, loc)
        "rich_text" -> RichTextBlock(block, loc)
        "progress_bar" -> ProgressBarBlock(block, loc)
        "timeline" -> TimelineBlock(block, loc)
        "animated_loading" -> AnimatedLoadingBlock(block, onAction)
        // SPEC-089d Phase A: Remaining stubs
        "wheel_picker" -> StubBlockPlaceholder("wheel_picker")
        "pulsing_avatar" -> StubBlockPlaceholder("pulsing_avatar")
        "star_background" -> StubBlockPlaceholder("star_background")
        // SPEC-089d Phase F: Container & advanced block types (stubs)
        "stack" -> StubBlockPlaceholder("stack")
        "custom_view" -> StubBlockPlaceholder("custom_view")
        "date_wheel_picker" -> StubBlockPlaceholder("date_wheel_picker")
        "circular_gauge" -> StubBlockPlaceholder("circular_gauge")
        "row" -> StubBlockPlaceholder("row")
        // SPEC-089d Nurrai: Pricing card
        "pricing_card" -> StubBlockPlaceholder("pricing_card")
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
    ai.appdna.sdk.core.NetworkImage(
        url = block.image_url,
        modifier = Modifier
            .fillMaxWidth()
            .height((block.height ?: 200.0).dp)
            .clip(RoundedCornerShape((block.corner_radius ?: 0.0).dp)),
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
    Button(
        onClick = {
            val action = block.action ?: "next"
            when (action) {
                "link" -> {
                    block.action_value?.let { url ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            // Malformed URL or no browser available — fall through to advance
                        }
                    }
                    onAction("next")
                }
                "permission" -> {
                    // P1: Requires runtime permission request infrastructure.
                    // action_value will specify the permission type (e.g. "camera", "notifications").
                    // For now, advance the step as a safe fallback.
                    onAction("next")
                }
                else -> onAction(action)
            }
        },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape((block.button_corner_radius ?: 12.0).dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StyleEngine.parseColor(block.bg_color ?: "#6366F1"),
        ),
    ) {
        Text(
            text = loc?.invoke("block.${block.id}.text", text) ?: text,
            style = effectiveStyle,
            color = StyleEngine.parseColor(block.text_color ?: "#FFFFFF"),
        )
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
private fun PageIndicatorBlock(block: ContentBlock) {
    val dotCount = block.dot_count ?: 3
    val activeIndex = block.active_index ?: 0
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

            val (match, type) = matches.first()

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
private fun ProgressBarBlock(block: ContentBlock, loc: ((String, String) -> String)? = null) {
    val variant = block.variant ?: "continuous"
    val segmentCount = block.segment_count ?: 5
    val activeSegments = block.active_segments ?: 1
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
                        progress = { overallProgress },
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
                    progress = { overallProgress },
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
