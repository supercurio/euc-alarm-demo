package supercurio.eucalarm.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.os.SystemClock


data class DeviceFound(
    val device: BluetoothDevice,
    val from: DeviceFoundFrom = DeviceFoundFrom.Unknown,
    val scanRecord: ScanRecord? = null,
    val rssi: Int? = null,
    val timeStamp: Long = SystemClock.elapsedRealtime()
) {
    val ageMs get() = SystemClock.elapsedRealtime() - timeStamp
    fun expired(maxAgeMs: Long) = when (from) {
        DeviceFoundFrom.Scan, DeviceFoundFrom.AlreadyConnected -> ageMs > maxAgeMs
        else -> false
    }
}

enum class DeviceFoundFrom {
    Unknown,
    Scan,
    AlreadyConnected
}
