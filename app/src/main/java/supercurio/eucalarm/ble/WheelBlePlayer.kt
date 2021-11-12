package supercurio.eucalarm.ble

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.parsers.GotwayAndVeteranParser
import supercurio.eucalarm.utils.RecordingProvider
import supercurio.eucalarm.utils.TimeUtils
import supercurio.wheeldata.recording.RecordingMessageType

class WheelBlePlayer(private val wheelConnection: WheelConnection) {

    private var playing = false
    private var characteristicsKeys: CharacteristicsKeys? = null
    private var input: RecordingProvider? = null
    val playingState = MutableStateFlow(false)

    suspend fun replay(recording: RecordingProvider, wheelDataStateFlows: WheelDataStateFlows) {
        wheelConnection.setReplayState(true)
        input = recording
        characteristicsKeys = CharacteristicsKeys()
        var gotwayAndVeteranParser: GotwayAndVeteranParser? = null
        val nanoStart = SystemClock.elapsedRealtimeNanos()

        playing = true
        playingState.value = true

        while (playing && recording.available() > 0) {
            val message = RecordingMessageType.parseDelimitedFrom(recording.inputStream)

            when {
                message.hasBleDeviceInfo() -> {
                    characteristicsKeys?.fromDeviceInfo(message.bleDeviceInfo)
                    gotwayAndVeteranParser = GotwayAndVeteranParser(
                        wheelDataStateFlows,
                        wheelConnection.parserConfigFlow,
                        message.bleDeviceInfo.address
                    )
                }

                message.hasGattNotification() -> {
                    val notification = message.gattNotification

                    TimeUtils.delayFromNotification(nanoStart, notification)

                    val bytes = notification.bytes.toByteArray()
                    gotwayAndVeteranParser?.notificationData(bytes)
                }
            }
        }

        Log.i(TAG, "Finished")

        wheelDataStateFlows.clear()
        gotwayAndVeteranParser?.stop()
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
