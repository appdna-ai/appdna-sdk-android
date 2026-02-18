package ai.appdna.sdk.onboarding

/**
 * Firestore schema types for onboarding flows (Android).
 * Mirrors the iOS OnboardingConfig.swift.
 */

/**
 * A single onboarding flow definition.
 */
data class OnboardingFlowConfig(
    val id: String,
    val name: String,
    val version: Int,
    val steps: List<OnboardingStep>,
    val settings: OnboardingSettings
)

/**
 * Flow-level settings.
 */
data class OnboardingSettings(
    val show_progress: Boolean = true,
    val allow_back: Boolean = true,
    val skip_to_step: String? = null
)

/**
 * A single step within a flow.
 */
data class OnboardingStep(
    val id: String,
    val type: StepType,
    val config: StepConfig
) {
    enum class StepType(val value: String) {
        WELCOME("welcome"),
        QUESTION("question"),
        VALUE_PROP("value_prop"),
        CUSTOM("custom");

        companion object {
            fun fromString(value: String): StepType {
                return entries.find { it.value == value } ?: CUSTOM
            }
        }
    }
}

/**
 * Step configuration -- varies by step type.
 */
data class StepConfig(
    // welcome
    val title: String? = null,
    val subtitle: String? = null,
    val image_url: String? = null,
    val cta_text: String? = null,
    val skip_enabled: Boolean? = null,

    // question
    val options: List<QuestionOption>? = null,
    val selection_mode: SelectionMode? = null,

    // value_prop
    val items: List<ValuePropItem>? = null,

    // custom
    val layout: Map<String, Any>? = null
)

data class QuestionOption(
    val id: String,
    val label: String,
    val icon: String? = null
)

enum class SelectionMode(val value: String) {
    SINGLE("single"),
    MULTI("multi");

    companion object {
        fun fromString(value: String): SelectionMode {
            return entries.find { it.value == value } ?: SINGLE
        }
    }
}

data class ValuePropItem(
    val icon: String,
    val title: String,
    val subtitle: String
)

/**
 * Listener for receiving onboarding flow lifecycle events.
 */
interface AppDNAOnboardingListener {
    fun onboardingStepViewed(flowId: String, stepId: String, stepIndex: Int) {}
    fun onboardingStepCompleted(flowId: String, stepId: String, data: Map<String, Any>?) {}
    fun onboardingStepSkipped(flowId: String, stepId: String) {}
    fun onboardingFlowCompleted(flowId: String, data: Map<String, Any>) {}
    fun onboardingFlowDismissed(flowId: String, lastStepId: String) {}
}

// MARK: - Parsing helpers

internal object OnboardingConfigParser {

    @Suppress("UNCHECKED_CAST")
    fun parseOnboardingRoot(data: Map<String, Any>): Pair<String?, Map<String, OnboardingFlowConfig>> {
        val activeFlowId = data["active_flow_id"] as? String

        val flowsMap = data["flows"] as? Map<String, Any> ?: emptyMap()
        val parsed = mutableMapOf<String, OnboardingFlowConfig>()
        for ((key, value) in flowsMap) {
            if (value is Map<*, *>) {
                try {
                    val map = value as Map<String, Any>
                    parsed[key] = parseFlowConfig(key, map)
                } catch (_: Exception) {}
            }
        }
        return activeFlowId to parsed
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFlowConfig(id: String, map: Map<String, Any>): OnboardingFlowConfig {
        val stepsList = map["steps"] as? List<Map<String, Any>> ?: emptyList()
        val steps = stepsList.map { parseStep(it) }

        val settingsMap = map["settings"] as? Map<String, Any> ?: emptyMap()
        val settings = OnboardingSettings(
            show_progress = settingsMap["show_progress"] as? Boolean ?: true,
            allow_back = settingsMap["allow_back"] as? Boolean ?: true,
            skip_to_step = settingsMap["skip_to_step"] as? String
        )

        return OnboardingFlowConfig(
            id = map["id"] as? String ?: id,
            name = map["name"] as? String ?: "",
            version = (map["version"] as? Number)?.toInt() ?: 1,
            steps = steps,
            settings = settings
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseStep(map: Map<String, Any>): OnboardingStep {
        val typeStr = map["type"] as? String ?: "custom"
        val configMap = map["config"] as? Map<String, Any> ?: emptyMap()

        val options = (configMap["options"] as? List<*>)?.mapNotNull { opt ->
            if (opt is Map<*, *>) {
                val optMap = opt as Map<String, Any>
                QuestionOption(
                    id = optMap["id"] as? String ?: "",
                    label = optMap["label"] as? String ?: "",
                    icon = optMap["icon"] as? String
                )
            } else null
        }

        val items = (configMap["items"] as? List<*>)?.mapNotNull { item ->
            if (item is Map<*, *>) {
                val itemMap = item as Map<String, Any>
                ValuePropItem(
                    icon = itemMap["icon"] as? String ?: "",
                    title = itemMap["title"] as? String ?: "",
                    subtitle = itemMap["subtitle"] as? String ?: ""
                )
            } else null
        }

        val selectionModeStr = configMap["selection_mode"] as? String
        val selectionMode = selectionModeStr?.let { SelectionMode.fromString(it) }

        val config = StepConfig(
            title = configMap["title"] as? String,
            subtitle = configMap["subtitle"] as? String,
            image_url = configMap["image_url"] as? String,
            cta_text = configMap["cta_text"] as? String,
            skip_enabled = configMap["skip_enabled"] as? Boolean,
            options = options,
            selection_mode = selectionMode,
            items = items,
            layout = configMap["layout"] as? Map<String, Any>
        )

        return OnboardingStep(
            id = map["id"] as? String ?: "",
            type = OnboardingStep.StepType.fromString(typeStr),
            config = config
        )
    }
}
