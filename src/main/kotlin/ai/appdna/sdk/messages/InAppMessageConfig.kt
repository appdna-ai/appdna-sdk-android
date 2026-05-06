package ai.appdna.sdk.messages

/**
 * SPEC-084: In-app message config models matching Firestore schema.
 */

data class MessageRoot(
    val version: Int = 1,
    val messages: Map<String, MessageConfig> = emptyMap(),
)

data class MessageConfig(
    val name: String,
    val message_type: MessageType,
    val content: MessageContent,
    val trigger_rules: TriggerRules,
    val priority: Int = 0,
    val start_date: String? = null,
    val end_date: String? = null,
)

enum class MessageType(val value: String) {
    BANNER("banner"),
    MODAL("modal"),
    FULLSCREEN("fullscreen"),
    TOOLTIP("tooltip");

    companion object {
        fun fromString(value: String): MessageType =
            entries.firstOrNull { it.value == value } ?: MODAL
    }
}

data class MessageContent(
    val title: String? = null,
    val body: String? = null,
    val image_url: String? = null,
    val cta_text: String? = null,
    val cta_action: CTAAction? = null,
    val dismiss_text: String? = null,
    val background_color: String? = null,
    val banner_position: String? = null, // "top" | "bottom"
    val auto_dismiss_seconds: Int? = null,
    // SPEC-084: Styling fields
    val text_color: String? = null,
    val button_color: String? = null,
    // SPEC-070-A F.2: button text color + button corner radius (iOS parity)
    val button_text_color: String? = null,
    val button_corner_radius: Int? = null,
    val corner_radius: Int? = null,
    val secondary_cta_text: String? = null,
    // SPEC-070-A F.2: typography parity (font family + title/body sizes)
    val font_family: String? = null,
    val title_font_size: Double? = null,
    val body_font_size: Double? = null,
    // SPEC-085: Rich media fields
    val lottie_url: String? = null,
    val rive_url: String? = null,
    // SPEC-070-A F.2: rive state machine name (iOS parity)
    val rive_state_machine: String? = null,
    val video_url: String? = null,
    val video_thumbnail_url: String? = null,
    val cta_icon: Any? = null,
    val secondary_cta_icon: Any? = null,
    val haptic: ai.appdna.sdk.core.HapticConfig? = null,
    val particle_effect: ai.appdna.sdk.core.ParticleEffect? = null,
    val blur_backdrop: ai.appdna.sdk.core.BlurConfig? = null,
    // SPEC-205 / SPEC-070-A F.2: sparse dark-mode overrides
    val dark: MessageContentDark? = null,
)

/**
 * SPEC-205 / SPEC-070-A D.4: render-time resolver. In dark mode, any field
 * set on [MessageContent.dark] overrides the matching field above; everything
 * else falls back to the light (default) value. In light mode or when no
 * `dark` overrides exist, returns this unchanged.
 *
 * Mirrors iOS `MessageContent.resolved(for: ColorScheme)` in
 * `InAppMessaging/MessageConfig.swift`.
 */
fun MessageContent.resolved(isDark: Boolean): MessageContent {
    if (!isDark) return this
    val d = this.dark ?: return this
    return this.copy(
        image_url = d.image_url ?: image_url,
        background_color = d.background_color ?: background_color,
        text_color = d.text_color ?: text_color,
        button_color = d.button_color ?: button_color,
        button_text_color = d.button_text_color ?: button_text_color,
        button_corner_radius = d.button_corner_radius ?: button_corner_radius,
        corner_radius = d.corner_radius ?: corner_radius,
        // Typography stays light-only by design — messages don't vary
        // fonts or sizes between modes (iOS parity).
        lottie_url = d.lottie_url ?: lottie_url,
        rive_url = d.rive_url ?: rive_url,
        video_url = d.video_url ?: video_url,
        video_thumbnail_url = d.video_thumbnail_url ?: video_thumbnail_url,
        cta_icon = d.cta_icon ?: cta_icon,
        secondary_cta_icon = d.secondary_cta_icon ?: secondary_cta_icon,
        particle_effect = d.particle_effect ?: particle_effect,
        blur_backdrop = d.blur_backdrop ?: blur_backdrop,
        // Drop the dark block on the resolved copy so downstream consumers
        // can't accidentally re-resolve.
        dark = null,
    )
}

