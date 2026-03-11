package ai.appdna.sdk.integrations

import ai.appdna.sdk.AppDNA
import ai.appdna.sdk.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.URL

/**
 * Firebase Cloud Messaging service for handling push notifications.
 * Apps should extend this or register it in AndroidManifest.xml.
 */
open class AppDNAMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val pushId = message.data["push_id"] ?: ""
        Log.info("Push received: $pushId")

        // Track delivery
        AppDNA.trackPushDelivered(pushId)

        // Build and display notification
        ensureNotificationChannel(message.data["channel_id"] ?: DEFAULT_CHANNEL_ID)
        val notification = buildNotification(message)
        try {
            NotificationManagerCompat.from(this).notify(pushId.hashCode(), notification)
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
        val title = data["title"] ?: message.notification?.title ?: ""
        val body = data["body"] ?: message.notification?.body ?: ""
        val pushId = data["push_id"] ?: ""

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(applicationInfo.icon)
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
                        extras.getParcelable("android.picture")
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

        // Tap intent with push_id for tracking
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            putExtra("push_id", pushId)
            putExtra("action_type", data["action_type"])
            putExtra("action_value", data["action_value"])
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

        // SPEC-084: Action buttons
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
                // SPEC-085: Resolve action button icon from drawable resources
                val iconResId = action.icon?.let { iconName ->
                    resources.getIdentifier(iconName, "drawable", packageName)
                } ?: 0
                builder.addAction(iconResId, action.label, pendingIntent)
            }
        }

        return builder.build()
    }

    private fun ensureNotificationChannel(channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    "Push Notifications",
                    NotificationManager.IMPORTANCE_HIGH
                )
                manager.createNotificationChannel(channel)
            }
        }
    }

    // SPEC-084: Parse action buttons from push data
    private data class ActionButton(val id: String, val label: String, val type: String, val value: String?, val icon: String? = null)

    private fun parseActionButtons(data: Map<String, String>): List<ActionButton> {
        val buttons = mutableListOf<ActionButton>()
        // Action buttons are sent as action_0_id, action_0_label, action_0_type, action_0_value, etc.
        for (i in 0..2) {
            val id = data["action_${i}_id"] ?: break
            val label = data["action_${i}_label"] ?: continue
            val type = data["action_${i}_type"] ?: "dismiss"
            val value = data["action_${i}_value"]
            val icon = data["action_${i}_icon"]
            buttons.add(ActionButton(id, label, type, value, icon))
        }
        return buttons
    }

    companion object {
        const val DEFAULT_CHANNEL_ID = "appdna_push"
    }
}
