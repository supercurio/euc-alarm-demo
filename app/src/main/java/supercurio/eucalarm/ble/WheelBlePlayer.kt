package supercurio.eucalarm.ble

//import com.google.protobuf.util.JsonFormat
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.oems.GotwayWheel
import supercurio.eucalarm.oems.VeteranWheel
import supercurio.eucalarm.utils.RecordingProvider
import supercurio.eucalarm.utils.TimeUtils
import supercurio.wheeldata.recording.RecordingMessageType

class WheelBlePlayer(private val wheelConnection: WheelConnection) {

    private var playing = false
    private var characteristicsKeys: CharacteristicsKeys? = null
    private var input: RecordingProvider? = null
    val playingState = MutableStateFlow(false)

    fun printAsJson() {
        Log.i(TAG, "Record size: ${input?.available()}")

//        while (input.available() > 0) {
//            Log.i(TAG, JsonFormat.printer().print(MessageType.parseDelimitedFrom(input)))
//        }
    }

    suspend fun replay(recording: RecordingProvider, wheelDataStateFlows: WheelDataStateFlows) {
        wheelConnection.setReplayState(true)
        input = recording
        characteristicsKeys = CharacteristicsKeys()
        val gotwayWheel = GotwayWheel(wheelDataStateFlows)
        val veteranWheel = VeteranWheel(wheelDataStateFlows)
        val nanoStart = SystemClock.elapsedRealtimeNanos()

        playing = true
        playingState.value = true

        while (playing && recording.available() > 0) {
            val message = RecordingMessageType.parseDelimitedFrom(recording.inputStream)

            when {
                message.hasBleDeviceInfo() ->
                    characteristicsKeys?.fromDeviceInfo(message.bleDeviceInfo)

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
        playing = false
        playingState.value = false
        wheelConnection.setReplayState(false)
    }

    fun stop() {
        playing = false
        playingState.value = false
        input?.close()
    }

    companion object {
        private const val TAG = "WheelBlePlayer"
    }
}
