package supercurio.eucalarm.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import supercurio.eucalarm.oems.GotwayWheel

class FindWheel(private val context: Context, private val scope: CoroutineScope) {

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    val isScanning = MutableStateFlow(false)
    val foundWheel = MutableStateFlow<WheelFound?>(null)

    fun find() {
        startLeScan()
        searchConnectedWheel()
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
        scope.launch { isScanning.emit(true) }
    }

    fun stopLeScan() {
        bluetoothLeScanner?.stopScan(leScanCallback)
        scope.launch { isScanning.emit(false) }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i(TAG, "LE scan result: $result")

            scope.launch {
                foundWheel.emit(WheelFound(result.device))
                isScanning.emit(false)
            }

            bluetoothLeScanner?.stopScan(this)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed")
            scope.launch { isScanning.emit(false) }
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
                scope.launch { foundWheel.emit(WheelFound(gatt.device)) }
            }
        }
    }


    companion object {
        private const val TAG = "FindWheel"
    }
}
