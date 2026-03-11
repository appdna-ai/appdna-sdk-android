package ai.appdna.sdk.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// In production: import com.airbnb.lottie.compose.*

data class LottieBlock(
    val lottie_url: String? = null,
    val lottie_json: Map<String, Any>? = null,
    val autoplay: Boolean = true,
    val loop: Boolean = true,
    val speed: Float = 1.0f,
    val width: Float? = null,
    val height: Float = 160f,
    val alignment: String = "center",
    val play_on_scroll: Boolean? = null,
    val play_on_tap: Boolean? = null,
    val color_overrides: Map<String, String>? = null,
)

@Composable
fun LottieBlockView(block: LottieBlock) {
    val horizontalAlignment = when (block.alignment) {
        "left" -> Alignment.Start
        "right" -> Alignment.End
        else -> Alignment.CenterHorizontally
    }

    // Placeholder -- in production, use com.airbnb.lottie.compose:
    // val composition by rememberLottieComposition(LottieCompositionSpec.Url(block.lottie_url ?: ""))
    // val progress by animateLottieCompositionAsState(
    //     composition,
    //     iterations = if (block.loop) LottieConstants.IterateForever else 1,
    //     speed = block.speed,
    // )
    // LottieAnimation(composition = composition, progress = { progress }, modifier = ...)

    Column(
        modifier = Modifier
            .let { mod ->
                if (block.width != null) mod.width(block.width.dp) else mod.fillMaxWidth()
            }
            .height(block.height.dp)
            .clip(RoundedCornerShape(12.dp)),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Placeholder UI
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("\u25B6", fontSize = 32.sp, color = Color(0xFF9333EA))
                Text("Lottie Animation", fontSize = 10.sp, color = Color.Gray)
                block.lottie_url?.let { url ->
                    Text(
                        url.substringAfterLast("/"),
                        fontSize = 9.sp,
                        color = Color.Gray,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
