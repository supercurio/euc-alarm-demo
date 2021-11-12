package supercurio.eucalarm.appstate

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import supercurio.eucalarm.di.AppLifecycle
import supercurio.eucalarm.log.AppLog
import supercurio.eucalarm.utils.directBootContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStateStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val appLog: AppLog
) {
    private val prefs: SharedPreferences = appContext
        .directBootContext
        .getSharedPreferences("app_state", Context.MODE_PRIVATE)

    var appState: AppState = OnStateDefault
        private set

    fun setState(value: AppState) {
        val prevState = appState
        appState = value

        if (value != prevState)
            prefs.edit(commit = true) {
                clear()
                when (value) {
                    is OffState -> putString(OFF_SATE, "")
                    is OnStateDefault -> putString(ON_SATE_DEFAULT, "")
                    is ConnectedState -> putString(CONNECTED_STATE, value.deviceAddr)
                    is RecordingState -> putString(RECORDING_STATE, value.deviceAddr)
                }
            }
        Log.i(TAG, "State set to $value")
    }

    fun restoreState(appLifecycle: AppLifecycle) {
        loadState()
        Log.i(TAG, "Restore state")

        appLog.log("Restore state: $appState")

        when (appState) {
            is OffState -> appLifecycle.off()
            is OnStateDefault, is ConnectedState, is RecordingState -> appLifecycle.on()
        }
    }

    fun loadState(): AppState {
        prefs.all.forEach { (key, value) ->
            appState = when (key) {
                OFF_SATE -> OffState
                ON_SATE_DEFAULT -> OnStateDefault
                CONNECTED_STATE -> ConnectedState(value.toString())
                RECORDING_STATE -> RecordingState(value.toString())
                else -> OnStateDefault
            }
        }
        Log.i(TAG, "Loaded state: $appState")
        return appState
    }

    companion object {
        private const val TAG = "AppStateStore"

        const val OFF_SATE = "off"
        const val ON_SATE_DEFAULT = "on"
        const val CONNECTED_STATE = "connected"
        const val RECORDING_STATE = "recording"
    }
}
