package supercurio.wheeldata.cli


import supercurio.eucalarm.data.WheelDataInterface
import supercurio.eucalarm.data.WheelDataPrimitives
import supercurio.eucalarm.oems.GotwayWheel
import supercurio.eucalarm.oems.VeteranWheel
import supercurio.eucalarm.utils.TimeUtils.toMs
import supercurio.wheeldata.recording.RecordingMessageType
import java.io.File
import java.text.SimpleDateFormat


fun main() {

    val runStartMs = System.currentTimeMillis()

    val input = File("/tmp/sample.bwr").inputStream().buffered()

    val output = File("/tmp/out.csv").outputStream().bufferedWriter()
    val header = "datetime,distance,distance_total,speed,voltage,current,power,battery,temp\n"
    output.write(header)


    val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")

    val newDataCallback = { wheelData: WheelDataInterface ->
        val d = ","

        with(wheelData) {
            var line = ""

            val dateTime = formatter.format(dataTimeMs)

            line += "${dateTime}$d"
            line += "${tripDistance?.toFloat() ?: 0}$d"
            line += "${totalDistance?.toFloat() ?: 0}$d"
            line += "${speed?.toFloat() ?: 0}$d"
            line += "${voltage?.toFloat() ?: 0}$d"
            line += "${current?.toFloat() ?: 0}$d"
            line += "0$d"
            line += "0$d"
            line += "${temperature?.toFloat() ?: 0}"
            line += "\n"
            output.write(line)
        }
    }


    val wheelData = WheelDataPrimitives(newDataCallback)

    val gotwayWheel = GotwayWheel(wheelData)
    val veteranWheel = VeteranWheel(wheelData)

    var startMs = System.currentTimeMillis()
    while (input.available() > 0) {
        val message = RecordingMessageType.parseDelimitedFrom(input)

        when {
            message.hasRecordingInfo() -> {
                startMs = message.recordingInfo.startTimestamp.toMs()
            }
            message.hasGattNotification() -> {
                val notification = message.gattNotification

                val bytes = notification.bytes.toByteArray()
                wheelData.dataTimeMs = startMs + notification.elapsedTimestamp.toMs()
                // TODO: re-add Gotway frame parser
                gotwayWheel.findFrame(bytes)
                veteranWheel.findFrame(bytes)
            }
        }
    }
    wheelData.gotNewData(end = true)

    output.flush()
    output.close()
    val elapsed = System.currentTimeMillis() - runStartMs

    println("Written to CSV in $elapsed ms")
}
