package supercurio.eucalarm.log

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import supercurio.eucalarm.di.CoroutineScopeProvider
import supercurio.eucalarm.utils.directBootContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scopes: CoroutineScopeProvider
) {
    private var output = openOutput().apply { write("-\n".toByteArray()) }

    private var _logFlow = MutableSharedFlow<String>()
    val logFlow = _logFlow.asSharedFlow()

    fun log(string: String) {
        scopes.app.launch { _logFlow.emit(string) }

        scopes.app.launch(Dispatchers.IO) {
            output.apply {

                val date = Calendar.getInstance().time
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
                val strDate = dateFormat.format(date)

                write("$strDate  $string\n".toByteArray())
                flush()
            }
        }
        Log.i(TAG, string)
    }

    fun read(): String {
        val reader = context.directBootContext
            .openFileInput(FILENAME)
            .reader()

        val text = reader.readText()
        reader.close()
        return text
    }

    private fun openOutput(): FileOutputStream {
        val file = File(context.directBootContext.filesDir, FILENAME)
        Log.i(TAG, "Open output file: ${file.absolutePath}, size: ${file.length()}")

        return FileOutputStream(file, true)
    }

    companion object {
        private const val FILENAME = "log.txt"
        private const val TAG = "AppLog"
    }
}
