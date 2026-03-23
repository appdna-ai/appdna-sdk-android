package ai.appdna.sdk.core

data class AudienceRule(
    val trait: String,
    val operator: String,  // "equals", "not_equals", "gt", "lt", "contains", "exists"
    val value: Any? = null,
) {
    companion object {
        fun fromMap(data: Map<String, Any?>): AudienceRule {
            return AudienceRule(
                trait = data["trait"] as? String ?: "",
                operator = data["operator"] as? String ?: "equals",
                value = data["value"],
            )
        }

        fun fromList(data: List<Map<String, Any?>>?): List<AudienceRule> {
            return data?.map { fromMap(it) } ?: emptyList()
        }
    }
}

data class AudienceRuleSet(
    val priority: Int = 0,
    val conditions: List<AudienceRule> = emptyList(),
    val matchMode: String = "all",  // "all" or "any"
) {
    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(data: Map<String, Any?>?): AudienceRuleSet? {
            if (data == null) return null
            return AudienceRuleSet(
                priority = (data["priority"] as? Number)?.toInt() ?: 0,
                conditions = (data["conditions"] as? List<Map<String, Any?>>)?.map { AudienceRule.fromMap(it) } ?: emptyList(),
                matchMode = data["match_mode"] as? String ?: "all",
            )
        }
    }
}

internal object AudienceRuleEvaluator {

    fun evaluate(ruleSet: AudienceRuleSet?, userTraits: Map<String, Any>): Boolean {
        if (ruleSet == null || ruleSet.conditions.isEmpty()) return true

        return if (ruleSet.matchMode == "all") {
            ruleSet.conditions.all { evaluateRule(it, userTraits) }
        } else {
            ruleSet.conditions.any { evaluateRule(it, userTraits) }
        }
    }

    fun evaluate(rules: List<AudienceRule>?, userTraits: Map<String, Any>): Boolean {
        if (rules.isNullOrEmpty()) return true
        return rules.all { evaluateRule(it, userTraits) }
    }

    private fun evaluateRule(rule: AudienceRule, userTraits: Map<String, Any>): Boolean {
        val traitValue = userTraits[rule.trait]

        return when (rule.operator) {
            "equals", "eq" -> ConditionEvaluator.valuesEqual(traitValue, rule.value)
            "not_equals", "neq" -> !ConditionEvaluator.valuesEqual(traitValue, rule.value)
            "gt" -> ConditionEvaluator.compareNumeric(traitValue, rule.value) > 0
            "gte" -> ConditionEvaluator.compareNumeric(traitValue, rule.value) >= 0
            "lt" -> ConditionEvaluator.compareNumeric(traitValue, rule.value) < 0
            "lte" -> ConditionEvaluator.compareNumeric(traitValue, rule.value) <= 0
            "contains" -> {
                when {
                    traitValue is String && rule.value is String -> traitValue.contains(rule.value, ignoreCase = true)
                    traitValue is List<*> && rule.value is String -> traitValue.contains(rule.value)
                    else -> false
                }
            }
            "exists" -> traitValue != null
            "in" -> {
                val arr = rule.value as? List<*>
                arr != null && arr.contains(traitValue?.toString())
            }
            "not_in" -> {
                val arr = rule.value as? List<*>
                arr == null || !arr.contains(traitValue?.toString())
            }
            else -> true
        }
    }
}
