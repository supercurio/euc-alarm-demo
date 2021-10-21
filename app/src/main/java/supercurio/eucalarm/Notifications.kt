package supercurio.eucalarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import supercurio.eucalarm.activities.MainActivity
import supercurio.eucalarm.service.AppService

object Notifications {

    var muted = false

    private var currentMessage = ""
    private var prevMessage = ""

    private const val NOTIFICATION_CHANNEL_FOREGROUND_SERVICE_ID = "AppService"

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

        val startActivityPi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopServicePi = PendingIntent.getBroadcast(
            context, 0, Intent(AppService.STOP_BROADCAST),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_FOREGROUND_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_stat_donut_small)
            .setContentTitle(title)
            .setContentIntent(startActivityPi)
            .addAction(0, "Stop", stopServicePi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun updateOngoing(context: Context, title: String) {
        if (muted) return

        prevMessage = currentMessage

        Log.i(TAG, "Update notification with title: $title")
        context.getSystemService<NotificationManager>()!!
            .notify(AppService.NOTIF_ID, foregroundServiceNotificationBuilder(context, title))
        currentMessage = title
    }

    fun rollbackOngoing(context: Context) {
        updateOngoing(context, prevMessage)
    }

    private const val TAG = "Notifications"
}
