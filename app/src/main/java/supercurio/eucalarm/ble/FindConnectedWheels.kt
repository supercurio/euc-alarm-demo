package supercurio.eucalarm.ble

import android.bluetooth.*
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.*
import supercurio.eucalarm.oems.GotwayWheel
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class FindConnectedWheels(
    private val context: Context,
    foundWheelCallback: (DeviceFound) -> Unit
) {
    private val scope = MainScope() + CoroutineName(TAG)

    /**
     * Find already connected wheel
     */

    fun find() = searchConnectedWheel()

    private fun searchConnectedWheel() = scope.launch {
        context.getSystemService<BluetoothManager>()!!
            .getConnectedDevices(BluetoothProfile.GATT)
            .forEach { connectAndCheckDevice(it) }
    }

    private suspend fun connectAndCheckDevice(device: BluetoothDevice) = withTimeout(TIMEOUT) {
        suspendCancellableCoroutine<Unit> { cont ->
            connectedDeviceGattCallback.continuation = cont
            device.connectGatt(context, false, connectedDeviceGattCallback)
        }
    }

    private val connectedDeviceGattCallback = object : BluetoothGattCallback() {
        var continuation: Continuation<Unit>? = null

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: ${gatt.device.name}, newState: $newState")
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i(TAG, "${gatt.device.address} (${gatt.device.name}) connected")
                    gatt.discoverServices()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered: ${gatt.device.address} (${gatt.device.name})")
            gatt.services.firstOrNull {
                it.uuid.toString() == GotwayWheel.SERVICE_UUID
            }?.let {
                foundWheelCallback(DeviceFound(gatt.device, DeviceFoundFrom.ALREADY_CONNECTED, null))
            }

            gatt.close()
            continuation?.resume(Unit)
            continuation = null
        }
    }

    fun stop() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "FindConnectedWheels"
        private const val TIMEOUT = 5000L
    }
}
