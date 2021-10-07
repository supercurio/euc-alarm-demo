package supercurio.eucalarm.ble

@Suppress("ArrayInDataClass")
data class NotifiedCharacteristic(
    val uuid: String,
    val value: ByteArray,
    val id: Long,
)
