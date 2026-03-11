package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion
import androidx.compose.ui.text.TextStyle

@Composable
fun RatingQuestionView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit,
    // SPEC-084: Gap #20 — question text style token
    questionTextStyle: TextStyle = TextStyle.Default
) {
    val maxRating = question.ratingConfig?.maxRating ?: 5
    val style = question.ratingConfig?.style ?: "star"
    val selectedRating = answer?.answer as? Int ?: 0

    val filledIcon = when (style) {
        "heart" -> Icons.Filled.Favorite
        "thumb" -> Icons.Filled.ThumbUp
        else -> Icons.Filled.Star
    }
    val outlinedIcon = when (style) {
        "heart" -> Icons.Outlined.FavoriteBorder
        "thumb" -> Icons.Outlined.ThumbUp
        else -> Icons.Outlined.Star
    }
    val activeColor = when (style) {
        "heart" -> Color.Red
        "thumb" -> Color.Blue
        else -> Color(0xFFFFD700)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = questionTextStyle.takeIf { it != TextStyle.Default } ?: MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (rating in 1..maxRating) {
                Icon(
                    imageVector = if (selectedRating >= rating) filledIcon else outlinedIcon,
                    contentDescription = "Rating $rating",
                    tint = if (selectedRating >= rating) activeColor else Color.Gray.copy(alpha = 0.3f),
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onAnswer(SurveyAnswer(question.id, rating)) }
                )
            }
        }
    }
}
