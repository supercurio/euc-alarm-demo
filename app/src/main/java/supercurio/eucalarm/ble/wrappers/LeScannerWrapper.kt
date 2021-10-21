package supercurio.eucalarm.ble.wrappers

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.util.Log
import supercurio.eucalarm.utils.BluetoothUtils.toScanFailError
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LeScannerWrapper {

    private var callback: ScanCallback? = null
    private var continuation: Continuation<ScannerWrapperResult>? = null

    suspend fun scan(
        scanFilters: List<ScanFilter>,
        scanSettings: ScanSettings,
        onScanResultCallback: (result: ScanResult) -> Unit
    ) = suspendCoroutine<ScannerWrapperResult> { cont ->

        continuation = cont

        callback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error: ${toScanFailError(errorCode)}")
                cont.resume(ScannerWrapperResult.Failure)
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                onScanResultCallback(result)
            }
        }

        scanner.startScan(scanFilters, scanSettings, callback)
    }


    fun stop() {
        Log.i(TAG, "stop")
        callback?.let { scanner.stopScan(it) }
        continuation?.resume(ScannerWrapperResult.Success)
        continuation = null
    }

    private val scanner get() = BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner

    companion object {
        private const val TAG = "LeScannerWrapper"
    }

    enum class ScannerWrapperResult {
        Success,
        Failure
    }
}
