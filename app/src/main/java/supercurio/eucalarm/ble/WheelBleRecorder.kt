package supercurio.eucalarm.ble

import android.content.Context
import android.os.SystemClock
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import supercurio.eucalarm.utils.TimeUtils
import supercurio.eucalarm.utils.hasNotify
import supercurio.eucalarm.utils.writeWireMessageTo
import supercurio.wheeldata.recording.*
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*

class WheelBleRecorder(
    context: Context,
    private val scope: CoroutineScope,
    private val connection: WheelConnection
) {

    private val nanosStart = SystemClock.elapsedRealtimeNanos()
    private val outFile = generateRecordingFilename(context)
    private val out = outFile.outputStream().buffered()

    private val characteristicsKeys = CharacteristicsKeys()

    init {
        connection.gatt?.let { gatt ->
            // serves as header
            writeDeviceInfo(
                WheelBleDeviceInfo.fromGatt(gatt)
            )

            // subscribe to each characteristic with notification
            gatt.services.forEach { service ->
                service.characteristics.forEach { characteristic ->
                    if (characteristic.hasNotify())
                        gatt.setCharacteristicNotification(characteristic, true)
                }
            }
        }
        start()
    }

    fun stop() {
        out.flush()
        out.close()
        scope.cancel()
    }

    private fun start() = scope.launch {
        connection.notifiedCharacteristic.collect { notifiedCharacteristic ->
            writeNotificationData(notifiedCharacteristic)
        }
    }

    private fun writeNotificationData(notifiedCharacteristic: NotifiedCharacteristic) {
        gattNotification {
            characteristicKey = characteristicsKeys[notifiedCharacteristic.uuid] ?: -1
            timestamp = TimeUtils.timestampSinceNanos(nanosStart)
            bytes = ByteString.copyFrom(notifiedCharacteristic.value)
        }.writeWireMessageTo(out)
    }

    private fun generateRecordingFilename(context: Context): File {
        val destDir = File(context.filesDir, RECORDINGS_DIR)
        destDir.mkdirs()

        val date = Calendar.getInstance().time
        val dateFormat: DateFormat = SimpleDateFormat("yyyy-mm-dd_HH:mm:ss", Locale.ROOT)
        val strDate: String = dateFormat.format(date)

        val name = connection.gatt?.device?.name ?: "no-name"
        return File(destDir, "$name-$strDate.protobuf")
    }

    private fun writeDeviceInfo(deviceInfo: WheelBleDeviceInfo) {
        bleDeviceInfo {
            address = deviceInfo.address
            name = deviceInfo.name

            var characteristicId = 0
            gattServices += deviceInfo.services.map { service ->
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

        }.writeWireMessageTo(out)
    }

    companion object {
        private const val RECORDINGS_DIR = "recordings"

        fun getLastRecordingFile(context: Context): File? {
            val filesList = File(context.filesDir, RECORDINGS_DIR).listFiles()
            filesList?.sortByDescending { it.lastModified() }
            return filesList?.first()
        }
    }
}
