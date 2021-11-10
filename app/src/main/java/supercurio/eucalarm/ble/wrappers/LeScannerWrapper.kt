package supercurio.eucalarm.ble.wrappers

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*
import android.util.Log
import kotlinx.coroutines.*
import supercurio.eucalarm.utils.BluetoothUtils.toScanFailError
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LeScannerWrapper {

    private var delayedStopScope: CoroutineScope? = null
    private var callback: ScanCallback? = null
    private var continuation: Continuation<Boolean>? = null

    private var muted = false
    private var currentScanFilters: List<ScanFilter>? = null
    private var currentScanSettings: ScanSettings? = null

    suspend fun scan(
        scanFilters: List<ScanFilter>,
        scanSettings: ScanSettings,
        onScanResultCallback: (result: ScanResult) -> Unit
    ) = suspendCoroutine<Boolean> { cont ->

        continuation = cont

        muted = false
        // in case we already had a scanner running with the same parameters
        if (scanFilters.sameAs(currentScanFilters) && scanSettings.sameAs(currentScanSettings) &&
            delayedStopScope != null
        ) {
            Log.d(TAG, "Keep the same scanner running")
            deleteDelayedStopScope()
            return@suspendCoroutine
        }

        Log.d(TAG, "Create a new scanner")

        currentScanFilters = scanFilters
        currentScanSettings = scanSettings

        callback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error: ${toScanFailError(errorCode)}")
                cont.resume(false)
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                if (!muted) onScanResultCallback(result)
            }
        }

        scanner?.apply {
            startScan(scanFilters, scanSettings, callback)
        } ?: run {
            cont.resume(false)
        }
    }

    fun stop() {
        Log.i(TAG, "stop")

        delayedStopScope?.cancel()
        delayedStopScope = (CoroutineScope(Dispatchers.Default) + CoroutineName(TAG)).apply {
            launch {
                Log.i(TAG, "Keep the scan running muted")
                muted = true
                delay(DELAY_BEFORE_STOPPING_SCAN)
                Log.i(TAG, "Actually stop the scan now")
                callback?.let {
                    scanner?.stopScan(it)
                    scanner?.flushPendingScanResults(it)
                    callback = null
                }

                deleteDelayedStopScope()
            }
        }


        continuation?.resume(true)
        continuation = null
    }

    private fun deleteDelayedStopScope() {
        delayedStopScope?.cancel()
        delayedStopScope = null
    }

    private val scanner: BluetoothLeScanner?
        get() = BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner

    companion object {
        private const val TAG = "LeScannerWrapper"
        private const val DELAY_BEFORE_STOPPING_SCAN = 10000L
    }
}

private fun List<ScanFilter>.sameAs(other: List<ScanFilter>?): Boolean {
    if (other == null) return false

    return size == other.size &&
            zip(other).all { (first, second) -> first == second }
}

private fun ScanFilter.sameAs(other: ScanFilter) =
    other.deviceAddress == deviceAddress &&
            other.deviceName == deviceName &&
            other.manufacturerId == manufacturerId &&
            other.serviceData.contentEquals(serviceData) &&
            other.serviceDataMask.contentEquals(serviceDataMask) &&
            other.serviceUuid?.uuid == serviceUuid?.uuid &&
            other.serviceDataUuid?.uuid == serviceDataUuid?.uuid

private fun ScanSettings.sameAs(other: ScanSettings?): Boolean {
    if (other == null) return false

    return other.callbackType == callbackType &&
            other.reportDelayMillis == reportDelayMillis &&
            other.scanMode == scanMode &&
            other.scanResultType == scanResultType
}
