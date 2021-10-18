package supercurio.eucalarm.appstate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ForceCrash : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        error("Forcing app crash")
    }
}
