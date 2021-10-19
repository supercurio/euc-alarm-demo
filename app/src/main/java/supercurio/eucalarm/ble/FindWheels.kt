package supercurio.eucalarm.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import supercurio.eucalarm.oems.GotwayWheel

class FindWheels(private val context: Context) {

    private val scope = MainScope() + CoroutineName(TAG)

    private var findConnectedWheels: FindConnectedWheels? = null
    val isScanning = MutableStateFlow(false)
    val foundWheels = MutableStateFlow(listOf<DeviceFound>())

    private val devicesFound = mutableMapOf<String, DeviceFound>()
    private val scanner = BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner

    private var findLeScanCallback: ScanCallback? = null

    private var keepFindingConnectedWheels = true

    fun find() {
        keepFindingConnectedWheels = true
        startLeScan()

        findConnectedWheels = FindConnectedWheels(context) {
            devicesFound[it.device.address] = it
            if (isScanning.value) updateSateFlow()
        }

        scope.launch {
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

        findLeScanCallback?.let {
            scanner.stopScan(it)
            scanner.flushPendingScanResults(it)
            findLeScanCallback = null
        }
        devicesFound.clear()
        if (isScanning.value) updateSateFlow()
        isScanning.value = false
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

    private fun startLeScan() {
        Log.i(TAG, "Start scan")

        val callback = getCallback().also { findLeScanCallback = it }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(GotwayWheel.SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        scanner.startScan(listOf(scanFilter), scanSettings, callback)
        isScanning.value = true
    }

    private fun getCallback() = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i(TAG, "LE scan result: $result")

            devicesFound[result.device.address] = DeviceFound(
                device = result.device,
                from = DeviceFoundFrom.SCAN,
                scanRecord = result.scanRecord,
                rssi = result.rssi
            )
            if (isScanning.value) updateSateFlow()
        }

        override fun onScanFailed(errorCode: Int) {
            val error = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                    "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                else -> "Unknown"
            }
            Log.e(TAG, "Scan failed with error: $error")
            isScanning.value = false
        }
    }

    companion object {
        private const val TAG = "FindWheels"

        private const val MAX_AGE_MS = 2000L
    }
}
