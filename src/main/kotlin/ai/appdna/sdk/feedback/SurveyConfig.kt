package ai.appdna.sdk.feedback

import org.json.JSONObject
import org.json.JSONArray
import ai.appdna.sdk.core.TextStyleConfig
import ai.appdna.sdk.core.ElementStyleConfig
import ai.appdna.sdk.core.BackgroundStyleConfig
import ai.appdna.sdk.core.BorderStyleConfig
import ai.appdna.sdk.core.ShadowStyleConfig
import ai.appdna.sdk.core.SpacingConfig
import ai.appdna.sdk.core.GradientConfig
import ai.appdna.sdk.core.GradientStopConfig

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
    val freeTextConfig: FreeTextConfig?,
    /**
     * SPEC-070-A F.6 / SPEC-085: optional question-level hero image rendered
     * above the question text. Mirrors iOS `SurveyQuestion.image_url`.
     */
    val imageUrl: String? = null,
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
                csatConfig = csatData?.let {
                    // SPEC-070-A F.4: read Firestore-canonical `scale` + `labels`,
                    // fall back to legacy `max_rating` + `style`.
                    @Suppress("UNCHECKED_CAST")
                    val labelsList = (it["labels"] as? List<*>)?.filterIsInstance<String>()
                    CSATConfig(
                        maxRating = (it["max_rating"] as? Number)?.toInt() ?: 5,
                        style = it["style"] as? String ?: "star",
                        scale = (it["scale"] as? Number)?.toInt(),
                        labels = labelsList,
                    )
                },
                ratingConfig = ratingData?.let {
                    // SPEC-070-A F.4: read Firestore `max` + `icon`,
                    // fall back to legacy `max_rating` + `style`.
                    RatingConfig(
                        maxRating = (it["max_rating"] as? Number)?.toInt() ?: 5,
                        style = it["style"] as? String ?: "star",
                        max = (it["max"] as? Number)?.toInt(),
                        icon = it["icon"] as? String,
                    )
                },
                options = optionsData?.mapNotNull { o ->
                    @Suppress("UNCHECKED_CAST")
                    val om = o as? Map<String, Any> ?: return@mapNotNull null
                    // SPEC-070-A F.5: accept both `label` (Firestore canonical)
                    // and `text` (legacy) — prefer label, fall back to text.
                    val labelOrText = (om["label"] as? String) ?: (om["text"] as? String) ?: ""
                    QuestionOption(om["id"] as? String ?: "", labelOrText, om["icon"] as? String)
                },
                emojiConfig = emojiData?.let {
                    @Suppress("UNCHECKED_CAST")
                    EmojiConfig((it["emojis"] as? List<String>) ?: listOf("\uD83D\uDE21", "\uD83D\uDE15", "\uD83D\uDE10", "\uD83D\uDE0A", "\uD83D\uDE0D"))
                },
                freeTextConfig = freeTextData?.let { FreeTextConfig(it["placeholder"] as? String, (it["max_length"] as? Number)?.toInt() ?: 500) },
                // SPEC-070-A F.6: question-level hero image
                imageUrl = data["image_url"] as? String,
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

/**
 * CSAT (customer satisfaction) question config.
 *
 * SPEC-070-A F.4: matches iOS `CSATConfig` shape — Firestore writes `scale`
 * (3, 5, or 7), `labels` (per-step), and `style`; legacy SDKs wrote
 * `max_rating`. Use [resolvedMax] to read the effective scale.
 */
data class CSATConfig(
    /** Legacy SDK field. Prefer [scale] when both are set. */
    val maxRating: Int = 5,
    val style: String = "star",
    /** Firestore-canonical scale value (3, 5, or 7). Null when only legacy `max_rating` provided. */
    val scale: Int? = null,
    /** Optional per-step labels rendered under each option. */
    val labels: List<String>? = null,
) {
    /** Effective max — prefer Firestore [scale], fall back to legacy [maxRating]. */
    val resolvedMax: Int get() = scale ?: maxRating
}

