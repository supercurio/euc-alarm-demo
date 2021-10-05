package supercurio.eucalarm.utils

import android.os.SystemClock
import com.google.protobuf.Timestamp
import com.google.protobuf.timestamp
import kotlinx.coroutines.delay
import supercurio.wheeldata.recording.GattNotification


object TimeUtils {

    private const val NANOS_PER_SECOND = 1_000_000_000

    private fun timestampFromNanos(nanoseconds: Long) = timestamp {
        seconds = nanoseconds / NANOS_PER_SECOND
        nanos = (nanoseconds % NANOS_PER_SECOND).toInt()
    }

    fun timestampSinceNanos(nanoseconds: Long) =
        timestampFromNanos(SystemClock.elapsedRealtimeNanos() - nanoseconds)

    private fun timestampToNanos(timestamp: Timestamp) =
        (timestamp.seconds * NANOS_PER_SECOND + timestamp.nanos)

    suspend fun delayFromNotification(nanoStart: Long, notification: GattNotification) {
        val notifNanos = timestampToNanos(notification.timestamp)
        val elapsed = SystemClock.elapsedRealtimeNanos() - nanoStart
        val delayMs = (notifNanos - elapsed) / 1_000_000
        if (delayMs > 0) delay(delayMs)
    }
}
