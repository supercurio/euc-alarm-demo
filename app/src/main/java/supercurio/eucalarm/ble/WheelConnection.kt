package supercurio.eucalarm.ble

import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import supercurio.eucalarm.appstate.AppStateStore
import supercurio.eucalarm.appstate.ConnectedState
import supercurio.eucalarm.appstate.OnStateDefault
import supercurio.eucalarm.ble.find.FindReconnectWheel
import supercurio.eucalarm.ble.wrappers.BluetoothAdapterLock
import supercurio.eucalarm.ble.wrappers.GattClient
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.log.AppLog
import supercurio.eucalarm.parsers.GotwayAndVeteranParser
import supercurio.eucalarm.parsers.NoConfig
import supercurio.eucalarm.parsers.ParserConfig
import supercurio.eucalarm.power.PowerManagement
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WheelConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wheelData: WheelDataStateFlows,
    private val powerManagement: PowerManagement,
    private val appStateStore: AppStateStore,
    private val devicesNamesCache: DevicesNamesCache,
    private val appLog: AppLog,
    private val bluetoothAdapterLock: BluetoothAdapterLock,
) {
    private val btManager = context.getSystemService<BluetoothManager>()!!

    var connectionState = BleConnectionState.UNSET
        private set(value) {
            Log.i(TAG, "ConnectionState change: $value")
            appLog.log("Connection state: $value")
            field = value
            _connectionStateFlow.value = value
        }

    private var gattConnected = false

    private val findReconnectWheel = FindReconnectWheel(context, this)

    private var shouldStayConnected = false

    var gattClient: GattClient? = null
        private set

    private var gotwayAndVeteranParser: GotwayAndVeteranParser? = null
    private var deviceFound: DeviceFound? = null

    val device get() = gattClient?.device
    val advertisement get() = deviceFound?.scanRecord
    val deviceName
        get() = device?.name ?: devicesNamesCache[device?.address
            ?: deviceFound?.device?.address
            ?: findReconnectWheel.reconnectToAddr
        ]

    // Flows
    private val _notifiedCharacteristic = MutableSharedFlow<NotifiedCharacteristic>()
    val notifiedCharacteristic = _notifiedCharacteristic.asSharedFlow()

    private val _connectionStateFlow = MutableStateFlow(BleConnectionState.UNSET)
    val connectionStateFlow = _connectionStateFlow.asStateFlow()

    val parserConfigFlow = MutableStateFlow<ParserConfig>(NoConfig)

    fun reconnectDeviceAddr(deviceAddr: String) {
        registerBtStateChangeReceiver()
        findReconnectWheel.reconnectToAddr = deviceAddr // to help deviceName resolve
        connectionState = BleConnectionState.SCANNING
        findReconnectWheel.findAndReconnect(context, deviceAddr)
    }

    fun connectDeviceFound(inputDeviceToConnect: DeviceFound) {
        Log.i(TAG, "connectDevice($inputDeviceToConnect), from connection state: $connectionState")
        findReconnectWheel.stopLeScan()

        if (gattConnected) {
            Log.w(TAG, "Trying to connect while GATT client already is, ignoring")
            return
        } else destroyGattClient()

        // set app state
        appStateStore.setState(ConnectedState(inputDeviceToConnect.device.address))

        inputDeviceToConnect.scanRecord?.let {
            devicesNamesCache.remember(inputDeviceToConnect.device.address, it)
        }

        // TODO: check if really necessary
        // get a fresh BluetoothDevice form the adapter
        val deviceToConnect = DeviceFound(
            device = btManager.adapter.getRemoteDevice(inputDeviceToConnect.device.address),
            scanRecord = inputDeviceToConnect.scanRecord
        )

        this.deviceFound = deviceToConnect
        shouldStayConnected = true
        powerManagement.getLock(TAG)

        registerBtStateChangeReceiver()


        when (btManager.getConnectionState(deviceToConnect.device, BluetoothProfile.GATT)) {
            // already connected: connect if the connection is not already ready and we're not
            // trying to reconnect already
            BluetoothGatt.STATE_CONNECTED -> if (gattClient == null) doConnect(deviceToConnect.device)
            BluetoothGatt.STATE_CONNECTING -> Log.i(TAG, "Device already connecting")

            // disconnecting or disconnected, connect again
            BluetoothGatt.STATE_DISCONNECTING, BluetoothGatt.STATE_DISCONNECTED -> {
                connectionState = BleConnectionState.CONNECTING
                doConnect(deviceToConnect.device)
            }
        }
    }

    fun connectAlreadyConnectedDevice(device: BluetoothDevice) {
        connectionState = BleConnectionState.SYSTEM_ALREADY_CONNECTED
        reconnectDeviceAddr(device.address)
    }

    fun disconnectDevice() {
        appLog.log("User action → Disconnect device")
        appStateStore.setState(OnStateDefault)

        if (connectionState.canDisconnect) connectionState = BleConnectionState.DISCONNECTED

        if (btStateChangeReceiver.registered) {
            btStateChangeReceiver.registered = false
            context.unregisterReceiver(btStateChangeReceiver)
        }

        findReconnectWheel.stopLeScan(immediately = true)

        shouldStayConnected = false
        powerManagement.removeLock(TAG)
        disableGattNotifications()
        gattClient?.disconnect()
    }

    fun setReplayState(state: Boolean) {
        connectionState = if (state)
            BleConnectionState.REPLAY
        else
            BleConnectionState.UNSET
    }

    fun shutdown() {
        Log.i(TAG, "Shutdown")
        disconnectDevice()
    }

    private fun doConnect(device: BluetoothDevice) {
        gattClient = GattClient(context, device, gattCallback, bluetoothAdapterLock)
        devicesNamesCache.remember(device)
    }

    private fun registerBtStateChangeReceiver() {
        if (!btStateChangeReceiver.registered) {
            context.registerReceiver(
                btStateChangeReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            )
            btStateChangeReceiver.registered = true
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTING -> {
                    gattConnected = true
                    connectionState = BleConnectionState.CONNECTING
                    logConnectionStateChange("STATE_CONNECTING", status)
                }

                BluetoothGatt.STATE_DISCONNECTING -> {
                    gattConnected = false
                    connectionState = BleConnectionState.DISCONNECTING
                    logConnectionStateChange("STATE_DISCONNECTING", status)
                }

                BluetoothGatt.STATE_CONNECTED -> {
                    gattConnected = true
                    connectionState = BleConnectionState.CONNECTED
                    findReconnectWheel.stopLeScan()
                    logConnectionStateChange("STATE_CONNECTED", status)
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    gattConnected = false
                    logConnectionStateChange("STATE_DISCONNECTED", status)
                    // source: https://cs.android.com/android/platform/superproject/+/master:system/bt/stack/include/gatt_api.h;l=65?q=gatt_api.h
                    gotDisconnected(gatt = gatt, failed = status in 0x80..0x89)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "Services discovered: ${gatt.services.map { it.uuid }}")
            val notificationService = gatt.services.firstOrNull {
                it.uuid.toString() == GotwayAndVeteranParser.SERVICE_UUID
            }

            // service discovery failed to find the one we're interested in
            if (notificationService == null) {
                destroyGattClient()
                return
            }

            gotwayAndVeteranParser = GotwayAndVeteranParser(
                wheelData,
                parserConfigFlow,
                gatt.device.address
            )
            enableGotwayAndVeteranNotification()
            connectionState = BleConnectionState.CONNECTED_READY
            appLog.log("Successful connection to $deviceName (${gatt.device.address})")
        }

        var id = 0L
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val charUUID = characteristic.uuid.toString()
            val charValue = characteristic.value ?: return

            gotwayAndVeteranParser?.notificationData(charValue)

            runBlocking {
                _notifiedCharacteristic.emit(NotifiedCharacteristic(charUUID, charValue, id))
            }
            id++
        }
    }

    private fun gotDisconnected(gatt: BluetoothGatt? = null, failed: Boolean = false) {
        gotwayAndVeteranParser?.stop()
        gotwayAndVeteranParser = null
        wheelData.clear()
        gotwayAndVeteranParser?.stop()

        // disable notifications
        disableGattNotifications()

        if (shouldStayConnected) {
            connectionState = BleConnectionState.DISCONNECTED_RECONNECTING
            gatt?.let {
                if (failed) appLog.log("Connection attempt failed")

                appLog.log("Attempt to reconnect")
                val address = it.device.address
                destroyGattClient()
                doConnect(btManager.adapter.getRemoteDevice(address))
            }
        } else {
            connectionState = BleConnectionState.DISCONNECTED
            destroyGattClient()
        }
    }

    private fun destroyGattClient() = gattClient?.let {
        Log.d(TAG, "Destroy current GATT client")
        gattConnected = false
        it.disconnect()
        it.close()
        gattClient = null
    }

    private val btStateChangeReceiver = object : BroadcastReceiver() {
        var registered = false
        override fun onReceive(context: Context, intent: Intent) {

            when (intent.extras?.get(BluetoothAdapter.EXTRA_STATE)) {
                BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth adapter off")
                    connectionState = BleConnectionState.BLUETOOTH_OFF
                    destroyGattClient()
                    gotDisconnected()
                }

                BluetoothAdapter.STATE_ON -> {
                    val addrToReconnect =
                        findReconnectWheel.reconnectToAddr ?: deviceFound?.device?.address

                    Log.i(TAG, "Bluetooth adapter ON, addrToReconnect: $addrToReconnect")

                    addrToReconnect?.let { addr ->
                        Log.i(
                            TAG, "Bluetooth adapter on, try to reconnect " +
                                    "to previous device by scanning for $addr"
                        )
                        findReconnectWheel.findAndReconnect(context, addr)
                    }
                }
            }
        }
    }

    private val notificationChar
        get() = gattClient?.let { gattClient ->
            gattClient
                .getService(UUID.fromString(GotwayAndVeteranParser.SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(GotwayAndVeteranParser.DATA_CHARACTERISTIC_UUID))
        }

    private fun enableGotwayAndVeteranNotification() {
        Log.i(TAG, "Enable Gotway/Veteran notification")
        gattClient?.setCharacteristicNotificationAndDescriptor(notificationChar, true)
    }

    private fun disableGattNotifications() {
        Log.i(TAG, "Disable Gotway/Veteran notification")
        gattClient?.setCharacteristicNotificationAndDescriptor(notificationChar, true)
    }

    private fun logConnectionStateChange(text: String, status: Int) =
        Log.i(TAG, "onConnectionStateChange: $text, status: 0x${status.toString(16)}")

    companion object {
        private const val TAG = "WheelConnection"
    }
}
