package supercurio.eucalarm.utils

import android.util.Log
//import com.google.protobuf.util.JsonFormat
import android.bluetooth.BluetoothGattCharacteristic
import supercurio.wheeldata.recording.BleDeviceInfo
import supercurio.wheeldata.recording.GattNotification
import supercurio.wheeldata.recording.messageType
import java.io.BufferedOutputStream

fun Any.writeWireMessageTo(out: BufferedOutputStream) {

    val message = when (val src = this) {
        is BleDeviceInfo -> messageType { bleDeviceInfo = src }
        is GattNotification -> messageType { gattNotification = src }
        else -> null
    } ?: error("Unsupported message type")

    message.writeDelimitedTo(out)
    out.flush()

//    Log.i(
//        "Message", JsonFormat.printer()
//            .includingDefaultValueFields()
//            .print(message)
//    )
}

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
