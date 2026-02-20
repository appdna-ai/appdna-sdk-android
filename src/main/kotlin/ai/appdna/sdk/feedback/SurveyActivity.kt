package ai.appdna.sdk.feedback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.appdna.sdk.feedback.views.*

/**
 * Activity to render survey UI using Jetpack Compose.
 * Supports bottom_sheet, modal, and fullscreen presentations.
 */
class SurveyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val surveyId = intent.getStringExtra(EXTRA_SURVEY_ID) ?: run {
            finish()
            return
        }

        val config = pendingSurveyConfig ?: run {
            finish()
            return
        }

        val callback = pendingCallback
        val qaCallback = questionAnsweredCallback
        val presentation = config.appearance.presentation

        // Apply presentation style
        when (presentation) {
            "bottom_sheet" -> {
                window.setBackgroundDrawableResource(android.R.color.transparent)
            }
            "modal" -> {
                window.setBackgroundDrawableResource(android.R.color.transparent)
            }
            // "fullscreen" -> default full activity
        }

        val onComplete: (List<SurveyAnswer>) -> Unit = { answers ->
            callback?.invoke(SurveyResult.Completed(answers))
            cleanup()
        }
        val onDismiss: (Int) -> Unit = { answeredCount ->
            callback?.invoke(SurveyResult.Dismissed(answeredCount))
            cleanup()
        }

        setContent {
            MaterialTheme {
                when (presentation) {
                    "bottom_sheet" -> BottomSheetSurveyWrapper(config, onComplete, onDismiss, qaCallback)
                    "modal" -> ModalSurveyWrapper(config, onComplete, onDismiss, qaCallback)
                    else -> SurveyScreen(config, onComplete, onDismiss, qaCallback)
                }
            }
        }
    }

    private fun cleanup() {
        pendingSurveyConfig = null
        pendingCallback = null
        finish()
    }

    companion object {
        private const val EXTRA_SURVEY_ID = "survey_id"
        private var pendingSurveyConfig: SurveyConfig? = null
        private var pendingCallback: ((SurveyResult) -> Unit)? = null
        internal var questionAnsweredCallback: ((String, String, Any) -> Unit)? = null

        fun launch(context: Context, surveyId: String, config: SurveyConfig, callback: (SurveyResult) -> Unit) {
            pendingSurveyConfig = config
            pendingCallback = callback
            val intent = Intent(context, SurveyActivity::class.java).apply {
                putExtra(EXTRA_SURVEY_ID, surveyId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
fun SurveyScreen(
    config: SurveyConfig,
    onComplete: (List<SurveyAnswer>) -> Unit,
    onDismiss: (Int) -> Unit,
    onQuestionAnswered: ((String, String, Any) -> Unit)? = null
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateMapOf<String, SurveyAnswer>() }
    val visibleQuestions by remember {
        derivedStateOf {
            config.questions.filter { q ->
                val showIf = q.showIf ?: return@filter true
                val prev = answers[showIf.questionId] ?: return@filter false
                showIf.answerIn.any { "${prev.answer}" == "$it" }
            }
        }
    }

    val bgColor = config.appearance.theme?.backgroundColor?.let { parseColor(it) } ?: Color.White
    val textColor = config.appearance.theme?.textColor?.let { parseColor(it) } ?: Color(0xFF1A1A1A)
    val accentColor = config.appearance.theme?.accentColor?.let { parseColor(it) } ?: Color(0xFF6366F1)
    val buttonColor = config.appearance.theme?.buttonColor?.let { parseColor(it) } ?: Color(0xFF6366F1)

    val canAdvance = if (currentIndex < visibleQuestions.size) {
        val q = visibleQuestions[currentIndex]
        if (q.required) answers.containsKey(q.id) else true
    } else false

    val customColors = MaterialTheme.colorScheme.copy(
        onSurface = textColor,
        onBackground = textColor
    )

    MaterialTheme(colorScheme = customColors) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Progress
        if (config.appearance.showProgress && visibleQuestions.isNotEmpty()) {
            LinearProgressIndicator(
                progress = (currentIndex + 1).toFloat() / visibleQuestions.size,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                color = accentColor,
            )
        }

        Spacer(Modifier.weight(1f))

        // Current question
        if (currentIndex < visibleQuestions.size) {
            val question = visibleQuestions[currentIndex]
            val answer = answers[question.id]

            val onAnswer: (SurveyAnswer) -> Unit = { ans ->
                answers[question.id] = ans
                onQuestionAnswered?.invoke(question.id, question.type, ans.answer)
            }

            when (question.type) {
                "nps" -> NpsQuestionView(question, answer, onAnswer)
                "csat" -> CsatQuestionView(question, answer, onAnswer)
                "rating" -> RatingQuestionView(question, answer, onAnswer)
                "single_choice" -> SingleChoiceView(question, answer, onAnswer)
                "multi_choice" -> MultiChoiceView(question, answer, onAnswer)
                "free_text" -> FreeTextView(question, answer, onAnswer)
                "yes_no" -> YesNoView(question, answer, onAnswer)
                "emoji_scale" -> EmojiScaleView(question, answer, onAnswer)
            }
        }

        Spacer(Modifier.weight(1f))

        // Navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (currentIndex > 0) {
                TextButton(onClick = { currentIndex-- }) {
                    Text("Back", color = accentColor)
                }
            } else {
                Spacer(Modifier.width(1.dp))
            }

            if (currentIndex < visibleQuestions.size - 1) {
                Button(
                    onClick = { currentIndex++ },
                    enabled = canAdvance,
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                ) { Text("Next") }
            } else {
                Button(
                    onClick = {
                        val allAnswers = visibleQuestions.mapNotNull { answers[it.id] }
                        onComplete(allAnswers)
                    },
                    enabled = canAdvance,
                    colors = ButtonDefaults.buttonColors(containerColor = buttonColor)
                ) { Text("Submit") }
            }
        }

        // Dismiss
        if (config.appearance.dismissAllowed) {
            TextButton(
                onClick = { onDismiss(answers.size) },
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text("Not now", color = Color.Gray)
            }
        }
    }
    } // MaterialTheme
}

private fun parseColor(hex: String): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val colorInt = cleaned.toLong(16)
        when (cleaned.length) {
            6 -> Color(0xFF000000 or colorInt)
            8 -> Color(colorInt)
            else -> Color.White
        }
    } catch (_: Exception) {
        Color.White
    }
}

@Composable
fun BottomSheetSurveyWrapper(
    config: SurveyConfig,
    onComplete: (List<SurveyAnswer>) -> Unit,
    onDismiss: (Int) -> Unit,
    onQuestionAnswered: ((String, String, Any) -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (config.appearance.dismissAllowed) onDismiss(0)
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(
                    config.appearance.theme?.backgroundColor?.let { parseColor(it) } ?: Color.White
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            // Grabber handle
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.Gray.copy(alpha = 0.4f))
            )
            SurveyScreen(config = config, onComplete = onComplete, onDismiss = onDismiss, onQuestionAnswered = onQuestionAnswered)
        }
    }
}

@Composable
fun ModalSurveyWrapper(
    config: SurveyConfig,
    onComplete: (List<SurveyAnswer>) -> Unit,
    onDismiss: (Int) -> Unit,
    onQuestionAnswered: ((String, String, Any) -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (config.appearance.dismissAllowed) onDismiss(0)
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    config.appearance.theme?.backgroundColor?.let { parseColor(it) } ?: Color.White
                )
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            SurveyScreen(config = config, onComplete = onComplete, onDismiss = onDismiss, onQuestionAnswered = onQuestionAnswered)
        }
    }
}

