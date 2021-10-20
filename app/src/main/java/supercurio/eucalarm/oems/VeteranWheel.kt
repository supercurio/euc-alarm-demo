package supercurio.eucalarm.oems

import supercurio.eucalarm.data.WheelDataInterface
import supercurio.eucalarm.utils.DataParsing.capSize
import supercurio.eucalarm.utils.DataParsing.declareByteArray
import supercurio.eucalarm.utils.DataParsing.endsWith
import supercurio.eucalarm.utils.DataParsing.startsWith
import supercurio.eucalarm.utils.showBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class VeteranWheel(val wheelData: WheelDataInterface) {

    private val ringBuffer = ArrayDeque<Byte>(FRAME_SIZE)
    private val frame = ByteBuffer.allocate(FRAME_SIZE)


    fun findFrame(data: ByteArray) {
        if (DEBUG_LOGGING) println("data:\n${data.showBuffer()}")

        // keep only first or second packets
        if (data.size != 20 && data.size != 16) return

        // identify first packet
        if (data.size == 20 && !data.startsWith(FRAME_HEADER)) return

        // identify second packet
        if (data.size == 16 && !data.endsWith(FRAME_FOOTER)) return

        // handle the case if the 2nd packet was received first
        if (data.size == 20 && ringBuffer.size > 0)
            ringBuffer.clear()

        // prevent the buffer to grow
        ringBuffer.capSize(FRAME_SIZE, data)

        ringBuffer.addAll(data.asList())

        if (ringBuffer.size == frame.array().size) {
            frame.clear()
            frame.put(ringBuffer.toByteArray())
            decodeFrame(frame)
            ringBuffer.clear()
        }
    }

    private fun decodeFrame(frame: ByteBuffer) {
        frame.order(ByteOrder.BIG_ENDIAN)
        // skip header
        frame.position(4)

        val voltage = frame.short / 100.0
        val speed = frame.short / 10.0
        val distance = frame.int.reversed() / 1000.0
        val totalDistance = frame.int.reversed() / 1000.0

        val current = frame.short / 10.0
        val temperature = frame.short / 100.0

        wheelData.voltage = voltage
        wheelData.speed = speed
        wheelData.tripDistance = distance
        wheelData.totalDistance = totalDistance
        wheelData.current = current
        wheelData.temperature = temperature

        wheelData.gotNewData()

        if (DATA_LOGGING) println(
            "voltage: $voltage V, speed $speed kph, distance: $distance km, " +
                    "totalDistance: $totalDistance, current: $current A, " +
                    "temperature: $temperature"
        )
    }

    private fun Int.reversed(): Int {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES)
        buffer.putInt(this)
        val revBuf = byteArrayOf(buffer[2], buffer[3], buffer[0], buffer[1])
        return ByteBuffer.wrap(revBuf).int
    }

    companion object {
        private const val TAG = "VeteranWheel"

        private const val FRAME_SIZE = 36

        private val FRAME_HEADER = declareByteArray(0xdc, 0x5a, 0x5c, 0x20)
        private val FRAME_FOOTER = declareByteArray(0x00, 0x00)

        private const val DEBUG_LOGGING = false
        private const val DATA_LOGGING = false
    }
}
