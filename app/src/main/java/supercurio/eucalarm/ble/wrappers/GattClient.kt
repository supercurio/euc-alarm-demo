package supercurio.eucalarm.ble.wrappers

import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.*
import kotlin.concurrent.withLock

class GattClient(
    context: Context,
    deviceToConnect: BluetoothDevice,
    private val callback: BluetoothGattCallback,
    private val bluetoothAdapterLock: BluetoothAdapterLock,
) {
    private val clientCallback = ClientCallback()
    private val gatt = deviceToConnect.connectGatt(context, false, clientCallback)

    val services: List<BluetoothGattService> get() = gatt.services
    val device: BluetoothDevice get() = gatt.device

    fun readCharacteristic(characteristic: BluetoothGattCharacteristic): ByteArray {
        bluetoothAdapterLock.lock.withLock {
            Log.v(TAG, "readCharacteristic ${characteristic.uuid}")
            gatt.readCharacteristic(characteristic)
            Log.v(TAG, "readCharacteristic started")
            bluetoothAdapterLock.awaitStatus()
        }
        return characteristic.value
    }

    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic) =
        bluetoothAdapterLock.lock.withLock {
            gatt.writeCharacteristic(characteristic)
            bluetoothAdapterLock.awaitStatus()
        }

    fun readDescriptor(descriptor: BluetoothGattDescriptor): ByteArray {
        bluetoothAdapterLock.lock.withLock {
            gatt.readDescriptor(descriptor)
            bluetoothAdapterLock.awaitStatus()
        }
        return descriptor.value
    }

    fun writeDescriptor(descriptor: BluetoothGattDescriptor) =
        bluetoothAdapterLock.lock.withLock {
            gatt.writeDescriptor(descriptor)
            bluetoothAdapterLock.awaitStatus()
        }

    fun disconnect() = gatt.disconnect()
    fun close() = gatt.close()
    fun getService(uuid: UUID): BluetoothGattService? = gatt.getService(uuid)

    fun setCharacteristicNotificationAndDescriptor(
        characteristic: BluetoothGattCharacteristic?,
        status: Boolean
    ) {
        if (characteristic == null) {
            Log.e(TAG, "setCharacteristicNotificationAndDescriptor: missing characteristic")
            return
        }
        gatt.setCharacteristicNotification(characteristic, status)

        val desc = characteristic.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))
        desc.value = if (status)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else
            BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(desc)
    }

    fun setCharacteristicNotification(
        characteristic: BluetoothGattCharacteristic,
        status: Boolean
    ) = gatt.setCharacteristicNotification(characteristic, status)


    inner class ClientCallback : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            callback.onConnectionStateChange(gatt, status, newState)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            callback.onServicesDiscovered(gatt, status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            callback.onCharacteristicChanged(gatt, characteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.v(TAG, "onCharacteristicRead ${characteristic.uuid}, status: $status")
            bluetoothAdapterLock.signalAll(status)
            callback.onCharacteristicRead(gatt, characteristic, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            bluetoothAdapterLock.signalAll(status)
            callback.onCharacteristicWrite(gatt, characteristic, status)
        }

        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            bluetoothAdapterLock.signalAll(status)
            callback.onDescriptorRead(gatt, descriptor, status)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            bluetoothAdapterLock.signalAll(status)
            callback.onDescriptorWrite(gatt, descriptor, status)
        }
    }

    companion object {
        private const val TAG = "GattClient"
        private const val CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    }
}
