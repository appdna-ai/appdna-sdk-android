package ai.appdna.sdk.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.core.StyleEngine

// MARK: - Content Block data class

data class ContentBlock(
    val id: String,
    val type: String,  // heading, text, image, button, spacer, list, divider, badge, icon, toggle, video
    val text: String? = null,
    val level: Int? = null,
    val image_url: String? = null,
    val corner_radius: Double? = null,
    val height: Double? = null,
    val action: String? = null,
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
)

// MARK: - Content Block Renderer

@Composable
fun ContentBlockRendererView(
    blocks: List<ContentBlock>,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        blocks.forEach { block ->
            RenderBlock(block = block, onAction = onAction, toggleValues = toggleValues)
        }
    }
}

@Composable
private fun RenderBlock(
    block: ContentBlock,
    onAction: (String) -> Unit,
    toggleValues: MutableMap<String, Boolean>,
) {
    when (block.type) {
        "heading" -> HeadingBlock(block)
        "text" -> TextBlock(block)
        "image" -> ImageBlock(block)
        "button" -> ButtonBlock(block, onAction)
        "spacer" -> Spacer(modifier = Modifier.height((block.spacer_height ?: 16.0).dp))
        "list" -> ListBlock(block)
        "divider" -> DividerBlock(block)
        "badge" -> BadgeBlock(block)
        "icon" -> IconBlock(block)
        "toggle" -> ToggleBlock(block, toggleValues)
        "video" -> VideoBlock(block)
    }
}

@Composable
private fun HeadingBlock(block: ContentBlock) {
    val fontSize = when (block.level ?: 1) {
        1 -> 28.sp
        2 -> 22.sp
        3 -> 18.sp
        else -> 28.sp
    }
    Text(
        text = block.text ?: "",
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun TextBlock(block: ContentBlock) {
    Text(
        text = block.text ?: "",
        fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ImageBlock(block: ContentBlock) {
    // Placeholder — real image loading needs Coil/Glide
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((block.height ?: 200.0).dp)
            .clip(RoundedCornerShape((block.corner_radius ?: 0.0).dp))
            .background(Color.Gray.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("\uD83D\uDDBC", fontSize = 32.sp) // Image emoji placeholder
    }
}

@Composable
private fun ButtonBlock(block: ContentBlock, onAction: (String) -> Unit) {
    Button(
        onClick = { onAction(block.action ?: "next") },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape((block.button_corner_radius ?: 12.0).dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = StyleEngine.parseColor(block.bg_color ?: "#6366F1"),
        ),
    ) {
        Text(
            text = block.text ?: "Continue",
            fontWeight = FontWeight.SemiBold,
            color = StyleEngine.parseColor(block.text_color ?: "#FFFFFF"),
        )
    }
}

@Composable
private fun ListBlock(block: ContentBlock) {
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
                Text(text = item, fontSize = 16.sp)
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
private fun BadgeBlock(block: ContentBlock) {
    Text(
        text = block.badge_text ?: "",
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
        Text(
            text = block.icon_emoji ?: "",
            fontSize = (block.icon_size ?: 32.0).sp,
        )
    }
}

@Composable
private fun ToggleBlock(block: ContentBlock, toggleValues: MutableMap<String, Boolean>) {
    var checked by remember { mutableStateOf(toggleValues[block.id] ?: (block.toggle_default ?: false)) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = block.toggle_label ?: "",
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
            Text(text = it, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun VideoBlock(block: ContentBlock) {
    // Thumbnail with play icon — full video in SPEC-085
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height((block.height ?: 200.0).dp)
            .clip(RoundedCornerShape((block.corner_radius ?: 8.0).dp))
            .background(Color.Gray.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Text("\u25B6", fontSize = 48.sp, color = Color.White.copy(alpha = 0.9f))
    }
}
