package supercurio.eucalarm.ble.wrappers

import android.bluetooth.le.*
import android.content.Context
import android.text.format.DateUtils
import android.util.Log
import kotlinx.coroutines.*
import supercurio.eucalarm.utils.BluetoothUtils.toScanFailError
import supercurio.eucalarm.utils.btManager
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LeScannerWrapper(
    private val context: Context,
    private val name: String,
    private val stopDelay: Long
) {

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
        // in case we already have a scanner still running with the same parameters
        if (scanFilters.sameAs(currentScanFilters) && scanSettings.sameAs(currentScanSettings) &&
            delayedStopScope != null
        ) {
            log("Keep the same scanner running")
            deleteDelayedStopScope()
            return@suspendCoroutine
        }

        log("Create a new scanner")

        currentScanFilters = scanFilters
        currentScanSettings = scanSettings

        callback = object : ScanCallback() {
            override fun onScanFailed(errorCode: Int) {
                Log.e(
                    TAG,
                    "$name wrapper#${hashCode()} Scan failed with error: " +
                            toScanFailError(errorCode)
                )
                cont.resume(false)
            }

            override fun onScanResult(callbackType: Int, result: ScanResult) {
                super.onScanResult(callbackType, result)
                if (muted)
                    log("Ignored scan result for ${result.device.address}")
                else
                    onScanResultCallback(result)
            }
        }

        scanner?.apply {
            startScan(scanFilters, scanSettings, callback)
        } ?: run {
            cont.resume(false)
        }
    }

    fun stop() {
        log("stop")

        if (stopDelay == 0L) {
            stopScanNow()
        } else {
            delayedStopScope?.cancel()
            delayedStopScope = (CoroutineScope(Dispatchers.Default) + CoroutineName(TAG)).apply {
                launch {
                    log("Keep the scan running muted")
                    muted = true
                    delay(stopDelay)
                    log("Actually stop the scan now")

                    stopScanNow()
                    deleteDelayedStopScope()
                }
            }
        }

        continuation?.resume(true)
        continuation = null
    }

    private fun stopScanNow() {
        callback?.let {
            scanner?.stopScan(it)
            scanner?.flushPendingScanResults(it)
            callback = null
        }
    }

    private fun deleteDelayedStopScope() {
        delayedStopScope?.cancel()
        delayedStopScope = null
    }

    private fun log(string: String) {
        Log.d(TAG, "$name wrapper#${hashCode()}: $string")
    }

    private val scanner: BluetoothLeScanner?
        get() = context.btManager.adapter.bluetoothLeScanner

    companion object {
        private const val TAG = "LeScannerWrapper"

        // TODO: Maybe implement some throttling mechanism using these values
        // source: https://cs.android.com/android/platform/superproject/+/master:packages/apps/Bluetooth/src/com/android/bluetooth/btservice/AdapterService.java
        private const val DEFAULT_SCAN_QUOTA_COUNT = 5
        private const val DEFAULT_SCAN_QUOTA_WINDOW_MILLIS = 30 * DateUtils.SECOND_IN_MILLIS
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
