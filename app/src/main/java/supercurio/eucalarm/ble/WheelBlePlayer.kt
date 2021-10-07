package supercurio.eucalarm.ble

import android.os.SystemClock
import android.util.Log
//import com.google.protobuf.util.JsonFormat
import kotlinx.coroutines.CoroutineScope
import supercurio.eucalarm.data.WheelData
import supercurio.eucalarm.oems.GotwayWheel
import supercurio.eucalarm.oems.VeteranWheel
import supercurio.eucalarm.utils.TimeUtils
import supercurio.wheeldata.recording.MessageType
import java.io.InputStream

class WheelBlePlayer(private val input: InputStream, val scope: CoroutineScope) {

    private val characteristicsKeys = CharacteristicsKeys()
    private var playing = false

    fun printAsJson() {
        Log.i(TAG, "Record size: ${input.available()}")

//        while (input.available() > 0) {
//            Log.i(TAG, JsonFormat.printer().print(MessageType.parseDelimitedFrom(input)))
//        }
    }

    suspend fun decode(wheelData: WheelData) {
        val gotwayWheel = GotwayWheel(wheelData)
        val veteranWheel = VeteranWheel(wheelData)
        val nanoStart = SystemClock.elapsedRealtimeNanos()

        playing = true

        while (input.available() > 0 && playing) {
            val message = MessageType.parseDelimitedFrom(input)

            when {
                message.hasBleDeviceInfo() ->
                    characteristicsKeys.fromDeviceInfo(message.bleDeviceInfo)

                message.hasGattNotification() -> {
                    val notification = message.gattNotification

                    TimeUtils.delayFromNotification(nanoStart, notification)

                    val bytes = notification.bytes.toByteArray()
                    gotwayWheel.findFrame(bytes)
                    veteranWheel.findFrame(bytes)
                }
            }
        }

        wheelData.clear()
    }

    fun stop() {
        playing = false
    }

    companion object {
        private const val TAG = "WheelBlePlayer"
    }
}
