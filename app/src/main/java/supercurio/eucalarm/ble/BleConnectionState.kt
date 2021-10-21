package supercurio.eucalarm.ble

enum class BleConnectionState(val canDisconnect: Boolean) {
    UNKNOWN(
        canDisconnect = false
    ),

    BLUETOOTH_OFF(
        canDisconnect = false
    ),

    DISCONNECTED(
        canDisconnect = false
    ),

    DISCONNECTING(
        canDisconnect = false
    ),

    SCANNING(
        canDisconnect = false
    ),

    SYSTEM_ALREADY_CONNECTED(
        canDisconnect = false
    ),

    CONNECTING(
        canDisconnect = true
    ),

    DISCONNECTED_RECONNECTING(
        canDisconnect = true
    ),

    CONNECTED(
        canDisconnect = true
    ),

    RECEIVING_DATA(
        canDisconnect = true
    ),

    REPLAY(
        canDisconnect = false
    )
}
