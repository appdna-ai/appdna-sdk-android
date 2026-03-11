package ai.appdna.sdk.core

import android.view.HapticFeedbackConstants
import android.view.View

enum class HapticType {
    LIGHT, MEDIUM, HEAVY, SELECTION, SUCCESS, WARNING, ERROR;

    companion object {
        fun fromString(value: String?): HapticType? = when (value?.lowercase()) {
            "light" -> LIGHT
            "medium" -> MEDIUM
            "heavy" -> HEAVY
            "selection" -> SELECTION
            "success" -> SUCCESS
            "warning" -> WARNING
            "error" -> ERROR
            else -> null
        }
    }
}

data class HapticConfig(
    val enabled: Boolean = false,
    val triggers: HapticTriggers = HapticTriggers(),
)

data class HapticTriggers(
    val on_step_advance: String? = null,
    val on_button_tap: String? = null,
    val on_plan_select: String? = null,
    val on_option_select: String? = null,
    val on_toggle: String? = null,
    val on_form_submit: String? = null,
    val on_error: String? = null,
    val on_success: String? = null,
)

object HapticEngine {
    fun trigger(view: View, type: HapticType) {
        val feedbackConstant = when (type) {
            HapticType.LIGHT -> HapticFeedbackConstants.CLOCK_TICK
            HapticType.MEDIUM -> HapticFeedbackConstants.CONTEXT_CLICK
            HapticType.HEAVY -> HapticFeedbackConstants.LONG_PRESS
            HapticType.SELECTION -> HapticFeedbackConstants.KEYBOARD_TAP
            HapticType.SUCCESS -> if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
            HapticType.WARNING -> if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            HapticType.ERROR -> if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(feedbackConstant)
    }

    fun triggerIfEnabled(view: View, type: String?, config: HapticConfig?) {
        if (config == null || !config.enabled) return
        val hapticType = HapticType.fromString(type) ?: return
        trigger(view, hapticType)
    }
}
