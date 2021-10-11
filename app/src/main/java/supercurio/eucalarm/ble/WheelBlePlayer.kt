package supercurio.eucalarm.ble

import android.os.SystemClock
import android.util.Log
//import com.google.protobuf.util.JsonFormat
import kotlinx.coroutines.CoroutineScope
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.oems.GotwayWheel
import supercurio.eucalarm.oems.VeteranWheel
import supercurio.eucalarm.utils.TimeUtils
import supercurio.wheeldata.recording.RecordingMessageType
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

    suspend fun decode(wheelDataStateFlows: WheelDataStateFlows) {
        val gotwayWheel = GotwayWheel(wheelDataStateFlows)
        val veteranWheel = VeteranWheel(wheelDataStateFlows)
        val nanoStart = SystemClock.elapsedRealtimeNanos()

        playing = true

        while (input.available() > 0 && playing) {
            val message = RecordingMessageType.parseDelimitedFrom(input)

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

        wheelDataStateFlows.clear()
    }

    fun stop() {
        playing = false
    }

    companion object {
        private const val TAG = "WheelBlePlayer"
    }
}
