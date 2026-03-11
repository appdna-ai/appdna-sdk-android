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
    val corner_radius: Int? = null,
    val secondary_cta_text: String? = null,
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
            corner_radius = (contentMap["corner_radius"] as? Number)?.toInt(),
            secondary_cta_text = contentMap["secondary_cta_text"] as? String,
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
