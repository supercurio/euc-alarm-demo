package supercurio.eucalarm.ble.wrappers

import android.bluetooth.*
import android.content.Context
import android.util.Log
import androidx.core.content.getSystemService
import kotlin.concurrent.withLock

class GattServer(
    context: Context, private val callback: BluetoothGattServerCallback,
    private val bluetoothAdapterLock: BluetoothAdapterLock,
) {
    private val serverCallback = ServerCallback()

    private val btManager = context.getSystemService<BluetoothManager>()!!
    private val server: BluetoothGattServer = btManager.openGattServer(context, serverCallback)

    val services: List<BluetoothGattService>
        get() = server.services

    fun addService(bluetoothGattService: BluetoothGattService): Int =
        bluetoothAdapterLock.lock.withLock {
            server.addService(bluetoothGattService)
            return bluetoothAdapterLock.awaitStatus()
        }

    fun notifyCharacteristicChanged(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        confirm: Boolean
    ) = bluetoothAdapterLock.lock.withLock {
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

    fun disconnectAllDevices() {
        btManager
            .getConnectedDevices(BluetoothProfile.GATT_SERVER)
            .forEach {
                Log.i(TAG, "Cancel connection to $it")
                server.cancelConnection(it)
            }
    }

    fun isConnected(device: BluetoothDevice) = btManager
        .getConnectedDevices(BluetoothProfile.GATT_SERVER)
        .contains(device)

    fun removeService(service: BluetoothGattService) = server.removeService(service)

    inner class ServerCallback : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            bluetoothAdapterLock.signalAll(status)
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
            bluetoothAdapterLock.signalAll(status)
            callback.onNotificationSent(device, status)
        }
    }

    companion object {
        private const val TAG = "GattServer"
    }
}
