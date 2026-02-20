package ai.appdna.sdk.feedback.views

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion

@Composable
fun EmojiScaleView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit
) {
    val emojis = question.emojiConfig?.emojis
        ?: listOf("\uD83D\uDE21", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE0A", "\uD83D\uDE0D")
    val selectedEmoji = answer?.answer as? String

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            emojis.forEach { emoji ->
                val scale by animateFloatAsState(
                    targetValue = if (selectedEmoji == emoji) 1.3f else 1.0f,
                    animationSpec = spring(),
                    label = "emojiScale"
                )

                Text(
                    text = emoji,
                    fontSize = 36.sp,
                    modifier = Modifier
                        .scale(scale)
                        .clickable { onAnswer(SurveyAnswer(question.id, emoji)) }
                )
            }
        }
    }
}
