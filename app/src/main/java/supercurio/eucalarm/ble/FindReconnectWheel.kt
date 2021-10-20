package supercurio.eucalarm.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import supercurio.eucalarm.utils.BluetoothUtils.toScanFailError

class FindReconnectWheel(private val wheelConnection: WheelConnection) {

    private var reconnectScanCallback: ScanCallback? = null
    private var isScanning = false
    private val offloadedFilteringSupported =
        BluetoothAdapter.getDefaultAdapter().isOffloadedFilteringSupported

    var reconnectToAddr: String? = null

    fun findAndReconnect(context: Context, deviceAddr: String) {
        reconnectToAddr = deviceAddr
        if (BluetoothAdapter.getDefaultAdapter().isEnabled) {
            FindConnectedWheels(context) { deviceFound ->
                stopLeScan()
                Log.i(TAG, "Reconnect to already connect device: $deviceAddr")
                wheelConnection.connectDevice(context, deviceFound)
            }.find()

            scanToReconnectTo(context, deviceAddr)
        }
    }

    fun stopLeScan() {
        reconnectScanCallback?.let { scanner.stopScan(it) }
        isScanning = false
        reconnectToAddr = null
    }

    private fun scanToReconnectTo(context: Context, deviceAddr: String) {
        if (isScanning) return

        Log.i(TAG, "Start scan for $deviceAddr")

        reconnectScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!isScanning) return
                Log.i(TAG, "reconnect scan result: $result")

                // filter
                if (!offloadedFilteringSupported && result.device.address != deviceAddr) return

                val found = DeviceFound(result.device, DeviceFoundFrom.SCAN, result.scanRecord)
                wheelConnection.connectDevice(context, found)

                scanner.stopScan(this)
                isScanning = false
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(TAG, "Scan failed with error: ${toScanFailError(errorCode)}")
                isScanning = false
            }
        }

        val scanFilter = if (offloadedFilteringSupported) ScanFilter.Builder()
            .setDeviceAddress(deviceAddr)
            .build()
        else
            ScanFilter.Builder().build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        scanner.startScan(listOf(scanFilter), scanSettings, reconnectScanCallback)
        isScanning = true
    }

    private val scanner get() = BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner

    companion object {
        private const val TAG = "FindReconnectWheel"
    }
}
