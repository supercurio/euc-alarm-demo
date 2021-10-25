package supercurio.eucalarm.service

import android.app.ActivityManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import supercurio.eucalarm.Notifications
import supercurio.eucalarm.R
import supercurio.eucalarm.appstate.AppStateStore
import supercurio.eucalarm.appstate.ConnectedState
import supercurio.eucalarm.appstate.RecordingState
import supercurio.eucalarm.ble.BleConnectionState
import supercurio.eucalarm.ble.WheelBleRecorder
import supercurio.eucalarm.ble.WheelConnection
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.di.AppLifecycle
import supercurio.eucalarm.di.CoroutineScopeProvider
import supercurio.eucalarm.feedback.AlertFeedback
import supercurio.eucalarm.power.PowerManagement
import javax.inject.Inject

@AndroidEntryPoint
class AppService : Service() {

    override fun onBind(intent: Intent): Binder? = null

    @Inject
    lateinit var appLifecycle: AppLifecycle

    @Inject
    lateinit var wheelData: WheelDataStateFlows

    @Inject
    lateinit var appStateStore: AppStateStore

    @Inject
    lateinit var powerManagement: PowerManagement

    @Inject
    lateinit var wheelConnection: WheelConnection

    @Inject
    lateinit var alert: AlertFeedback

    @Inject
    lateinit var wheelBleRecorder: WheelBleRecorder

    @Inject
    lateinit var notifications: Notifications

    @Inject
    lateinit var scopeProvider: CoroutineScopeProvider


    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "onCreate")

        registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(STOP_BROADCAST)
            addAction(DISCONNECT_BROADCAST)
        })

        // when the service is started by the system after being killed or a crash
        if (startedBySystem) appStateStore.restoreState(appLifecycle)

        updateNotificationBasedOnState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")

        val notif = notifications.foregroundServiceNotificationBuilder(getString(R.string.app_name))

        startForeground(Notifications.SERVICE_ID, notif)

        // restore app state
        when (val state = appStateStore.loadState()) {
            is ConnectedState -> {
                Log.i(TAG, "Reconnect device: ${state.deviceAddr}")
                wheelConnection.reconnectDeviceAddr(state.deviceAddr)
            }

            is RecordingState -> {
                Log.i(TAG, "Reconnect device and resume recording")
                wheelConnection.reconnectDeviceAddr(state.deviceAddr)
                wheelBleRecorder.start(applicationContext, state.deviceAddr)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        unregisterReceiver(broadcastReceiver)

        startedBySystem = true
    }


    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                STOP_BROADCAST -> appLifecycle.off()
                DISCONNECT_BROADCAST -> wheelConnection.disconnectDevice()
            }
        }
    }

    private fun updateNotificationBasedOnState() = scopeProvider.appScope.launch {
        wheelConnection.connectionStateFlow.collect {
            val title = when (it) {
                BleConnectionState.CONNECTED -> "Connected to ${wheelConnection.deviceName}"
                BleConnectionState.CONNECTING -> "Connecting to ${wheelConnection.deviceName}"
                BleConnectionState.RECEIVING_DATA -> "Receiving data from ${wheelConnection.deviceName}"
                BleConnectionState.DISCONNECTED_RECONNECTING -> "Reconnecting to ${wheelConnection.deviceName}"
                BleConnectionState.SCANNING -> "Scanning for ${wheelConnection.deviceName}"
                BleConnectionState.BLUETOOTH_OFF -> "Bluetooth is off"
                BleConnectionState.DISCONNECTED -> "Waiting for connection"
                else -> "Standby"
            }

            notifications.updateOngoing(title)
        }
    }

    companion object {
        private const val TAG = "AppService"

        const val STOP_BROADCAST = "StopAndExitService"
        const val DISCONNECT_BROADCAST = "DisconnectBluetoothDevice"

        private var startedBySystem = true

        fun enable(context: Context, status: Boolean) {
            startedBySystem = false

            if (status == context.isServiceRunning()) return

            if (status) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(getIntent(context))
                } else context.startService(getIntent(context))
            } else {
                context.stopService(getIntent(context))
            }
        }

        private fun getIntent(context: Context) = Intent(context, AppService::class.java)

        private fun Context.isServiceRunning(): Boolean {
            val am = getSystemService<ActivityManager>()!!
            am.getRunningServices(Integer.MAX_VALUE).forEach { serviceInfo ->
                if (serviceInfo.service.className == AppService::class.qualifiedName) return true
            }
            return false
        }
    }
}
