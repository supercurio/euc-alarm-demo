package supercurio.eucalarm.oems

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import supercurio.eucalarm.data.WheelData
import supercurio.eucalarm.utils.showFrame
import supercurio.eucalarm.utils.toHexString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*
import kotlin.math.roundToInt

class GotwayWheel(val wheelData: WheelData, private val scope: CoroutineScope) {

    private val ringBuffer = ArrayDeque<Byte>(40)
    private val frame = ByteBuffer.allocate(24)

    fun findFrame(data: ByteArray) {
        ringBuffer.addAll(data.asList())

        val bufferList = ringBuffer.toList()

        val index = Collections.indexOfSubList(bufferList, END_SEQUENCE)
        if (index != -1) {

            if (DEBUG_LOGGING) {
                Log.i(TAG, "BufferList: " + bufferList.map { String.format("%02x", it) })
                Log.i(TAG, "index: $index")
            }

            frame.clear()
            frame.put(bufferList.subList(0, 24).toByteArray())

            if (FRAME_LOGGING) {
                val frameType = if (frame[18] == 0.toByte()) "A" else "B"
                Log.i(TAG, "Frame $frameType:\n${frame.array().showFrame()}")
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

                scope.launch { wheelData.timestamp.emit(System.nanoTime()) }

                if (DATA_LOGGING) Log.i(
                    TAG, "voltage: $voltage V, speed $speed kph, distance: $distance km, " +
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

                scope.launch { wheelData.timestamp.emit(System.nanoTime()) }

                if (DATA_LOGGING) Log.i(
                    TAG, "total distance: $totalDistance km, pedal mode: $pedalMode, " +
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

        private val END_SEQUENCE = listOf<Byte>(0x18, 0x5A, 0x5A, 0x5A, 0x5A)


        private const val DEBUG_LOGGING = false
        private const val FRAME_LOGGING = false
        private const val DATA_LOGGING = false
    }
}
