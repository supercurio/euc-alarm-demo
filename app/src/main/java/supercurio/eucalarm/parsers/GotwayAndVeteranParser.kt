package supercurio.eucalarm.parsers

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import supercurio.eucalarm.data.WheelDataInterface

class GotwayAndVeteranParser(
    wheelData: WheelDataInterface,
    private val parserConfigFlow: MutableStateFlow<ParserConfig>,
    private val deviceAddress: String,
) {
    private val scope = CoroutineScope(Dispatchers.Default) + CoroutineName(TAG)

    private var gotwayParser = GotwayParser(wheelData)
    private var veteranParser = VeteranParser(wheelData)

    private var type = Type.Unidentified

    init {
        scope.launch {
            parserConfigFlow
                .filterIsInstance<GotwayConfig>()
                .collect { config ->
                    config.voltage?.let { gotwayParser.voltageMultiplier = it }
                }
        }
    }

    fun notificationData(data: ByteArray) {
        when (type) {
            Type.Unidentified -> {
                if (gotwayParser.findFrame(data)) {
                    type = Type.Gotway
                    parserConfigFlow.value = GotwayConfig(deviceAddress)
                }
                if (veteranParser.findFrame(data)) type = Type.Veteran
                if (type != Type.Unidentified)
                    println("$TAG, Identified wheel data format: ${type.brandName}")
            }
            Type.Gotway -> gotwayParser.findFrame(data)
            Type.Veteran -> veteranParser.findFrame(data)
        }
    }

    fun stop() {
        parserConfigFlow.value = NoConfig
        scope.cancel()
    }


    enum class Type(val brandName: String? = null) {
        Unidentified,
        Gotway("Gotway/Begode/Extreme Bull"),
        Veteran("Veteran")
    }

    companion object {
        private const val TAG = "GotwayAndVeteranParser"

        const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val DATA_CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    }
}
