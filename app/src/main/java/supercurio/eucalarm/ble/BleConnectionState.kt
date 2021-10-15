package supercurio.eucalarm.ble

enum class BleConnectionState {
    UNKNOWN,
    SYSTEM_ALREADY_CONNECTED,
    DISCONNECTED,
    DISCONNECTING,
    CONNECTING,
    DISCONNECTED_RECONNECTING,
    CONNECTED,
    CONNECTED_READY,
}
