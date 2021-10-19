package supercurio.eucalarm.appstate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class RestoreStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!VALID_INTENT_ACTIONS.contains(intent.action)) return

        // skip regular ACTION_BOOT_COMPLETED on newer Android versions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "${intent.action}")

        AppStateStore
            .getInstance(context)
            .restoreState(context)
    }

    companion object {
        private const val TAG = "RestoreStateReceiver"

        private val VALID_INTENT_ACTIONS = mutableListOf(
            Intent.ACTION_MY_PACKAGE_REPLACED
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                add(Intent.ACTION_LOCKED_BOOT_COMPLETED)

        }
    }
}
