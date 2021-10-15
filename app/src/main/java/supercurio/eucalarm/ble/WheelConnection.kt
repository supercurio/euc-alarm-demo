package supercurio.eucalarm.ble

import android.bluetooth.*
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import supercurio.eucalarm.data.WheelDataInterface
import supercurio.eucalarm.oems.GotwayWheel
import supercurio.eucalarm.oems.VeteranWheel
import supercurio.eucalarm.power.PowerManagement
import java.util.*

class WheelConnection(
    private val wheelData: WheelDataInterface,
    private val powerManagement: PowerManagement
) {
    private var connectionState = BleConnectionState.UNKNOWN
        set(value) {
            Log.i(TAG, "ConnectionState change: $value")
            field = value
            _connectionStateFlow.value = value
        }

    private var shouldStayConnected = false

    private var _gatt: BluetoothGatt? = null
    private var notificationChar: BluetoothGattCharacteristic? = null
    private var gotwayWheel: GotwayWheel? = null
    private var veteranWheel: VeteranWheel? = null
    private var deviceFound: DeviceFound? = null

    val gatt get() = _gatt
    val advertisement get() = deviceFound?.scanRecord
    val device get() = _gatt?.device

    // Flows
    private val _notifiedCharacteristic = MutableSharedFlow<NotifiedCharacteristic>()
    val notifiedCharacteristic = _notifiedCharacteristic.asSharedFlow()
    private val _connectionStateFlow = MutableStateFlow(BleConnectionState.UNKNOWN)
    val connectionStateFlow = _connectionStateFlow.asStateFlow()


    fun connectDevice(context: Context, deviceFound: DeviceFound) {
        this.deviceFound = deviceFound
        Log.i(TAG, "connectDevice()")
        shouldStayConnected = true
        powerManagement.getLock(TAG)

        val btManager = context.getSystemService<BluetoothManager>()!!

        when (btManager.getConnectionState(deviceFound.device, BluetoothProfile.GATT)) {
            // already connected: connect if the connection is not already ready and we're not
            // trying to reconnect already
            BluetoothGatt.STATE_CONNECTED -> {
                if (_gatt == null) {
                    connectionState = BleConnectionState.SYSTEM_ALREADY_CONNECTED
                    connectToDeviceFound(context, deviceFound.device)
                }
            }

            BluetoothGatt.STATE_CONNECTING -> Log.i(TAG, "Device already connecting")

            // disconnecting or disconnected, connect again
            BluetoothGatt.STATE_DISCONNECTING, BluetoothGatt.STATE_DISCONNECTED -> {
                connectionState = BleConnectionState.CONNECTING
                connectToDeviceFound(context, deviceFound.device)
            }
        }
    }

    private fun connectToDeviceFound(context: Context, device: BluetoothDevice) {
        _gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnectDevice() {
        if (connectionState == BleConnectionState.DISCONNECTED_RECONNECTING)
            connectionState = BleConnectionState.DISCONNECTED

        shouldStayConnected = false
        powerManagement.removeLock(TAG)
        notificationChar?.apply { gatt?.let { setNotification(it, false) } }
        _gatt?.disconnect()
    }

    fun shutdown() {
        Log.i(TAG, "Shutdown")
        disconnectDevice()
        instance = null
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

                    gotwayWheel = null
                    veteranWheel = null

                    if (shouldStayConnected) {
                        connectionState = BleConnectionState.DISCONNECTED_RECONNECTING
                        Log.i(TAG, "Attempt to reconnect")
                        gatt.connect()
                    } else {
                        connectionState = BleConnectionState.DISCONNECTED
                        _gatt = null
                        notificationChar = null
                        wheelData.clear()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i("GATT", "Services discovered: ${gatt.services.map { it.uuid }}")
            gotwayWheel = GotwayWheel(wheelData)
            veteranWheel = VeteranWheel(wheelData)
            setupGotwayType()
            connectionState = BleConnectionState.CONNECTED_READY
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

        private var instance: WheelConnection? = null

        fun getInstance(wheelData: WheelDataInterface, powerManagement: PowerManagement) =
            instance ?: WheelConnection(wheelData, powerManagement).also { instance = it }
    }
}
