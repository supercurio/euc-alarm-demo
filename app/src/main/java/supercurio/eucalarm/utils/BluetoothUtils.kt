package supercurio.eucalarm.utils

import android.bluetooth.BluetoothGatt
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.util.Log
import java.nio.ByteBuffer
import java.util.*

object BluetoothUtils {

    val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

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

    fun toServiceAddedStatus(status: Int) = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "Success"
        else -> "Failure"
    }

    fun getAdvertiserCallback(tag: String) = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.d(tag, "Advertisement start success: $settingsInEffect")
        }

        override fun onStartFailure(errorCode: Int) {
            val failure = when (errorCode) {
                ADVERTISE_FAILED_ALREADY_STARTED -> "ADVERTISE_FAILED_ALREADY_STARTED"
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_ALREADY_STARTED"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "ADVERTISE_FAILED_ALREADY_STARTED"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "ADVERTISE_FAILED_INTERNAL_ERROR"
                else -> "Unknown"
            }

            Log.i(tag, "Advertisement start failure: $failure")
        }
    }

    fun customGattUuid(value: Int): UUID {
        val bb = ByteBuffer.allocate(2 * Long.SIZE_BYTES).apply {
            putInt(value)
            put("supercurio:)".toByteArray())
            rewind()
        }

        return UUID(bb.long, bb.long)
    }
}
