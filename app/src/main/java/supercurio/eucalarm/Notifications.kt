package supercurio.eucalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import supercurio.eucalarm.activities.MainActivity

object Notifications {

    private const val NOTIFICATION_CHANNEL_FOREGROUND_SERVICE_ID = "AppService"
    const val ID_FOREGROUND_SERVICE = 1

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channels = listOf(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_FOREGROUND_SERVICE_ID,
                    context.getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )

            val nm = context.getSystemService<NotificationManager>()!!
            channels.forEach { nm.createNotificationChannel(it) }
        }
    }

    fun foregroundServiceNotificationBuilder(context: Context, title: String): Notification {

        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_FOREGROUND_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_stat_donut_small)
            .setContentTitle(title)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
