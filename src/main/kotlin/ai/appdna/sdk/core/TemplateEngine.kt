package ai.appdna.sdk.core

import ai.appdna.sdk.AppDNA

/**
 * Context for variable resolution across all SDK modules (SPEC-088).
 */
data class TemplateContext(
    val userTraits: Map<String, Any>?,
    val remoteConfig: (String) -> String?,
    val onboardingResponses: Map<String, Map<String, Any>>,
    val computedData: Map<String, Any>,
    val sessionData: Map<String, Any>,
    val deviceInfo: Map<String, String>
)

/**
 * Shared template interpolation engine for all SDK modules (SPEC-088).
 * Resolves `{{namespace.key}}` and `{{namespace.key | fallback}}` variables.
 */
object TemplateEngine {

    private val regex = Regex("\\{\\{([^}|]+)(?:\\|([^}]*))?\\}\\}")

    /**
     * Build a TemplateContext from current SDK state.
     */
    fun buildContext(): TemplateContext {
        val identity = AppDNA.getIdentityRef()
        val sessionStore = SessionDataStore.instance

        return TemplateContext(
            userTraits = identity?.traits,
            remoteConfig = { key -> AppDNA.getRemoteConfigFlag(key) },
            onboardingResponses = sessionStore?.onboardingResponses ?: emptyMap(),
            computedData = sessionStore?.computedData ?: emptyMap(),
            sessionData = sessionStore?.sessionData ?: emptyMap(),
            deviceInfo = deviceInfo()
        )
    }

    /**
     * Interpolate all `{{...}}` variables in a string.
     */
    fun interpolate(value: String, context: TemplateContext): String {
        if (!value.contains("{{")) return value // Fast path

        return regex.replace(value) { match ->
            val varPath = match.groupValues[1].trim()
            val fallback = match.groupValues.getOrNull(2)?.trim() ?: ""
            resolveVariable(varPath, context) ?: fallback
        }
    }

    /**
     * Interpolate multiple string fields at once.
     */
    fun interpolateFields(fields: List<String?>, context: TemplateContext): List<String?> {
        return fields.map { field ->
            field?.let { interpolate(it, context) }
        }
    }

    // MARK: - Variable Resolution

    private fun resolveVariable(path: String, context: TemplateContext): String? {
        val parts = path.split(".", limit = 3)
        val namespace = parts.firstOrNull() ?: return null

        return when (namespace) {
            "user" -> {
                if (parts.size < 2) return null
                context.userTraits?.get(parts[1])?.let(::stringify)
            }

            "remote_config" -> {
                if (parts.size < 2) return null
                context.remoteConfig(parts[1])
            }

            "onboarding", "responses" -> {
                // onboarding.stepId.fieldId
                if (parts.size < 3) {
                    // Try two-part path with dot in remaining
                    if (parts.size < 2) return null
                    val remaining = path.removePrefix("onboarding.")
                    val subParts = remaining.split(".", limit = 2)
                    if (subParts.size < 2) return null
                    context.onboardingResponses[subParts[0]]?.get(subParts[1])?.let(::stringify)
                } else {
                    context.onboardingResponses[parts[1]]?.get(parts[2])?.let(::stringify)
                }
            }

            "computed" -> {
                if (parts.size < 2) return null
                context.computedData[parts[1]]?.let(::stringify)
            }

            "session" -> {
                if (parts.size < 2) return null
                context.sessionData[parts[1]]?.let(::stringify)
            }

            "device" -> {
                if (parts.size < 2) return null
                context.deviceInfo[parts[1]]
            }

            else -> {
                // Legacy: bare variable name → remote config (backward compat)
                context.remoteConfig(path)
            }
        }
    }

    // MARK: - Helpers

    private fun stringify(value: Any): String {
        return when (value) {
            is String -> value
            is Boolean -> value.toString()
            is Number -> value.toString()
            else -> value.toString()
        }
    }

    private fun deviceInfo(): Map<String, String> {
        val info = mutableMapOf(
            "platform" to "android",
            "os_version" to android.os.Build.VERSION.RELEASE,
            "locale" to java.util.Locale.getDefault().language,
        )
        java.util.Locale.getDefault().country.takeIf { it.isNotEmpty() }?.let {
            info["country"] = it
        }
        return info
    }
}

/**
 * SPEC-087: Convenience extension to interpolate {{variables}} in onboarding text fields.
 */
fun String.interpolated(): String {
    if (!this.contains("{{")) return this
    val ctx = TemplateEngine.buildContext()
    return TemplateEngine.interpolate(this, ctx)
}
