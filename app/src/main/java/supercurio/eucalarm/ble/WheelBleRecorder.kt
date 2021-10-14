package supercurio.eucalarm.ble

import android.bluetooth.BluetoothGattService
import android.bluetooth.le.ScanRecord
import android.content.Context
import androidx.core.util.forEach
import com.google.protobuf.kotlin.toByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import supercurio.eucalarm.utils.NowAndTimestamp
import supercurio.eucalarm.utils.TimeUtils
import supercurio.eucalarm.utils.hasNotify
import supercurio.wheeldata.recording.*
import java.io.BufferedOutputStream
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class WheelBleRecorder(private val connection: WheelConnection) {

    private var startTime: NowAndTimestamp? = null
    private var out: BufferedOutputStream? = null
    private var recorderScope: CoroutineScope? = null
    private var characteristicsKeys: CharacteristicsKeys? = null

    val isRecording = MutableStateFlow(false)

    fun start(context: Context) {
        isRecording.value = true
        characteristicsKeys = CharacteristicsKeys()
        startTime = TimeUtils.timestampNow()
        out = generateRecordingFilename(context)
            .outputStream()
            .buffered()

        connection.gatt?.let { gatt ->
            // serves as header
            writeDeviceInfo(
                deviceAddress = gatt.device.address,
                deviceName = gatt.device.name ?: "name-missing",
                deviceServices = gatt.services,
                scanRecord = connection.advertisement
            )

            writeRecordingInfo()

            // subscribe to each characteristic with notification
            gatt.services.forEach { service ->
                service.characteristics.forEach { characteristic ->
                    if (characteristic.hasNotify())
                        gatt.setCharacteristicNotification(characteristic, true)
                }
            }
        }

        val scope = (MainScope() + CoroutineName(TAG)).also { recorderScope = it }

        scope.launch {
            connection.notifiedCharacteristic.collect { notifiedCharacteristic ->
                writeNotificationData(notifiedCharacteristic)
            }
        }
    }

    fun stop() {
        out?.flush()
        out?.close()
        characteristicsKeys = null
        out = null
        isRecording.value = false
    }

    fun shutDown() {
        stop()
        startTime = null
        recorderScope?.cancel()
        recorderScope = null
        // always re-use the same instance after clearing it
    }

    private fun writeNotificationData(notifiedCharacteristic: NotifiedCharacteristic) =
        characteristicsKeys?.let { characteristicsKeys ->

            gattNotification {
                characteristicKey = characteristicsKeys[notifiedCharacteristic.uuid]
                    ?: error("Invalid characteristic")
                startTime?.nano?.let { elapsedTimestamp = TimeUtils.timestampSinceNanos(it) }
                bytes = notifiedCharacteristic.value.toByteString()
            }.writeWireMessageTo(out)
        }


    private fun generateRecordingFilename(context: Context): File {
        val destDir = File(context.filesDir, RECORDINGS_DIR)
        destDir.mkdirs()

        val date = Calendar.getInstance().time
        val dateFormat: DateFormat = SimpleDateFormat("yyyy-mm-dd_HH:mm:ss", Locale.ROOT)
        val strDate: String = dateFormat.format(date)

        val name = connection.gatt?.device?.name ?: "no-name"
        return File(destDir, "$name-$strDate.bwr")
    }

    private fun writeDeviceInfo(
        deviceAddress: String,
        deviceName: String,
        deviceServices: List<BluetoothGattService>,
        scanRecord: ScanRecord?
    ) = bleDeviceInfo {
        val characteristicsKeys = characteristicsKeys ?: return

        address = deviceAddress
        name = deviceName

        var characteristicId = 0
        gattServices += deviceServices.map { service ->
            gattService {
                uuid = service.uuid.toString()
                type = service.type

                service.characteristics.forEach { characteristic ->
                    val charUUID = characteristic.uuid.toString()

                    characteristicsKeys[charUUID] = characteristicId

                    gattCharacteristics[characteristicId] = gattCharacteristic {
                        uuid = charUUID
                        properties = characteristic.properties

                        gattDescriptors += characteristic.descriptors.map { descriptor ->
                            gattDescriptor { uuid = descriptor.uuid.toString() }
                        }
                    }
                    characteristicId++
                }
            }
        }

        scanRecord?.let { scanRecord ->
            advertisement = bleAdvertisement {
                scanRecord.serviceData.forEach { (uuid, bytes) ->
                    serviceData[uuid.toString()] = bytes.toByteString()
                }
                scanRecord.manufacturerSpecificData.forEach { key, value ->
                    manufacturerData[key] = value.toByteString()
                }
            }
        }

    }.writeWireMessageTo(out)

    private fun writeRecordingInfo() = recordingInfo {
        startTime?.timestamp?.let { startTimestamp = it }
    }.writeWireMessageTo(out)

    private fun Any.writeWireMessageTo(out: BufferedOutputStream?) {
        if (out == null) return
        val message = when (val src = this) {
            is BleDeviceInfo -> recordingMessageType { bleDeviceInfo = src }
            is GattNotification -> recordingMessageType { gattNotification = src }
            is RecordingInfo -> recordingMessageType { recordingInfo = src }
            else -> null
        } ?: error("Unsupported message type")

        message.writeDelimitedTo(out)
        out.flush()

//        Log.i(
//            TAG, JsonFormat.printer()
//                .includingDefaultValueFields()
//                .print(message)
//        )
    }


    companion object {
        private const val TAG = "WheelBleRecorder"
        private const val RECORDINGS_DIR = "recordings"

        fun getLastRecordingFile(context: Context): File? {
            val filesList = File(context.filesDir, RECORDINGS_DIR).listFiles()
            filesList?.sortByDescending { it.lastModified() }
            return filesList?.first()
        }

        private var instance: WheelBleRecorder? = null
        fun getInstance(connection: WheelConnection) =
            instance ?: WheelBleRecorder(connection).also { instance = it }
    }
}
