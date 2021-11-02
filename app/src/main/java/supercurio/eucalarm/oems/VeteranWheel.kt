package supercurio.eucalarm.oems

import supercurio.eucalarm.data.WheelDataInterface
import supercurio.eucalarm.utils.DataParsing.bytes
import supercurio.eucalarm.utils.DataParsing.capSize
import supercurio.eucalarm.utils.DataParsing.declareByteArray
import supercurio.eucalarm.utils.DataParsing.div
import supercurio.eucalarm.utils.DataParsing.endsWith
import supercurio.eucalarm.utils.DataParsing.startsWith
import supercurio.eucalarm.utils.DataParsing.uint
import supercurio.eucalarm.utils.DataParsing.ushort
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
        if (LOG_FRAME) println("frame:\n${frame.array().showBuffer()}")

        frame.order(ByteOrder.BIG_ENDIAN)
        // skip header
        frame.position(4)

        // 4-5
        val voltage = frame.short / 100.0
        // 6-7
        val speed = frame.short / 10.0
        // 8-11
        val distance = frame.uintReversed / 1000.0
        // 12-15
        val totalDistance = frame.uintReversed / 1000.0
        // 16-17
        val current = frame.short / 10.0
        // 18-19
        val temperature = frame.short / 100.0
        // 20-21
        val offTimer = frame.ushort
        // 22-23
        val chargeMode = frame.ushort
        // 24-25
        val alertSpeed = frame.ushort / 10.0
        // 26-27
        val tiltbackSpeed = frame.ushort / 10.0
        // 28-29
        val version = frame.ushort
        // 30-31
        val pedalMode = frame.ushort
        // 32-33
        val tilt = -frame.short / 100.0
        // 34-35
        val unknown1 = frame.bytes(2)

        wheelData.voltage = voltage
        wheelData.speed = speed
        wheelData.tripDistance = distance
        wheelData.totalDistance = totalDistance
        wheelData.current = current
        wheelData.temperature = temperature
        wheelData.tilt = tilt

        wheelData.gotNewData()

        if (DATA_LOGGING) println(
            "voltage: $voltage V, speed $speed kph, distance: $distance km, " +
                    "totalDistance: $totalDistance, current: $current A, " +
                    "temperature: $temperature, offTimer: $offTimer, " +
                    "chargeMode: $chargeMode, alertSpeed: $alertSpeed, " +
                    "tiltbackSpeed: $tiltbackSpeed, version: $version, pedalMode: $pedalMode, " +
                    "tilt: $tilt, unknown2: $unknown1 "
        )
    }

    private val ByteBuffer.uintReversed: UInt
        get() {
            val tmp = ByteBuffer.allocate(Int.SIZE_BYTES)
            tmp.putInt(this.int)
            val revBuf = byteArrayOf(tmp[2], tmp[3], tmp[0], tmp[1])
            return ByteBuffer.wrap(revBuf).uint
        }

    companion object {
        private const val FRAME_SIZE = 36

        private val FRAME_HEADER = declareByteArray(0xdc, 0x5a, 0x5c, 0x20)
        private val FRAME_FOOTER = declareByteArray(0x00, 0x00)

        private const val DEBUG_LOGGING = false
        private const val LOG_FRAME = false
        private const val DATA_LOGGING = false
    }
}
