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
    // SPEC-085: Rich media
    val haptic: ai.appdna.sdk.core.HapticConfig? = null,
    val particle_effect: ai.appdna.sdk.core.ParticleEffect? = null,
    val video_background_url: String? = null,
)

data class PaywallLayout(
    val type: String, // "stack", "grid", "carousel"
    val spacing: Float? = null,
    val padding: Float? = null,
)

data class PaywallSection(
    val type: String, // "header", "features", "plans", "cta", "social_proof", "guarantee", "image", "spacer", "testimonial", "lottie", "video", "rive", "countdown", "legal", "divider", "sticky_footer", "card", "carousel", "timeline", "icon_grid", "comparison_table", "promo_input", "toggle", "reviews_carousel"
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

    // SPEC-085: Rich media section fields
    val lottie_url: String? = null,
    val lottie_loop: Boolean? = null,
    val lottie_speed: Float? = null,
    val rive_url: String? = null,
    val rive_state_machine: String? = null,
    val video_url: String? = null,
    val video_thumbnail_url: String? = null,
    val video_autoplay: Boolean? = null,
    val video_loop: Boolean? = null,

    // SPEC-089d: Countdown section
    val variant: String? = null,             // digital | circular | flip | bar
    val duration_seconds: Int? = null,
    val target_datetime: String? = null,
    val show_days: Boolean? = null,
    val show_hours: Boolean? = null,
    val show_minutes: Boolean? = null,
    val show_seconds: Boolean? = null,
    val labels: Map<String, String>? = null,
    val on_expire_action: String? = null,    // hide | show_expired_text | auto_advance
    val expired_text: String? = null,
    val accent_color: String? = null,
    val background_color: String? = null,
    val font_size: Float? = null,
    val alignment: String? = null,

    // SPEC-089d: Legal section
    val color: String? = null,
    val links: List<PaywallLink>? = null,

    // SPEC-089d: Divider section
    val thickness: Float? = null,
    val line_style: String? = null,          // solid | dashed | dotted
    val margin_top: Float? = null,
    val margin_bottom: Float? = null,
    val margin_horizontal: Float? = null,
    val label_text: String? = null,
    val label_color: String? = null,
    val label_bg_color: String? = null,
    val label_font_size: Float? = null,

    // SPEC-089d: Sticky footer section
    val cta_text: String? = null,
    val cta_bg_color: String? = null,
    val cta_text_color: String? = null,
    val cta_corner_radius: Float? = null,
    val secondary_text: String? = null,
    val secondary_action: String? = null,    // restore | link
    val secondary_url: String? = null,
    val legal_text: String? = null,
    val blur_background: Boolean? = null,
    val padding: Float? = null,

    // SPEC-089d: Carousel section
    val pages: List<PaywallCarouselPage>? = null,
    val auto_scroll: Boolean? = null,
    val auto_scroll_interval_ms: Int? = null,
    val show_indicators: Boolean? = null,
    val indicator_color: String? = null,
    val indicator_active_color: String? = null,

    // SPEC-089d: Timeline / Icon grid items
    val items: List<PaywallGenericItem>? = null,
    val line_color: String? = null,
    val completed_color: String? = null,
    val current_color: String? = null,
    val upcoming_color: String? = null,
    val show_line: Boolean? = null,
    val compact: Boolean? = null,

    // SPEC-089d: Icon grid / comparison table
    val columns: Int? = null,
    val icon_size: Float? = null,
    val icon_color: String? = null,
    val spacing: Float? = null,

    // SPEC-089d: Comparison table section
    val table_columns: List<PaywallTableColumn>? = null,
    val rows: List<PaywallTableRow>? = null,
    val check_color: String? = null,
    val cross_color: String? = null,
    val highlight_color: String? = null,
    val border_color: String? = null,

    // SPEC-089d: Promo input section
    val placeholder: String? = null,
    val button_text: String? = null,
    val success_text: String? = null,
    val error_text: String? = null,

    // SPEC-089d: Toggle section
    val label: String? = null,
    val description: String? = null,
    val default_value: Boolean? = null,
    val on_color: String? = null,
    val off_color: String? = null,
    val label_color_val: String? = null,
    val description_color: String? = null,
    val icon: String? = null,
    val affects_price: Boolean? = null,

    // SPEC-089d: Reviews carousel section
    val reviews: List<PaywallReview>? = null,
    val show_rating_stars: Boolean? = null,
    val star_color: String? = null,
)

// SPEC-089d: Sub-types for new paywall sections

data class PaywallLink(
    val label: String,
    val url: String,
)

