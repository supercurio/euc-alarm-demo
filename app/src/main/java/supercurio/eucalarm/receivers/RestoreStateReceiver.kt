package supercurio.eucalarm.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import supercurio.eucalarm.di.AppLifecycle
import supercurio.eucalarm.appstate.AppStateStore
import javax.inject.Inject

@AndroidEntryPoint
class RestoreStateReceiver : BroadcastReceiver() {

    @Inject
    lateinit var appStateStore: AppStateStore

    @Inject
    lateinit var appLifecycle: AppLifecycle

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive: ${intent.action}")

        if (!VALID_INTENT_ACTIONS.contains(intent.action)) return

        // skip regular ACTION_BOOT_COMPLETED on newer Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            intent.action == Intent.ACTION_BOOT_COMPLETED
        ) return

        appStateStore.restoreState(appLifecycle)
    }

    companion object {
        private const val TAG = "RestoreStateReceiver"

        private val VALID_INTENT_ACTIONS = mutableListOf(
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_BOOT_COMPLETED,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                add(Intent.ACTION_LOCKED_BOOT_COMPLETED)
        }
    }
}
