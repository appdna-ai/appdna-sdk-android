package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import ai.appdna.sdk.PushAction
import ai.appdna.sdk.PushPayload
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Firebase Cloud Messaging service for handling push notifications.
 * Apps should extend this or register it in AndroidManifest.xml.
 *
 * SPEC-070-A H.5–H.22 — full push hardening:
 *   H.6: dismissal broadcast wired via setDeleteIntent → push_dismissed event
 *   H.8: AppDNAPushDelegate.onPushReceived fires before notify()
 *   H.10: payload schema parses canonical iOS-shaped action/actions plus legacy flat keys
 *   H.16: foreground state inferred from ActivityManager (works in :fcm process)
 *   H.19: server-supplied channel name/description/group honored
 *   H.21: notificationIcon resolved via Configuration → manifest meta-data → app icon
 *   H.21b: server-supplied channel importance honored
 *   H.22: hybrid notification+data payloads skip notify() (FCM auto-displays)
 */
open class AppDNAMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val pushId = message.data["push_id"] ?: ""
        Log.info("Push received: $pushId")

        // Track delivery
        AppDNA.trackPushDelivered(pushId)

        // SPEC-070-A H.7: persist push_id so the next event session folds it
        // into context.push_id, matching iOS PushNotificationHandler.swift:73.
        try {
            PushSessionContext.recordPushReceived(applicationContext, pushId)
        } catch (e: Throwable) {
            Log.warning("PushSessionContext: recordPushReceived failed: ${e.message}")
        }

        // SPEC-070-A H.8: fire AppDNAPushDelegate.onPushReceived BEFORE we
        // build the notification. Hosts can suppress display by reading
        // the payload (no veto signal yet — iOS contract is observe-only).
        notifyPushDelegateReceived(message)

        // SPEC-070-A H.22: a hybrid notification+data payload is rendered
        // by FCM itself when the app is backgrounded. Building our own
        // notification here would produce a duplicate. Only render for
        // pure data-only payloads.
        if (message.notification != null) {
            Log.debug("Push has notification block — FCM auto-displays; skipping SDK notify()")
            return
        }

        // Build and display notification
        ensureNotificationChannel(message.data)
        val notification = buildNotification(message)
        try {
            val channelId = message.data["channel_id"] ?: DEFAULT_CHANNEL_ID
            NotificationManagerCompat.from(this).notify(pushId.hashCode(), notification)
            // pin channelId for log clarity
            Log.debug("Push displayed (channel=$channelId, id=$pushId)")
        } catch (e: SecurityException) {
            Log.warning("Missing POST_NOTIFICATIONS permission")
        }
    }

    override fun onNewToken(token: String) {
        AppDNA.onNewPushToken(token)
    }

    protected open fun buildNotification(message: RemoteMessage): android.app.Notification {
        val data = message.data
        val channelId = data["channel_id"] ?: DEFAULT_CHANNEL_ID
        // SPEC-088: Interpolate push title and body via TemplateEngine
        val pushCtx = ai.appdna.sdk.core.TemplateEngine.buildContext()
        val title = ai.appdna.sdk.core.TemplateEngine.interpolate(
            data["title"] ?: message.notification?.title ?: "", pushCtx
        )
        val body = ai.appdna.sdk.core.TemplateEngine.interpolate(
            data["body"] ?: message.notification?.body ?: "", pushCtx
        )
        val pushId = data["push_id"] ?: ""

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            // SPEC-070-A H.21: resolve icon via Configuration.notificationIcon →
            // manifest <meta-data> → app icon. setSmallIcon throws when given a
            // 0/missing res id, so we always have a valid fallback.
            .setSmallIcon(resolveNotificationIcon())
            .setAutoCancel(true)
            .setPriority(
                if (data["priority"] == "high") NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )

        // SPEC-085: Rich image via RichPushHandler for reliable download with timeout
        data["image_url"]?.let { url ->
            try {
                val richBuilder = RichPushHandler.buildRichNotification(
                    context = this,
                    channelId = channelId,
                    title = title,
                    body = body,
                    imageUrl = url,
                    mediaType = data["media_type"],
                    customSound = data["sound"],
                )
                // Copy the style from the rich builder
                val richNotification = richBuilder.build()
                richNotification.extras?.let { extras ->
                    builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(
                        extras.getParcelable("android.picture") as? Bitmap
                    ))
                    extras.getParcelable<Bitmap>("android.largeIcon")?.let { icon ->
                        builder.setLargeIcon(icon)
                    }
                }
            } catch (e: Exception) {
                Log.warning("Failed to download push image: ${e.message}")
            }
        }

        // Custom color
        data["color"]?.let { color ->
            try {
                builder.setColor(android.graphics.Color.parseColor(color))
            } catch (_: Exception) {}
        }

        // Tap intent with push_id for tracking + iOS-canonical action fields
        // (action_type, action_value, screen_id, deep_link).
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra("push_id", pushId)
            putExtra("action_type", data["action_type"])
            putExtra("action_value", data["action_value"])
            putExtra("screen_id", data["screen_id"])
            putExtra("deep_link", data["deep_link"])
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (tapIntent != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, pushId.hashCode(), tapIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }

        // SPEC-070-A H.6: dismissal intent → push_dismissed event broadcast.
        // Set scheme + data via setData() so we don't shadow the outer
        // `data: Map<String,String>` inside the apply block.
        val dismissIntent = Intent(this, PushDismissReceiver::class.java).apply {
            action = PushDismissReceiver.ACTION_PUSH_DISMISSED
            putExtra("push_id", pushId)
            setData(android.net.Uri.parse("appdna://push/dismiss/$pushId"))
        }
        builder.setDeleteIntent(
            PendingIntent.getBroadcast(
                this, pushId.hashCode(), dismissIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )

        // SPEC-084: Action buttons + SPEC-088: Interpolate action button labels
        parseActionButtons(data).forEach { action ->
            val actionIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                putExtra("push_id", pushId)
                putExtra("action_id", action.id)
                putExtra("action_type", action.type)
                putExtra("action_value", action.value)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            if (actionIntent != null) {
                val pendingIntent = PendingIntent.getActivity(
                    this, action.id.hashCode(), actionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                // SPEC-085 + SPEC-070-A finalization B3#P1: Resolve action button
                // icon. Accept BOTH schemas iOS supports:
                //   1. Flat string "ic_email" → R.drawable lookup
                //   2. JSON dict {library:"lucide"|"material", name:"mail"} →
                //      look up via library-specific name then fall back to
                //      drawable lookup on the resolved name.
                val iconResId = action.icon?.let { NotificationIconResolver.resolvePushActionIconRes(this, it) } ?: 0
                val interpolatedLabel = ai.appdna.sdk.core.TemplateEngine.interpolate(action.label, pushCtx)
                builder.addAction(iconResId, interpolatedLabel, pendingIntent)
            }
        }

        return builder.build()
    }

    /**
     * SPEC-070-A H.19: read server-supplied channel id/name/description/group
     * from the FCM data payload before falling back to defaults. Console-defined
     * channel grouping per push category must reach the end-user device.
     *
     * SPEC-070-A H.21b: also honor channel importance (`high|default|low|min`).
     */
    private fun ensureNotificationChannel(data: Map<String, String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        ensureNotificationChannelOreoPlus(data)
    }

    /**
     * SPEC-070-A J.15 — extracted helper that ASSUMES API 26+ so Android Lint
     * flags any accidental call that isn't behind a
     * `Build.VERSION.SDK_INT >= O` guard. [NotificationChannel] and the
     * `NotificationManager.getNotificationChannel` / `createNotificationChannel`
     * APIs were introduced in API 26.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureNotificationChannelOreoPlus(data: Map<String, String>) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channelId = data["channel_id"] ?: DEFAULT_CHANNEL_ID
        if (manager.getNotificationChannel(channelId) != null) return

        val channelName = data["channel_name"] ?: "Push Notifications"
        val channelDescription = data["channel_description"]
        val channelImportance = mapImportanceOreoPlus(data["channel_importance"])

        val channel = NotificationChannel(channelId, channelName, channelImportance).apply {
            channelDescription?.let { description = it }
            data["channel_group"]?.let {
                // Channel group must exist before the channel can adopt it; create
                // both lazily.
                if (manager.getNotificationChannelGroup(it) == null) {
                    manager.createNotificationChannelGroup(
                        android.app.NotificationChannelGroup(it, it),
                    )
                }
                group = it
            }
        }
        manager.createNotificationChannel(channel)
    }

    private fun mapImportance(raw: String?): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0
        return mapImportanceOreoPlus(raw)
    }

    /**
     * SPEC-070-A J.15 — extracted helper that ASSUMES API 26+ so Android Lint
     * flags any accidental call that isn't behind a
     * `Build.VERSION.SDK_INT >= O` guard. The `NotificationManager.IMPORTANCE_*`
     * constants surfaced from this helper are the ones consumed by
     * [NotificationChannel] (API 26+); even though the constant values were
     * declared in older API levels, callers using them must be on a path
     * where the channel APIs are available.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun mapImportanceOreoPlus(raw: String?): Int = when (raw?.lowercase()) {
        "high" -> NotificationManager.IMPORTANCE_HIGH
        "default" -> NotificationManager.IMPORTANCE_DEFAULT
        "low" -> NotificationManager.IMPORTANCE_LOW
        "min" -> NotificationManager.IMPORTANCE_MIN
        else -> NotificationManager.IMPORTANCE_HIGH
    }

    /**
     * SPEC-070-A H.21: small-icon resolution priority:
     *   1. AppDNA.notificationIcon (developer-supplied via Configuration)
     *   2. <meta-data android:name="com.google.firebase.messaging.default_notification_icon">
     *   3. ApplicationInfo.icon (last resort)
     */
    private fun resolveNotificationIcon(): Int =
        NotificationIconResolver.resolve(applicationContext)

    /**
     * SPEC-070-A H.10: parse action buttons from a push data payload.
     *
     * Accepts BOTH schemas that the AppDNA backend may emit:
     *   1. **Canonical (iOS-shaped)**: `actions` is a JSON array of
     *      `{ id, label, type, value, icon? }` objects.
     *   2. **Legacy flat**: `action_0_id`, `action_0_label`, ... (no cap).
     *
     * Also reads a single canonical `action` JSON object (`{type, value, ...}`)
     * for completeness — surfaced via tap-intent extras.
     *
     * The previous Android implementation hard-capped at 3 buttons; uncapping
     * here matches Android's notification limit (system caps at 3 anyway, but
     * we let the backend send more so future OS versions or wear devices can
     * surface them).
     */
    private fun parseActionButtons(data: Map<String, String>): List<ActionButton> {
        val buttons = mutableListOf<ActionButton>()

        // Canonical schema first.
        data["actions"]?.let { rawActions ->
            try {
                val arr = JSONArray(rawActions)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (id.isEmpty()) continue
                    val label = obj.optString("label", "")
                    val type = obj.optString("type", "dismiss")
                    val value = obj.optString("value", null)
                    val icon = obj.optString("icon", null)
                    buttons.add(ActionButton(id, label, type, value, icon))
                }
            } catch (e: Exception) {
                Log.warning("Push: failed to parse 'actions' JSON: ${e.message}")
            }
        }
        if (buttons.isNotEmpty()) return buttons

        // Legacy flat schema. SPEC-070-A H.10 — uncap. Walk indices until a
        // gap appears (action_${i}_id missing).
        var i = 0
        while (true) {
            val id = data["action_${i}_id"] ?: break
            val label = data["action_${i}_label"] ?: ""
            val type = data["action_${i}_type"] ?: "dismiss"
            val value = data["action_${i}_value"]
            val icon = data["action_${i}_icon"]
            buttons.add(ActionButton(id, label, type, value, icon))
            i++
        }
        return buttons
    }

    /**
     * SPEC-070-A H.8 + H.16: surface the push payload to the host's
     * AppDNAPushDelegate. Foreground state inferred via ActivityManager so it
     * works correctly even when this Service runs in a `:fcm` sub-process
     * (where ProcessLifecycleOwner is incorrect). Posts to main-thread to
     * isolate host code from the FCM listener thread.
     */
    private fun notifyPushDelegateReceived(message: RemoteMessage) {
        val delegate = try { AppDNA.push.delegate() } catch (_: Throwable) { null } ?: return
        val data = message.data
        val payload = PushPayload(
            pushId = data["push_id"] ?: "",
            title = data["title"] ?: message.notification?.title ?: "",
            body = data["body"] ?: message.notification?.body ?: "",
            imageUrl = data["image_url"],
            data = data.toMap(),
            action = parseCanonicalAction(data),
        )
        val inForeground = isAppInForeground(applicationContext)
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    delegate.onPushReceived(payload, inForeground)
                } catch (e: Throwable) {
                    Log.warning("AppDNAPushDelegate.onPushReceived threw: ${e.message}")
                }
            }
        } catch (e: Throwable) {
            Log.warning("AppDNAPushDelegate fan-out failed: ${e.message}")
        }
    }

    /**
     * SPEC-070-A H.10: read canonical `action` field if present
     * (`{"type": "show_screen", "value": "settings"}`) — falls back to flat
     * `action_type`/`action_value` keys for legacy senders.
     */
    private fun parseCanonicalAction(data: Map<String, String>): PushAction? {
        data["action"]?.let { rawAction ->
            try {
                val obj = JSONObject(rawAction)
                val type = obj.optString("type", "").ifEmpty { return@let null }
                val value = obj.optString("value", "")
                return PushAction(type, value)
            } catch (e: Exception) {
                Log.warning("Push: failed to parse 'action' JSON: ${e.message}")
            }
        }
        val type = data["action_type"]?.takeIf { it.isNotEmpty() } ?: return null
        val value = data["action_value"] ?: ""
        return PushAction(type, value)
    }

    // SPEC-084: Parse action buttons from push data
    private data class ActionButton(val id: String, val label: String, val type: String, val value: String?, val icon: String? = null)

    companion object {
        const val DEFAULT_CHANNEL_ID = "appdna_push"

        /**
         * SPEC-070-A H.16: foreground detection that works in any process,
         * including `:fcm`. Reads the running-process importance of the
         * app's main process — `IMPORTANCE_FOREGROUND` means the user is
         * actively interacting; anything else means backgrounded or killed.
         */
        internal fun isAppInForeground(context: Context): Boolean {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    ?: return false
                val processes = am.runningAppProcesses ?: return false
                val pkg = context.packageName
                processes.any {
                    it.processName == pkg && it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }
            } catch (_: Throwable) {
                false
            }
        }
    }
}

