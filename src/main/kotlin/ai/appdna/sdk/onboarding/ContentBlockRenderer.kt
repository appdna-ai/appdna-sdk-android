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
)

// MARK: - Content Block Renderer

@Composable
fun ContentBlockRendererView(
    blocks: List<ContentBlock>,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
    loc: ((String, String) -> String)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        blocks.forEach { block ->
            RenderBlock(block = block, onAction = onAction, toggleValues = toggleValues, loc = loc)
        }
    }
}

@Composable
private fun RenderBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
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
                RenderBlockContent(block, onAction, toggleValues, loc)
            }
        }
    } else {
        Box(modifier = contentModifier) {
            RenderBlockContent(block, onAction, toggleValues, loc)
        }
    }
}

@Composable
private fun RenderBlockContent(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
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
        // SPEC-089d Phase A: New onboarding block types (stubs)
        "page_indicator" -> StubBlockPlaceholder("page_indicator")
        "wheel_picker" -> StubBlockPlaceholder("wheel_picker")
        "pulsing_avatar" -> StubBlockPlaceholder("pulsing_avatar")
        "social_login" -> StubBlockPlaceholder("social_login")
        "timeline" -> StubBlockPlaceholder("timeline")
        "animated_loading" -> StubBlockPlaceholder("animated_loading")
        "star_background" -> StubBlockPlaceholder("star_background")
        "countdown_timer" -> StubBlockPlaceholder("countdown_timer")
        "rating" -> StubBlockPlaceholder("rating")
        "rich_text" -> StubBlockPlaceholder("rich_text")
        "progress_bar" -> StubBlockPlaceholder("progress_bar")
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
