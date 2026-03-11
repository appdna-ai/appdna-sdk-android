package ai.appdna.sdk.onboarding

import ai.appdna.sdk.core.TextStyleConfig

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
    val config: StepConfig,
    val hook: StepHookConfig? = null
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
    val validation_mode: String? = null,  // "on_submit" or "realtime"

    // SPEC-083: Populated by applyOverrides from StepConfigOverride.fieldDefaults
    val field_defaults: Map<String, Any>? = null,

    // SPEC-084: Rendering fidelity
    val content_blocks: List<ContentBlock>? = null,
    val layout_variant: String? = null,  // image_top, image_bottom, image_fullscreen, image_split, no_image
    val background: ai.appdna.sdk.core.BackgroundStyleConfig? = null,
    val text_style: ai.appdna.sdk.core.TextStyleConfig? = null,
    val element_style: ai.appdna.sdk.core.ElementStyleConfig? = null,
    val animation: ai.appdna.sdk.core.AnimationConfig? = null,
    val localizations: Map<String, Map<String, String>>? = null,
    val default_locale: String? = null
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
    val operator: String,  // equals, not_equals, contains, not_empty, empty, gt, lt, is_set
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

// MARK: - Async Step Hook Types (SPEC-083)

/**
 * Result of the async step hook called before advancing.
 */
sealed class StepAdvanceResult {
    /** Continue to next step normally. */
    object Proceed : StepAdvanceResult()

    /** Continue to next step, merging additional data into responses. */
    data class ProceedWithData(val data: Map<String, Any>) : StepAdvanceResult()

    /** Block advancement. Stay on current step and display error message. */
    data class Block(val message: String) : StepAdvanceResult()

    /** Skip to a specific step by ID, optionally merging data. */
    data class SkipTo(val stepId: String, val data: Map<String, Any>? = null) : StepAdvanceResult()
}

/**
 * Optional config override for dynamic step content.
 */
data class StepConfigOverride(
    val fieldDefaults: Map<String, Any>? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val ctaText: String? = null,
    val layoutOverrides: Map<String, Any>? = null
)

// MARK: - Step Hook Config (SPEC-083 P1)

/**
 * Server-side webhook configuration for a step.
 */
data class StepHookConfig(
    val enabled: Boolean,
    val webhook_url: String,
    val timeout_ms: Int = 10000,
    val loading_text: String? = null,
    val error_text: String? = null,
    val retry_count: Int = 0,
    val headers: Map<String, String>? = null
)

/**
 * Delegate for receiving onboarding flow lifecycle events.
 */
interface AppDNAOnboardingDelegate {
    // Observe-only callbacks (unchanged)
    fun onOnboardingStarted(flowId: String) {}
    fun onOnboardingStepChanged(flowId: String, stepId: String, stepIndex: Int, totalSteps: Int) {}
    fun onOnboardingCompleted(flowId: String, responses: Map<String, Any>) {}
    fun onOnboardingDismissed(flowId: String, atStep: Int) {}

    // SPEC-083: Async hook called BEFORE advancing from a step.
    suspend fun onBeforeStepAdvance(
        flowId: String,
        fromStepId: String,
        stepIndex: Int,
        stepType: String,
        responses: Map<String, Any>,
        stepData: Map<String, Any>?
    ): StepAdvanceResult = StepAdvanceResult.Proceed

    // SPEC-083: Optional hook to modify step config before rendering.
    suspend fun onBeforeStepRender(
        flowId: String,
        stepId: String,
        stepIndex: Int,
        stepType: String,
        responses: Map<String, Any>
    ): StepConfigOverride? = null
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

