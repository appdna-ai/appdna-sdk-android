package ai.appdna.sdk.feedback.views

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.SurveyAnswer
import ai.appdna.sdk.feedback.SurveyQuestion
import ai.appdna.sdk.core.ElementStyleConfig
import ai.appdna.sdk.core.StyleEngine

@Composable
fun MultiChoiceView(
    question: SurveyQuestion,
    answer: SurveyAnswer?,
    onAnswer: (SurveyAnswer) -> Unit,
    // SPEC-084: Gap #20 — question text style token
    questionTextStyle: TextStyle = TextStyle.Default,
    // SPEC-084: Gap #21 — option card container style token
    optionStyle: ElementStyleConfig? = null
) {
    @Suppress("UNCHECKED_CAST")
    val selectedIds = (answer?.answer as? List<String>) ?: emptyList()

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = question.text,
            style = questionTextStyle.takeIf { it != TextStyle.Default } ?: MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        question.options?.forEach { option ->
            val isSelected = option.id in selectedIds
            // SPEC-401-A R66 (Lens C P1) — single click target via
            // `Modifier.toggleable(role = Role.Checkbox)` mirrors iOS
            // `Button { … }` wrapping the row. Inner Checkbox has
            // `onCheckedChange = null` so the entire row is the single tap
            // target with one TalkBack focus stop reading
            // "Checkbox, checked/unchecked, <option text>".
            if (optionStyle != null) {
                // SPEC-084: Gap #21 — apply full style engine token to option card
                Surface(
                    modifier = with(StyleEngine) {
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .applyContainerStyle(optionStyle)
                            .toggleable(
                                value = isSelected,
                                role = Role.Checkbox,
                                onValueChange = { checked ->
                                    val updated = if (checked) selectedIds + option.id else selectedIds - option.id
                                    onAnswer(SurveyAnswer(question.id, updated))
                                },
                            )
                    },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = null,
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
                        .toggleable(
                            value = isSelected,
                            role = Role.Checkbox,
                            onValueChange = { checked ->
                                val updated = if (checked) selectedIds + option.id else selectedIds - option.id
                                onAnswer(SurveyAnswer(question.id, updated))
                            },
                        ),
                    shape = RoundedCornerShape(8.dp),
                    // R88 — match iOS MultiChoiceView.swift:27 `Color(hex: "#6366F1")`
                    // (indigo brand color) for selected border.
                    border = BorderStroke(1.dp, if (isSelected) ai.appdna.sdk.AppDNA.brandAccentColor() else Color.Gray.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = null,
                        )
                        option.icon?.let { Text(it, modifier = Modifier.padding(end = 8.dp)) }
                        Text(option.text)
                    }
                }
            }
        }
    }
}
