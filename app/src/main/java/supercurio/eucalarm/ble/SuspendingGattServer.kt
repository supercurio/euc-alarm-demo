package supercurio.eucalarm.ble

import android.bluetooth.*
import android.content.Context
import androidx.core.content.getSystemService
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SuspendingGattServer(context: Context, private val callback: BluetoothGattServerCallback) {

    inner class ServerCallback : BluetoothGattServerCallback() {
        var continuation: Continuation<Int>? = null

        private fun resumeContinuation(status: Int) = continuation?.apply {
            continuation = null
            resume(status)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            resumeContinuation(status)
            callback.onServiceAdded(status, service)
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            callback.onConnectionStateChange(device, status, newState)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) = callback.onCharacteristicReadRequest(device, requestId, offset, characteristic)

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            callback.onCharacteristicWriteRequest(
                device,
                requestId,
                characteristic,
                preparedWrite,
                responseNeeded,
                offset,
                value
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) = callback.onDescriptorWriteRequest(
            device,
            requestId,
            descriptor,
            preparedWrite,
            responseNeeded,
            offset,
            value
        )

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) = callback.onDescriptorReadRequest(device, requestId, offset, descriptor)


        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            resumeContinuation(status)
            callback.onNotificationSent(device, status)
        }
    }

    private val serverCallback = ServerCallback()

    private val btManager = context.getSystemService<BluetoothManager>()!!
    private val server: BluetoothGattServer = btManager.openGattServer(context, serverCallback)

    val services: List<BluetoothGattService>
        get() = server.services

    suspend fun addService(bluetoothGattService: BluetoothGattService) =
        suspendCoroutine<Int> { cont ->
            serverCallback.continuation = cont
            server.addService(bluetoothGattService)
        }

    suspend fun notifyCharacteristicChanged(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        confirm: Boolean
    ) = suspendCoroutine<Int> { cont ->
        serverCallback.continuation = cont
        server.notifyCharacteristicChanged(device, characteristic, confirm)
    }

    fun sendSuccess(device: BluetoothDevice?, requestId: Int) =
        server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)

    fun sendResponse(
        device: BluetoothDevice?, requestId: Int, status: Int, offset: Int, value: ByteArray?
    ) = server.sendResponse(device, requestId, status, offset, value)

    fun cancelConnection(device: BluetoothDevice) = server.cancelConnection(device)
    fun clearServices() = server.clearServices()
    fun close() = server.close()

}
