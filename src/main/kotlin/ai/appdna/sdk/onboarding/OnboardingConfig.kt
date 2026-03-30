package ai.appdna.sdk.onboarding

import ai.appdna.sdk.core.TextStyleConfig
import ai.appdna.sdk.core.HapticConfig
import ai.appdna.sdk.core.HapticTriggers
import ai.appdna.sdk.core.ParticleEffect

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
    val skip_to_step: String? = null,
    // SPEC-085: Rich media config
    val haptic: HapticConfig? = null,
    val particle_effect: ParticleEffect? = null,
    // Gap 9: Custom progress bar colors
    val progress_color: String? = null,
    val progress_track_color: String? = null,
)

/**
 * A single step within a flow.
 */
data class NextStepRule(
    val condition: Any? = null,
    val target_step_id: String = ""
)

data class OnboardingStep(
    val id: String,
    val type: StepType,
    val config: StepConfig,
    val hook: StepHookConfig? = null,
    /** When true, the progress indicator is hidden on this step but the step still counts toward total progress. */
    val hide_progress: Boolean? = null,
    val next_step_rules: List<NextStepRule>? = null
) {
    enum class StepType(val value: String) {
        WELCOME("welcome"),
        QUESTION("question"),
        VALUE_PROP("value_prop"),
        CUSTOM("custom"),
        FORM("form"),
        INTERACTIVE_CHAT("interactive_chat");

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

    // SPEC-090: Interactive chat
    val chat_config: ChatConfig? = null,

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
    STEPPER("stepper"), SEGMENTED("segmented"),
    LOCATION("location");

    companion object {
        fun fromString(value: String): FormFieldType {
            return entries.find { it.value == value } ?: TEXT
        }
    }
}

/** Structured location data from geocoding autocomplete (SPEC-089). */
data class LocationData(
    val formatted_address: String = "",
    val city: String = "",
    val state: String = "",
    val state_code: String = "",
    val country: String = "",
    val country_code: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val timezone: String = "UTC",
    val timezone_offset: Int = 0,
    val postal_code: String? = null,
    val raw_query: String = ""
)

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
    val default_value: Any? = null,
    // Location (SPEC-089)
    val location_type: String? = null,
    val location_bias_country: String? = null,
    val location_language: String? = null,
    val location_placeholder: String? = null,
    val location_min_chars: Int? = null
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
        // SPEC-085: Parse haptic config
        val hapticMap = settingsMap["haptic"] as? Map<String, Any>
        val haptic = hapticMap?.let { h ->
            val triggersMap = h["triggers"] as? Map<String, Any>
            HapticConfig(
                enabled = h["enabled"] as? Boolean ?: false,
                triggers = triggersMap?.let { t ->
                    HapticTriggers(
                        on_step_advance = t["on_step_advance"] as? String,
                        on_button_tap = t["on_button_tap"] as? String,
                        on_option_select = t["on_option_select"] as? String,
                        on_toggle = t["on_toggle"] as? String,
                        on_form_submit = t["on_form_submit"] as? String,
                        on_error = t["on_error"] as? String,
                        on_success = t["on_success"] as? String,
                    )
                } ?: HapticTriggers(),
            )
        }
        // SPEC-085: Parse particle effect config
        val particleMap = settingsMap["particle_effect"] as? Map<String, Any>
        val particleEffect = particleMap?.let { p ->
            ParticleEffect(
                type = p["type"] as? String ?: "confetti",
                trigger = p["trigger"] as? String ?: "on_appear",
                duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                intensity = p["intensity"] as? String ?: "medium",
                colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
            )
        }
        val settings = OnboardingSettings(
            show_progress = settingsMap["show_progress"] as? Boolean ?: true,
            allow_back = settingsMap["allow_back"] as? Boolean ?: true,
            skip_to_step = settingsMap["skip_to_step"] as? String,
            haptic = haptic,
            particle_effect = particleEffect,
            progress_color = settingsMap["progress_color"] as? String,
            progress_track_color = settingsMap["progress_track_color"] as? String,
        )

        return OnboardingFlowConfig(
            id = map["id"] as? String ?: id,
            name = map["name"] as? String ?: "",
            version = (map["version"] as? Number)?.toInt() ?: 1,
            steps = steps,
            settings = settings
        )
    }

    /** Exposed as internal for unit testing. */
    @Suppress("UNCHECKED_CAST")
    internal fun parseStepForTest(map: Map<String, Any>): OnboardingStep? = try { parseStep(map) } catch (_: Exception) { null }

    @Suppress("UNCHECKED_CAST")
    private fun parseStep(map: Map<String, Any>): OnboardingStep {
        val typeStr = map["type"] as? String ?: "custom"
        val configMap = (map["config"] as? Map<String, Any>)
            ?: (map["layout"] as? Map<String, Any>)
            ?: emptyMap()

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
                        default_value = fc["default_value"],
                        location_type = fc["location_type"] as? String,
                        location_bias_country = fc["location_bias_country"] as? String,
                        location_language = fc["location_language"] as? String,
                        location_placeholder = fc["location_placeholder"] as? String,
                        location_min_chars = (fc["location_min_chars"] as? Number)?.toInt()
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

        // SPEC-084: Parse content blocks (fallback to step-level content_blocks if not in configMap)
        @Suppress("UNCHECKED_CAST")
        val rawContentBlocks = (configMap["content_blocks"] as? List<*>)
            ?: (map["content_blocks"] as? List<*>)
        val contentBlocks = rawContentBlocks?.mapNotNull { b ->
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
                    // SPEC-085: Rich media fields
                    lottie_url = bm["lottie_url"] as? String,
                    lottie_json = bm["lottie_json"] as? Map<String, Any>,
                    lottie_autoplay = bm["lottie_autoplay"] as? Boolean,
                    lottie_loop = bm["lottie_loop"] as? Boolean,
                    lottie_speed = (bm["lottie_speed"] as? Number)?.toFloat(),
                    rive_url = bm["rive_url"] as? String,
                    rive_artboard = bm["rive_artboard"] as? String,
                    rive_state_machine = bm["rive_state_machine"] as? String,
                    icon_ref = bm["icon_ref"] ?: bm["icon"],
                    video_autoplay = bm["video_autoplay"] as? Boolean,
                    video_loop = bm["video_loop"] as? Boolean,
                    video_muted = bm["video_muted"] as? Boolean,
                    // SPEC-089d §6.1: Per-block style design tokens
                    block_style = (bm["block_style"] as? Map<String, Any>)?.let { parseBlockStyle(it) },
                    // SPEC-089d §6.2: 2D positioning
                    vertical_align = bm["vertical_align"] as? String,
                    horizontal_align = bm["horizontal_align"] as? String,
                    vertical_offset = (bm["vertical_offset"] as? Number)?.toDouble(),
                    horizontal_offset = (bm["horizontal_offset"] as? Number)?.toDouble(),
                    // SPEC-089d: page_indicator fields
                    dot_count = (bm["dot_count"] as? Number)?.toInt(),
                    active_index = (bm["active_index"] as? Number)?.toInt(),
                    active_color = bm["active_color"] as? String,
                    inactive_color = bm["inactive_color"] as? String,
                    dot_size = (bm["dot_size"] as? Number)?.toDouble(),
                    dot_spacing = (bm["dot_spacing"] as? Number)?.toDouble(),
                    active_dot_width = (bm["active_dot_width"] as? Number)?.toDouble(),
                    // SPEC-089d: social_login fields
                    providers = (bm["providers"] as? List<*>)?.mapNotNull { p ->
                        if (p is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val pm = p as Map<String, Any>
                            SocialProvider(
                                type = pm["type"] as? String ?: "",
                                label = pm["label"] as? String,
                                enabled = pm["enabled"] as? Boolean ?: true,
                            )
                        } else null
                    },
                    button_style = bm["button_style"] as? String,
                    button_height = (bm["button_height"] as? Number)?.toDouble(),
                    spacing = (bm["spacing"] as? Number)?.toDouble(),
                    show_divider = bm["show_divider"] as? Boolean,
                    divider_text = bm["divider_text"] as? String,
                    // SPEC-089d: countdown_timer fields
                    target_type = bm["target_type"] as? String,
                    duration_seconds = (bm["duration_seconds"] as? Number)?.toInt(),
                    target_datetime = bm["target_datetime"] as? String,
                    show_days = bm["show_days"] as? Boolean,
                    show_hours = bm["show_hours"] as? Boolean,
                    show_minutes = bm["show_minutes"] as? Boolean,
                    show_seconds = bm["show_seconds"] as? Boolean,
                    labels = (bm["labels"] as? Map<String, Any>)?.let { lm ->
                        CountdownLabels(
                            days = lm["days"] as? String,
                            hours = lm["hours"] as? String,
                            minutes = lm["minutes"] as? String,
                            seconds = lm["seconds"] as? String,
                        )
                    },
                    on_expire_action = bm["on_expire_action"] as? String,
                    expired_text = bm["expired_text"] as? String,
                    accent_color = bm["accent_color"] as? String,
                    font_size = (bm["font_size"] as? Number)?.toDouble(),
                    alignment = bm["alignment"] as? String,
                    // SPEC-089d: rating fields
                    field_id = bm["field_id"] as? String,
                    max_stars = (bm["max_stars"] as? Number)?.toInt(),
                    default_value = (bm["default_value"] as? Number)?.toDouble(),
                    star_size = (bm["star_size"] as? Number)?.toDouble(),
                    active_rating_color = bm["active_rating_color"] as? String ?: bm["filled_color"] as? String ?: bm["active_color"] as? String,
                    inactive_rating_color = bm["inactive_rating_color"] as? String ?: bm["empty_color"] as? String ?: bm["inactive_color"] as? String,
                    allow_half = bm["allow_half"] as? Boolean,
                    label = bm["label"] as? String,
                    // SPEC-089d: rich_text fields
                    content = bm["content"] as? String,
                    base_style = (bm["base_style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
                    link_color = bm["link_color"] as? String,
                    max_lines = (bm["max_lines"] as? Number)?.toInt(),
                    // SPEC-089d: progress_bar fields
                    segment_count = (bm["segment_count"] as? Number)?.toInt() ?: (bm["total_segments"] as? Number)?.toInt(),
                    active_segments = (bm["active_segments"] as? Number)?.toInt() ?: (bm["filled_segments"] as? Number)?.toInt(),
                    fill_color = bm["fill_color"] as? String,
                    track_color = bm["track_color"] as? String,
                    segment_gap = (bm["segment_gap"] as? Number)?.toDouble(),
                    show_label = bm["show_label"] as? Boolean,
                    label_style = (bm["label_style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
                    // SPEC-089d: timeline fields
                    timeline_items = (bm["timeline_items"] as? List<*>
                        ?: bm["items"] as? List<*>)?.mapNotNull { ti ->
                        if (ti is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val tm = ti as Map<String, Any>
                            // Only parse as TimelineItem if it has a title (to distinguish from string items)
                            val title = tm["title"] as? String ?: return@mapNotNull null
                            TimelineItem(
                                id = tm["id"] as? String ?: "",
                                title = title,
                                subtitle = tm["subtitle"] as? String,
                                icon = tm["icon"] as? String,
                                status = tm["status"] as? String ?: "upcoming",
                            )
                        } else null
                    },
                    line_color = bm["line_color"] as? String,
                    completed_color = bm["completed_color"] as? String,
                    current_color = bm["current_color"] as? String,
                    upcoming_color = bm["upcoming_color"] as? String,
                    show_line = bm["show_line"] as? Boolean,
                    compact = bm["compact"] as? Boolean,
                    title_style = (bm["title_style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
                    subtitle_style = (bm["subtitle_style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
                    // SPEC-089d: animated_loading fields
                    loading_items = (bm["loading_items"] as? List<*>
                        ?: if (bm["type"] == "animated_loading") bm["items"] as? List<*> else null)?.mapNotNull { li ->
                        if (li is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val lm = li as Map<String, Any>
                            LoadingItem(
                                label = lm["label"] as? String ?: "",
                                duration_ms = (lm["duration_ms"] as? Number)?.toInt() ?: 1000,
                                icon = lm["icon"] as? String,
                            )
                        } else null
                    },
                    progress_color = bm["progress_color"] as? String,
                    check_color = bm["check_color"] as? String,
                    total_duration_ms = (bm["total_duration_ms"] as? Number)?.toInt(),
                    auto_advance = bm["auto_advance"] as? Boolean,
                    show_percentage = bm["show_percentage"] as? Boolean,
                    // SPEC-089d: Form input fields
                    field_label = bm["field_label"] as? String,
                    field_placeholder = bm["field_placeholder"] as? String,
                    field_required = bm["field_required"] as? Boolean,
                    field_options = (bm["field_options"] as? List<*>)?.mapNotNull { fo ->
                        if (fo is Map<*, *>) {
                            @Suppress("UNCHECKED_CAST")
                            val fm = fo as Map<String, Any>
                            InputOption(
                                value = fm["id"] as? String ?: fm["value"] as? String ?: "",
                                label = fm["label"] as? String ?: "",
                                image_url = fm["image_url"] as? String,
                            )
                        } else null
                    },
                    // Gap 8: Parse field_config for display_style, use_variable, use_webhook
                    field_config = bm["field_config"] as? Map<String, Any>,
                    // SPEC-089d: Visibility, animation, pressed style, bindings, sizing
                    visibility_condition = (bm["visibility_condition"] as? Map<String, Any>)?.let { vc ->
                        VisibilityCondition(
                            type = vc["type"] as? String ?: "always",
                            variable = vc["variable"] as? String,
                            value = vc["value"],
                            expression = vc["expression"] as? String,
                        )
                    },
                    entrance_animation = (bm["entrance_animation"] as? Map<String, Any>)?.let { ea ->
                        EntranceAnimationConfig(
                            type = ea["type"] as? String ?: "none",
                            duration_ms = (ea["duration_ms"] as? Number)?.toInt() ?: 300,
                            delay_ms = (ea["delay_ms"] as? Number)?.toInt() ?: 0,
                            easing = ea["easing"] as? String ?: "ease_out",
                            spring_damping = (ea["spring_damping"] as? Number)?.toDouble(),
                        )
                    },
                    pressed_style = (bm["pressed_style"] as? Map<String, Any>)?.let { ps ->
                        PressedStyleConfig(
                            scale = (ps["scale"] as? Number)?.toDouble(),
                            opacity = (ps["opacity"] as? Number)?.toDouble(),
                            bg_color = ps["bg_color"] as? String,
                            text_color = ps["text_color"] as? String,
                        )
                    },
                    bindings = (bm["bindings"] as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() },
                    element_width = bm["element_width"] as? String,
                    element_height = bm["element_height"] as? String,
                    overflow = bm["overflow"] as? String,
                    // Row / stack container fields
                    children = parseChildBlocks(bm["children"]),
                    row_direction = bm["row_direction"] as? String,
                    row_distribution = bm["row_distribution"] as? String,
                    row_child_fill = bm["row_child_fill"] as? Boolean,
                    gap = (bm["gap"] as? Number)?.toDouble(),
                    wrap = bm["wrap"] as? Boolean,
                    justify = bm["justify"] as? String,
                    align_items = bm["align_items"] as? String,
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
            chat_config = parseChatConfig(configMap["chat_config"]),
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

        // Parse next_step_rules
        @Suppress("UNCHECKED_CAST")
        val nextStepRules = (map["next_step_rules"] as? List<*>)?.mapNotNull { r ->
            if (r is Map<*, *>) {
                val rm = r as Map<String, Any>
                NextStepRule(
                    condition = rm["condition"],
                    target_step_id = rm["target_step_id"] as? String ?: ""
                )
            } else null
        }

        return OnboardingStep(
            id = map["id"] as? String ?: "",
            type = OnboardingStep.StepType.fromString(typeStr),
            config = config,
            hook = hook,
            hide_progress = map["hide_progress"] as? Boolean,
            next_step_rules = nextStepRules
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

    /**
     * Parse a raw Firestore map into a BlockStyle (SPEC-089d §6.1).
     * Used for per-block design tokens: background, border, shadow, padding, margin, opacity.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseBlockStyle(map: Map<String, Any>): BlockStyle {
        val shadowMap = map["shadow"] as? Map<String, Any>
        val shadow = shadowMap?.let { s ->
            BlockShadowStyle(
                x = (s["x"] as? Number)?.toDouble() ?: 0.0,
                y = (s["y"] as? Number)?.toDouble() ?: 2.0,
                blur = (s["blur"] as? Number)?.toDouble() ?: 8.0,
                spread = (s["spread"] as? Number)?.toDouble() ?: 0.0,
                color = s["color"] as? String ?: "#1A000000",
            )
        }
        val gradientMap = map["background_gradient"] as? Map<String, Any>
        val gradient = gradientMap?.let { g ->
            BlockGradientStyle(
                angle = (g["angle"] as? Number)?.toDouble() ?: 135.0,
                start = g["start"] as? String ?: "#6366f1",
                end = g["end"] as? String ?: "#a855f7",
            )
        }
        return BlockStyle(
            background_color = map["background_color"] as? String,
            background_gradient = gradient,
            border_color = map["border_color"] as? String,
            border_width = (map["border_width"] as? Number)?.toDouble(),
            border_style = map["border_style"] as? String,
            border_radius = (map["border_radius"] as? Number)?.toDouble(),
            shadow = shadow,
            padding_top = (map["padding_top"] as? Number)?.toDouble(),
            padding_right = (map["padding_right"] as? Number)?.toDouble(),
            padding_bottom = (map["padding_bottom"] as? Number)?.toDouble(),
            padding_left = (map["padding_left"] as? Number)?.toDouble(),
            margin_top = (map["margin_top"] as? Number)?.toDouble(),
            margin_bottom = (map["margin_bottom"] as? Number)?.toDouble(),
            margin_left = (map["margin_left"] as? Number)?.toDouble(),
            margin_right = (map["margin_right"] as? Number)?.toDouble(),
            opacity = (map["opacity"] as? Number)?.toDouble(),
        )
    }

    /**
     * Recursively parse child ContentBlocks for row/stack containers.
     * Accepts the raw `children` value from a Firestore map and returns a list of ContentBlock,
     * or null if no children are present.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseChildBlocks(raw: Any?): List<ContentBlock>? {
        val list = raw as? List<*> ?: return null
        return list.mapNotNull { child ->
            if (child is Map<*, *>) {
                val cm = child as Map<String, Any>
                ContentBlock(
                    id = cm["id"] as? String ?: "",
                    type = cm["type"] as? String ?: "text",
                    text = cm["text"] as? String,
                    level = (cm["level"] as? Number)?.toInt(),
                    image_url = cm["image_url"] as? String,
                    alt = cm["alt"] as? String,
                    corner_radius = (cm["corner_radius"] as? Number)?.toDouble(),
                    height = (cm["height"] as? Number)?.toDouble(),
                    variant = cm["variant"] as? String,
                    action = cm["action"] as? String,
                    action_value = cm["action_value"] as? String,
                    bg_color = cm["bg_color"] as? String,
                    text_color = cm["text_color"] as? String,
                    button_corner_radius = (cm["button_corner_radius"] as? Number)?.toDouble(),
                    spacer_height = (cm["spacer_height"] as? Number)?.toDouble(),
                    icon_emoji = cm["icon_emoji"] as? String,
                    icon_size = (cm["icon_size"] as? Number)?.toDouble(),
                    style = (cm["style"] as? Map<String, Any>)?.let { parseTextStyleConfig(it) },
                    block_style = (cm["block_style"] as? Map<String, Any>)?.let { parseBlockStyle(it) },
                    field_id = cm["field_id"] as? String,
                    field_label = cm["field_label"] as? String,
                    field_placeholder = cm["field_placeholder"] as? String,
                    field_required = cm["field_required"] as? Boolean,
                    field_config = cm["field_config"] as? Map<String, Any>,
                    children = parseChildBlocks(cm["children"]),
                    row_direction = cm["row_direction"] as? String,
                    row_distribution = cm["row_distribution"] as? String,
                    row_child_fill = cm["row_child_fill"] as? Boolean,
                    gap = (cm["gap"] as? Number)?.toDouble(),
                    align_items = cm["align_items"] as? String,
                    element_width = cm["element_width"] as? String,
                    element_height = cm["element_height"] as? String,
                    overflow = cm["overflow"] as? String,
                )
            } else null
        }.takeIf { it.isNotEmpty() }
    }
}