/**
 * SPEC-070-A H.6: Receives the dismissal broadcast (deleteIntent) and emits
 * a `push_dismissed` event on behalf of the user. Registered dynamically by
 * the deleteIntent — no manifest entry needed because [PendingIntent.getBroadcast]
 * delivers via the standard broadcast pipeline.
 */
internal class PushDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PUSH_DISMISSED) return
        val pushId = intent.getStringExtra("push_id") ?: return
        try {
            AppDNA.track("push_dismissed", mapOf("push_id" to pushId))
        } catch (e: Throwable) {
            Log.warning("PushDismissReceiver: failed to track push_dismissed: ${e.message}")
        }
    }

    companion object {
        const val ACTION_PUSH_DISMISSED = "ai.appdna.sdk.PUSH_DISMISSED"
    }
}

/**
 * SPEC-070-A H.21: shared resolver so both [AppDNAMessagingService] and
 * [RichPushHandler] use identical icon-resolution logic.
 */
internal object NotificationIconResolver {
    fun resolve(context: Context): Int {
        // 1. Configuration override (set by host via AppDNA.notificationIcon).
        val override = AppDNA.notificationIcon
        if (override != 0) return override

        // 2. Manifest meta-data (matches Firebase Messaging convention).
        try {
            val ai = context.packageManager
                .getApplicationInfo(context.packageName, android.content.pm.PackageManager.GET_META_DATA)
            val metaName = "com.google.firebase.messaging.default_notification_icon"
            val resId = ai.metaData?.getInt(metaName, 0) ?: 0
            if (resId != 0) return resId
        } catch (_: Throwable) { /* fall through */ }

        // 3. Last resort — application icon (NotificationCompat will reject 0).
        val fallback = context.applicationInfo.icon
        return if (fallback != 0) fallback else android.R.drawable.ic_dialog_info
    }

