package supercurio.eucalarm.appstate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        Log.i(TAG, "${intent.action}")

        AppStateStore
            .getInstance(context)
            .restoreState(context)
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
