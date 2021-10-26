package supercurio.eucalarm.oems

import android.util.Log
import supercurio.eucalarm.data.WheelDataInterface
import supercurio.eucalarm.utils.DataParsing.byte
import supercurio.eucalarm.utils.DataParsing.bytes
import supercurio.eucalarm.utils.DataParsing.capSize
import supercurio.eucalarm.utils.DataParsing.declareByteArray
import supercurio.eucalarm.utils.DataParsing.div
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
            try {
                frame.put(bufferList.subList(0, 24).toByteArray())
            } catch (t: Throwable) {
                Log.e(TAG, "Invalid buffer for frame: ${bufferList.toHexString()}")
            }

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
                val voltage = frame.ushort / 67.2 * 84.0 / 100.0
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
                wheelData.beeper = beeper.toBoolean()

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
        private val END_SEQUENCE = declareByteArray(0x18, 0x5A, 0x5A, 0x5A, 0x5A).asList()

        private const val DEBUG_LOGGING = false
        private const val FRAME_LOGGING = false
        private const val DATA_LOGGING = false
    }
}
