package ai.appdna.sdk.integrations

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import java.net.URL

/**
 * Helper for building rich push notifications with images.
 * Used by AppDNAMessagingService to display BigPictureStyle notifications.
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Download and attach rich image
        imageUrl?.let { url ->
            try {
                val bitmap = downloadBitmap(url)
                if (bitmap != null) {
                    builder.setStyle(
                        NotificationCompat.BigPictureStyle()
                            .bigPicture(bitmap)
                            .bigLargeIcon(null as Bitmap?)
                    )
                    builder.setLargeIcon(bitmap)
                }
            } catch (_: Exception) {
                // Fall back to text-only notification
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

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.getInputStream().use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun ensureChannel(context: Context, channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
