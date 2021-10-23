package supercurio.eucalarm.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanRecord
import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import supercurio.eucalarm.utils.directBootContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevicesNamesCache @Inject constructor(@ApplicationContext context: Context) {

    private val map = mutableMapOf<String, String>()

    private val prefs = context.directBootContext.getSharedPreferences(
        "device_names",
        Context.MODE_PRIVATE
    )

    fun remember(address: String, scanRecord: ScanRecord) = scanRecord.deviceName?.let { name ->
        prefs.edit {
            putString(address, name)
        }
    }

    fun remember(device: BluetoothDevice) = device.name?.let { name ->
        prefs.edit { putString(device.address, name) }
    }


    operator fun get(address: String?): String {
        if (address == null) return NO_ADDRESS
        map[address]?.let { return it }

        val name = prefs.getString(address, null)?.also { map[address] = it }
        return name ?: address
    }

    fun isKnown(device: BluetoothDevice): Boolean {
        val map = prefs.all
        return when {
            map.containsKey(device.address) -> true
            map.containsValue(device.name) -> true
            else -> false
        }
    }

    companion object {
        private const val NO_ADDRESS = "no-address"
    }
}
