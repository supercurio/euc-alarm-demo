package supercurio.eucalarm.oems

import supercurio.eucalarm.data.WheelDataInterface
import supercurio.eucalarm.utils.DataParsing.capSize
import supercurio.eucalarm.utils.showBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class VeteranWheel(val wheelData: WheelDataInterface) {

    private val ringBuffer = ArrayDeque<Byte>(FRAME_SIZE)
    private val frame = ByteBuffer.allocate(FRAME_SIZE)


    // TODO: Improve the robustness of this routine with packet loss
    fun findFrame(data: ByteArray) {
        if (DEBUG_LOGGING) println("data:\n${data.showBuffer()}")

        // handle the case if the 2nd packet was received first
        if (data.size == 20 && ringBuffer.size > 0)
            ringBuffer.clear()

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
        val distance = revInt(frame.int) / 1000.0
        val totalDistance = revInt(frame.int) / 1000.0

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

    private fun revInt(value: Int): Int {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES)
        buffer.putInt(value)
        val revBuf = byteArrayOf(buffer[2], buffer[3], buffer[0], buffer[1])
        return ByteBuffer.wrap(revBuf).int
    }

    companion object {
        private const val TAG = "VeteranWheel"

        private const val FRAME_SIZE = 36

        private const val DEBUG_LOGGING = false
        private const val DATA_LOGGING = true
    }
}
