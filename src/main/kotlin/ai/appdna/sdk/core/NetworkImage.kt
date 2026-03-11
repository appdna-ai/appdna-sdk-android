package ai.appdna.sdk.core

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Simple network image composable for the SDK.
 * Uses BitmapFactory to avoid adding Coil/Glide dependency.
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

    var state by remember(url) { mutableStateOf<NetworkImageState>(NetworkImageState.Loading) }

    LaunchedEffect(url) {
        state = try {
            val bitmap = withContext(Dispatchers.IO) {
                val connection = URL(url).openConnection()
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.getInputStream().use { BitmapFactory.decodeStream(it) }
            }
            if (bitmap != null) NetworkImageState.Success(bitmap) else NetworkImageState.Error
        } catch (_: Exception) {
            NetworkImageState.Error
        }
    }

    when (val s = state) {
        is NetworkImageState.Loading -> {
            Box(modifier = modifier.background(placeholderColor), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(0.3f),
                    color = Color.White.copy(alpha = 0.5f),
                    strokeWidth = androidx.compose.ui.unit.dp.times(2),
                )
            }
        }
        is NetworkImageState.Success -> {
            Image(
                bitmap = s.bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = modifier,
                contentScale = contentScale,
            )
        }
        is NetworkImageState.Error -> {
            Box(modifier = modifier.background(placeholderColor))
        }
    }
}

private sealed class NetworkImageState {
    data object Loading : NetworkImageState()
    data class Success(val bitmap: android.graphics.Bitmap) : NetworkImageState()
    data object Error : NetworkImageState()
}
