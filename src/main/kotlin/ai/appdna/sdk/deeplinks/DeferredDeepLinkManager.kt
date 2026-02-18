package ai.appdna.sdk.deeplinks

import android.content.Context
import ai.appdna.sdk.Log
import ai.appdna.sdk.events.EventTracker
import com.google.firebase.firestore.FirebaseFirestore

/**
 * A resolved deferred deep link.
 */
data class DeferredDeepLink(
    val screen: String,
    val params: Map<String, String>,
    val visitorId: String
) {
    fun toMap(): Map<String, Any> = mapOf(
        "screen" to screen,
        "params" to params,
        "visitorId" to visitorId
    )
}

/**
 * Checks for and resolves deferred deep links on first app launch.
 * Path: /orgs/{orgId}/apps/{appId}/config/deferred_deep_links/{visitorId}
 */
internal class DeferredDeepLinkManager(
    private val context: Context,
    private val orgId: String,
    private val appId: String,
    private val eventTracker: EventTracker?
) {
    companion object {
        private const val FIRST_LAUNCH_KEY = "ai.appdna.sdk.first_launch_completed"
        private const val EXPIRY_HOURS = 72L
    }

    fun checkDeferredDeepLink(callback: (DeferredDeepLink?) -> Unit) {
        if (!isFirstLaunch()) {
            callback(null)
            return
        }

        val visitorId = resolveVisitorId()
        if (visitorId == null) {
            Log.debug("DeferredDeepLink: no visitor ID resolved")
            markLaunched()
            callback(null)
            return
        }

        val path = "orgs/$orgId/apps/$appId/config/deferred_deep_links/$visitorId"
        Log.debug("DeferredDeepLink: checking $path")

        FirebaseFirestore.getInstance().document(path).get()
            .addOnSuccessListener { snapshot ->
                markLaunched()

                val data = snapshot.data
                if (data == null) {
                    callback(null)
                    return@addOnSuccessListener
                }

                // Check expiry
                val createdAt = (data["created_at"] as? Number)?.toDouble()
                if (createdAt != null) {
                    val age = (System.currentTimeMillis() / 1000.0) - createdAt
                    if (age > EXPIRY_HOURS * 3600) {
                        Log.debug("DeferredDeepLink: expired (age: ${age / 3600}h)")
                        snapshot.reference.delete()
                        callback(null)
                        return@addOnSuccessListener
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val deepLink = DeferredDeepLink(
                    screen = data["screen"] as? String ?: "",
                    params = (data["params"] as? Map<String, String>) ?: emptyMap(),
                    visitorId = visitorId
                )

                // Delete after resolving (one-time use)
                snapshot.reference.delete()

                // Track event
                eventTracker?.track("deferred_deep_link_resolved", mapOf(
                    "path" to deepLink.screen,
                    "visitor_id" to visitorId
                ))

                callback(deepLink)
            }
            .addOnFailureListener { e ->
                markLaunched()
                Log.error("DeferredDeepLink fetch failed: ${e.message}")
                callback(null)
            }
    }

    private fun resolveVisitorId(): String? {
        // Strategy 1: Check clipboard for visitor ID (web page sets it before store redirect)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clipData = clipboard?.primaryClip
        if (clipData != null && clipData.itemCount > 0) {
            val text = clipData.getItemAt(0).text?.toString()
            if (text != null && text.startsWith("appdna:visitor:")) {
                val visitorId = text.removePrefix("appdna:visitor:")
                // Clear clipboard
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
                Log.debug("DeferredDeepLink: resolved visitor ID from clipboard")
                return visitorId
            }
        }

        // Strategy 2: Android Install Referrer (if available)
        // Note: In production, integrate com.android.installreferrer library
        return null
    }

    private fun isFirstLaunch(): Boolean {
        val prefs = context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
        return !prefs.getBoolean(FIRST_LAUNCH_KEY, false)
    }

    private fun markLaunched() {
        val prefs = context.getSharedPreferences("ai.appdna.sdk", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(FIRST_LAUNCH_KEY, true).apply()
    }
}
