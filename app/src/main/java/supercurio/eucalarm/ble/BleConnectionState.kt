package supercurio.eucalarm.ble

enum class BleConnectionState {
    UNKNOWN,
    DISCONNECTED,
    DISCONNECTING,
    SYSTEM_ALREADY_CONNECTED,
    CONNECTING,
    DISCONNECTED_RECONNECTING,
    CONNECTED,
    CONNECTED_READY,
}