/**
 * SPEC-205 / SPEC-070-A F.2: Dark-mode overrides for [MessageContent].
 * Sparse — only fields set here override the matching light-mode field on the
 * parent. Mirrors iOS `MessageContentDark` in `InAppMessaging/MessageConfig.swift`.
 */
data class MessageContentDark(
    // Colors
    val background_color: String? = null,
    val text_color: String? = null,
    val button_color: String? = null,
    val button_text_color: String? = null,
    // Corner radii
    val button_corner_radius: Int? = null,
    val corner_radius: Int? = null,
    // Themed assets
    val image_url: String? = null,
    val lottie_url: String? = null,
    val rive_url: String? = null,
    val video_url: String? = null,
    val video_thumbnail_url: String? = null,
    val cta_icon: Any? = null,
    val secondary_cta_icon: Any? = null,
    // Rich effects
    val particle_effect: ai.appdna.sdk.core.ParticleEffect? = null,
    val blur_backdrop: ai.appdna.sdk.core.BlurConfig? = null,
)

data class CTAAction(
    val type: String, // "dismiss", "deep_link", "open_url"
    val url: String? = null,
)

data class TriggerRules(
    val event: String,
    val conditions: List<TriggerCondition>? = null,
    val frequency: String = "every_time", // "once", "once_per_session", "every_time", "max_times"
    val max_displays: Int? = null,
    val delay_seconds: Int? = null,
)

data class TriggerCondition(
    val field: String,
    val operator: String, // "eq", "gte", "lte", "gt", "lt", "contains"
    val value: Any? = null,
)

// MARK: - Parsing helpers

internal object MessageConfigParser {

