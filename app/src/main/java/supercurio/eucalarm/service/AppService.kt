package supercurio.eucalarm.service

import android.app.ActivityManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import supercurio.eucalarm.Notifications
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
import supercurio.eucalarm.log.AppLog
import supercurio.eucalarm.power.PowerManagement
import supercurio.eucalarm.receivers.BluetoothConnectionReceiver
import supercurio.eucalarm.utils.ConnectionStatusNotif
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
    lateinit var scopes: CoroutineScopeProvider

    @Inject
    lateinit var appLog: AppLog

    private val bluetoothConnectionReceiver = BluetoothConnectionReceiver()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate")

        registerReceiver(actionsReceiver, IntentFilter().apply {
            addAction(STOP_BROADCAST)
            addAction(DISCONNECT_BROADCAST)
        })

        appLog.log("Service created - by system: $startedBySystem")
        // when the service is started by the system after being killed or a crash
        if (startedBySystem) appStateStore.restoreState(appLifecycle)

        updateNotificationBasedOnState()
        updateNotificationBasedOnRecording()

        registerReceiver(
            bluetoothConnectionReceiver,
            IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")

        val notif = notifications.foregroundServiceNotificationBuilder()

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
                wheelBleRecorder.start(state.deviceAddr)
            }

            else -> {
                // Do nothing otherwise
            }
        }

        appLog.log("Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        unregisterReceiver(actionsReceiver)
        unregisterReceiver(bluetoothConnectionReceiver)

        appLog.log("Service destroyed")
        startedBySystem = true
    }


    private val actionsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                STOP_BROADCAST -> appLifecycle.off()
                DISCONNECT_BROADCAST -> wheelConnection.disconnectDevice()
                STOP_RECORDING_BROADCAST -> wheelBleRecorder.shutDown()
            }
        }
    }

    private fun updateNotificationBasedOnState() = scopes.app.launch {
        wheelConnection.connectionStateFlow.collect {
            val title = notificationBasedOnConnectionState(it)
            notifications.update(title)
        }
    }

    private fun notificationBasedOnConnectionState(state: BleConnectionState) =
        when (state) {
            BleConnectionState.CONNECTED -> ConnectionStatusNotif("Connecting 2/2 to ${wheelConnection.deviceName}")
            BleConnectionState.CONNECTING -> ConnectionStatusNotif("Connecting 1/2 to ${wheelConnection.deviceName}")
            BleConnectionState.CONNECTED_READY -> ConnectionStatusNotif("Connected to ${wheelConnection.deviceName}")
            BleConnectionState.DISCONNECTED_RECONNECTING -> ConnectionStatusNotif("Reconnecting to ${wheelConnection.deviceName}")
            BleConnectionState.SCANNING -> ConnectionStatusNotif("Scanning for ${wheelConnection.deviceName}")
            BleConnectionState.BLUETOOTH_OFF -> ConnectionStatusNotif("Bluetooth is off")
            BleConnectionState.DISCONNECTED -> ConnectionStatusNotif("Waiting for connection", true)
            else -> ConnectionStatusNotif("Standby", true)
        }

    private fun updateNotificationBasedOnRecording() = scopes.app.launch {
        wheelBleRecorder.isRecording.collect { isRecording ->
            if (isRecording)
                notifications.update(ConnectionStatusNotif("Recording from ${wheelConnection.deviceName}"))
            else {
                val title = notificationBasedOnConnectionState(wheelConnection.connectionState)
                notifications.update(title)
            }
        }
    }

    companion object {
        private const val TAG = "AppService"

        const val STOP_BROADCAST = "StopAndExitService"
        const val DISCONNECT_BROADCAST = "DisconnectBluetoothDevice"
        const val STOP_RECORDING_BROADCAST = "StopRecording"

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
