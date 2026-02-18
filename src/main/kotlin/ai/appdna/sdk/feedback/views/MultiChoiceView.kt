package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion

@Composable
fun MultiChoiceView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit
) {
    @Suppress("UNCHECKED_CAST")
    val selectedIds = (answer?.answer as? List<String>) ?: emptyList()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        question.options?.forEach { option ->
            val isSelected = option.id in selectedIds
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        val updated = if (isSelected) {
                            selectedIds - option.id
                        } else {
                            selectedIds + option.id
                        }
                        onAnswer(SurveyAnswer(question.id, updated))
                    },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { checked ->
                            val updated = if (checked) selectedIds + option.id else selectedIds - option.id
                            onAnswer(SurveyAnswer(question.id, updated))
                        }
                    )
                    option.icon?.let { Text(it, modifier = Modifier.padding(end = 8.dp)) }
                    Text(option.text)
                }
            }
        }
    }
}
