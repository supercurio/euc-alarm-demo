package supercurio.eucalarm.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import supercurio.eucalarm.Notifications
import supercurio.eucalarm.R
import supercurio.eucalarm.appstate.AppStateStore
import supercurio.eucalarm.appstate.ConnectedState
import supercurio.eucalarm.appstate.RecordingState
import supercurio.eucalarm.ble.BleConnectionState
import supercurio.eucalarm.ble.WheelBleRecorder
import supercurio.eucalarm.ble.WheelBleSimulator
import supercurio.eucalarm.ble.WheelConnection
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.feedback.AlertFeedback
import supercurio.eucalarm.power.PowerManagement

class AppService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default) + CoroutineName(TAG)

    override fun onBind(intent: Intent): Binder? = null

    private val wheelData = WheelDataStateFlows.getInstance()
    private lateinit var appStateStore: AppStateStore
    private lateinit var powerManagement: PowerManagement
    private lateinit var wheelConnection: WheelConnection
    private lateinit var alert: AlertFeedback
    private lateinit var wheelBleRecorder: WheelBleRecorder
    private lateinit var simulator: WheelBleSimulator

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")
        appStateStore = AppStateStore.getInstance(applicationContext)
        powerManagement = PowerManagement.getInstance(applicationContext)
        wheelConnection = WheelConnection.getInstance(wheelData, powerManagement, appStateStore)
        alert = AlertFeedback.getInstance(wheelData, wheelConnection)
        alert.setup(applicationContext, serviceScope)

        wheelBleRecorder = WheelBleRecorder.getInstance(wheelConnection, appStateStore)
        simulator = WheelBleSimulator.getInstance(applicationContext, powerManagement)

        registerReceiver(shutdownReceiver, IntentFilter(STOP_BROADCAST))
        if (!isRunning)
            appStateStore.restoreState(applicationContext)

        updateNotificationBasedOnState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")

        val notif = Notifications.foregroundServiceNotificationBuilder(
            applicationContext,
            getString(R.string.app_name)
        )

        startForeground(NOTIF_ID, notif)

        // restore app state
        when (val state = appStateStore.loadState()) {
            is ConnectedState -> {
                Log.i(TAG, "Reconnect device: ${state.deviceAddr}")
                wheelConnection.reconnectDevice(applicationContext, state.deviceAddr)
            }

            is RecordingState -> {
                Log.i(TAG, "Reconnect device and resume recording")
                wheelConnection.reconnectDevice(applicationContext, state.deviceAddr)
                wheelBleRecorder.start(applicationContext, state.deviceAddr)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning = false

        unregisterReceiver(shutdownReceiver)

        wheelBleRecorder.shutDown()
        wheelConnection.shutdown(applicationContext)
        simulator.shutdown()
        alert.shutdown()
        powerManagement.releaseAll()
        serviceScope.cancel()
    }


    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == STOP_BROADCAST) enable(context, false)
        }
    }

    private fun updateNotificationBasedOnState() = serviceScope.launch {
        wheelConnection.connectionStateFlow.collect {
            val title = when (it) {
                BleConnectionState.CONNECTED -> "Connected to ${wheelConnection.deviceName}"
                BleConnectionState.CONNECTING -> "Connecting to ${wheelConnection.deviceName}"
                BleConnectionState.CONNECTED_READY -> "Connected to ${wheelConnection.deviceName} and ready"
                BleConnectionState.DISCONNECTED_RECONNECTING -> "Reconnecting to ${wheelConnection.deviceName}"
                BleConnectionState.SCANNING -> "Scanning for ${wheelConnection.deviceName}"
                else -> "Not connected"
            }

            Notifications.updateOngoing(applicationContext, title)
        }
    }

    companion object {
        private const val TAG = "AppService"

        const val NOTIF_ID = 1
        const val STOP_BROADCAST = "StopAndExitService"

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
