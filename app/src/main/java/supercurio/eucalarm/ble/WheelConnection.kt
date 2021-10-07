package supercurio.eucalarm.ble

import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import supercurio.eucalarm.data.WheelData
import supercurio.eucalarm.oems.GotwayWheel
import supercurio.eucalarm.oems.VeteranWheel
import java.util.*

class WheelConnection(val wheelData: WheelData) {

    private var shouldStayConnected = false

    private var bleGatt: BluetoothGatt? = null
    private var notificationChar: BluetoothGattCharacteristic? = null
    private var gotwayWheel : GotwayWheel? = null
    private var veteranWheel : VeteranWheel? = null

    // Flows
    private val _notifiedCharacteristic = MutableSharedFlow<NotifiedCharacteristic>()
    private val _connectionLost = MutableStateFlow(false)

    val notifiedCharacteristic = _notifiedCharacteristic.asSharedFlow()
    val connectionLost = _connectionLost.asStateFlow()

    val bleConnectionReady = MutableStateFlow(false)

    val gatt
        get() = bleGatt

    val device
        get() = bleGatt?.device

    fun connectDevice(context: Context, device: BluetoothDevice) {
        Log.i(TAG, "connectDevice()")
        shouldStayConnected = true
        bleGatt?.connect() ?: run {
            bleGatt = device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnectDevice() {
        shouldStayConnected = false
        notificationChar?.let {
            bleGatt?.setCharacteristicNotification(it, false)
        }
        bleGatt?.disconnect()
    }

    fun isConnected() = bleGatt != null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTING -> Log.i(TAG, "STATE_CONNECTING")
                BluetoothGatt.STATE_DISCONNECTING -> Log.i(TAG, "STATE_DISCONNECTING")
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i(TAG, "STATE_CONNECTED")
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.i(TAG, "STATE_DISCONNECTED")
                    bleConnectionReady.value = false

                    gotwayWheel = null
                    veteranWheel = null

                    if (shouldStayConnected) {
                        _connectionLost.value = true
                        Log.i(TAG, "Attempt to reconnect")
                        gatt.connect()
                    } else {
                        bleGatt = null
                        notificationChar = null
                        wheelData.clear()
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i("GATT", "Services discovered: ${gatt.services.map { it.uuid }}")
            setupGotwayType()
            gotwayWheel = GotwayWheel(wheelData)
            veteranWheel = VeteranWheel(wheelData)
            bleConnectionReady.value = true
            _connectionLost.value = false
        }

        var id = 0L
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val charUUID = characteristic.toString()
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
        Log.i(TAG, "Setup wheel")
        bleGatt?.let { gatt ->
            val service = gatt.getService(UUID.fromString(GotwayWheel.SERVICE_UUID))
            notificationChar = service.getCharacteristic(
                UUID.fromString(GotwayWheel.DATA_CHARACTERISTIC_UUID)
            )?.apply {
                Log.i(TAG, "char uuid: ${this.uuid}")
                gatt.setCharacteristicNotification(this, true)
                val desc = getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }
    }

    companion object {
        private const val TAG = "WheelConnection"
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"

        private var instance: WheelConnection? = null

        fun getInstance(wheelData: WheelData) = instance ?: WheelConnection(wheelData).also {
            instance = it
        }
    }
}
