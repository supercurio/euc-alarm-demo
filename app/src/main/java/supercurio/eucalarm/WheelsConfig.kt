package supercurio.eucalarm

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import supercurio.eucalarm.ble.WheelConnection
import supercurio.eucalarm.di.CoroutineScopeProvider
import supercurio.eucalarm.parsers.GotwayConfig
import supercurio.eucalarm.parsers.GotwayParser
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WheelsConfig @Inject constructor(
    @ApplicationContext context: Context,
    private val wheelConnection: WheelConnection,
    private val scopes: CoroutineScopeProvider,
) {
    private val prefs = context.getSharedPreferences("wheels_config", Context.MODE_PRIVATE)

    fun start() = scopes.app.launch {
        wheelConnection.parserConfigFlow.collect { config ->
            if (config is GotwayConfig) {
                when (val voltage = config.voltage) {
                    null -> wheelConnection.parserConfigFlow.value = GotwayConfig(
                        config.address,
                        getGotwayVoltage(config.address)
                    )

                    else -> setGotwayVoltage(config.address, voltage)
                }
            }
        }
    }

    private fun getGotwayVoltage(addr: String) =
        prefs.getFloat(addr, GotwayParser.DEFAULT_VOLTAGE_MULTIPLIER)

    private fun setGotwayVoltage(addr: String, voltage: Float) =
        prefs.edit { putFloat(addr, voltage) }
}
