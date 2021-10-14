package supercurio.eucalarm.utils

import android.content.Context
import supercurio.eucalarm.R
import supercurio.eucalarm.ble.WheelBleRecorder
import java.io.File
import java.io.InputStream

class RecordingProvider(
    private val context: Context,
    private val file: File? = null,
    private val resourceId: Int,
) {

    private var realInputStream: InputStream = open()

    val inputStream get() = realInputStream
    fun available() = realInputStream.available()

    fun reset() {
        realInputStream.close()
        realInputStream = open()
    }

    fun close() = realInputStream.close()

    private fun open() = when {
        file != null -> file.inputStream().buffered()
        else -> context.resources.openRawResource(resourceId).buffered()
    }

    companion object {
        fun getLastRecordingOrSample(context: Context) =
            RecordingProvider(
                context,
                WheelBleRecorder.getLastRecordingFile(context),
                R.raw.sample,
            )
    }
}
