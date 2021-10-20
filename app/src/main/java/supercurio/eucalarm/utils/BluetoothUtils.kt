package supercurio.eucalarm.utils

import android.bluetooth.le.ScanCallback

object BluetoothUtils {

    fun toScanFailError(errorCode: Int) = when (errorCode) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
            "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
        5 -> "SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES"
        6 -> "SCAN_FAILED_SCANNING_TOO_FREQUENTLY"
        else -> "Unknown ($errorCode)"
    }
}
