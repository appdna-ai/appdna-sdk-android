package ai.appdna.sdk.feedback

import android.content.Context
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
                val prefs = context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
                put("session_count", prefs.getInt("session_count", 0))
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

    private fun executeFollowUp(config: SurveyConfig, answers: List<SurveyAnswer>) {
        val sentiment = determineSentiment(config, answers)
        val actions = config.followUpActions ?: return

        when (sentiment) {
            SurveySentiment.POSITIVE -> {
                if (actions.onPositive?.action == "prompt_review") {
                    ReviewPromptManager.triggerReview(context)
                }
            }
            SurveySentiment.NEGATIVE -> {
                Log.info("Negative sentiment — feedback follow-up")
            }
            SurveySentiment.NEUTRAL -> {}
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
