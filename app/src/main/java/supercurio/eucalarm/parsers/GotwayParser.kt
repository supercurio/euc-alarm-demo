package supercurio.eucalarm.parsers

import supercurio.eucalarm.data.WheelDataInterface
import supercurio.eucalarm.utils.DataParsing.byte
import supercurio.eucalarm.utils.DataParsing.bytes
import supercurio.eucalarm.utils.DataParsing.capSize
import supercurio.eucalarm.utils.DataParsing.declareByteArray
import supercurio.eucalarm.utils.DataParsing.div
import supercurio.eucalarm.utils.DataParsing.eprintln
import supercurio.eucalarm.utils.DataParsing.findSequence
import supercurio.eucalarm.utils.DataParsing.negative
import supercurio.eucalarm.utils.DataParsing.toBoolean
import supercurio.eucalarm.utils.DataParsing.uint
import supercurio.eucalarm.utils.DataParsing.ushort
import supercurio.eucalarm.utils.showBuffer
import supercurio.eucalarm.utils.toHexString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.roundToInt
import kotlin.random.Random

class GotwayParser(private val wheelData: WheelDataInterface) {
    var voltageMultiplier = DEFAULT_VOLTAGE_MULTIPLIER

    private val ringBuffer = ArrayDeque<Byte>(MAX_RING_BUFFER_SIZE)
    private val frame = ByteBuffer.allocate(24)

    private var packets = 0
    private var lostPackets = 0

    fun findFrame(input: ByteArray): Boolean {
        /*
         * Packet dropped simulation
         */
        packets++
        if (SIMULATE_DROP_PACKETS && Random.nextInt(100) - SIMULATE_DROP_PACKETS_PERCENTS < 0) {
            eprintln("Simulate dropped packet")
            lostPackets++
            return false
        }

        if (LOG_LOSS) println(
            "Packets: $packets, lost: $lostPackets, " +
                    "loss: ${String.format("%.2f", 100.0 * lostPackets / packets)}%"
        )


        /*
         * Rebuild frames
         */
        if (DEBUG_LOGGING) println("input:\n${input.showBuffer()}")

        ringBuffer.capSize(input, MAX_RING_BUFFER_SIZE)
        ringBuffer.addAll(input.asList())

        // return here if the buffer doesn't contain enough data for a frame
        if (ringBuffer.size < FRAME_SIZE) return false

        val frameBuffer = ringBuffer.toList()

        // check if we can find a footer
        if (SIMULATE_DROP_PACKETS) println("Frame buffer:\n${frameBuffer.showBuffer()}")
        val footerPos = frameBuffer.findSequence(FOOTER)

        // then, if we found a footer
        if (footerPos != -1) {

            // clear ring buffer and add post footer data
            val next = frameBuffer.subList(footerPos + FOOTER.size, frameBuffer.size)
            ringBuffer.clear()
            ringBuffer.addAll(next)

            // find header position
            val headerPos = frameBuffer.findSequence(HEADER)
            if (headerPos == -1) {
                eprintln("Found footer but no header, likely due to packet loss")
                return false
            }

            if (headerPos > footerPos) {
                eprintln("Found header at the wrong position")
                return false
            }

            val frameSizeFound = footerPos - headerPos + FOOTER.size
            if (frameSizeFound != FRAME_SIZE) {
                eprintln("Invalid frame size found: $frameSizeFound")
                return false
            }

            if (frameSizeFound == FRAME_SIZE) {
                try {
                    frame.clear()
                    frame.put(frameBuffer.subList(headerPos, headerPos + FRAME_SIZE).toByteArray())
                    if (FRAME_LOGGING) {
                        val frameType = if (frame[18] == 0.toByte()) "A" else "B"
                        println("Frame $frameType:\n${frame.array().showBuffer()}")
                    }
                    decodeFrame(frame)
                    return true
                } catch (t: Throwable) {
                    eprintln("Invalid frame: ${frameBuffer.toHexString()}")
                }
            }
        }
        return false
    }

    private fun decodeFrame(frame: ByteBuffer) {
        frame.order(ByteOrder.BIG_ENDIAN)
        when (frame[18]) {
            0.toByte() -> {
                // skip header
                frame.position(2)

                // 2-3
                val voltage = frame.ushort / 67.2 * voltageMultiplier / 100.0
                // 4-5
                val speed = frame.short * 3.6 / 100
                // 6-9
                val distance = frame.uint / 1000.0
                // 10-11
                val current = frame.short / 100.0
                // 12-13
                val temperature = frame.short / 340.0 + 36.53
                // 14-17
                val unknown1 = frame.bytes(4).toHexString()

                wheelData.voltage = voltage
                wheelData.speed = speed.negative
                wheelData.tripDistance = distance
                wheelData.current = current.negative
                wheelData.temperature = temperature

                wheelData.gotNewData()

                if (DATA_LOGGING) println(
                    "voltage: $voltage V, speed $speed kph, distance: $distance km, " +
                            "current: $current A, temperature: ${temperature.roundToInt()}, " +
                            "unknown 1: $unknown1"
                )
            }

            4.toByte() -> {
                // skip header
                frame.position(2)

                // 2-5
                val totalDistance = frame.uint / 1000.0
                // 6
                val tmp = frame.byte
                val pedalMode = (tmp.toInt() shr 4).toString(16)
                val speedAlarms = (tmp.toInt() and 0x0F).toString(16)
                // 7-12
                val unknown2 = frame.bytes(6).toHexString()
                // 13
                val ledMode = frame.byte
                // 14
                val beeper = frame.byte
                // 15-17
                val unknown3 = frame.bytes(3).toHexString()

                wheelData.totalDistance = totalDistance

                // Some firmwares report beeper status when changing light settings.
                // In order to filter some unnecessary confirmation beeps as alerts,
                // require speed to not be zero.
                wheelData.beeper = if (wheelData.speed == 0.0) false else beeper.toBoolean()

                wheelData.gotNewData()

                if (DATA_LOGGING) println(
                    "total distance: $totalDistance km, pedal mode: $pedalMode, " +
                            "speed alarms: $speedAlarms, unknown 2: $unknown2, " +
                            "led mode: $ledMode, beeper: $beeper, unknown 3: $unknown3"
                )
            }
        }
    }

    companion object {
        const val DEFAULT_VOLTAGE_MULTIPLIER = 100.8f

        private const val FRAME_SIZE = 24
        private const val MAX_RING_BUFFER_SIZE = 40
        private val HEADER = declareByteArray(0x55, 0xaa).asList()
        private val FOOTER = declareByteArray(0x18, 0x5a, 0x5a, 0x5a, 0x5a).asList()

        private const val DEBUG_LOGGING = false
        private const val FRAME_LOGGING = false
        private const val DATA_LOGGING = false

        private const val SIMULATE_DROP_PACKETS_PERCENTS = 0
        private const val SIMULATE_DROP_PACKETS = SIMULATE_DROP_PACKETS_PERCENTS > 0
        private const val LOG_LOSS = false
    }
}
