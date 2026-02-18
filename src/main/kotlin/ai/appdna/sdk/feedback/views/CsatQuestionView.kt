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
