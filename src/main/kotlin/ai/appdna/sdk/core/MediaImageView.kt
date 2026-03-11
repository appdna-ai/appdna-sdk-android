package ai.appdna.sdk.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

/// Handles SVG, GIF, and standard images uniformly
@Composable
fun MediaImageView(
    url: String,
    modifier: Modifier = Modifier,
    maxHeight: Float = 200f,
    cornerRadius: Float = 0f,
    contentScale: ContentScale = ContentScale.Crop,
) {
    // In production:
    // - SVG: use com.caverock.androidsvg:androidsvg for runtime SVG rendering
    // - GIF: use Coil with GifDecoder.Factory()
    // Both currently fall through to NetworkImage which handles standard formats

    NetworkImage(
        url = url,
        contentDescription = null,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .clip(RoundedCornerShape(cornerRadius.dp)),
        contentScale = contentScale,
    )
}
