package ai.appdna.sdk.paywalls

/**
 * Codable structs matching SPEC-002 Firestore PaywallConfig schema (Android).
 */
data class PaywallConfig(
    val id: String,
    val name: String,
    val layout: PaywallLayout,
    val sections: List<PaywallSection>,
    val dismiss: PaywallDismiss? = null,
    val background: PaywallBackground? = null,
    // SPEC-084: Design tokens
    val animation: ai.appdna.sdk.core.AnimationConfig? = null,
    val localizations: Map<String, Map<String, String>>? = null,
    val default_locale: String? = null,
)

data class PaywallLayout(
    val type: String, // "stack", "grid", "carousel"
    val spacing: Float? = null,
    val padding: Float? = null,
)

data class PaywallSection(
    val type: String, // "header", "features", "plans", "cta", "social_proof", "guarantee", "image", "spacer", "testimonial"
    val data: PaywallSectionData? = null,
    // SPEC-084: Per-section styling
    val style: ai.appdna.sdk.core.SectionStyleConfig? = null,
)

data class PaywallSectionData(
    // Header
    val title: String? = null,
    val subtitle: String? = null,
    val image_url: String? = null,

    // Features
    val features: List<String>? = null,

    // Plans
    val plans: List<PaywallPlan>? = null,

    // CTA
    val cta: PaywallCTA? = null,

    // Social proof
    val rating: Double? = null,
    val review_count: Int? = null,
    val testimonial: String? = null,
    val sub_type: String? = null,       // "app_rating", "countdown", "trial_badge"
    val countdown_seconds: Int? = null,
    val text: String? = null,

    // Guarantee
    val guarantee_text: String? = null,

    // Image section
    val height: Float? = null,
    val corner_radius: Float? = null,

    // Spacer section
    val spacer_height: Float? = null,

    // Testimonial section
    val quote: String? = null,
    val author_name: String? = null,
    val author_role: String? = null,
    val avatar_url: String? = null,
)

data class PaywallPlan(
    val id: String,
    val product_id: String,
    val name: String,
    val price: String,
    val period: String? = null,
    val badge: String? = null,
    val trial_duration: String? = null,
    val is_default: Boolean? = null
)

data class PaywallCTA(
    val text: String,
    val style: String? = null // "primary", "gradient"
)

data class PaywallDismiss(
    val type: String, // "x_button", "swipe", "text_link"
    val delay_seconds: Int? = null,
    val text: String? = null
)

data class PaywallBackground(
    val type: String, // "color", "gradient", "image"
    val value: String? = null, // hex color, gradient def, or image URL
    val colors: List<String>? = null
)

// MARK: - Public types

/**
 * Context passed when presenting a paywall.
 */
data class PaywallContext(
    val placement: String,
    val experiment: String? = null,
    val variant: String? = null
)

/**
 * Reason a paywall was dismissed.
 */
enum class DismissReason(val value: String) {
    PURCHASED("purchased"),
    DISMISSED("dismissed"),
    TAPPED_OUTSIDE("tappedOutside"),
    PROGRAMMATIC("programmatic")
}

/**
 * Action taken by the user on a paywall.
 */
enum class PaywallAction(val value: String) {
    CTA_TAPPED("cta_tapped"),
    FEATURE_SELECTED("feature_selected"),
    PLAN_CHANGED("plan_changed"),
    LINK_TAPPED("link_tapped"),
    CUSTOM("custom")
}

/**
 * Delegate for paywall lifecycle events.
 */
interface AppDNAPaywallDelegate {
    fun onPaywallPresented(paywallId: String) {}
    fun onPaywallAction(paywallId: String, action: PaywallAction) {}
    fun onPaywallPurchaseStarted(paywallId: String, productId: String) {}
    fun onPaywallPurchaseCompleted(paywallId: String, productId: String, transaction: ai.appdna.sdk.TransactionInfo) {}
    fun onPaywallPurchaseFailed(paywallId: String, error: Exception) {}
    fun onPaywallDismissed(paywallId: String) {}
}

// MARK: - Parsing helpers

internal object PaywallConfigParser {