    @Suppress("UNCHECKED_CAST")
    fun parseMessages(data: Map<String, Any>): Map<String, MessageConfig> {
        val parsed = mutableMapOf<String, MessageConfig>()
        for ((key, value) in data) {
            if (value is Map<*, *>) {
                try {
                    val map = value as Map<String, Any>
                    parsed[key] = parseMessage(map)
                } catch (_: Exception) {}
            }
        }
        return parsed
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMessage(map: Map<String, Any>): MessageConfig {
        val contentMap = map["content"] as? Map<String, Any> ?: emptyMap()
        val content = MessageContent(
            title = contentMap["title"] as? String,
            body = contentMap["body"] as? String,
            image_url = contentMap["image_url"] as? String,
            cta_text = contentMap["cta_text"] as? String,
            cta_action = (contentMap["cta_action"] as? Map<String, Any>)?.let {
                CTAAction(
                    type = it["type"] as? String ?: "dismiss",
                    url = it["url"] as? String,
                )
            },
            dismiss_text = contentMap["dismiss_text"] as? String,
            background_color = contentMap["background_color"] as? String,
            banner_position = contentMap["banner_position"] as? String,
            auto_dismiss_seconds = (contentMap["auto_dismiss_seconds"] as? Number)?.toInt(),
            text_color = contentMap["text_color"] as? String,
            button_color = contentMap["button_color"] as? String,
            // SPEC-070-A F.2
            button_text_color = contentMap["button_text_color"] as? String,
            button_corner_radius = (contentMap["button_corner_radius"] as? Number)?.toInt(),
            corner_radius = (contentMap["corner_radius"] as? Number)?.toInt(),
            secondary_cta_text = contentMap["secondary_cta_text"] as? String,
            // SPEC-070-A F.2: typography
            font_family = contentMap["font_family"] as? String,
            title_font_size = (contentMap["title_font_size"] as? Number)?.toDouble(),
            body_font_size = (contentMap["body_font_size"] as? Number)?.toDouble(),
            // SPEC-085: Rich media fields
            lottie_url = contentMap["lottie_url"] as? String,
            rive_url = contentMap["rive_url"] as? String,
            // SPEC-070-A F.2
            rive_state_machine = contentMap["rive_state_machine"] as? String,
            video_url = contentMap["video_url"] as? String,
            video_thumbnail_url = contentMap["video_thumbnail_url"] as? String,
            cta_icon = contentMap["cta_icon"],
            secondary_cta_icon = contentMap["secondary_cta_icon"],
            haptic = (contentMap["haptic"] as? Map<String, Any>)?.let { h ->
                @Suppress("UNCHECKED_CAST")
                val triggersMap = h["triggers"] as? Map<String, Any>
                ai.appdna.sdk.core.HapticConfig(
                    enabled = h["enabled"] as? Boolean ?: false,
                    triggers = triggersMap?.let { t ->
                        ai.appdna.sdk.core.HapticTriggers(
                            on_button_tap = t["on_button_tap"] as? String,
                            on_success = t["on_success"] as? String,
                        )
                    } ?: ai.appdna.sdk.core.HapticTriggers(),
                )
            },
            particle_effect = (contentMap["particle_effect"] as? Map<String, Any>)?.let { p ->
                ai.appdna.sdk.core.ParticleEffect(
                    type = p["type"] as? String ?: "confetti",
                    trigger = p["trigger"] as? String ?: "on_appear",
                    duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                    intensity = p["intensity"] as? String ?: "medium",
                    colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
                )
            },
            blur_backdrop = (contentMap["blur_backdrop"] as? Map<String, Any>)?.let { b ->
                ai.appdna.sdk.core.BlurConfig(
                    radius = (b["radius"] as? Number)?.toFloat() ?: 0f,
                    tint = b["tint"] as? String,
                    saturation = (b["saturation"] as? Number)?.toFloat(),
                )
            },
            // SPEC-205 / SPEC-070-A F.2: dark mode sparse overrides
            dark = (contentMap["dark"] as? Map<String, Any>)?.let { d ->
                MessageContentDark(
                    background_color = d["background_color"] as? String,
                    text_color = d["text_color"] as? String,
                    button_color = d["button_color"] as? String,
                    button_text_color = d["button_text_color"] as? String,
                    button_corner_radius = (d["button_corner_radius"] as? Number)?.toInt(),
                    corner_radius = (d["corner_radius"] as? Number)?.toInt(),
                    image_url = d["image_url"] as? String,
                    lottie_url = d["lottie_url"] as? String,
                    rive_url = d["rive_url"] as? String,
                    video_url = d["video_url"] as? String,
                    video_thumbnail_url = d["video_thumbnail_url"] as? String,
                    cta_icon = d["cta_icon"],
                    secondary_cta_icon = d["secondary_cta_icon"],
                    particle_effect = (d["particle_effect"] as? Map<String, Any>)?.let { p ->
                        ai.appdna.sdk.core.ParticleEffect(
                            type = p["type"] as? String ?: "confetti",
                            trigger = p["trigger"] as? String ?: "on_appear",
                            duration_ms = (p["duration_ms"] as? Number)?.toInt() ?: 2500,
                            intensity = p["intensity"] as? String ?: "medium",
                            colors = (p["colors"] as? List<*>)?.filterIsInstance<String>(),
                        )
                    },
                    blur_backdrop = (d["blur_backdrop"] as? Map<String, Any>)?.let { b ->
                        ai.appdna.sdk.core.BlurConfig(
                            radius = (b["radius"] as? Number)?.toFloat() ?: 0f,
                            tint = b["tint"] as? String,
                            saturation = (b["saturation"] as? Number)?.toFloat(),
                        )
                    },
                )
            },
        )

        val rulesMap = map["trigger_rules"] as? Map<String, Any> ?: emptyMap()
        val conditions = (rulesMap["conditions"] as? List<Map<String, Any>>)?.map {
            TriggerCondition(
                field = it["field"] as? String ?: "",
                operator = it["operator"] as? String ?: "eq",
                value = it["value"],
            )
        }
        val triggerRules = TriggerRules(
            event = rulesMap["event"] as? String ?: "",
            conditions = conditions,
            frequency = rulesMap["frequency"] as? String ?: "every_time",
            max_displays = (rulesMap["max_displays"] as? Number)?.toInt(),
            delay_seconds = (rulesMap["delay_seconds"] as? Number)?.toInt(),
        )

        return MessageConfig(
            name = map["name"] as? String ?: "",
            message_type = MessageType.fromString(map["message_type"] as? String ?: "modal"),
            content = content,
            trigger_rules = triggerRules,
            priority = (map["priority"] as? Number)?.toInt() ?: 0,
            start_date = map["start_date"] as? String,
            end_date = map["end_date"] as? String,
        )
    }
}
