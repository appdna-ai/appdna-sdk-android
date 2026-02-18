package ai.appdna.sdk.feedback

import org.json.JSONObject
import org.json.JSONArray

/**
 * Firestore schema types for surveys (SPEC-023).
 */

data class SurveyConfig(
    val name: String,
    val surveyType: String,
    val questions: List<SurveyQuestion>,
    val triggerRules: SurveyTriggerRules,
    val appearance: SurveyAppearance,
    val followUpActions: SurveyFollowUpActions?
) {
    companion object {
        fun fromMap(data: Map<String, Any>): SurveyConfig? {
            return try {
                val questions = (data["questions"] as? List<*>)?.mapNotNull { q ->
                    @Suppress("UNCHECKED_CAST")
                    SurveyQuestion.fromMap(q as? Map<String, Any> ?: return@mapNotNull null)
                } ?: emptyList()

                @Suppress("UNCHECKED_CAST")
                val triggerData = data["trigger_rules"] as? Map<String, Any> ?: return null
                @Suppress("UNCHECKED_CAST")
                val appearanceData = data["appearance"] as? Map<String, Any> ?: return null
                @Suppress("UNCHECKED_CAST")
                val followUpData = data["follow_up_actions"] as? Map<String, Any>

                SurveyConfig(
                    name = data["name"] as? String ?: "",
                    surveyType = data["survey_type"] as? String ?: "custom",
                    questions = questions,
                    triggerRules = SurveyTriggerRules.fromMap(triggerData),
                    appearance = SurveyAppearance.fromMap(appearanceData),
                    followUpActions = followUpData?.let { SurveyFollowUpActions.fromMap(it) }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class SurveyQuestion(
    val id: String,
    val type: String,
    val text: String,
    val required: Boolean,
    val showIf: ShowIfCondition?,
    val npsConfig: NPSConfig?,
    val csatConfig: CSATConfig?,
    val ratingConfig: RatingConfig?,
    val options: List<QuestionOption>?,
    val emojiConfig: EmojiConfig?,
    val freeTextConfig: FreeTextConfig?
) {
    companion object {
        fun fromMap(data: Map<String, Any>): SurveyQuestion {
            @Suppress("UNCHECKED_CAST")
            val showIfData = data["show_if"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val npsData = data["nps_config"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val csatData = data["csat_config"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val ratingData = data["rating_config"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val emojiData = data["emoji_config"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val freeTextData = data["free_text_config"] as? Map<String, Any>
            val optionsData = data["options"] as? List<*>

            return SurveyQuestion(
                id = data["id"] as? String ?: "",
                type = data["type"] as? String ?: "",
                text = data["text"] as? String ?: "",
                required = data["required"] as? Boolean ?: false,
                showIf = showIfData?.let { ShowIfCondition.fromMap(it) },
                npsConfig = npsData?.let { NPSConfig(it["low_label"] as? String, it["high_label"] as? String) },
                csatConfig = csatData?.let { CSATConfig((it["max_rating"] as? Number)?.toInt() ?: 5, it["style"] as? String ?: "star") },
                ratingConfig = ratingData?.let { RatingConfig((it["max_rating"] as? Number)?.toInt() ?: 5, it["style"] as? String ?: "star") },
                options = optionsData?.mapNotNull { o ->
                    @Suppress("UNCHECKED_CAST")
                    val om = o as? Map<String, Any> ?: return@mapNotNull null
                    QuestionOption(om["id"] as? String ?: "", om["text"] as? String ?: "", om["icon"] as? String)
                },
                emojiConfig = emojiData?.let {
                    @Suppress("UNCHECKED_CAST")
                    EmojiConfig((it["emojis"] as? List<String>) ?: listOf("\uD83D\uDE21", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE0A", "\uD83D\uDE0D"))
                },
                freeTextConfig = freeTextData?.let { FreeTextConfig(it["placeholder"] as? String, (it["max_length"] as? Number)?.toInt() ?: 500) }
            )
        }
    }
}

data class ShowIfCondition(val questionId: String, val answerIn: List<Any>) {
    companion object {
        fun fromMap(data: Map<String, Any>): ShowIfCondition {
            @Suppress("UNCHECKED_CAST")
            return ShowIfCondition(
                questionId = data["question_id"] as? String ?: "",
                answerIn = (data["answer_in"] as? List<*>)?.filterNotNull() ?: emptyList()
            )
        }
    }
}

data class NPSConfig(val lowLabel: String?, val highLabel: String?)
data class CSATConfig(val maxRating: Int = 5, val style: String = "star")
data class RatingConfig(val maxRating: Int = 5, val style: String = "star")
data class QuestionOption(val id: String, val text: String, val icon: String?)
data class EmojiConfig(val emojis: List<String> = listOf("\uD83D\uDE21", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE0A", "\uD83D\uDE0D"))
data class FreeTextConfig(val placeholder: String?, val maxLength: Int = 500)

data class ScoreRange(val min: Int, val max: Int)

data class SurveyTriggerRules(
    val event: String,
    val conditions: List<TriggerCondition>,
    val loveScoreRange: ScoreRange?,
    val frequency: String,
    val maxDisplays: Int?,
    val delaySeconds: Int?,
    val minSessions: Int?
) {
    companion object {
        fun fromMap(data: Map<String, Any>): SurveyTriggerRules {
            val conditionsList = (data["conditions"] as? List<*>)?.mapNotNull { c ->
                @Suppress("UNCHECKED_CAST")
                val cm = c as? Map<String, Any> ?: return@mapNotNull null
                TriggerCondition(
                    field = cm["field"] as? String ?: "",
                    operator = cm["operator"] as? String ?: "eq",
                    value = cm["value"]
                )
            } ?: emptyList()

            @Suppress("UNCHECKED_CAST")
            val rangeData = data["love_score_range"] as? Map<String, Any>
            val scoreRange = rangeData?.let {
                ScoreRange(
                    min = (it["min"] as? Number)?.toInt() ?: 0,
                    max = (it["max"] as? Number)?.toInt() ?: 100
                )
            }

            return SurveyTriggerRules(
                event = data["event"] as? String ?: "",
                conditions = conditionsList,
                loveScoreRange = scoreRange,
                frequency = data["frequency"] as? String ?: "once",
                maxDisplays = (data["max_displays"] as? Number)?.toInt(),
                delaySeconds = (data["delay_seconds"] as? Number)?.toInt(),
                minSessions = (data["min_sessions"] as? Number)?.toInt()
            )
        }
    }
}

data class TriggerCondition(val field: String, val operator: String, val value: Any?)

data class SurveyAppearance(
    val presentation: String,
    val theme: SurveyTheme?,
    val dismissAllowed: Boolean,
    val showProgress: Boolean
) {
    companion object {
        fun fromMap(data: Map<String, Any>): SurveyAppearance {
            @Suppress("UNCHECKED_CAST")
            val themeData = data["theme"] as? Map<String, Any>
            return SurveyAppearance(
                presentation = data["presentation"] as? String ?: "bottom_sheet",
                theme = themeData?.let { SurveyTheme(it["background_color"] as? String, it["text_color"] as? String, it["accent_color"] as? String, it["button_color"] as? String) },
                dismissAllowed = data["dismiss_allowed"] as? Boolean ?: true,
                showProgress = data["show_progress"] as? Boolean ?: false
            )
        }
    }
}

data class SurveyTheme(
    val backgroundColor: String?,
    val textColor: String?,
    val accentColor: String?,
    val buttonColor: String?
)

data class SurveyFollowUpActions(
    val onPositive: FollowUpAction?,
    val onNegative: FollowUpAction?,
    val onNeutral: FollowUpAction?
) {
    companion object {
        fun fromMap(data: Map<String, Any>): SurveyFollowUpActions {
            @Suppress("UNCHECKED_CAST")
            val pos = data["on_positive"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val neg = data["on_negative"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val neut = data["on_neutral"] as? Map<String, Any>
            return SurveyFollowUpActions(
                onPositive = pos?.let { FollowUpAction(it["action"] as? String ?: "") },
                onNegative = neg?.let { FollowUpAction(it["action"] as? String ?: "") },
                onNeutral = neut?.let { FollowUpAction(it["action"] as? String ?: "") }
            )
        }
    }
}

data class FollowUpAction(val action: String)

data class SurveyAnswer(val questionId: String, val answer: Any) {
    fun toMap(): Map<String, Any> = mapOf("question_id" to questionId, "answer" to answer)
}

enum class SurveySentiment { POSITIVE, NEGATIVE, NEUTRAL }
