package supercurio.eucalarm.ble.find

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
import supercurio.eucalarm.utils.btManager

class FindReconnectWheel(context: Context, private val wheelConnection: WheelConnection) {

    private var scope: CoroutineScope? = null
    private var scannerWrapper = LeScannerWrapper(
        context = context,
        name = "$TAG#${hashCode()}",
        stopDelay = 0
    )
    private var isScanning = false
    private val offloadedFilteringSupported =
        context.btManager.adapter.isOffloadedFilteringSupported

    var reconnectToAddr: String? = null

    fun findAndReconnect(context: Context, deviceAddr: String) {
        scope = CoroutineScope(Dispatchers.Main) + CoroutineName(TAG)
        reconnectToAddr = deviceAddr
        if (context.btManager.adapter.isEnabled) {
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

    fun stopLeScan(immediately: Boolean = false) {
        scannerWrapper.stop(immediately = immediately)
        isScanning = false
        reconnectToAddr = null
        scope?.cancel()
    }

    private fun scanToReconnectTo(deviceAddr: String) {
        if (isScanning) return

        Log.i(TAG, "Start scan for $deviceAddr")

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
            if (scannerWrapper.scan(
                    scanFilters = listOf(scanFilter),
                    scanSettings = standardScanSettings, onScanResultCallback = onResultCallback
                )
            ) {
                Log.i(TAG, "Scanner success")
            } else {
                // didn't work, try with the fallback settings instead
                scannerWrapper.scan(
                    scanFilters = listOf(scanFilter),
                    scanSettings = fallbackScanSettings,
                    onScanResultCallback = onResultCallback
                )
            }
            isScanning = false
        }
    }

    companion object {
        private const val TAG = "FindReconnectWheel"
    }
}
