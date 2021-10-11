package supercurio.eucalarm.utils

//import com.google.protobuf.util.JsonFormat
import android.bluetooth.BluetoothGattCharacteristic

fun BluetoothGattCharacteristic.hasNotify() =
    properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0


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
