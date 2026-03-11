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
        CUSTOM("custom"),
        FORM("form");

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
    val layout: Map<String, Any>? = null,

    // form (SPEC-082)
    val fields: List<FormField>? = null,
    val validation_mode: String? = null  // "on_submit" or "realtime"
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

// MARK: - Form Field Types (SPEC-082)

enum class FormFieldType(val value: String) {
    TEXT("text"), TEXTAREA("textarea"), NUMBER("number"),
    EMAIL("email"), PHONE("phone"),
    DATE("date"), TIME("time"), DATETIME("datetime"),
    SELECT("select"), SLIDER("slider"), TOGGLE("toggle"),
    STEPPER("stepper"), SEGMENTED("segmented");

    companion object {
        fun fromString(value: String): FormFieldType {
            return entries.find { it.value == value } ?: TEXT
        }
    }
}

data class FormFieldOption(
    val id: String,
    val label: String,
    val icon: String? = null,
    val value: Any? = null
)

data class FormFieldValidation(
    val pattern: String? = null,
    val pattern_message: String? = null
)

data class FormFieldDependency(
    val field_id: String,
    val operator: String,  // equals, not_equals, contains, not_empty, empty
    val value: Any? = null
)

data class FormFieldConfig(
    val max_length: Int? = null,
    val keyboard_type: String? = null,
    val autocapitalize: String? = null,
    val min_value: Double? = null,
    val max_value: Double? = null,
    val step: Double? = null,
    val unit: String? = null,
    val decimal_places: Int? = null,
    val min_date: String? = null,
    val max_date: String? = null,
    val picker_style: String? = null,
    val search_enabled: Boolean? = null,
    val multi_select: Boolean? = null,
    val default_value: Any? = null
)

data class FormField(
    val id: String,
    val type: FormFieldType,
    val label: String,
    val placeholder: String? = null,
    val required: Boolean = false,
    val validation: FormFieldValidation? = null,
    val options: List<FormFieldOption>? = null,
    val config: FormFieldConfig? = null,
    val depends_on: FormFieldDependency? = null
)

/**
 * Delegate for receiving onboarding flow lifecycle events.
 */
interface AppDNAOnboardingDelegate {
    fun onOnboardingStarted(flowId: String) {}
    fun onOnboardingStepChanged(flowId: String, stepId: String, stepIndex: Int, totalSteps: Int) {}
    fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {}
    fun onOnboardingDismissed(flowId: String, atStep: Int) {}
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

        @Suppress("UNCHECKED_CAST")
        val fields = (configMap["fields"] as? List<*>)?.mapNotNull { f ->
            if (f is Map<*, *>) {
                val fm = f as Map<String, Any>
                val fieldOptions = (fm["options"] as? List<*>)?.mapNotNull { o ->
                    if (o is Map<*, *>) {
                        val om = o as Map<String, Any>
                        FormFieldOption(
                            id = om["id"] as? String ?: "",
                            label = om["label"] as? String ?: "",
                            icon = om["icon"] as? String,
                            value = om["value"]
                        )
                    } else null
                }
                val fieldConfigMap = fm["config"] as? Map<String, Any>
                val fieldConfig = fieldConfigMap?.let { fc ->
                    FormFieldConfig(
                        max_length = (fc["max_length"] as? Number)?.toInt(),
                        keyboard_type = fc["keyboard_type"] as? String,
                        autocapitalize = fc["autocapitalize"] as? String,
                        min_value = (fc["min_value"] as? Number)?.toDouble(),
                        max_value = (fc["max_value"] as? Number)?.toDouble(),
                        step = (fc["step"] as? Number)?.toDouble(),
                        unit = fc["unit"] as? String,
                        decimal_places = (fc["decimal_places"] as? Number)?.toInt(),
                        min_date = fc["min_date"] as? String,
                        max_date = fc["max_date"] as? String,
                        picker_style = fc["picker_style"] as? String,
                        search_enabled = fc["search_enabled"] as? Boolean,
                        multi_select = fc["multi_select"] as? Boolean,
                        default_value = fc["default_value"]
                    )
                }
                val validationMap = fm["validation"] as? Map<String, Any>
                val fieldValidation = validationMap?.let { v ->
                    FormFieldValidation(
                        pattern = v["pattern"] as? String,
                        pattern_message = v["pattern_message"] as? String
                    )
                }
                val depMap = fm["depends_on"] as? Map<String, Any>
                val dependency = depMap?.let { d ->
                    FormFieldDependency(
                        field_id = d["field_id"] as? String ?: "",
                        operator = d["operator"] as? String ?: "not_empty",
                        value = d["value"]
                    )
                }
                FormField(
                    id = fm["id"] as? String ?: "",
                    type = FormFieldType.fromString(fm["type"] as? String ?: "text"),
                    label = fm["label"] as? String ?: "",
                    placeholder = fm["placeholder"] as? String,
                    required = fm["required"] as? Boolean ?: false,
                    validation = fieldValidation,
                    options = fieldOptions,
                    config = fieldConfig,
                    depends_on = dependency
                )
            } else null
        }

        val config = StepConfig(
            title = configMap["title"] as? String,
            subtitle = configMap["subtitle"] as? String,
            image_url = configMap["image_url"] as? String,
            cta_text = configMap["cta_text"] as? String,
            skip_enabled = configMap["skip_enabled"] as? Boolean,
            options = options,
            selection_mode = selectionMode,
            items = items,
            layout = configMap["layout"] as? Map<String, Any>,
            fields = fields,
            validation_mode = configMap["validation_mode"] as? String
        )

        return OnboardingStep(
            id = map["id"] as? String ?: "",
            type = OnboardingStep.StepType.fromString(typeStr),
            config = config
        )
    }
}
