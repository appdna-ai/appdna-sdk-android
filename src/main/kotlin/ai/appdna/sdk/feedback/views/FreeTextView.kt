package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion

@Composable
fun FreeTextView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit
) {
    val maxLength = question.freeTextConfig?.maxLength ?: 500
    val placeholder = question.freeTextConfig?.placeholder ?: "Type your answer..."
    var text by remember { mutableStateOf((answer?.answer as? String) ?: "") }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        OutlinedTextField(
            value = text,
            onValueChange = { newValue ->
                if (newValue.length <= maxLength) {
                    text = newValue
                    onAnswer(SurveyAnswer(question.id, newValue))
                }
            },
            placeholder = { Text(placeholder) },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            maxLines = 6
        )

        Text(
            text = "${text.length}/$maxLength",
            style = MaterialTheme.typography.labelSmall,
            color = if (text.length >= maxLength) Color.Red else Color.Gray,
            modifier = Modifier.align(Alignment.End).padding(top = 4.dp)
        )
    }
}
