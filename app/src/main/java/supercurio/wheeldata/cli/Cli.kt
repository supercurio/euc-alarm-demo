package supercurio.wheeldata.cli


import kotlinx.coroutines.flow.MutableStateFlow
import supercurio.eucalarm.data.WheelDataInterface
import supercurio.eucalarm.data.WheelDataPrimitives
import supercurio.eucalarm.parsers.GotwayAndVeteranParser
import supercurio.eucalarm.parsers.NoConfig
import supercurio.eucalarm.parsers.ParserConfig
import supercurio.eucalarm.utils.TimeUtils.toMs
import supercurio.wheeldata.recording.RecordingMessageType
import java.io.File
import java.text.SimpleDateFormat


fun main() {

    val runStartMs = System.currentTimeMillis()

    val input = File("/tmp/sample.bwr").inputStream().buffered()

    val output = File("/tmp/out.csv").outputStream().bufferedWriter()
    val header = "datetime,distance,distance_total,speed,voltage,current,power,battery,temp,tilt\n"
    output.write(header)


    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")

    val newDataCallback = { wheelData: WheelDataInterface ->
        val d = ","

        with(wheelData) {
            var line = ""

            val dateTime = formatter.format(dataTimeMs)

            line += "${dateTime}$d"
            line += "${tripDistance ?: 0}$d"
            line += "${totalDistance ?: 0}$d"
            line += "${speed ?: 0}$d"
            line += "${voltage ?: 0}$d"
            line += "${current ?: 0}$d"
            line += "0$d"
            line += "0$d"
            line += "${temperature ?: 0}$d"
            line += "${tilt ?: 0}"
            line += "\n"
            output.write(line)
        }
    }


    val wheelData = WheelDataPrimitives(newDataCallback)
    val parserConfigFlow = MutableStateFlow<ParserConfig>(NoConfig)

    var gotwayAndVeteranParser: GotwayAndVeteranParser? = null

    var startMs = System.currentTimeMillis()
    while (input.available() > 0) {
        val message = RecordingMessageType.parseDelimitedFrom(input)

        when {
            message.hasRecordingInfo() -> startMs = message.recordingInfo.startTimestamp.toMs()

            message.hasBleDeviceInfo() -> gotwayAndVeteranParser = GotwayAndVeteranParser(
                wheelData,
                parserConfigFlow,
                message.bleDeviceInfo.address
            )

            message.hasGattNotification() -> {
                val notification = message.gattNotification

                val bytes = notification.bytes.toByteArray()
                wheelData.dataTimeMs = startMs + notification.elapsedTimestamp.toMs()
                gotwayAndVeteranParser?.notificationData(bytes)
            }
        }
    }
    wheelData.gotNewData()
    gotwayAndVeteranParser?.stop()

    output.flush()
    output.close()
    val elapsed = System.currentTimeMillis() - runStartMs

    println("Written to CSV in $elapsed ms")
}
