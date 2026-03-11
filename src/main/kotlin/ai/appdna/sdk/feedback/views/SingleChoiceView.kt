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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion
import ai.appdna.sdk.core.ElementStyleConfig
import ai.appdna.sdk.core.StyleEngine

@Composable
fun SingleChoiceView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit,
    // SPEC-084: Gap #20 — question text style token
    questionTextStyle: TextStyle = TextStyle.Default,
    // SPEC-084: Gap #21 — option card container style token
    optionStyle: ElementStyleConfig? = null
) {
    val selectedId = answer?.answer as? String

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = questionTextStyle.takeIf { it != TextStyle.Default } ?: MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        question.options?.forEach { option ->
            val isSelected = selectedId == option.id
            if (optionStyle != null) {
                // SPEC-084: Gap #21 — apply full style engine token to option card
                Surface(
                    modifier = with(StyleEngine) {
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .applyContainerStyle(optionStyle)
                            .clickable { onAnswer(SurveyAnswer(question.id, option.id)) }
                    },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onAnswer(SurveyAnswer(question.id, option.id)) }
                        )
                        option.icon?.let { Text(it, modifier = Modifier.padding(end = 8.dp)) }
                        Text(option.text)
                    }
                }
            } else {
                // Default: original border-only card style
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onAnswer(SurveyAnswer(question.id, option.id)) },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onAnswer(SurveyAnswer(question.id, option.id)) }
                        )
                        option.icon?.let { Text(it, modifier = Modifier.padding(end = 8.dp)) }
                        Text(option.text)
                    }
                }
            }
        }
    }
}
