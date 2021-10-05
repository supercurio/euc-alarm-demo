package supercurio.eucalarm.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import supercurio.eucalarm.Notifications
import supercurio.eucalarm.R
import kotlin.time.ExperimentalTime

class ForegroundService : Service() {
    override fun onBind(intent: Intent): Binder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        Notifications.foregroundServiceNotificationBuilder(
            applicationContext,
            getString(R.string.app_name)
        )

        return START_STICKY
    }

    companion object {
        private const val TAG = "ForegroundService"

        private var isRunning = false

        fun enable(context: Context, status: Boolean) {
            if (status == isRunning) return

            isRunning = status

            if (status) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(getIntent(context))
                } else {
                    context.startService(getIntent(context))
                }
            } else {
                context.stopService(getIntent(context))
            }
        }

        private fun getIntent(context: Context) = Intent(context, ForegroundService::class.java)
    }
}
