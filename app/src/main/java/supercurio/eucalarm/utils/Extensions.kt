package supercurio.eucalarm.utils

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.os.Build

fun BluetoothGattCharacteristic.hasNotify() =
    properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0

fun ByteArray.toHexString() = asList().toHexString()
fun List<Byte>.toHexString() = map { String.format("%02x", it) }

fun <K, V> Map<K, V>.reversed() = HashMap<V, K>().also { newMap ->
    entries.forEach { newMap[it.value] = it.key }
}

fun ByteArray.showBuffer(): String {
    val sb = StringBuilder()
    sb.append(indices.joinToString(" ") { String.format("%02d", it) })
    sb.append("\n")
    sb.append(joinToString(" ") { String.format("%02x", it) })
    return sb.toString()
}

val Context.directBootContext: Context
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        createDeviceProtectedStorageContext()
    } else this