    /**
     * SPEC-070-A finalization B3#P1 — resolve a push action button icon ref
     * to an Android drawable resource id, given a host context. Accepts:
     *   1. Flat string `"ic_email"` → host R.drawable lookup.
     *   2. JSON dict `{library:"lucide"|"material"|"sf-symbols"|"emoji", name:"mail"}`
     *      → try host R.drawable named after `name`, then fall back to a
     *      small map of common Lucide/Material names → `android.R.drawable.*`.
     * Mirrors iOS `IconMapping.lucideToSFSymbol` at
     * Push/PushNotificationHandler.swift:62-95.
     */
    fun resolvePushActionIconRes(context: Context, raw: String): Int {
        val (library, name) = try {
            val obj = org.json.JSONObject(raw)
            (obj.optString("library").takeIf { it.isNotEmpty() } ?: "lucide") to obj.optString("name", "")
        } catch (_: Throwable) {
            "" to raw
        }
        if (name.isEmpty()) return 0

        val hostId = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (hostId != 0) return hostId

        val lower = name.lowercase().replace('-', '_')
        return when (library.lowercase()) {
            "lucide", "material", "sf-symbols", "" -> when (lower) {
                "mail", "envelope", "email" -> android.R.drawable.ic_dialog_email
                "phone", "call" -> android.R.drawable.ic_menu_call
                "share", "share_2" -> android.R.drawable.ic_menu_share
                "delete", "trash", "trash_2", "x", "close" -> android.R.drawable.ic_menu_delete
                "edit", "edit_3", "pencil" -> android.R.drawable.ic_menu_edit
                "info" -> android.R.drawable.ic_dialog_info
                "alert_triangle", "warning" -> android.R.drawable.ic_dialog_alert
                "check", "check_circle" -> android.R.drawable.checkbox_on_background
                "search" -> android.R.drawable.ic_menu_search
                "settings", "cog", "gear" -> android.R.drawable.ic_menu_preferences
                "calendar" -> android.R.drawable.ic_menu_my_calendar
                "map_pin", "map", "location" -> android.R.drawable.ic_menu_mylocation
                "external_link", "link" -> android.R.drawable.ic_menu_view
                else -> 0
            }
            else -> 0
        }
    }
}

