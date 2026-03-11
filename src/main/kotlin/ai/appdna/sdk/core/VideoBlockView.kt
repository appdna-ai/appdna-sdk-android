package ai.appdna.sdk.core

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// In production: import androidx.media3.exoplayer.ExoPlayer
// In production: import androidx.media3.ui.PlayerView

data class VideoBlock(
    val video_url: String,
    val video_thumbnail_url: String? = null,
    val video_height: Float = 200f,
    val video_corner_radius: Float? = null,
    val autoplay: Boolean? = false,
    val loop: Boolean? = false,
    val muted: Boolean? = true,
    val controls: Boolean? = true,
    val inline_playback: Boolean? = true,
)

@Composable
fun VideoBlockView(block: VideoBlock) {
    var showThumbnail by remember { mutableStateOf(!(block.autoplay ?: false)) }
    val cornerRadius = (block.video_corner_radius ?: 0f).dp

    if (showThumbnail) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(block.video_height.dp)
                .clip(RoundedCornerShape(cornerRadius))
                .clickable { showThumbnail = false },
            contentAlignment = Alignment.Center,
        ) {
            // Thumbnail or gradient background
            if (block.video_thumbnail_url != null) {
                NetworkImage(
                    url = block.video_thumbnail_url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(Color(0xFF1A1A2E), Color(0xFF16213E))
                            )
                        )
                )
            }

            // Play button overlay
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u25B6", fontSize = 18.sp, color = Color.Black)
            }
        }
    } else {
        // In production, use ExoPlayer AndroidView:
        // AndroidView(factory = { context ->
        //     PlayerView(context).apply {
        //         player = ExoPlayer.Builder(context).build().apply {
        //             setMediaItem(MediaItem.fromUri(block.video_url))
        //             prepare(); if (block.autoplay == true) play()
        //         }
        //     }
        // }, modifier = Modifier.height(block.video_height.dp).clip(RoundedCornerShape(cornerRadius)))

        // Placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(block.video_height.dp)
                .clip(RoundedCornerShape(cornerRadius))
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Text("\u25B6 Playing video", color = Color.White, fontSize = 14.sp)
        }
    }
}
