package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion
import androidx.compose.ui.text.TextStyle

@Composable
fun NpsQuestionView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit,
    // SPEC-084: Gap #20 — question text style token
    questionTextStyle: TextStyle = TextStyle.Default
) {
    val selectedScore = answer?.answer as? Int

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = questionTextStyle.takeIf { it != TextStyle.Default } ?: MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (score in 0..10) {
                val isSelected = selectedScore == score
                Box(
                    modifier = Modifier
                        .size(width = 30.dp, height = 40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.1f))
                        .clickable { onAnswer(SurveyAnswer(question.id, score)) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "$score",
                        color = if (isSelected) Color.White else Color.Unspecified,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(question.npsConfig?.lowLabel ?: "Not likely", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Text(question.npsConfig?.highLabel ?: "Extremely likely", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
        }
    }
}
