package supercurio.eucalarm

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import supercurio.eucalarm.ble.WheelBleProxy
import supercurio.eucalarm.utils.directBootContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeneralConfig @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.directBootContext
        .getSharedPreferences("general_config", Context.MODE_PRIVATE)

    var originalBluetoothName
        get() = prefs.getString(KEY_BT_NAME, null)
        set(value) {
            if (value?.endsWith(WheelBleProxy.PROXY_SUFFIX) != true)
                prefs.edit { putString(KEY_BT_NAME, value) }
        }

    var wheelProxy
        get() = prefs.getBoolean(KEY_WHEEL_PROXY, false)
        set(value) = prefs.edit { putBoolean(KEY_WHEEL_PROXY, value) }

    var unitsDistanceImperial
        get() = prefs.getBoolean(KEY_UNITS_DISTANCE_IMPERIAL, false)
        set(value) = prefs.edit { putBoolean(KEY_UNITS_DISTANCE_IMPERIAL, value) }

    companion object {
        private const val KEY_BT_NAME = "btName"
        private const val KEY_WHEEL_PROXY = "wheelProxy"
        private const val KEY_UNITS_DISTANCE_IMPERIAL = "unitsDistanceImperial"
    }
}
