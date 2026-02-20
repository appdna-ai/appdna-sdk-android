package ai.appdna.sdk.config

/**
 * Feature flag evaluation — thin wrapper over RemoteConfigManager.
 */
internal class FeatureFlagManager(private val remoteConfigManager: RemoteConfigManager) {

    /**
     * Check if a feature flag is enabled.
     * Returns true if the config value is truthy (boolean true, or numeric 1).
     */
    fun isEnabled(flag: String): Boolean {
        return when (val value = remoteConfigManager.getConfig(flag)) {
            is Boolean -> value
            is Number -> value.toInt() == 1
            is String -> value.lowercase() == "true" || value == "1"
            else -> false
        }
    }

    /**
     * Get the raw value of a feature flag.
     */
    fun getValue(flag: String): Any? = remoteConfigManager.getConfig(flag)

    /**
     * Register a listener for feature flag changes (stub).
     */
    fun addChangeListener(callback: (Map<String, Boolean>) -> Unit) {
        // Stub — change listeners are managed at the RemoteConfigManager level
    }
}
