package supercurio.eucalarm.ble

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import supercurio.eucalarm.AppLifecycle
import supercurio.eucalarm.appstate.AppStateStore
import javax.inject.Inject

@AndroidEntryPoint
class BluetoothConnectionReceiver : BroadcastReceiver() {

    @Inject
    lateinit var devicesNamesCache: DevicesNamesCache

    @Inject
    lateinit var appStateStore: AppStateStore

    @Inject
    lateinit var appLifecycle: AppLifecycle

    @Inject
    lateinit var wheelConnection: WheelConnection

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return

        val device = intent.extras
            ?.getParcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            ?: return

        val name = devicesNamesCache[device.address]
        val known = devicesNamesCache.isKnown(device)

        Log.i(TAG, "Name from device: ${device.name}, from cache: $name, is known: $known")

        if (known) {
            appLifecycle.on()
            wheelConnection.reconnectDevice(device.address)
        }
    }

    companion object {
        private const val TAG = "BluetoothConnectionReceiver"
    }
}
