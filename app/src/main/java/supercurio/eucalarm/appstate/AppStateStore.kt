package supercurio.eucalarm.appstate

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import supercurio.eucalarm.AppLifecycle
import supercurio.eucalarm.utils.directBootContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStateStore @Inject constructor(@ApplicationContext private val appContext: Context) {
    private val prefs: SharedPreferences = appContext
        .directBootContext
        .getSharedPreferences("app_state", Context.MODE_PRIVATE)

    var appState: AppState = UndefinedState
        private set

    fun setState(value: AppState) {
        appState = value

        prefs.edit(commit = true) {
            clear()
            when (value) {
                is ClosedState -> putString(CLOSED_SATE, "")
                is ConnectedState -> putString(CONNECTED_STATE, value.deviceAddr)
                is RecordingState -> putString(RECORDING_STATE, value.deviceAddr)
                else -> putString(UNDEFINED_SATE, "")
            }
        }
        Log.i(TAG, "State set to $value")
    }

    fun restoreState(appLifecycle: AppLifecycle) {
        loadState()
        Log.i(TAG, "Restore state")

        when (appState) {
            is ClosedState -> appLifecycle.off()
            is ConnectedState -> appLifecycle.on()
            is RecordingState -> appLifecycle.on()
            else -> Unit
        }
    }

    fun loadState(): AppState {
        prefs.all.forEach { (key, value) ->
            appState = when (key) {
                CLOSED_SATE -> ClosedState
                CONNECTED_STATE -> ConnectedState(value.toString())
                RECORDING_STATE -> RecordingState(value.toString())
                else -> UndefinedState
            }
        }
        Log.i(TAG, "Loaded state: $appState")
        return appState
    }

    companion object {
        private const val TAG = "AppStateStore"

        const val UNDEFINED_SATE = "undefined"
        const val CLOSED_SATE = "closed"
        const val CONNECTED_STATE = "connected"
        const val RECORDING_STATE = "recording"
    }
}
