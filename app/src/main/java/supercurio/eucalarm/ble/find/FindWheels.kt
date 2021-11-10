package supercurio.eucalarm.ble.find

import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import supercurio.eucalarm.ble.DeviceFound
import supercurio.eucalarm.ble.DeviceFoundFrom
import supercurio.eucalarm.ble.wrappers.LeScannerWrapper
import supercurio.eucalarm.oems.GotwayWheel
import supercurio.eucalarm.utils.btManager

class FindWheels(private val context: Context) {

    private var scope: CoroutineScope? = null
    private var findConnectedWheels: FindConnectedWheels? = null
    val isScanning = MutableStateFlow(false)
    val foundWheels = MutableStateFlow(listOf<DeviceFound>())

    private val offloadedFilteringSupported = context.btManager
        .adapter
        .isOffloadedFilteringSupported
    private val devicesFound = mutableMapOf<String, DeviceFound>()
    private var scannerWrapper = LeScannerWrapper(
        context = context,
        name = "$TAG#${hashCode()}",
        stopDelay = 10_000 // 10 seconds
    )

    private var keepFindingConnectedWheels = true

    fun find() {
        scope = CoroutineScope(Dispatchers.Default) + CoroutineName(TAG)

        keepFindingConnectedWheels = true
        startLeScan()

        findConnectedWheels = FindConnectedWheels(context) {
            devicesFound[it.device.address] = it
            if (isScanning.value) updateSateFlow()
        }

        scope?.launch {
            while (keepFindingConnectedWheels) {
                findConnectedWheels?.find()
                delay(MAX_AGE_MS)
                updateSateFlow()
            }
        }
    }

    fun stop() {
        keepFindingConnectedWheels = false
        findConnectedWheels?.stop()
        scannerWrapper.stop()
        devicesFound.clear()
        if (isScanning.value) updateSateFlow()
        isScanning.value = false

        scope?.cancel()
        scope = null
    }

    private fun updateSateFlow() {
        val list = devicesFound.values
            .filter { !it.expired(MAX_AGE_MS) }
            .sortedBy { it.device.name }
        foundWheels.value = list
    }

    /**
     * BLE Scanner
     */

    private fun startLeScan() = scope?.launch {
        Log.i(TAG, "Start scan, offloadedFilteringSupported: $offloadedFilteringSupported")

        val scanFilter = if (offloadedFilteringSupported) ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(GotwayWheel.SERVICE_UUID))
            .build()
        else
            ScanFilter.Builder().build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        val onResultCallback = fun(result: ScanResult) {
            Log.i(TAG, "LE scan result: $result")

            if (!offloadedFilteringSupported) {
                if (result.scanRecord
                        ?.serviceUuids
                        ?.contains(ParcelUuid.fromString(GotwayWheel.SERVICE_UUID)) != true
                ) return
            }

            devicesFound[result.device.address] = DeviceFound(
                device = result.device,
                from = DeviceFoundFrom.SCAN,
                scanRecord = result.scanRecord,
                rssi = result.rssi
            )
            if (isScanning.value) updateSateFlow()
        }

        isScanning.value = true
        scannerWrapper.scan(
            scanFilters = listOf(scanFilter),
            scanSettings = scanSettings,
            onScanResultCallback = onResultCallback
        )
        isScanning.value = false
    }

    companion object {
        private const val TAG = "FindWheels"

        private const val MAX_AGE_MS = 5000L
    }
}
