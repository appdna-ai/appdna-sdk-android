package ai.appdna.sdk.core

internal object ConditionEvaluator {

    fun valuesEqual(lhs: Any?, rhs: Any?): Boolean {
        if (lhs == null && rhs == null) return true
        if (lhs == null || rhs == null) return false

        if (lhs is String && rhs is String) return lhs == rhs
        if (lhs is Boolean && rhs is Boolean) return lhs == rhs

        val ln = toDouble(lhs)
        val rn = toDouble(rhs)
        if (ln != null && rn != null) return ln == rn

        return lhs.toString() == rhs.toString()
    }

    fun compareNumeric(lhs: Any?, rhs: Any?): Int {
        val ln = toDouble(lhs) ?: return 0
        val rn = toDouble(rhs) ?: return 0
        return ln.compareTo(rn)
    }

    fun evaluateCondition(
        type: String,
        variable: String?,
        value: Any?,
        context: Map<String, Any>,
    ): Boolean {
        val resolvedValue = resolveVariable(variable, context)

        return when (type) {
            "always" -> true
            "when_equals" -> valuesEqual(resolvedValue, value)
            "when_not_equals" -> !valuesEqual(resolvedValue, value)
            "when_gt" -> compareNumeric(resolvedValue, value) > 0
            "when_lt" -> compareNumeric(resolvedValue, value) < 0
            "when_not_empty" -> resolvedValue != null && resolvedValue.toString().isNotEmpty()
            "when_empty" -> resolvedValue == null || resolvedValue.toString().isEmpty()
            else -> true
        }
    }

    fun resolveVariable(path: String?, context: Map<String, Any>): Any? {
        if (path.isNullOrEmpty()) return null
        val parts = path.split(".")

        var current: Any? = context
        for (part in parts) {
            current = when (current) {
                is Map<*, *> -> current[part]
                else -> return null
            }
        }
        return current
    }

    private fun toDouble(value: Any?): Double? {
        return when (value) {
            is Double -> value
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Float -> value.toDouble()
            is String -> value.toDoubleOrNull()
            is Number -> value.toDouble()
            else -> null
        }
    }
}