/**
 * 1–N rating question config (star/heart/thumb).
 *
 * SPEC-070-A F.4: matches iOS `RatingConfig` shape — Firestore writes `max`
 * (e.g., 5 or 10) + `icon` ("star" | "heart" | "thumb"); legacy SDKs wrote
 * `max_rating` + `style`. Use [resolvedMax] / [resolvedIcon] for the effective
 * values.
 */
data class RatingConfig(
    /** Legacy SDK field. Prefer [max] when both are set. */
    val maxRating: Int = 5,
    /** Legacy SDK field. Prefer [icon] when both are set. */
    val style: String = "star",
    /** Firestore-canonical max value. Null when only legacy `max_rating` provided. */
    val max: Int? = null,
    /** Firestore-canonical icon. Null when only legacy `style` provided. */
    val icon: String? = null,
) {
    /** Effective max — prefer Firestore [max], fall back to legacy [maxRating]. */
    val resolvedMax: Int get() = max ?: maxRating
    /** Effective icon — prefer Firestore [icon], fall back to legacy [style]. */
    val resolvedIcon: String get() = icon ?: style
}
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
    val showProgress: Boolean,
    // SPEC-084: Style engine integration
    val cornerRadius: Int? = null,
    // SPEC-084: Gap #20 — question text style token
    val questionTextStyle: TextStyleConfig? = null,
    // SPEC-084: Gap #21 — option card container style token
    val optionStyle: ElementStyleConfig? = null,
    // SPEC-085: Rich media
    val introLottieUrl: String? = null,
    val thankyouLottieUrl: String? = null,
    val thankyouParticleEffect: ai.appdna.sdk.core.ParticleEffect? = null,
    val blurBackdrop: ai.appdna.sdk.core.BlurConfig? = null,
    val haptic: ai.appdna.sdk.core.HapticConfig? = null,
    // SPEC-088: Configurable thank-you text for interpolation
    val thankYouText: String? = null,
) {
    companion object {
        fun fromMap(data: Map<String, Any>): SurveyAppearance {
            @Suppress("UNCHECKED_CAST")
            val themeData = data["theme"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val qtsData = data["question_text_style"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val osData = data["option_style"] as? Map<String, Any>
            // SPEC-085: Parse particle effect
            @Suppress("UNCHECKED_CAST")
            val peData = data["thankyou_particle_effect"] as? Map<String, Any>
            val particleEffect = peData?.let { p ->
                ai.appdna.sdk.core.ParticleEffect(
                    type = p["type"] as? String ?: "confetti",
                    trigger = p["trigger"] as? String ?: "on_flow_complete",
                    duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                    intensity = p["intensity"] as? String ?: "medium",
                    colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
                )
            }
            // SPEC-085: Parse blur config
            @Suppress("UNCHECKED_CAST")
            val blurData = data["blur_backdrop"] as? Map<String, Any>
            val blurConfig = blurData?.let { b ->
                ai.appdna.sdk.core.BlurConfig(
                    radius = (b["radius"] as? Number)?.toFloat() ?: 0f,
                    tint = b["tint"] as? String,
                    saturation = (b["saturation"] as? Number)?.toFloat(),
                )
            }
            // SPEC-085: Parse haptic config
            @Suppress("UNCHECKED_CAST")
            val hapticData = data["haptic"] as? Map<String, Any>
            val hapticConfig = hapticData?.let { h ->
                val triggersMap = h["triggers"] as? Map<String, Any>
                ai.appdna.sdk.core.HapticConfig(
                    enabled = h["enabled"] as? Boolean ?: false,
                    triggers = triggersMap?.let { t ->
                        ai.appdna.sdk.core.HapticTriggers(
                            on_option_select = t["on_option_select"] as? String,
                            on_button_tap = t["on_button_tap"] as? String,
                            on_form_submit = t["on_form_submit"] as? String,
                            on_success = t["on_success"] as? String,
                        )
                    } ?: ai.appdna.sdk.core.HapticTriggers(),
                )
            }
            // SPEC-070-A F.3: parse rich-media + typography fields from BOTH
            // `appearance.theme.*` (iOS canonical location) and the legacy
            // `appearance.*` location, preferring theme.* when set.
            // SPEC-070-A F.3: gradient parser (theme.gradient / theme.button_gradient)
            fun parseGradient(raw: Any?): ai.appdna.sdk.core.GradientConfig? {
                @Suppress("UNCHECKED_CAST")
                val m = raw as? Map<String, Any> ?: return null
                val stops = (m["stops"] as? List<*>)?.mapNotNull { s ->
                    @Suppress("UNCHECKED_CAST")
                    val sm = s as? Map<String, Any> ?: return@mapNotNull null
                    ai.appdna.sdk.core.GradientStopConfig(
                        color = sm["color"] as? String ?: "#000000",
                        position = (sm["position"] as? Number)?.toDouble() ?: 0.0,
                    )
                }
                return ai.appdna.sdk.core.GradientConfig(
                    type = m["type"] as? String,
                    angle = (m["angle"] as? Number)?.toDouble(),
                    stops = stops,
                )
            }

            fun parseHaptic(raw: Any?): ai.appdna.sdk.core.HapticConfig? {
                @Suppress("UNCHECKED_CAST")
                val h = raw as? Map<String, Any> ?: return null
                @Suppress("UNCHECKED_CAST")
                val triggersMap = h["triggers"] as? Map<String, Any>
                // SPEC-070-A F.11: read all 8 trigger fields (iOS HapticTriggers parity).
                return ai.appdna.sdk.core.HapticConfig(
                    enabled = h["enabled"] as? Boolean ?: false,
                    triggers = triggersMap?.let { t ->
                        ai.appdna.sdk.core.HapticTriggers(
                            on_step_advance = t["on_step_advance"] as? String,
                            on_button_tap = t["on_button_tap"] as? String,
                            on_plan_select = t["on_plan_select"] as? String,
                            on_option_select = t["on_option_select"] as? String,
                            on_toggle = t["on_toggle"] as? String,
                            on_form_submit = t["on_form_submit"] as? String,
                            on_error = t["on_error"] as? String,
                            on_success = t["on_success"] as? String,
                        )
                    } ?: ai.appdna.sdk.core.HapticTriggers(),
                )
            }

            fun parseParticle(raw: Any?, defaultTrigger: String): ai.appdna.sdk.core.ParticleEffect? {
                @Suppress("UNCHECKED_CAST")
                val p = raw as? Map<String, Any> ?: return null
                return ai.appdna.sdk.core.ParticleEffect(
                    type = p["type"] as? String ?: "confetti",
                    trigger = p["trigger"] as? String ?: defaultTrigger,
                    duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                    intensity = p["intensity"] as? String ?: "medium",
                    colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
                )
            }

            fun parseBlur(raw: Any?): ai.appdna.sdk.core.BlurConfig? {
                @Suppress("UNCHECKED_CAST")
                val b = raw as? Map<String, Any> ?: return null
                return ai.appdna.sdk.core.BlurConfig(
                    radius = (b["radius"] as? Number)?.toFloat() ?: 0f,
                    tint = b["tint"] as? String,
                    saturation = (b["saturation"] as? Number)?.toFloat(),
                )
            }

            return SurveyAppearance(
                presentation = data["presentation"] as? String ?: "bottom_sheet",
                theme = themeData?.let {
                    // SPEC-070-A F.3: read theme-level + (back-compat) appearance-level
                    val tIntroLottie = it["intro_lottie_url"] as? String ?: data["intro_lottie_url"] as? String
                    val tThankyouLottie = it["thankyou_lottie_url"] as? String ?: data["thankyou_lottie_url"] as? String
                    val tParticle = parseParticle(it["thankyou_particle_effect"], "on_flow_complete")
                        ?: parseParticle(data["thankyou_particle_effect"], "on_flow_complete")
                    val tBlur = parseBlur(it["blur_backdrop"]) ?: parseBlur(data["blur_backdrop"])
                    val tHaptic = parseHaptic(it["haptic"]) ?: parseHaptic(data["haptic"])
                    val tThankYouText = it["thank_you_text"] as? String ?: data["thank_you_text"] as? String

                    SurveyTheme(
                        backgroundColor = it["background_color"] as? String,
                        textColor = it["text_color"] as? String,
                        accentColor = it["accent_color"] as? String,
                        buttonColor = it["button_color"] as? String,
                        fontFamily = it["font_family"] as? String,
                        buttonTextColor = it["button_text_color"] as? String,
                        gradient = parseGradient(it["gradient"]),
                        buttonGradient = parseGradient(it["button_gradient"]),
                        textAlign = it["text_align"] as? String,
                        questionFontSize = (it["question_font_size"] as? Number)?.toDouble(),
                        fontWeight = it["font_weight"] as? String,
                        introLottieUrl = tIntroLottie,
                        thankyouLottieUrl = tThankyouLottie,
                        thankyouParticleEffect = tParticle,
                        blurBackdrop = tBlur,
                        haptic = tHaptic,
                        thankYouText = tThankYouText,
                    )
                },
                dismissAllowed = data["dismiss_allowed"] as? Boolean ?: true,
                showProgress = data["show_progress"] as? Boolean ?: false,
                cornerRadius = (data["corner_radius"] as? Number)?.toInt(),
                questionTextStyle = qtsData?.let { parseTextStyleConfig(it) },
                optionStyle = osData?.let { parseElementStyleConfig(it) },
                introLottieUrl = data["intro_lottie_url"] as? String,
                thankyouLottieUrl = data["thankyou_lottie_url"] as? String,
                thankyouParticleEffect = particleEffect,
                blurBackdrop = blurConfig,
                haptic = hapticConfig,
                thankYouText = data["thank_you_text"] as? String,
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseTextStyleConfig(data: Map<String, Any>): TextStyleConfig {
            return TextStyleConfig(
                font_family = data["font_family"] as? String,
                font_size = (data["font_size"] as? Number)?.toDouble(),
                font_weight = (data["font_weight"] as? Number)?.toInt(),
                color = data["color"] as? String,
                alignment = data["alignment"] as? String,
                line_height = (data["line_height"] as? Number)?.toDouble(),
                letter_spacing = (data["letter_spacing"] as? Number)?.toDouble(),
                opacity = (data["opacity"] as? Number)?.toDouble(),
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseElementStyleConfig(data: Map<String, Any>): ElementStyleConfig {
            val bgData = data["background"] as? Map<String, Any>
            val borderData = data["border"] as? Map<String, Any>
            val shadowData = data["shadow"] as? Map<String, Any>
            val paddingData = data["padding"] as? Map<String, Any>
            return ElementStyleConfig(
                background = bgData?.let {
                    val stopsRaw = (it["gradient"] as? Map<String, Any>)
                        ?.let { g -> (g["stops"] as? List<*>)?.mapNotNull { s ->
                            val sm = s as? Map<String, Any> ?: return@mapNotNull null
                            GradientStopConfig(
                                color = sm["color"] as? String ?: "#000000",
                                position = (sm["position"] as? Number)?.toDouble() ?: 0.0,
                            )
                        }}
                    val gradData = it["gradient"] as? Map<String, Any>
                    BackgroundStyleConfig(
                        type = it["type"] as? String,
                        color = it["color"] as? String,
                        gradient = gradData?.let { g ->
                            GradientConfig(
                                type = g["type"] as? String,
                                angle = (g["angle"] as? Number)?.toDouble(),
                                stops = stopsRaw,
                            )
                        },
                        image_url = it["image_url"] as? String,
                        image_fit = it["image_fit"] as? String,
                        overlay = it["overlay"] as? String,
                    )
                },
                border = borderData?.let {
                    BorderStyleConfig(
                        width = (it["width"] as? Number)?.toDouble(),
                        color = it["color"] as? String,
                        style = it["style"] as? String,
                        radius = (it["radius"] as? Number)?.toDouble(),
                        radius_top_left = (it["radius_top_left"] as? Number)?.toDouble(),
                        radius_top_right = (it["radius_top_right"] as? Number)?.toDouble(),
                        radius_bottom_left = (it["radius_bottom_left"] as? Number)?.toDouble(),
                        radius_bottom_right = (it["radius_bottom_right"] as? Number)?.toDouble(),
                    )
                },
                shadow = shadowData?.let {
                    ShadowStyleConfig(
                        x = (it["x"] as? Number)?.toDouble(),
                        y = (it["y"] as? Number)?.toDouble(),
                        blur = (it["blur"] as? Number)?.toDouble(),
                        spread = (it["spread"] as? Number)?.toDouble(),
                        color = it["color"] as? String,
                    )
                },
                padding = paddingData?.let {
                    SpacingConfig(
                        top = (it["top"] as? Number)?.toDouble(),
                        right = (it["right"] as? Number)?.toDouble(),
                        bottom = (it["bottom"] as? Number)?.toDouble(),
                        left = (it["left"] as? Number)?.toDouble(),
                    )
                },
                corner_radius = (data["corner_radius"] as? Number)?.toDouble(),
                opacity = (data["opacity"] as? Number)?.toDouble(),
            )
        }
    }
}

/**
 * Survey theme tokens. Mirrors iOS `Feedback/SurveyConfig.swift` `SurveyTheme`.
 *
 * SPEC-070-A F.3: adds button text color, gradients, typography overrides,
 * intro/thank-you Lottie URLs, particle effect, blur backdrop, haptic config,
 * and configurable thank-you text. Server writes these under
 * `appearance.theme.*` (mirroring iOS) — see
 * [SurveyAppearance.fromMap] for parser entries.
 */
data class SurveyTheme(
    val backgroundColor: String?,
    val textColor: String?,
    val accentColor: String?,
    val buttonColor: String?,
    val fontFamily: String? = null,
    // SPEC-070-A F.3: button text color (iOS parity)
    val buttonTextColor: String? = null,
    // SPEC-070-A F.3: gradients
    val gradient: ai.appdna.sdk.core.GradientConfig? = null,
    val buttonGradient: ai.appdna.sdk.core.GradientConfig? = null,
    // SPEC-070-A F.3: typography
    val textAlign: String? = null,            // "left" | "center" | "right"
    val questionFontSize: Double? = null,
    val fontWeight: String? = null,            // "normal" | "medium" | "semibold" | "bold"
    // SPEC-070-A F.3: rich media (mirrors iOS SurveyTheme)
    val introLottieUrl: String? = null,
    val thankyouLottieUrl: String? = null,
    val thankyouParticleEffect: ai.appdna.sdk.core.ParticleEffect? = null,
    val blurBackdrop: ai.appdna.sdk.core.BlurConfig? = null,
    val haptic: ai.appdna.sdk.core.HapticConfig? = null,
    // SPEC-088 / SPEC-070-A F.3: configurable thank-you text
    val thankYouText: String? = null,
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
                onPositive = pos?.let { FollowUpAction(it["action"] as? String ?: "", it["message"] as? String) },
                onNegative = neg?.let { FollowUpAction(it["action"] as? String ?: "", it["message"] as? String) },
                onNeutral = neut?.let { FollowUpAction(it["action"] as? String ?: "", it["message"] as? String) }
            )
        }
    }
}

data class FollowUpAction(val action: String, val message: String? = null)

data class SurveyAnswer(val questionId: String, val answer: Any) {
    fun toMap(): Map<String, Any> = mapOf("question_id" to questionId, "answer" to answer)
}

enum class SurveySentiment { POSITIVE, NEGATIVE, NEUTRAL }
