package supercurio.eucalarm.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus
import supercurio.eucalarm.Notifications
import supercurio.eucalarm.R
import supercurio.eucalarm.ble.WheelBleRecorder
import supercurio.eucalarm.ble.WheelBleSimulator
import supercurio.eucalarm.ble.WheelConnection
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.feedback.AlertFeedback
import supercurio.eucalarm.power.PowerManagement

class AppService : Service() {
    private val serviceScope = MainScope() + CoroutineName(TAG)

    override fun onBind(intent: Intent): Binder? = null

    private val wheelData = WheelDataStateFlows.getInstance()
    private lateinit var powerManagement: PowerManagement
    private lateinit var wheelConnection: WheelConnection
    private lateinit var alert: AlertFeedback
    private lateinit var wheelBleRecorder: WheelBleRecorder
    private lateinit var simulator: WheelBleSimulator

    override fun onCreate() {
        super.onCreate()
        powerManagement = PowerManagement.getInstance(applicationContext)
        wheelConnection = WheelConnection.getInstance(wheelData, powerManagement)
        alert = AlertFeedback.getInstance(wheelData, wheelConnection)
        alert.setup(applicationContext, serviceScope)

        wheelBleRecorder = WheelBleRecorder.getInstance(wheelConnection)
        simulator = WheelBleSimulator.getInstance(applicationContext, powerManagement)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val notif = Notifications.foregroundServiceNotificationBuilder(
            applicationContext,
            getString(R.string.app_name)
        )

        startForeground(NOTIF_ID, notif)

        return START_STICKY
    }

    override fun onDestroy() {
        wheelBleRecorder.shutDown()
        wheelConnection.shutdown(applicationContext)
        simulator.shutdown()
        alert.shutdown()
        powerManagement.releaseAll()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "AppService"
        private const val NOTIF_ID = 1

        private var isRunning = false

        fun enable(context: Context, status: Boolean) {
            if (status == isRunning) return

            isRunning = status

            if (status) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(getIntent(context))
                } else context.startService(getIntent(context))
            } else {
                context.stopService(getIntent(context))
            }
        }

        private fun getIntent(context: Context) = Intent(context, AppService::class.java)

    }
}
