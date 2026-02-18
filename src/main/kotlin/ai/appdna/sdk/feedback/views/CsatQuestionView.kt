package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion

@Composable
fun CsatQuestionView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit
) {
    val maxRating = question.csatConfig?.maxRating ?: 5
    val style = question.csatConfig?.style ?: "star"
    val selectedRating = answer?.answer as? Int ?: 0

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rating in 1..maxRating) {
                if (style == "emoji") {
                    Text(
                        text = emojiForRating(rating, maxRating),
                        fontSize = 32.sp,
                        modifier = Modifier.clickable {
                            onAnswer(SurveyAnswer(question.id, rating))
                        }
                    )
                } else {
                    Icon(
                        imageVector = if (selectedRating >= rating) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = "Rating $rating",
                        tint = if (selectedRating >= rating) Color(0xFFFFD700) else Color.Gray.copy(alpha = 0.3f),
                        modifier = Modifier
                            .size(36.dp)
                            .clickable { onAnswer(SurveyAnswer(question.id, rating)) }
                    )
                }
            }
        }
    }
}

/**
 * Map a numeric rating to an emoji, distributing evenly across 5 emojis.
 * Matches the iOS CSATQuestionView.emojiFor(rating:max:) logic.
 */
private fun emojiForRating(rating: Int, max: Int): String {
    val emojis = listOf("\uD83D\uDE21", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE0A", "\uD83D\uDE0D")
    val index = ((rating - 1).toDouble() / (max - 1).toDouble() * (emojis.size - 1)).toInt()
    return emojis[index.coerceAtMost(emojis.size - 1)]
}
