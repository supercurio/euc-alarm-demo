package supercurio.eucalarm.utils

import java.nio.ByteBuffer
import java.util.*

object DataParsing {

    fun declareByteArray(vararg values: Int) = values
        .map { it.toByte() }
        .toByteArray()

    // cap the size of the ring buffer to its maximum
    fun <E> ArrayDeque<E>.capSize(maxRingBufferSize: Int, data: ByteArray) {
        val toRemove = size + data.size - maxRingBufferSize
        if (toRemove > 0) (0 until toRemove).forEach { _ -> remove() }
    }

    // test if a ByteArray starts of ends with the content of another ByteArray
    fun ByteArray.startsWith(bytes: ByteArray): Boolean {
        bytes.indices.forEach { i -> if (this[i] != bytes[i]) return false }
        return true
    }

    fun ByteArray.endsWith(bytes: ByteArray): Boolean {
        bytes.indices.forEach { i ->
            val bytesIndex = bytes.size - i - 1
            val arrayIndex = size - i - 1
            if (this[arrayIndex] != bytes[bytesIndex]) return false
        }
        return true
    }

    fun Byte.toBoolean(): Boolean = this != 0.toByte()

    val Double.negative
        get() = when (this) {
            0.0 -> 0.0
            else -> -this
        }

    fun ByteBuffer.bytes(size: Int): ByteArray {
        val dst = ByteArray(size)
        get(dst)
        return dst
    }

    val ByteBuffer.ushort: UShort get() = short.toUShort()
    val ByteBuffer.uint: UInt get() = int.toUInt()
    val ByteBuffer.uByte: UByte get() = get().toUByte()
    val ByteBuffer.byte: Byte get() = get()

    operator fun UInt.div(d: Double): Double = this.toDouble() / d;
    operator fun UShort.div(d: Double): Double = this.toDouble() / d;
}
