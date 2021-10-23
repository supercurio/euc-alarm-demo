package supercurio.eucalarm.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.os.SystemClock


data class DeviceFound(
    val device: BluetoothDevice,
    val from: DeviceFoundFrom = DeviceFoundFrom.UNKNOWN,
    val scanRecord: ScanRecord? = null,
    val rssi: Int? = null,
    val timeStamp: Long = SystemClock.elapsedRealtime()
) {
    private val ageMs get() = SystemClock.elapsedRealtime() - timeStamp
    fun expired(maxAgeMs: Long) = when (from) {
        DeviceFoundFrom.SCAN, DeviceFoundFrom.ALREADY_CONNECTED -> ageMs > maxAgeMs
        else -> false
    }
}

enum class DeviceFoundFrom {
    UNKNOWN,
    SCAN,
    ALREADY_CONNECTED
}