/**
 * SPEC-070-A H.7: session-scoped `push_id` propagation. We persist the
 * latest delivered push_id in SharedPreferences so the next event session
 * (which may be a different process or a cold launch from tap) can fold
 * it into `context.push_id` for attribution. Mirrors iOS
 * `PushNotificationHandler.lastPushId` (Push/PushNotificationHandler.swift).
 */
internal object PushSessionContext {
    private const val PREFS_NAME = "ai.appdna.sdk.push"
    private const val KEY_LAST_PUSH_ID = "last_push_id"
    private const val KEY_LAST_PUSH_AT = "last_push_at"
    // Treat a push older than 30 minutes as no longer "current" — matches
    // SessionManager rotation (SPEC-070-A A.8).
    private const val WINDOW_MS = 30 * 60 * 1000L

    fun recordPushReceived(context: Context, pushId: String) {
        if (pushId.isBlank()) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_LAST_PUSH_ID, pushId)
                .putLong(KEY_LAST_PUSH_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) { /* SharedPreferences is best-effort */ }
    }

    /**
     * Returns the last push_id received within the rolling 30-minute window.
     * Returned value is consumed lazily — callers do NOT clear it; the
     * window logic ensures stale ids age out.
     */
    fun currentPushId(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pushId = prefs.getString(KEY_LAST_PUSH_ID, null) ?: return null
            val at = prefs.getLong(KEY_LAST_PUSH_AT, 0L)
            if (at == 0L) return null
            if (System.currentTimeMillis() - at > WINDOW_MS) return null
            pushId
        } catch (_: Throwable) {
            null
        }
    }
}
