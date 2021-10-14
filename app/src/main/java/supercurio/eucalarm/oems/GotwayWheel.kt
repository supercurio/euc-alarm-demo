package supercurio.eucalarm.oems

import supercurio.eucalarm.data.WheelDataInterface
import supercurio.eucalarm.utils.DataParsing.capSize
import supercurio.eucalarm.utils.showBuffer
import supercurio.eucalarm.utils.toHexString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.roundToInt

class GotwayWheel(val wheelData: WheelDataInterface) {

    private val ringBuffer = ArrayDeque<Byte>(MAX_RING_BUFFER_SIZE)
    private val frame = ByteBuffer.allocate(24)


    fun findFrame(data: ByteArray) {
        if (DEBUG_LOGGING) println("data:\n${data.showBuffer()}")

        ringBuffer.capSize(MAX_RING_BUFFER_SIZE, data)

        ringBuffer.addAll(data.asList())

        val bufferList = ringBuffer.toList()

        // check if we got an end sequence
        val index = Collections.indexOfSubList(bufferList, END_SEQUENCE)
        if (index != -1) {

            if (DEBUG_LOGGING) {
                println("BufferList:\n" + bufferList.toByteArray().showBuffer())
                println("index: $index")
            }

            frame.clear()
            frame.put(bufferList.subList(0, 24).toByteArray())

            if (FRAME_LOGGING) {
                val frameType = if (frame[18] == 0.toByte()) "A" else "B"
                println("Frame $frameType:\n${frame.array().showBuffer()}")
            }

            decodeFrame(frame)

            val next = bufferList.subList(index + END_SEQUENCE.size, bufferList.size)
            ringBuffer.clear()
            ringBuffer.addAll(next)
        }
    }

    private fun decodeFrame(frame: ByteBuffer) {
        frame.order(ByteOrder.BIG_ENDIAN)
        when (frame[18]) {
            0.toByte() -> {
                // skip header
                frame.position(2)

                // 2-3
                val voltage = frame.short / 67.2 * 84.0 / 100.0
                // 4-5
                val speed = frame.short * 3.6 / 100
                // 6-9
                val distance = frame.int / 1000.0
                // 10-11
                val current = frame.short / 100.0
                // 12-13
                val temperature = frame.short / 340.0 + 36.53
                // 14-17
                val unknown1 =
                    listOf(frame.get(), frame.get(), frame.get(), frame.get())
                        .toHexString()

                wheelData.voltage = voltage
                wheelData.speed = speed
                wheelData.tripDistance = distance
                wheelData.current = current
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
                val totalDistance = frame.int / 1000.0
                // 6
                val tmp = frame.get()
                val pedalMode = (tmp.toInt() shr 4).toString(16)
                val speedAlarms = (tmp.toInt() and 0x0F).toString(16)
                // 7-12
                val unknown2 = listOf(
                    frame.get(),
                    frame.get(),
                    frame.get(),
                    frame.get(),
                    frame.get(),
                    frame.get()
                ).toHexString()
                // 13
                val ledMode = frame.get()
                // 14
                val beeper = frame.get()
                // 15-17
                val unknown3 = listOf(
                    frame.get(),
                    frame.get(),
                    frame.get(),
                )

                wheelData.totalDistance = totalDistance
                wheelData.beeper = beeper.toInt() != 0

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
        private const val TAG = "GotwayWheel"

        const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val DATA_CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

        private const val MAX_RING_BUFFER_SIZE = 40
        private val END_SEQUENCE = listOf<Byte>(0x18, 0x5A, 0x5A, 0x5A, 0x5A)

        private const val DEBUG_LOGGING = false
        private const val FRAME_LOGGING = false
        private const val DATA_LOGGING = false
    }
}
