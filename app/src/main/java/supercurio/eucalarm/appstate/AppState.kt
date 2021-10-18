package supercurio.eucalarm.appstate

sealed class AppState(val name: String)

object UndefinedState : AppState(AppStateStore.UNDEFINED_SATE)
object ClosedState : AppState(AppStateStore.CLOSED_SATE)
data class ConnectedState(val deviceAddr: String) : AppState(AppStateStore.CONNECTED_STATE)
data class RecordingState(val deviceAddr: String) : AppState(AppStateStore.RECORDING_STATE)
