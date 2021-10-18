package supercurio.eucalarm.ble

import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log

class FindReconnectWheel(private val wheelConnection: WheelConnection) {

    private var reconnectScanCallback: ScanCallback? = null
    private var isScanning = false

    fun findAndReconnect(context: Context, deviceAddr: String) {
        FindConnectedWheel(context) { deviceFound ->
            stopLeScan()
            Log.i(TAG, "Reconnect to already connect device: $deviceAddr")
            wheelConnection.connectDevice(context, deviceFound)
        }.find()

        scanToReconnectTo(context, deviceAddr)
    }

    private fun scanToReconnectTo(context: Context, deviceAddr: String) {
        if (isScanning) return

        Log.i(TAG, "Start scan for $deviceAddr")

        reconnectScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.i(TAG, "reconnect scan result: $result")

                val found = DeviceFound(result.device, result.scanRecord)
                wheelConnection.connectDevice(context, found)

                FindWheel.bleScanner.stopScan(this)
                isScanning = false
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)
                Log.e(TAG, "Scan failed")
                isScanning = false
            }
        }

        val scanFilter = ScanFilter.Builder()
            .setDeviceAddress(deviceAddr)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .build()

        FindWheel.bleScanner.startScan(listOf(scanFilter), scanSettings, reconnectScanCallback)
        isScanning = true
    }

    fun stopLeScan() {
        reconnectScanCallback?.let { FindWheel.bleScanner.stopScan(it) }
        isScanning = false
    }

    companion object {
        private const val TAG = "FindReconnectWheel"
    }
}
