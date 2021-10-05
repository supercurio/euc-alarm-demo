package supercurio.eucalarm.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService

data class WheelBleDeviceInfo(
    val address: String,
    val name: String,
    val services: List<BluetoothGattService>
) {
    companion object {
        fun fromGatt(gatt: BluetoothGatt) = WheelBleDeviceInfo(
            address = gatt.device.address,
            name = gatt.device.name ?: "name-missing",
            services = gatt.services
        )
    }
}
