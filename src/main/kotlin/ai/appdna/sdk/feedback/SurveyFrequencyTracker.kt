package ai.appdna.sdk.feedback

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks survey display frequency using SharedPreferences and in-memory session tracking.
 */
internal class SurveyFrequencyTracker(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ai.appdna.sdk.survey_freq", Context.MODE_PRIVATE)
    private val sessionShownIds = mutableSetOf<String>()

    fun canShow(surveyId: String, frequency: String, maxDisplays: Int?): Boolean {
        return when (frequency) {
            "once" -> !prefs.getBoolean("$surveyId.shown", false)
            "once_per_session" -> surveyId !in sessionShownIds
            "every_time" -> true
            "max_times" -> {
                val max = maxDisplays ?: return true
                prefs.getInt("$surveyId.count", 0) < max
            }
            else -> true
        }
    }

    fun recordDisplay(surveyId: String) {
        sessionShownIds.add(surveyId)
        val count = prefs.getInt("$surveyId.count", 0)
        prefs.edit()
            .putBoolean("$surveyId.shown", true)
            .putInt("$surveyId.count", count + 1)
            .apply()
    }

    fun resetSession() {
        sessionShownIds.clear()
    }
}
