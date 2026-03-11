package ai.appdna.sdk.core

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// In production: import app.rive.runtime.kotlin.core.*

data class RiveBlock(
    val rive_url: String,
    val artboard: String? = null,
    val state_machine: String? = null,
    val autoplay: Boolean = true,
    val height: Float = 160f,
    val alignment: String = "center",
    val inputs: Map<String, Any>? = null,
    val trigger_on_step_complete: String? = null,
)

@Composable
fun RiveBlockView(block: RiveBlock) {
    val horizontalAlignment = when (block.alignment) {
        "left" -> Alignment.Start
        "right" -> Alignment.End
        else -> Alignment.CenterHorizontally
    }

    // Placeholder -- in production, use RiveAnimationView from rive-android
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(block.height.dp)
            .clip(RoundedCornerShape(12.dp)),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("\uD83C\uDFAC", fontSize = 32.sp)
                Text("Rive Animation", fontSize = 10.sp, color = Color.Gray)
                block.state_machine?.let {
                    Text("State: $it", fontSize = 9.sp, color = Color.Gray)
                }
            }
        }
    }
}
