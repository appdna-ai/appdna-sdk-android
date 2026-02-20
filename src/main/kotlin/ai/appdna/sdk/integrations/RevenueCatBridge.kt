package ai.appdna.sdk.integrations

import ai.appdna.sdk.Log

/**
 * RevenueCat billing bridge for Android.
 * Wraps RevenueCat SDK calls when available.
 */
internal class RevenueCatBridge {

    fun configure(apiKey: String) {
        try {
            // RevenueCat is a compileOnly dependency — use reflection to check availability
            Class.forName("com.revenuecat.purchases.Purchases")
            Log.info("RevenueCat bridge initialized")
        } catch (_: ClassNotFoundException) {
            Log.warning("RevenueCat SDK not available — billing operations will be no-ops")
        }
    }
}
