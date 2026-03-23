package ai.appdna.sdk.core

data class UnifiedTriggerRules(
    val events: List<EventTrigger>? = null,
    val sessionCount: SessionTrigger? = null,
    val daysSinceInstall: TimeTrigger? = null,
    val onScreen: String? = null,
    val userTraits: List<TraitCondition>? = null,
    val frequency: FrequencyConfig? = null,
    val priority: Int = 0,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>?): UnifiedTriggerRules? {
            if (data == null) return null
            return UnifiedTriggerRules(
                events = (data["events"] as? List<Map<String, Any?>>)?.map { EventTrigger.fromMap(it) },
                sessionCount = (data["session_count"] as? Map<String, Any?>)?.let { SessionTrigger.fromMap(it) },
                daysSinceInstall = (data["days_since_install"] as? Map<String, Any?>)?.let { TimeTrigger.fromMap(it) },
                onScreen = data["on_screen"] as? String,
                userTraits = (data["user_traits"] as? List<Map<String, Any?>>)?.map { TraitCondition.fromMap(it) },
                frequency = (data["frequency"] as? Map<String, Any?>)?.let { FrequencyConfig.fromMap(it) },
                priority = (data["priority"] as? Number)?.toInt() ?: 0,
            )
        }
    }
}

data class EventTrigger(
    val eventName: String,
    val conditions: List<TriggerConditionData>? = null,
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>): EventTrigger {
            return EventTrigger(
                eventName = data["event_name"] as? String ?: "",
                conditions = (data["conditions"] as? List<Map<String, Any?>>)?.map { TriggerConditionData.fromMap(it) },
            )
        }
    }
}

data class TriggerConditionData(
    val field: String,
    val operator: String,
    val value: Any? = null,
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): TriggerConditionData {
            return TriggerConditionData(
                field = data["field"] as? String ?: "",
                operator = data["operator"] as? String ?: "equals",
                value = data["value"],
            )
        }
    }
}

data class SessionTrigger(
    val min: Int? = null,
    val max: Int? = null,
    val exact: Int? = null,
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): SessionTrigger {
            return SessionTrigger(
                min = (data["min"] as? Number)?.toInt(),
                max = (data["max"] as? Number)?.toInt(),
                exact = (data["exact"] as? Number)?.toInt(),
            )
        }
    }
}

data class TimeTrigger(
    val min: Int? = null,
    val max: Int? = null,
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): TimeTrigger {
            return TimeTrigger(
                min = (data["min"] as? Number)?.toInt(),
                max = (data["max"] as? Number)?.toInt(),
            )
        }
    }
}

data class TraitCondition(
    val trait: String,
    val operator: String,
    val value: Any? = null,
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): TraitCondition {
            return TraitCondition(
                trait = data["trait"] as? String ?: "",
                operator = data["operator"] as? String ?: "equals",
                value = data["value"],
            )
        }
    }
}

data class FrequencyConfig(
    val maxImpressions: Int? = null,
    val cooldownHours: Int? = null,
    val oncePerSession: Boolean? = null,
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): FrequencyConfig {
            return FrequencyConfig(
                maxImpressions = (data["max_impressions"] as? Number)?.toInt(),
                cooldownHours = (data["cooldown_hours"] as? Number)?.toInt(),
                oncePerSession = data["once_per_session"] as? Boolean,
            )
        }
    }
}
