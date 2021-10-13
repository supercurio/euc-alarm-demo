package supercurio.eucalarm.utils

import java.util.*

object DataParsing {

    // cap the size of the ring buffer to its maximum
    fun <E> ArrayDeque<E>.capSize(maxRingBufferSize: Int, data: ByteArray) {
        val toRemove = size + data.size - maxRingBufferSize
        if (toRemove > 0) (0 until toRemove).forEach { _ -> remove() }
    }
}
