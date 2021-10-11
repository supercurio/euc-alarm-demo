package supercurio.eucalarm.utils

import android.os.SystemClock
import com.google.protobuf.Timestamp
import com.google.protobuf.timestamp
import kotlinx.coroutines.delay
import supercurio.wheeldata.recording.GattNotification


object TimeUtils {

    private const val NANOS_PER_SECOND = 1_000_000_000
    private const val NANOS_PER_MS = 1_000_000

    private fun timestampFromNanos(nanoseconds: Long) = timestamp {
        seconds = nanoseconds / NANOS_PER_SECOND
        nanos = (nanoseconds % NANOS_PER_SECOND).toInt()
    }

    fun timestampSinceNanos(nanoseconds: Long) =
        timestampFromNanos(SystemClock.elapsedRealtimeNanos() - nanoseconds)

    private fun timestampToNanos(timestamp: Timestamp) =
        (timestamp.seconds * NANOS_PER_SECOND + timestamp.nanos)

    fun timestampNow() = NowAndTimestamp(
        timestamp {
            val currentMs = System.currentTimeMillis()
            seconds = currentMs / 1000
            nanos = (currentMs * NANOS_PER_MS % NANOS_PER_SECOND).toInt()
        },
        SystemClock.elapsedRealtimeNanos()
    )

    suspend fun delayFromNotification(nanoStart: Long, notification: GattNotification) {
        val notifNanos = timestampToNanos(notification.elapsedTimestamp)
        val elapsed = SystemClock.elapsedRealtimeNanos() - nanoStart
        val delayMs = (notifNanos - elapsed) / 1_000_000
        if (delayMs > 0) delay(delayMs)
    }

    fun Timestamp.toMs() = seconds * 1000 + nanos / NANOS_PER_MS
}

data class NowAndTimestamp(
    val timestamp: Timestamp,
    val nano: Long,
)
