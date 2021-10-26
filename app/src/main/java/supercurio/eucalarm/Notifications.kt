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
import dagger.hilt.android.qualifiers.ApplicationContext
import supercurio.eucalarm.activities.MainActivity
import supercurio.eucalarm.ble.WheelBleRecorder
import supercurio.eucalarm.ble.WheelConnection
import supercurio.eucalarm.service.AppService
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Notifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelConnection: WheelConnection,
    private val recorder: WheelBleRecorder,
) {

    private val nm = context.getSystemService<NotificationManager>()!!

    var muted = false

    private var currentMessage = ""
    private var prevMessage = ""

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channels = listOf(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_FOREGROUND_SERVICE_ID,
                    context.getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ),
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ALERT_ID,
                    context.getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    setBypassDnd(true)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                },
            )

            channels.forEach { nm.createNotificationChannel(it) }
        }
    }

    fun foregroundServiceNotificationBuilder(title: String): Notification =
        NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_FOREGROUND_SERVICE_ID)
            .setSmallIcon(R.drawable.ic_stat_donut_small)
            .setContentTitle(title)
            .setContentIntent(startActivityPi)
            .addAction(0, "Stop", pendingIntentFor(AppService.STOP_BROADCAST))
            .apply {
                if (wheelConnection.connectionStateFlow.value.canDisconnect)
                    addAction(
                        0, "Disconnect wheel",
                        pendingIntentFor(AppService.DISCONNECT_BROADCAST)
                    )
                if (recorder.isRecording.value)
                    addAction(
                        0, "Disconnect wheel",
                        pendingIntentFor(AppService.STOP_RECORDING_BROADCAST)
                    )
            }
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun updateOngoing(title: String, storeCurrent: Boolean = true) {
        if (muted) return

        if (storeCurrent) prevMessage = currentMessage

        Log.i(TAG, "Update notification with title: $title")
        nm.notify(SERVICE_ID, foregroundServiceNotificationBuilder(title))
        currentMessage = title
    }

    fun rollbackOngoing() {
        updateOngoing(prevMessage, false)
    }

    fun notifyAlert() {
        if (muted) return
        updateOngoing("Wheel alarm !")

        val time = System.currentTimeMillis()
        val formatter = SimpleDateFormat("HH:mm:ss")

        val notif = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ALERT_ID)
            .setSmallIcon(R.drawable.ic_stat_donut_small)
            .setContentTitle("Wheel alarm")
            .setContentText("EUC Alarm (${formatter.format(Date(time))})")
            .setContentIntent(startActivityPi)
            .setTimeoutAfter(ALERT_TIMEOUT)
            .build()

        nm.notify(ALERT_ID, notif)
    }

    fun cancelAlerts() {
        rollbackOngoing()
        nm.cancel(ALERT_ID)
    }

    private fun pendingIntentFor(action: String) = PendingIntent.getBroadcast(
        context, 0, Intent(action),
        PendingIntent.FLAG_IMMUTABLE
    )

    private val startActivityPi
        get() = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )


    companion object {
        private const val TAG = "Notifications"
        private const val NOTIFICATION_CHANNEL_FOREGROUND_SERVICE_ID = "AppService"
        private const val NOTIFICATION_CHANNEL_ALERT_ID = "Alerts"

        const val SERVICE_ID = 1
        const val ALERT_ID = 2
        const val ALERT_TIMEOUT = 5000L
    }
}
