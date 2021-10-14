package supercurio.eucalarm.utils

import android.content.Context
import supercurio.eucalarm.R
import java.io.File
import java.io.InputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

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
                getLastRecordingFile(context),
                R.raw.sample,
            )

        fun getLastRecordingFile(context: Context): File? {
            val filesList = File(context.filesDir, RECORDINGS_DIR).listFiles()
            filesList?.sortByDescending { it.lastModified() }
            return filesList?.first()
        }

        fun generateRecordingFilename(context: Context, deviceName: String?): File {
            val destDir = File(context.filesDir, RECORDINGS_DIR)
            destDir.mkdirs()

            val date = Calendar.getInstance().time
            val dateFormat: DateFormat = SimpleDateFormat("yyyy-mm-dd_HH:mm:ss", Locale.ROOT)
            val strDate: String = dateFormat.format(date)

            val name = deviceName ?: "no-name"
            return File(destDir, "$name-$strDate.bwr")
        }

        fun generateImportedFilename(context: Context): File {
            val destDir = File(context.filesDir, RECORDINGS_DIR)
            destDir.mkdirs()

            return File(destDir, "imported.bwr")
        }

        private const val RECORDINGS_DIR = "recordings"

    }
}