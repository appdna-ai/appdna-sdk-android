package ai.appdna.sdk.feedback

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.EditText
import android.widget.FrameLayout
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import ai.appdna.sdk.network.ApiClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages survey trigger evaluation, display queue, frequency tracking, and presentation.
 */
internal class SurveyManager(
    private val context: Context,
    private val eventTracker: EventTracker,
    internal var apiClient: ApiClient? = null
) {
    private val frequencyTracker = SurveyFrequencyTracker(context)
    private var surveyConfigs: Map<String, SurveyConfig> = emptyMap()
    private var isPresenting = false
    private var currentSurveyId: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun updateConfigs(configs: Map<String, SurveyConfig>) {
        surveyConfigs = configs
    }

    /**
     * Evaluate all surveys against an event. Called on every tracked event.
     */
    fun onEvent(eventName: String, properties: Map<String, Any>?) {
        if (isPresenting) return

        for ((surveyId, config) in surveyConfigs) {
            // 1. Event match
            if (config.triggerRules.event != eventName) continue

            // 2. Conditions
            if (!evaluateConditions(config.triggerRules.conditions, properties ?: emptyMap())) continue

            // 3. Frequency
            if (!frequencyTracker.canShow(surveyId, config.triggerRules.frequency, config.triggerRules.maxDisplays)) continue

            // 4. Love score range
            if (!meetsLoveScoreRange(config.triggerRules.loveScoreRange)) continue

            // 5. Min sessions
            if (!meetsMinSessions(config.triggerRules.minSessions)) continue

            // 5. Present with delay
            val delay = config.triggerRules.delaySeconds?.toLong() ?: 0L
            scope.launch {
                if (delay > 0) delay(delay * 1000)
                presentSurvey(surveyId, config, eventName)
            }
            break // Only one survey per event
        }
    }

    /**
     * Present a specific survey by ID, bypassing trigger evaluation.
     */
    fun present(surveyId: String) {
        val config = surveyConfigs[surveyId]
        if (config == null) {
            Log.warning("Survey config not found for id: $surveyId")
            return
        }
        presentSurvey(surveyId, config, "manual")
    }

    fun resetSession() {
        frequencyTracker.resetSession()
    }

    /**
     * Called by SurveyActivity when a question is answered.
     */
    internal fun onQuestionAnswered(questionId: String, questionType: String, answer: Any) {
        val surveyId = currentSurveyId ?: return
        eventTracker.track("survey_question_answered", mapOf(
            "survey_id" to surveyId,
            "question_id" to questionId,
            "question_type" to questionType,
            "answer" to answer
        ))
    }

    private fun presentSurvey(surveyId: String, config: SurveyConfig, triggerEvent: String) {
        if (isPresenting) return
        isPresenting = true
        currentSurveyId = surveyId

        eventTracker.track("survey_shown", mapOf(
            "survey_id" to surveyId,
            "survey_type" to config.surveyType,
            "trigger_event" to triggerEvent
        ))

        SurveyActivity.questionAnsweredCallback = { qId, qType, answer ->
            onQuestionAnswered(qId, qType, answer)
        }
        SurveyActivity.launch(context, surveyId, config) { result ->
            SurveyActivity.questionAnsweredCallback = null
            isPresenting = false

            when (result) {
                is SurveyResult.Completed -> {
                    frequencyTracker.recordDisplay(surveyId)
                    trackCompleted(surveyId, config, result.answers)
                    submitResponse(surveyId, config, result.answers)
                    executeFollowUp(config, result.answers)
                }
                is SurveyResult.Dismissed -> {
                    frequencyTracker.recordDisplay(surveyId)
                    eventTracker.track("survey_dismissed", mapOf(
                        "survey_id" to surveyId,
                        "questions_answered" to result.answeredCount
                    ))
                }
            }
        }
    }

    private fun trackCompleted(surveyId: String, config: SurveyConfig, answers: List<SurveyAnswer>) {
        eventTracker.track("survey_completed", mapOf(
            "survey_id" to surveyId,
            "survey_type" to config.surveyType,
            "answers" to answers.map { it.toMap() }
        ))
    }

    private fun submitResponse(surveyId: String, config: SurveyConfig, answers: List<SurveyAnswer>) {
        val body = JSONObject().apply {
            put("survey_id", surveyId)
            put("survey_type", config.surveyType)
            put("answers", JSONArray(answers.map { JSONObject(it.toMap()) }))
            put("context", JSONObject().apply {
                put("sdk_version", AppDNA.sdkVersion)
                put("platform", "android")
                put("device", Build.MODEL)
                put("app_version", getAppVersion())
                val prefs = context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
                put("session_count", prefs.getInt("session_count", 0))
                put("days_since_install", daysSinceInstall())
            })
        }

        scope.launch(Dispatchers.IO) {
            try {
                apiClient?.post("/api/v1/feedback/responses", body.toString())
                Log.debug("Survey response submitted for $surveyId")
            } catch (e: Exception) {
                Log.error("Failed to submit survey response: ${e.message}")
            }
        }
    }

    private fun getAppVersion(): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    @Suppress("DEPRECATION")
    private fun daysSinceInstall(): Int {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val installTime = packageInfo.firstInstallTime
            val elapsed = System.currentTimeMillis() - installTime
            maxOf(0, (elapsed / 86_400_000).toInt())
        } catch (_: Exception) {
            0
        }
    }

    private fun executeFollowUp(config: SurveyConfig, answers: List<SurveyAnswer>) {
        val sentiment = determineSentiment(config, answers)
        val actions = config.followUpActions ?: return

        val action = when (sentiment) {
            SurveySentiment.POSITIVE -> actions.onPositive
            SurveySentiment.NEGATIVE -> actions.onNegative
            SurveySentiment.NEUTRAL -> actions.onNeutral
        } ?: return

        when (action.action) {
            "prompt_review" -> ReviewPromptManager.triggerReview(context)
            "show_feedback_form" -> presentFeedbackForm(action.message)
            "trigger_winback" -> {
                // SPEC-084: Fire custom event for downstream handling
                eventTracker.track("survey_winback_triggered", mapOf(
                    "sentiment" to sentiment.name.lowercase(),
                    "message" to (action.message ?: ""),
                ))
            }
            "dismiss" -> { /* No-op */ }
        }
    }

    private fun presentFeedbackForm(message: String?) {
        scope.launch(Dispatchers.Main) {
            val activity = getCurrentActivity() ?: run {
                Log.warning("No activity available for feedback form presentation")
                return@launch
            }

            val input = EditText(activity).apply {
                hint = "Tell us what you think..."
                maxLines = 4
            }
            val container = FrameLayout(activity).apply {
                val padding = (16 * activity.resources.displayMetrics.density).toInt()
                setPadding(padding, 0, padding, 0)
                addView(input)
            }

            AlertDialog.Builder(activity)
                .setTitle(message ?: "We'd love your feedback")
                .setMessage("What could we do better?")
                .setView(container)
                .setPositiveButton("Submit") { _, _ ->
                    val feedback = input.text.toString()
                    eventTracker.track("survey_feedback_submitted", mapOf(
                        "feedback" to feedback
                    ))
                }
                .setNegativeButton("Cancel") { _, _ ->
                    eventTracker.track("feedback_form_dismissed")
                }
                .create()
                .show()
        }
    }

    private fun getCurrentActivity(): Activity? {
        // Use reflection to get the current activity from ActivityThread, matching iOS's
        // pattern of getting the topmost view controller via UIApplication.shared
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentMethod = activityThread.getMethod("currentActivityThread")
            val thread = currentMethod.invoke(null)
            val activitiesField = activityThread.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val activities = activitiesField.get(thread) as? android.util.ArrayMap<Any, Any>
            activities?.values?.firstNotNullOfOrNull { record ->
                val pausedField = record.javaClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(record)) {
                    val activityField = record.javaClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    activityField.get(record) as? Activity
                } else null
            }
        } catch (e: Exception) {
            Log.warning("Failed to get current activity: ${e.message}")
            null
        }
    }

    private fun determineSentiment(config: SurveyConfig, answers: List<SurveyAnswer>): SurveySentiment {
        val firstAnswer = answers.firstOrNull() ?: return SurveySentiment.NEUTRAL

        if (config.surveyType == "nps") {
            val score = (firstAnswer.answer as? Number)?.toInt() ?: return SurveySentiment.NEUTRAL
            return when {
                score >= 9 -> SurveySentiment.POSITIVE
                score <= 6 -> SurveySentiment.NEGATIVE
                else -> SurveySentiment.NEUTRAL
            }
        }

        if (config.surveyType in listOf("csat", "rating")) {
            val rating = (firstAnswer.answer as? Number)?.toInt() ?: return SurveySentiment.NEUTRAL
            return when {
                rating >= 4 -> SurveySentiment.POSITIVE
                rating <= 2 -> SurveySentiment.NEGATIVE
                else -> SurveySentiment.NEUTRAL
            }
        }

        // Emoji scale: index 0-1 = negative, 2 = neutral, 3-4 = positive
        if (config.surveyType == "emoji_scale" || firstAnswer.answer is String) {
            val emojis = listOf("\uD83D\uDE21", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE0A", "\uD83D\uDE0D")
            val emoji = firstAnswer.answer as? String
            val idx = emojis.indexOf(emoji)
            if (idx >= 0) {
                return when {
                    idx >= 3 -> SurveySentiment.POSITIVE
                    idx <= 1 -> SurveySentiment.NEGATIVE
                    else -> SurveySentiment.NEUTRAL
                }
            }
        }

        return SurveySentiment.NEUTRAL
    }

    private fun evaluateConditions(conditions: List<TriggerCondition>, properties: Map<String, Any>): Boolean {
        if (conditions.isEmpty()) return true
        return conditions.all { condition ->
            val propValue = properties[condition.field] ?: return@all false
            evaluateOperator(condition.operator, propValue, condition.value)
        }
    }

    private fun evaluateOperator(op: String, propValue: Any, condValue: Any?): Boolean {
        condValue ?: return false
        return when (op) {
            "eq" -> "$propValue" == "$condValue"
            "gte" -> toDouble(propValue)?.let { p -> toDouble(condValue)?.let { c -> p >= c } } ?: false
            "lte" -> toDouble(propValue)?.let { p -> toDouble(condValue)?.let { c -> p <= c } } ?: false
            "gt" -> toDouble(propValue)?.let { p -> toDouble(condValue)?.let { c -> p > c } } ?: false
            "lt" -> toDouble(propValue)?.let { p -> toDouble(condValue)?.let { c -> p < c } } ?: false
            "contains" -> "$propValue".contains("$condValue")
            else -> false
        }
    }

    private fun toDouble(value: Any): Double? {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }

    private fun meetsLoveScoreRange(range: ScoreRange?): Boolean {
        range ?: return true
        val prefs = context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
        val loveScore = prefs.getInt("love_score", 0)
        return loveScore in range.min..range.max
    }

    private fun meetsMinSessions(minSessions: Int?): Boolean {
        val min = minSessions ?: return true
        if (min <= 0) return true
        val prefs = context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
        return prefs.getInt("session_count", 0) >= min
    }
}

sealed class SurveyResult {
    data class Completed(val answers: List<SurveyAnswer>) : SurveyResult()
    data class Dismissed(val answeredCount: Int) : SurveyResult()
}
