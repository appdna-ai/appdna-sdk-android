package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.layout.*
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
fun YesNoView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit
) {
    val selected = answer?.answer as? String

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { onAnswer(SurveyAnswer(question.id, "yes")) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected == "yes") MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.1f),
                    contentColor = if (selected == "yes") Color.White else Color.Unspecified
                )
            ) { Text("Yes") }

            Button(
                onClick = { onAnswer(SurveyAnswer(question.id, "no")) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected == "no") MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.1f),
                    contentColor = if (selected == "no") Color.White else Color.Unspecified
                )
            ) { Text("No") }
        }
    }
}
