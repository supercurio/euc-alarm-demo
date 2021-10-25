package supercurio.eucalarm.ble.find

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import supercurio.eucalarm.ble.DeviceFound
import supercurio.eucalarm.ble.DeviceFoundFrom
import supercurio.eucalarm.ble.WheelConnection
import supercurio.eucalarm.ble.wrappers.LeScannerWrapper

class FindReconnectWheel(private val wheelConnection: WheelConnection) {

    private var scope: CoroutineScope? = null
    private var scannerWrapper = LeScannerWrapper()
    private var isScanning = false
    private val offloadedFilteringSupported =
        BluetoothAdapter.getDefaultAdapter().isOffloadedFilteringSupported

    var reconnectToAddr: String? = null

    fun findAndReconnect(context: Context, deviceAddr: String) {
        scope = CoroutineScope(Dispatchers.Main) + CoroutineName(TAG)
        reconnectToAddr = deviceAddr
        if (BluetoothAdapter.getDefaultAdapter().isEnabled) {
            FindConnectedWheels(context) { deviceFound ->
                if (deviceFound.device.address == reconnectToAddr) {
                    stopLeScan()
                    Log.i(TAG, "Reconnect to already connect device: $deviceAddr")
                    wheelConnection.connectDeviceFound(deviceFound)
                }
            }.find()

            scanToReconnectTo(deviceAddr)
        }
    }

    fun stopLeScan() {
        scannerWrapper.stop()
        isScanning = false
        reconnectToAddr = null
        scope?.cancel()
    }

    private fun scanToReconnectTo(deviceAddr: String) {
        if (isScanning) return

        Log.i(TAG, "Start scan for $deviceAddr")

        scannerWrapper = LeScannerWrapper()

        val onResultCallback = fun(result: ScanResult) {
            if (!isScanning) return
            Log.i(TAG, "reconnect scan result: $result")

            // filter
            if (!offloadedFilteringSupported && result.device.address != deviceAddr) return

            val found = DeviceFound(result.device, DeviceFoundFrom.SCAN, result.scanRecord)
            wheelConnection.connectDeviceFound(found)

            scannerWrapper.stop()
            isScanning = false
        }

        val scanFilter = if (offloadedFilteringSupported) ScanFilter.Builder()
            .setDeviceAddress(deviceAddr)
            .build()
        else
            ScanFilter.Builder().build()

        val standardScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .build()

        val fallbackScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()

        scope?.launch {
            // try first with CALLBACK_TYPE_FIRST_MATCH

            isScanning = true
            when (scannerWrapper.scan(listOf(scanFilter), standardScanSettings, onResultCallback)) {
                LeScannerWrapper.ScannerWrapperResult.Success -> Log.i(TAG, "Scanner success")
                LeScannerWrapper.ScannerWrapperResult.Failure ->
                    // didn't work, try with the fallback settings instead
                    scannerWrapper.scan(listOf(scanFilter), fallbackScanSettings, onResultCallback)
            }
            isScanning = false
        }
    }

    companion object {
        private const val TAG = "FindReconnectWheel"
    }
}
