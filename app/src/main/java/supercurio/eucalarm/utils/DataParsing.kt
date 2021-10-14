package supercurio.eucalarm.utils

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

    // test if a ByteArrat starts of ends with the content of another ByteArrat
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

}
