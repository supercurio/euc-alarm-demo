package supercurio.eucalarm.oems

import supercurio.eucalarm.data.WheelData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class VeteranWheel(val wheelData: WheelData) {

    private val ringBuffer = ArrayDeque<Byte>(40)
    private val frame = ByteBuffer.allocate(36)

    fun findFrame(data: ByteArray) {
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

        wheelData.voltage.value = voltage
        wheelData.speed.value = speed
        wheelData.tripDistance.value = distance
        wheelData.totalDistance.value = totalDistance
        wheelData.current.value = current
        wheelData.temperature.value = temperature
    }

    private fun revInt(value: Int): Int {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES)
        buffer.putInt(value)
        val revBuf = byteArrayOf(buffer[2], buffer[3], buffer[0], buffer[1])
        return ByteBuffer.wrap(revBuf).int
    }
}
