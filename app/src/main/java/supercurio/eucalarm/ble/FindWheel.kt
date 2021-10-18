package supercurio.eucalarm.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import supercurio.eucalarm.oems.GotwayWheel

class FindWheel(private val context: Context) {

    val isScanning = MutableStateFlow(false)
    val foundWheel = MutableStateFlow<DeviceFound?>(null)

    fun find() {
        startLeScan()
        FindConnectedWheel(context) {
            stopLeScan()
            foundWheel.value = it
        }.find()
    }


    fun stopLeScan() {
        if (isScanning.value) {
            bleScanner.stopScan(findLeScanCallback)
            isScanning.value = false
        }
    }

    /**
     * BLE Scanner
     */

    private fun startLeScan() {
        Log.i(TAG, "Start scan")

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(GotwayWheel.SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        bleScanner.startScan(listOf(scanFilter), scanSettings, findLeScanCallback)
        isScanning.value = true
    }

    private val findLeScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i(TAG, "LE scan result: $result")

            foundWheel.value = DeviceFound(result.device, result.scanRecord)
            isScanning.value = false

            bleScanner.stopScan(this)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed")
            isScanning.value = false
        }
    }


    companion object {
        private const val TAG = "FindWheel"


        val bleScanner: BluetoothLeScanner
            get() = BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner
    }
}


data class DeviceFound(val device: BluetoothDevice, val scanRecord: ScanRecord?)