data class PaywallCarouselPage(
    val id: String,
    val children: List<PaywallSection>? = null,
)

data class PaywallGenericItem(
    val id: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val icon: String? = null,
    val status: String? = null,       // completed | current | upcoming
    val label: String? = null,
    val description: String? = null,
)

data class PaywallTableColumn(
    val label: String,
    val highlighted: Boolean? = null,
)

data class PaywallTableRow(
    val feature: String,
    val values: List<String>,
)

data class PaywallReview(
    val text: String,
    val author: String,
    val rating: Double? = null,
    val avatar_url: String? = null,
    val date: String? = null,
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

        // SPEC-085: Parse haptic config
        val hapticMap = map["haptic"] as? Map<String, Any>
        val haptic = hapticMap?.let { h ->
            val triggersMap = h["triggers"] as? Map<String, Any>
            ai.appdna.sdk.core.HapticConfig(
                enabled = h["enabled"] as? Boolean ?: false,
                triggers = triggersMap?.let { t ->
                    ai.appdna.sdk.core.HapticTriggers(
                        on_button_tap = t["on_button_tap"] as? String,
                        on_plan_select = t["on_plan_select"] as? String,
                        on_success = t["on_success"] as? String,
                    )
                } ?: ai.appdna.sdk.core.HapticTriggers(),
            )
        }

        // SPEC-085: Parse particle effect
        val particleMap = map["particle_effect"] as? Map<String, Any>
        val particleEffect = particleMap?.let { p ->
            ai.appdna.sdk.core.ParticleEffect(
                type = p["type"] as? String ?: "confetti",
                trigger = p["trigger"] as? String ?: "on_purchase",
                duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                intensity = p["intensity"] as? String ?: "medium",
                colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
            )
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
            haptic = haptic,
            particle_effect = particleEffect,
            video_background_url = map["video_background_url"] as? String,
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
                // SPEC-085: Rich media fields
                lottie_url = d["lottie_url"] as? String,
                lottie_loop = d["lottie_loop"] as? Boolean,
                lottie_speed = (d["lottie_speed"] as? Number)?.toFloat(),
                rive_url = d["rive_url"] as? String,
                rive_state_machine = d["rive_state_machine"] as? String,
                video_url = d["video_url"] as? String,
                video_thumbnail_url = d["video_thumbnail_url"] as? String,
                video_autoplay = d["video_autoplay"] as? Boolean,
                video_loop = d["video_loop"] as? Boolean,
                // SPEC-089d: Countdown section
                variant = d["variant"] as? String,
                duration_seconds = (d["duration_seconds"] as? Number)?.toInt(),
                target_datetime = d["target_datetime"] as? String,
                show_days = d["show_days"] as? Boolean,
                show_hours = d["show_hours"] as? Boolean,
                show_minutes = d["show_minutes"] as? Boolean,
                show_seconds = d["show_seconds"] as? Boolean,
                labels = (d["labels"] as? Map<*, *>)?.entries?.associate { (k, v) -> (k as? String ?: "") to (v as? String ?: "") },
                on_expire_action = d["on_expire_action"] as? String,
                expired_text = d["expired_text"] as? String,
                accent_color = d["accent_color"] as? String,
                background_color = d["background_color"] as? String,
                font_size = (d["font_size"] as? Number)?.toFloat(),
                alignment = d["alignment"] as? String,
                // SPEC-089d: Legal section
                color = d["color"] as? String,
                links = (d["links"] as? List<*>)?.mapNotNull { linkData ->
                    (linkData as? Map<*, *>)?.let { lm ->
                        PaywallLink(
                            label = lm["label"] as? String ?: "",
                            url = lm["url"] as? String ?: "",
                        )
                    }
                },
                // SPEC-089d: Divider section
                thickness = (d["thickness"] as? Number)?.toFloat(),
                line_style = d["style"] as? String,
                margin_top = (d["margin_top"] as? Number)?.toFloat(),
                margin_bottom = (d["margin_bottom"] as? Number)?.toFloat(),
                margin_horizontal = (d["margin_horizontal"] as? Number)?.toFloat(),
                label_text = d["label_text"] as? String,
                label_color = d["label_color"] as? String,
                label_bg_color = d["label_bg_color"] as? String,
                label_font_size = (d["label_font_size"] as? Number)?.toFloat(),
                // SPEC-089d: Sticky footer section
                cta_text = d["cta_text"] as? String,
                cta_bg_color = d["cta_bg_color"] as? String,
                cta_text_color = d["cta_text_color"] as? String,
                cta_corner_radius = (d["cta_corner_radius"] as? Number)?.toFloat(),
                secondary_text = d["secondary_text"] as? String,
                secondary_action = d["secondary_action"] as? String,
                secondary_url = d["secondary_url"] as? String,
                legal_text = d["legal_text"] as? String,
                blur_background = d["blur_background"] as? Boolean,
                padding = (d["padding"] as? Number)?.toFloat(),
                // SPEC-089d: Carousel section
                pages = (d["pages"] as? List<*>)?.mapNotNull { pageData ->
                    (pageData as? Map<*, *>)?.let { pm ->
                        @Suppress("UNCHECKED_CAST")
                        val childSections = (pm["children"] as? List<Map<String, Any>>)?.map { parseSectionFromMap(it) }
                        PaywallCarouselPage(
                            id = pm["id"] as? String ?: "",
                            children = childSections,
                        )
                    }
                },
                auto_scroll = d["auto_scroll"] as? Boolean,
                auto_scroll_interval_ms = (d["auto_scroll_interval_ms"] as? Number)?.toInt(),
                show_indicators = d["show_indicators"] as? Boolean,
                indicator_color = d["indicator_color"] as? String,
                indicator_active_color = d["indicator_active_color"] as? String,
                // SPEC-089d: Timeline / Icon grid items
                items = (d["items"] as? List<*>)?.mapNotNull { itemData ->
                    (itemData as? Map<*, *>)?.let { im ->
                        PaywallGenericItem(
                            id = im["id"] as? String,
                            title = im["title"] as? String,
                            subtitle = im["subtitle"] as? String,
                            icon = im["icon"] as? String,
                            status = im["status"] as? String,
                            label = im["label"] as? String,
                            description = im["description"] as? String,
                        )
                    }
                },
                line_color = d["line_color"] as? String,
                completed_color = d["completed_color"] as? String,
                current_color = d["current_color"] as? String,
                upcoming_color = d["upcoming_color"] as? String,
                show_line = d["show_line"] as? Boolean,
                compact = d["compact"] as? Boolean,
                // SPEC-089d: Icon grid / comparison table
                columns = (d["columns"] as? Number)?.toInt(),
                icon_size = (d["icon_size"] as? Number)?.toFloat(),
                icon_color = d["icon_color"] as? String,
                spacing = (d["spacing"] as? Number)?.toFloat(),
                // SPEC-089d: Comparison table section
                table_columns = ((d["table_columns"] ?: d["columns"]) as? List<*>)?.mapNotNull { colData ->
                    (colData as? Map<*, *>)?.let { cm ->
                        PaywallTableColumn(
                            label = cm["label"] as? String ?: "",
                            highlighted = cm["highlighted"] as? Boolean,
                        )
                    }
                },
                rows = (d["rows"] as? List<*>)?.mapNotNull { rowData ->
                    (rowData as? Map<*, *>)?.let { rm ->
                        PaywallTableRow(
                            feature = rm["feature"] as? String ?: "",
                            values = (rm["values"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        )
                    }
                },
                check_color = d["check_color"] as? String,
                cross_color = d["cross_color"] as? String,
                highlight_color = d["highlight_color"] as? String,
                border_color = d["border_color"] as? String,
                // SPEC-089d: Promo input section
                placeholder = d["placeholder"] as? String,
                button_text = d["button_text"] as? String,
                success_text = d["success_text"] as? String,
                error_text = d["error_text"] as? String,
                // SPEC-089d: Toggle section
                label = d["label"] as? String,
                description = d["description"] as? String,
                default_value = d["default_value"] as? Boolean,
                on_color = d["on_color"] as? String,
                off_color = d["off_color"] as? String,
                label_color_val = d["label_color"] as? String,
                description_color = d["description_color"] as? String,
                icon = d["icon"] as? String,
                affects_price = d["affects_price"] as? Boolean,
                // SPEC-089d: Reviews carousel section
                reviews = (d["reviews"] as? List<*>)?.mapNotNull { reviewData ->
                    (reviewData as? Map<*, *>)?.let { rm ->
                        PaywallReview(
                            text = rm["text"] as? String ?: "",
                            author = rm["author"] as? String ?: "",
                            rating = (rm["rating"] as? Number)?.toDouble(),
                            avatar_url = rm["avatar_url"] as? String,
                            date = rm["date"] as? String,
                        )
                    }
                },
                show_rating_stars = d["show_rating_stars"] as? Boolean,
                star_color = d["star_color"] as? String,
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
