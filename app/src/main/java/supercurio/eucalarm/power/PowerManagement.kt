package supercurio.eucalarm.power

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.core.content.getSystemService

class PowerManagement(context: Context) {

    private val pm = context.getSystemService<PowerManager>()!!
    private val activeWakelocks = mutableMapOf<String, PowerManager.WakeLock>()

    @SuppressLint("WakelockTimeout")
    fun addLock(tag: String) {
        // skip creating a wakelock already existing
        if (activeWakelocks.contains(tag)) return

        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire()
        activeWakelocks[tag] = wakeLock
        Log.i(TAG, "Acquired wakelock for $tag")
    }

    fun removeLock(tag: String) {
        activeWakelocks[tag]?.apply {
            Log.i(TAG, "Release wakelock for $tag")
            release()
        }
        activeWakelocks.remove(tag)
    }

    fun releaseAll() {
        Log.i(TAG, "Release all wakelocks")
        activeWakelocks.forEach { (tag, lock) ->
            Log.i(TAG, "Release $tag wakelock")
            lock.release()
        }
        activeWakelocks.clear()
    }

    companion object {
        private const val TAG = "PowerManagement"

        private var instance: PowerManagement? = null

        fun getInstance(context: Context) =
            instance ?: PowerManagement(context).also { instance = it }
    }
}
