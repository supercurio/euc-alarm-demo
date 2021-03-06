package supercurio.eucalarm.appstate

sealed class AppState(val name: String)

object OffState : AppState(AppStateStore.OFF_SATE) {
    override fun toString() = name
}

object OnStateDefault : AppState(AppStateStore.ON_SATE_DEFAULT) {
    override fun toString() = name
}

data class ConnectedState(val deviceAddr: String) : AppState(AppStateStore.CONNECTED_STATE)
data class RecordingState(val deviceAddr: String) : AppState(AppStateStore.RECORDING_STATE)
