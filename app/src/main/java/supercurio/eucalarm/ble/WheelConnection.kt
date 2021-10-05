package supercurio.eucalarm.ble

import android.bluetooth.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import supercurio.eucalarm.data.WheelData
import supercurio.eucalarm.oems.GotwayWheel
import supercurio.eucalarm.oems.VeteranWheel
import java.util.*

class WheelConnection(val wheelData: WheelData) {

    private var bleGatt: BluetoothGatt? = null
    private var notificationChar: BluetoothGattCharacteristic? = null
    private val gotwayWheel = GotwayWheel(wheelData)
    private val veteranWheel = VeteranWheel(wheelData)

    private val _changedCharacteristic = MutableStateFlow<BluetoothGattCharacteristic?>(null)
    val changedCharacteristic = _changedCharacteristic.asStateFlow()

    val bleConnectionReady = MutableStateFlow(false)

    val gatt
        get() = bleGatt

    val device
        get() = bleGatt?.device

    fun connectDevice(context: Context, device: BluetoothDevice) {
        Log.i(TAG, "connectDevice()")
        bleGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnectDevice() {
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
                    bleGatt = null
                    bleConnectionReady.value = false
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i("GATT", "Services discovered: ${gatt.services.map { it.uuid }}")
            setupGotwayType()
            bleConnectionReady.value = true
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val values = characteristic.value ?: return

            gotwayWheel.findFrame(values)
            veteranWheel.findFrame(values)

            _changedCharacteristic.value = characteristic
        }
    }

    fun setupGotwayType() {
        Log.i(TAG, "Setup wheel")
        bleGatt?.let { gatt ->
            val service = gatt.getService(UUID.fromString(GotwayWheel.SERVICE_UUID))
            notificationChar = service.getCharacteristic(
                UUID.fromString(
                    GotwayWheel.DATA_CHARACTERISTIC_UUID
                )
            )?.apply {
                Log.i(TAG, "char uuid: ${this.uuid}")
                val desc = getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
                gatt.setCharacteristicNotification(this, true)
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }
    }

    companion object {
        private const val TAG = "WheelConnection"
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
