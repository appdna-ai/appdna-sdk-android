package ai.appdna.sdk.integrations

import ai.appdna.sdk.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL

/**
 * Helper for building rich push notifications with images.
 * Used by AppDNAMessagingService to display BigPictureStyle notifications.
 *
 * SPEC-070-A H.21 — small-icon resolution delegated to
 * [NotificationIconResolver] so the icon priority chain stays in lockstep
 * with [AppDNAMessagingService].
 *
 * SPEC-070-A H.21b — server-supplied `channel_importance` overrides the
 * default `IMPORTANCE_HIGH` when the channel is created here.
 *
 * SPEC-070-A H.23 — bitmap download moved off the FCM listener thread into
 * a coroutine with an 8-second wall-clock cap (well under FCM's 10s service
 * window). Previously, the default URL connection's 25s combined timeout
 * could cause the FCM service to be killed before the notification posted.
 */
object RichPushHandler {

    fun buildRichNotification(
        context: Context,
        channelId: String,
        title: String,
        body: String,
        imageUrl: String? = null,
        mediaType: String? = null,
        customSound: String? = null,
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, channelId)
            // SPEC-070-A H.21 — host-aware icon resolution.
            .setSmallIcon(NotificationIconResolver.resolve(context))
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // SPEC-070-A H.23: download bitmap with a coroutine-scoped 8-second
        // timeout. runBlocking is acceptable here because (a) callers are
        // already on the FCM service thread (NOT main), and (b) we cap the
        // wait at 8s — well below FCM's 10s service-window kill timer.
        imageUrl?.let { url ->
            try {
                val bitmap = runBlocking {
                    withTimeoutOrNull(BITMAP_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { downloadBitmap(url) }
                    }
                }
                if (bitmap != null) {
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as Bitmap?)
                    )
                    builder.setLargeIcon(bitmap)
                } else {
                    Log.warning("RichPushHandler: bitmap fetch timed out / failed for $url")
                }
            } catch (e: Throwable) {
                Log.warning("RichPushHandler: bitmap fetch threw: ${e.message}")
            }
        }

        // Custom sound
        customSound?.let { soundName ->
            val soundResId = context.resources.getIdentifier(soundName, "raw", context.packageName)
            if (soundResId != 0) {
                val soundUri = android.net.Uri.parse("android.resource://${context.packageName}/$soundResId")
                builder.setSound(soundUri)
            }
        }

        return builder
    }

    private const val BITMAP_TIMEOUT_MS = 8_000L

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val connection = URL(url).openConnection()
            // SPEC-070-A H.23 — keep individual socket timeouts tight so the
            // 8s outer cap can preempt slow servers. Connect: 4s, read: 4s.
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.getInputStream().use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            Log.warning("RichPushHandler.downloadBitmap failed: ${e.message}")
            null
        }
    }

    /**
     * SPEC-070-A H.21b — overload that accepts server-supplied importance.
     * Existing 2-arg overload retained for source-compat.
     */
    fun ensureChannel(
        context: Context,
        channelId: String,
        channelName: String,
        importance: Int = NotificationManager.IMPORTANCE_HIGH,
        description: String? = null,
        group: String? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(channelId) != null) return
        val channel = NotificationChannel(channelId, channelName, importance).apply {
            description?.let { this.description = it }
            group?.let {
                if (manager.getNotificationChannelGroup(it) == null) {
                    manager.createNotificationChannelGroup(android.app.NotificationChannelGroup(it, it))
                }
                this.group = it
            }
        }
        manager.createNotificationChannel(channel)
    }
}