    @Suppress("UNCHECKED_CAST")
    fun parsePaywalls(data: Map<String, Any>): Map<String, PaywallConfig> {
        val parsed = mutableMapOf<String, PaywallConfig>()
        for ((key, value) in data) {
            if (value is Map<*, *>) {
                try {
                    val map = value as Map<String, Any>
                    parsed[key] = parsePaywallConfig(key, map)
                } catch (_: Exception) {}
            }
        }
        return parsed
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePaywallConfig(id: String, map: Map<String, Any>): PaywallConfig {
        val layoutMap = map["layout"] as? Map<String, Any> ?: emptyMap()
        val layout = PaywallLayout(
            type = layoutMap["type"] as? String ?: "stack",
            spacing = (layoutMap["spacing"] as? Number)?.toFloat(),
            padding = (layoutMap["padding"] as? Number)?.toFloat()
        )

        val sectionsList = map["sections"] as? List<Map<String, Any>> ?: emptyList()
        val sections = sectionsList.map { parseSectionFromMap(it) }

        val dismissMap = map["dismiss"] as? Map<String, Any>
        val dismiss = dismissMap?.let {
            PaywallDismiss(
                type = it["type"] as? String ?: "x_button",
                delay_seconds = (it["delay_seconds"] as? Number)?.toInt(),
                text = it["text"] as? String
            )
        }

        val bgMap = map["background"] as? Map<String, Any>
        val background = bgMap?.let {
            PaywallBackground(
                type = it["type"] as? String ?: "color",
                value = it["value"] as? String,
                colors = (it["colors"] as? List<*>)?.filterIsInstance<String>()
            )
        }

        // SPEC-084: Parse animation config
        val animMap = map["animation"] as? Map<String, Any>
        val animation = animMap?.let {
            ai.appdna.sdk.core.AnimationConfig(
                entry_animation = it["entry_animation"] as? String,
                entry_duration_ms = (it["entry_duration_ms"] as? Number)?.toInt(),
                section_stagger = it["section_stagger"] as? String,
                section_stagger_delay_ms = (it["section_stagger_delay_ms"] as? Number)?.toInt(),
                cta_animation = it["cta_animation"] as? String,
                plan_selection_animation = it["plan_selection_animation"] as? String,
                dismiss_animation = it["dismiss_animation"] as? String,
            )
        }

        // SPEC-084: Parse localizations
        val locMap = map["localizations"] as? Map<String, Any>
        val localizations = locMap?.mapValues { (_, v) ->
            (v as? Map<*, *>)?.entries?.associate { (k, val2) ->
                (k as? String ?: "") to (val2 as? String ?: "")
            } ?: emptyMap()
        }

        return PaywallConfig(
            id = map["id"] as? String ?: id,
            name = map["name"] as? String ?: "",
            layout = layout,
            sections = sections,
            dismiss = dismiss,
            background = background,
            animation = animation,
            localizations = localizations,
            default_locale = map["default_locale"] as? String,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseSectionFromMap(map: Map<String, Any>): PaywallSection {
        val type = map["type"] as? String ?: ""
        val dataMap = map["data"] as? Map<String, Any>

        val data = dataMap?.let { d ->
            PaywallSectionData(
                title = d["title"] as? String,
                subtitle = d["subtitle"] as? String,
                image_url = d["image_url"] as? String,
                features = (d["features"] as? List<*>)?.filterIsInstance<String>(),
                plans = (d["plans"] as? List<*>)?.mapNotNull { planData ->
                    if (planData is Map<*, *>) {
                        parsePlanFromMap(planData as Map<String, Any>)
                    } else null
                },
                cta = (d["cta"] as? Map<String, Any>)?.let { ctaMap ->
                    PaywallCTA(
                        text = ctaMap["text"] as? String ?: "",
                        style = ctaMap["style"] as? String
                    )
                },
                rating = (d["rating"] as? Number)?.toDouble(),
                review_count = (d["review_count"] as? Number)?.toInt(),
                testimonial = d["testimonial"] as? String,
                sub_type = d["sub_type"] as? String,
                countdown_seconds = (d["countdown_seconds"] as? Number)?.toInt(),
                text = d["text"] as? String,
                guarantee_text = d["guarantee_text"] as? String,
                height = (d["height"] as? Number)?.toFloat(),
                corner_radius = (d["corner_radius"] as? Number)?.toFloat(),
                spacer_height = (d["spacer_height"] as? Number)?.toFloat(),
                quote = d["quote"] as? String,
                author_name = d["author_name"] as? String,
                author_role = d["author_role"] as? String,
                avatar_url = d["avatar_url"] as? String,
            )
        }

        // SPEC-084: Parse per-section style
        val styleMap = map["style"] as? Map<String, Any>
        val style = styleMap?.let { parseStyle(it) }

        return PaywallSection(type = type, data = data, style = style)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseStyle(map: Map<String, Any>): ai.appdna.sdk.core.SectionStyleConfig {
        val containerMap = map["container"] as? Map<String, Any>
        val container = containerMap?.let { parseElementStyle(it) }
        val elementsMap = map["elements"] as? Map<String, Any>
        val elements = elementsMap?.mapValues { (_, v) ->
            parseElementStyle(v as? Map<String, Any> ?: emptyMap())
        }
        return ai.appdna.sdk.core.SectionStyleConfig(container = container, elements = elements)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseElementStyle(map: Map<String, Any>): ai.appdna.sdk.core.ElementStyleConfig {
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
        val textStyle = tsMap?.let {
            ai.appdna.sdk.core.TextStyleConfig(
                font_family = it["font_family"] as? String,
                font_size = (it["font_size"] as? Number)?.toDouble(),
                font_weight = (it["font_weight"] as? Number)?.toInt(),
                color = it["color"] as? String,
                alignment = it["alignment"] as? String,
                line_height = (it["line_height"] as? Number)?.toDouble(),
                letter_spacing = (it["letter_spacing"] as? Number)?.toDouble(),
                opacity = (it["opacity"] as? Number)?.toDouble(),
            )
        }
        return ai.appdna.sdk.core.ElementStyleConfig(
            background = bg, border = border, shadow = shadow, padding = padding,
            corner_radius = (map["corner_radius"] as? Number)?.toDouble(),
            opacity = (map["opacity"] as? Number)?.toDouble(),
            text_style = textStyle,
        )
    }

    private fun parsePlanFromMap(map: Map<String, Any>): PaywallPlan {
        return PaywallPlan(
            id = map["id"] as? String ?: "",
            product_id = map["product_id"] as? String ?: "",
            name = map["name"] as? String ?: "",
            price = map["price"] as? String ?: "",
            period = map["period"] as? String,
            badge = map["badge"] as? String,
            trial_duration = map["trial_duration"] as? String,
            is_default = map["is_default"] as? Boolean
        )
    }
}
