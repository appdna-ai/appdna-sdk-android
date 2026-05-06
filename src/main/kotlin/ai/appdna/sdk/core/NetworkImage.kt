package ai.appdna.sdk.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest

/**
 * Network image composable for the SDK.
 *
 * SPEC-070-A A.3 — backed by Coil ([io.coil-kt:coil-compose]). The previous
 * implementation re-ran `BitmapFactory.decodeStream` on every recomposition
 * with no caching; Coil now handles HTTP, decoding, and memory + disk caching
 * via the [AppDNAImageLoader] singleton (25% memory budget, 50 MB disk cache
 * at `<cacheDir>/appdna_image_cache`).
 *
 * Public signature is preserved so all existing callers (paywall renderer,
 * onboarding renderer, message renderer, video thumbnail, media block) work
 * without changes — caller-supplied [modifier] and [contentScale] still
 * pass through untouched.
 */
@Composable
fun NetworkImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholderColor: Color = Color.Gray.copy(alpha = 0.2f),
    contentDescription: String? = null,
) {
    if (url.isNullOrBlank()) {
        Box(modifier = modifier.background(placeholderColor))
        return
    }

    val context = LocalContext.current
    val request = ImageRequest.Builder(context)
        .data(url)
        .crossfade(true)
        .build()

    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        imageLoader = AppDNAImageLoader.singleton(context),
        modifier = modifier,
        contentScale = contentScale,
    ) {
        // Mirror the prior loading / error visuals exactly so visual
        // snapshots and host-app expectations don't shift.
        when (painter.state) {
            is AsyncImagePainter.State.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(placeholderColor),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize(0.3f),
                        color = Color.White.copy(alpha = 0.5f),
                        strokeWidth = 2.dp,
                    )
                }
            }
            is AsyncImagePainter.State.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(placeholderColor),
                )
            }
            else -> SubcomposeAsyncImageContent()
        }
    }
}