        // SPEC-084: Parse content blocks
        @Suppress("UNCHECKED_CAST")
        val contentBlocks = (configMap["content_blocks"] as? List<*>)?.mapNotNull { b ->
            if (b is Map<*, *>) {
                val bm = b as Map<String, Any>
                ContentBlock(
                    id = bm["id"] as? String ?: "",
                    type = bm["type"] as? String ?: "text",
                    text = bm["text"] as? String,
                    level = (bm["level"] as? Number)?.toInt(),
                    image_url = bm["image_url"] as? String,
                    alt = bm["alt"] as? String,
                    corner_radius = (bm["corner_radius"] as? Number)?.toDouble(),
                    height = (bm["height"] as? Number)?.toDouble(),
                    variant = bm["variant"] as? String,
                    action = bm["action"] as? String,
                    action_value = bm["action_value"] as? String,
                    bg_color = bm["bg_color"] as? String,
                    text_color = bm["text_color"] as? String,
                    button_corner_radius = (bm["button_corner_radius"] as? Number)?.toDouble(),
                    spacer_height = (bm["spacer_height"] as? Number)?.toDouble(),
                    items = (bm["items"] as? List<*>)?.filterIsInstance<String>(),
                    list_style = bm["list_style"] as? String,
                    divider_color = bm["divider_color"] as? String,
                    divider_thickness = (bm["divider_thickness"] as? Number)?.toDouble(),
                    divider_margin_y = (bm["divider_margin_y"] as? Number)?.toDouble(),
                    badge_text = bm["badge_text"] as? String,
                    badge_bg_color = bm["badge_bg_color"] as? String,
                    badge_text_color = bm["badge_text_color"] as? String,
                    badge_corner_radius = (bm["badge_corner_radius"] as? Number)?.toDouble(),
                    icon_emoji = bm["icon_emoji"] as? String,
                    icon_size = (bm["icon_size"] as? Number)?.toDouble(),
                    icon_alignment = bm["icon_alignment"] as? String,
                    toggle_label = bm["toggle_label"] as? String,
                    toggle_description = bm["toggle_description"] as? String,
                    toggle_default = bm["toggle_default"] as? Boolean,
                    video_thumbnail_url = bm["video_thumbnail_url"] as? String,
                    // SPEC-084: per-block text style + video source fields
                    style = (bm["style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
                    video_url = bm["video_url"] as? String,
                    video_height = (bm["video_height"] as? Number)?.toDouble(),
                    video_corner_radius = (bm["video_corner_radius"] as? Number)?.toDouble(),
                )
            } else null
        }

        // SPEC-084: Parse background config
        @Suppress("UNCHECKED_CAST")
        val bgMap = configMap["background"] as? Map<String, Any>
        val background = bgMap?.let { bg ->
            val gradMap = bg["gradient"] as? Map<String, Any>
            val gradient = gradMap?.let { g ->
                val stopsList = (g["stops"] as? List<*>)?.mapNotNull { s ->
                    if (s is Map<*, *>) {
                        ai.appdna.sdk.core.GradientStopConfig(
                            color = (s as Map<String, Any>)["color"] as? String ?: "#000000",
                            position = ((s as Map<String, Any>)["position"] as? Number)?.toDouble() ?: 0.0,
                        )
                    } else null
                }
                ai.appdna.sdk.core.GradientConfig(type = g["type"] as? String, angle = (g["angle"] as? Number)?.toDouble(), stops = stopsList)
            }
            ai.appdna.sdk.core.BackgroundStyleConfig(
                type = bg["type"] as? String, color = bg["color"] as? String,
                gradient = gradient, image_url = bg["image_url"] as? String,
                image_fit = bg["image_fit"] as? String, overlay = bg["overlay"] as? String,
            )
        }

        // SPEC-084: Parse animation config
        @Suppress("UNCHECKED_CAST")
        val animMap = configMap["animation"] as? Map<String, Any>
        val animConfig = animMap?.let { a ->
            ai.appdna.sdk.core.AnimationConfig(
                entry_animation = a["entry_animation"] as? String,
                entry_duration_ms = (a["entry_duration_ms"] as? Number)?.toInt(),
                section_stagger = a["section_stagger"] as? String,
                section_stagger_delay_ms = (a["section_stagger_delay_ms"] as? Number)?.toInt(),
                cta_animation = a["cta_animation"] as? String,
                plan_selection_animation = a["plan_selection_animation"] as? String,
                dismiss_animation = a["dismiss_animation"] as? String,
            )
        }

        // SPEC-084: Parse localizations
        @Suppress("UNCHECKED_CAST")
        val localizations = configMap["localizations"] as? Map<String, Map<String, String>>

        // SPEC-084: Parse step-level text_style and element_style
        @Suppress("UNCHECKED_CAST")
        val textStyle = (configMap["text_style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) }
        @Suppress("UNCHECKED_CAST")
        val elementStyle = (configMap["element_style"] as? Map<String, Any>)?.let { parseElementStyleConfig(it) }

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
            validation_mode = configMap["validation_mode"] as? String,
            content_blocks = contentBlocks,
            layout_variant = configMap["layout_variant"] as? String,
            background = background,
            text_style = textStyle,
            element_style = elementStyle,
            animation = animConfig,
            localizations = localizations,
            default_locale = configMap["default_locale"] as? String,
        )

        // SPEC-083 P1: Parse hook config
        @Suppress("UNCHECKED_CAST")
        val hookMap = map["hook"] as? Map<String, Any>
        val hook = hookMap?.let { h ->
            val enabled = h["enabled"] as? Boolean ?: false
            val webhookUrl = h["webhook_url"] as? String ?: ""
            if (enabled && webhookUrl.isNotEmpty()) {
                StepHookConfig(
                    enabled = true,
                    webhook_url = webhookUrl,
                    timeout_ms = (h["timeout_ms"] as? Number)?.toInt() ?: 10000,
                    loading_text = h["loading_text"] as? String,
                    error_text = h["error_text"] as? String,
                    retry_count = (h["retry_count"] as? Number)?.toInt() ?: 0,
                    headers = h["headers"] as? Map<String, String>
                )
            } else null
        }

        return OnboardingStep(
            id = map["id"] as? String ?: "",
            type = OnboardingStep.StepType.fromString(typeStr),
            config = config,
            hook = hook
        )
    }

    /**
     * Parse a raw map from Firestore into a TextStyleConfig.
     * Used for per-block style overrides on ContentBlock.style (SPEC-084).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseTextStyleConfig(map: Map<String, Any>): TextStyleConfig {
        return TextStyleConfig(
            font_family = map["font_family"] as? String,
            font_size = (map["font_size"] as? Number)?.toDouble(),
            font_weight = (map["font_weight"] as? Number)?.toInt(),
            color = map["color"] as? String,
            alignment = map["alignment"] as? String,
            line_height = (map["line_height"] as? Number)?.toDouble(),
            letter_spacing = (map["letter_spacing"] as? Number)?.toDouble(),
            opacity = (map["opacity"] as? Number)?.toDouble(),
        )
    }

    /**
     * Parse a raw map into an ElementStyleConfig.
     * Used for step-level element_style (SPEC-084).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseElementStyleConfig(map: Map<String, Any>): ai.appdna.sdk.core.ElementStyleConfig {
        val bgMap = map["background"] as? Map<String, Any>
        val bg = bgMap?.let {
            val gradMap = it["gradient"] as? Map<String, Any>
            val grad = gradMap?.let { g ->
                val stops = (g["stops"] as? List<Map<String, Any>>)?.map { s ->
                    ai.appdna.sdk.core.GradientStopConfig(
                        color = s["color"] as? String ?: "#000000",
                        position = (s["position"] as? Number)?.toDouble() ?: 0.0,
                    )
                }
                ai.appdna.sdk.core.GradientConfig(type = g["type"] as? String, angle = (g["angle"] as? Number)?.toDouble(), stops = stops)
            }
            ai.appdna.sdk.core.BackgroundStyleConfig(
                type = it["type"] as? String, color = it["color"] as? String,
                gradient = grad, image_url = it["image_url"] as? String,
                image_fit = it["image_fit"] as? String, overlay = it["overlay"] as? String,
            )
        }
        val borderMap = map["border"] as? Map<String, Any>
        val border = borderMap?.let {
            ai.appdna.sdk.core.BorderStyleConfig(
                width = (it["width"] as? Number)?.toDouble(), color = it["color"] as? String,
                style = it["style"] as? String, radius = (it["radius"] as? Number)?.toDouble(),
                radius_top_left = (it["radius_top_left"] as? Number)?.toDouble(),
                radius_top_right = (it["radius_top_right"] as? Number)?.toDouble(),
                radius_bottom_left = (it["radius_bottom_left"] as? Number)?.toDouble(),
                radius_bottom_right = (it["radius_bottom_right"] as? Number)?.toDouble(),
            )
        }
        val shadowMap = map["shadow"] as? Map<String, Any>
        val shadow = shadowMap?.let {
            ai.appdna.sdk.core.ShadowStyleConfig(
                x = (it["x"] as? Number)?.toDouble(), y = (it["y"] as? Number)?.toDouble(),
                blur = (it["blur"] as? Number)?.toDouble(), spread = (it["spread"] as? Number)?.toDouble(),
                color = it["color"] as? String,
            )
        }
        val paddingMap = map["padding"] as? Map<String, Any>
        val padding = paddingMap?.let {
            ai.appdna.sdk.core.SpacingConfig(
                top = (it["top"] as? Number)?.toDouble(), right = (it["right"] as? Number)?.toDouble(),
                bottom = (it["bottom"] as? Number)?.toDouble(), left = (it["left"] as? Number)?.toDouble(),
            )
        }
        val tsMap = map["text_style"] as? Map<String, Any>
        val textStyle = tsMap?.let { parseTextStyleConfig(it) }
        return ai.appdna.sdk.core.ElementStyleConfig(
            background = bg, border = border, shadow = shadow, padding = padding,
            corner_radius = (map["corner_radius"] as? Number)?.toDouble(),
            opacity = (map["opacity"] as? Number)?.toDouble(),
            text_style = textStyle,
        )
    }
}
