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
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.oems.GotwayWheel
import supercurio.eucalarm.oems.VeteranWheel
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
) {
    private var connectionState = BleConnectionState.UNKNOWN
        set(value) {
            Log.i(TAG, "ConnectionState change: $value")
            field = value
            _connectionStateFlow.value = value
        }

    private val findReconnectWheel = FindReconnectWheel(this)

    private var shouldStayConnected = false

    private var _gatt: BluetoothGatt? = null
    private var notificationChar: BluetoothGattCharacteristic? = null
    private var gotwayWheel: GotwayWheel? = null
    private var veteranWheel: VeteranWheel? = null
    private var deviceFound: DeviceFound? = null

    val gatt get() = _gatt
    val device get() = _gatt?.device
    val advertisement get() = deviceFound?.scanRecord
    val deviceName
        get() = device?.name ?: devicesNamesCache[device?.address
            ?: deviceFound?.device?.address
            ?: findReconnectWheel.reconnectToAddr
        ]

    // Flows
    private val _notifiedCharacteristic = MutableSharedFlow<NotifiedCharacteristic>()
    val notifiedCharacteristic = _notifiedCharacteristic.asSharedFlow()
    private val _connectionStateFlow = MutableStateFlow(BleConnectionState.UNKNOWN)
    val connectionStateFlow = _connectionStateFlow.asStateFlow()

    fun reconnectDevice(deviceAddr: String) {
        registerBtStateChangeReceiver()
        findReconnectWheel.reconnectToAddr = deviceAddr // to help deviceName resolve
        connectionState = BleConnectionState.SCANNING
        findReconnectWheel.findAndReconnect(context, deviceAddr)
    }

    fun connectDevice(inputDeviceToConnect: DeviceFound) {
        Log.i(TAG, "connectDevice($inputDeviceToConnect)")
        findReconnectWheel.stopLeScan()

        // set app state
        appStateStore.setState(ConnectedState(inputDeviceToConnect.device.address))

        inputDeviceToConnect.scanRecord?.let {
            devicesNamesCache.remember(inputDeviceToConnect.device.address, it)
        }

        // TODO: check if really necessary
        // get a fresh BluetoothDevice form the adapter
        val deviceToConnect = DeviceFound(
            device = BluetoothAdapter
                .getDefaultAdapter()
                .getRemoteDevice(inputDeviceToConnect.device.address),
            scanRecord = inputDeviceToConnect.scanRecord
        )

        this.deviceFound = deviceToConnect
        shouldStayConnected = true
        powerManagement.getLock(TAG)

        registerBtStateChangeReceiver()

        val btManager = context.getSystemService<BluetoothManager>()!!

        when (btManager.getConnectionState(deviceToConnect.device, BluetoothProfile.GATT)) {
            // already connected: connect if the connection is not already ready and we're not
            // trying to reconnect already
            BluetoothGatt.STATE_CONNECTED -> {
                if (_gatt == null) {
                    connectionState = BleConnectionState.SYSTEM_ALREADY_CONNECTED
                    doConnect(deviceToConnect.device)
                }
            }

            BluetoothGatt.STATE_CONNECTING -> Log.i(TAG, "Device already connecting")

            // disconnecting or disconnected, connect again
            BluetoothGatt.STATE_DISCONNECTING, BluetoothGatt.STATE_DISCONNECTED -> {
                connectionState = BleConnectionState.CONNECTING
                doConnect(deviceToConnect.device)
            }
        }
    }

    fun disconnectDevice() {
        appStateStore.setState(OnStateDefault)

        if (connectionState == BleConnectionState.DISCONNECTED_RECONNECTING)
            connectionState = BleConnectionState.DISCONNECTED

        if (btStateChangeReceiver.registered) {
            btStateChangeReceiver.registered = false
            context.unregisterReceiver(btStateChangeReceiver)
        }

        findReconnectWheel.stopLeScan()

        shouldStayConnected = false
        powerManagement.removeLock(TAG)
        notificationChar?.apply { gatt?.let { setNotification(it, false) } }
        _gatt?.disconnect()
    }

    fun setReplayState(state: Boolean) {
        connectionState = if (state)
            BleConnectionState.REPLAY
        else
            BleConnectionState.UNKNOWN
    }

    fun shutdown() {
        Log.i(TAG, "Shutdown")
        disconnectDevice()
    }

    fun setupGotwayType() {
        Log.i(TAG, "Setup connection")
        _gatt?.let { gatt ->
            val service = gatt.getService(UUID.fromString(GotwayWheel.SERVICE_UUID))
            notificationChar = service.getCharacteristic(
                UUID.fromString(GotwayWheel.DATA_CHARACTERISTIC_UUID)
            )?.apply {
                Log.i(TAG, "char uuid: ${this.uuid}")
                setNotification(gatt, true)
            }
        }
    }

    private fun doConnect(device: BluetoothDevice) {
        _gatt = device.connectGatt(context, false, gattCallback)
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
                    connectionState = BleConnectionState.CONNECTING
                    Log.i(TAG, "onConnectionStateChange: STATE_CONNECTING")
                }

                BluetoothGatt.STATE_DISCONNECTING -> {
                    connectionState = BleConnectionState.DISCONNECTING
                    Log.i(TAG, "onConnectionStateChange: STATE_DISCONNECTING")
                }

                BluetoothGatt.STATE_CONNECTED -> {
                    connectionState = BleConnectionState.CONNECTED
                    Log.i(TAG, "onConnectionStateChange: STATE_CONNECTED")
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.i(TAG, "onConnectionStateChange: STATE_DISCONNECTED")
                    gotDisconnected(gatt)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i("GATT", "Services discovered: ${gatt.services.map { it.uuid }}")
            gotwayWheel = GotwayWheel(wheelData)
            veteranWheel = VeteranWheel(wheelData)
            setupGotwayType()
            connectionState = BleConnectionState.RECEIVING_DATA
        }

        var id = 0L
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val charUUID = characteristic.uuid.toString()
            val charValue = characteristic.value ?: return

            gotwayWheel?.findFrame(charValue)
            veteranWheel?.findFrame(charValue)

            runBlocking {
                _notifiedCharacteristic.emit(NotifiedCharacteristic(charUUID, charValue, id))
            }
            id++
        }
    }

    private fun gotDisconnected(gatt: BluetoothGatt? = null) {
        gotwayWheel = null
        veteranWheel = null
        wheelData.clear()

        if (shouldStayConnected) {
            connectionState = BleConnectionState.DISCONNECTED_RECONNECTING
            gatt?.let {
                Log.i(TAG, "Attempt to reconnect")
                it.connect()
            }
        } else {
            connectionState = BleConnectionState.DISCONNECTED
            _gatt = null
            notificationChar = null
            wheelData.clear()
        }
    }

    private val btStateChangeReceiver = object : BroadcastReceiver() {
        var registered = false
        override fun onReceive(context: Context, intent: Intent) {

            when (intent.extras?.get(BluetoothAdapter.EXTRA_STATE)) {
                BluetoothAdapter.STATE_OFF -> {
                    Log.i(TAG, "Bluetooth adapter off")
                    connectionState = BleConnectionState.BLUETOOTH_OFF
                    _gatt?.disconnect()
                    _gatt = null
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

    private fun BluetoothGattCharacteristic.setNotification(gatt: BluetoothGatt, status: Boolean) {
        gatt.setCharacteristicNotification(this, status)

        val desc = getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
        desc.value = if (status)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(desc)
    }

    companion object {
        private const val TAG = "WheelConnection"
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"

    }
}
