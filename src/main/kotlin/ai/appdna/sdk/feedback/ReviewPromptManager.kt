package ai.appdna.sdk.feedback

import android.app.Activity
import android.content.Context
import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import com.google.android.play.core.review.ReviewManagerFactory

/**
 * Manages review prompt presentation using Google Play In-App Review API.
 */
object ReviewPromptManager {

    /**
     * Trigger the Google Play In-App Review flow.
     */
    fun triggerReview(context: Context) {
        val reviewManager = ReviewManagerFactory.create(context)
        val request = reviewManager.requestReviewFlow()

        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo = task.result
                if (context is Activity) {
                    val flow = reviewManager.launchReviewFlow(context, reviewInfo)
                    flow.addOnCompleteListener {
                        Log.info("Review flow completed")
                    }
                }
                AppDNA.track("review_prompt_shown", mapOf("prompt_type" to "direct"))
            } else {
                Log.warning("Failed to request review flow: ${task.exception?.message}")
            }
        }
    }

    /**
     * Two-step review prompt: show dialog first, then trigger native review on positive response.
     */
    fun triggerTwoStepReview(activity: Activity, appName: String? = null) {
        val name = appName ?: activity.applicationInfo.loadLabel(activity.packageManager).toString()

        val dialog = android.app.AlertDialog.Builder(activity)
            .setTitle("Enjoying $name?")
            .setMessage("We'd love to hear your feedback!")
            .setPositiveButton("Yes! \uD83D\uDE0A") { _, _ ->
                AppDNA.track("review_prompt_accepted")
                triggerReview(activity)
            }
            .setNegativeButton("Not really") { _, _ ->
                AppDNA.track("review_prompt_declined")
            }
            .create()

        dialog.show()
        AppDNA.track("review_prompt_shown", mapOf("prompt_type" to "two_step"))
    }
}
