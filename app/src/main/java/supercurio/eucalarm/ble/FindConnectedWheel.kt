package supercurio.eucalarm.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import supercurio.eucalarm.oems.GotwayWheel

class FindConnectedWheel(
    private val context: Context,
    foundWheelCallback: (DeviceFound) -> Unit
) {

    /**
     * Find already connected wheel
     */

    fun find() = searchConnectedWheel()

    private fun searchConnectedWheel() {
        context.getSystemService<BluetoothManager>()!!
            .getConnectedDevices(BluetoothProfile.GATT)
            .forEach {
                it.connectGatt(context, false, connectedDeviceGattCallback)
            }
    }

    private val connectedDeviceGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: ${gatt.device.name}, newState: $newState")
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i(TAG, "STATE_CONNECTED")
                    gatt.discoverServices()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered: ${gatt.device.name}")
            gatt.services.firstOrNull {
                it.uuid.toString() == GotwayWheel.SERVICE_UUID
            }?.let {
                foundWheelCallback(DeviceFound(gatt.device, null))
            }
        }
    }

    companion object {
        private const val TAG = "FindConnectedWheel"
    }
}
