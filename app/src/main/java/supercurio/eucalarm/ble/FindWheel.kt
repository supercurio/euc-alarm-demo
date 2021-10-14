package supercurio.eucalarm.ble

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import supercurio.eucalarm.oems.GotwayWheel

class FindWheel(private val context: Context) {

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    val isScanning = MutableStateFlow(false)
    val foundWheel = MutableStateFlow<DeviceFound?>(null)

    fun find() {
        startLeScan()
        searchConnectedWheel()
    }


    fun stopLeScan() {
        if (isScanning.value) {
            bluetoothLeScanner?.stopScan(leScanCallback)
            isScanning.value = false
        }
    }

    /**
     * BLE Scanner
     */

    private fun startLeScan() {
        Log.i(TAG, "Start scan")

        bluetoothLeScanner = context.getSystemService<BluetoothManager>()!!
            .adapter
            .bluetoothLeScanner

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(GotwayWheel.SERVICE_UUID))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, leScanCallback)
        isScanning.value = true
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i(TAG, "LE scan result: $result")

            foundWheel.value = DeviceFound(result.device, result.scanRecord)
            isScanning.value = false

            bluetoothLeScanner?.stopScan(this)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed")
            isScanning.value = false
        }
    }


    /**
     * Find already connected wheel
     */

    private fun searchConnectedWheel() {
        context.getSystemService<BluetoothManager>()!!
            .getConnectedDevices(BluetoothProfile.GATT)
            .forEach {
                it.connectGatt(context, false, connectedDeviceGattCallback)
            }
    }

    private val connectedDeviceGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange: ${gatt.device.name}, newState: $newState")
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i(TAG, "STATE_CONNECTED")
                    gatt.discoverServices()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered: ${gatt.device.name}")
            gatt.services.firstOrNull {
                it.uuid.toString() == GotwayWheel.SERVICE_UUID
            }?.let {
                stopLeScan()
                foundWheel.value = DeviceFound(gatt.device, null)
            }
        }
    }

    companion object {
        private const val TAG = "FindWheel"
    }
}


data class DeviceFound(val device: BluetoothDevice, val scanRecord: ScanRecord?)
