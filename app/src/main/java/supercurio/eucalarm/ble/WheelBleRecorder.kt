package supercurio.eucalarm.ble

import android.bluetooth.BluetoothGattService
import android.bluetooth.le.ScanRecord
import android.content.Context
import android.util.Log
import androidx.core.util.forEach
import com.google.protobuf.kotlin.toByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import supercurio.eucalarm.appstate.AppStateStore
import supercurio.eucalarm.appstate.ConnectedState
import supercurio.eucalarm.appstate.RecordingState
import supercurio.eucalarm.utils.*
import supercurio.wheeldata.recording.*
import java.io.BufferedOutputStream

class WheelBleRecorder(
    private val connection: WheelConnection,
    private val appStateStore: AppStateStore
) {
    private var startTime: NowAndTimestamp? = null
    private var out: BufferedOutputStream? = null
    private var recorderScope: CoroutineScope? = null
    private var characteristicsKeys: CharacteristicsKeys? = null

    val isRecording = MutableStateFlow(false)

    fun start(context: Context, storedDeviceAddr: String? = null) {
        val currentDeviceAddr = connection.device?.address

        if (storedDeviceAddr != null &&
            currentDeviceAddr != null &&
            storedDeviceAddr != currentDeviceAddr
        ) {
            Log.i(TAG, "Not recording from $currentDeviceAddr, stored device was $storedDeviceAddr")
            return
        }

        recorderScope = (MainScope() + CoroutineName(TAG))

        when (connection.connectionStateFlow.value) {
            BleConnectionState.CONNECTED_READY -> {
                Log.i(TAG, "Connection is ready, record")
                doRecording(context)
            }
            else -> recorderScope?.launch {
                connection.connectionStateFlow
                    .filter { it == BleConnectionState.CONNECTED_READY }
                    .take(1)
                    .collect {
                        if (storedDeviceAddr == connection.device?.address) {
                            Log.i(TAG, "Waited for a ready connection, record now")
                            doRecording(context)
                        }
                    }
            }
        }
    }

    fun stop() {
        out?.flush()
        out?.close()
        characteristicsKeys = null
        out = null
        connection.device?.address?.let {
            appStateStore.setState(ConnectedState(it))
        }
        isRecording.value = false
    }

    fun shutDown() {
        Log.i(TAG, "Shutdown")
        stop()
        startTime = null
        recorderScope?.cancel()
        recorderScope = null
        // always re-use the same instance after clearing it
    }

    private fun doRecording(context: Context) {
        // save app state if we record and have a connection to a device
        connection.device?.address?.let { addr ->
            appStateStore.setState(RecordingState(addr))
        }

        isRecording.value = true
        characteristicsKeys = CharacteristicsKeys()
        startTime = TimeUtils.timestampNow()
        out = RecordingProvider.generateRecordingFilename(
            context.directBootContext,
            connection.deviceName
        ).outputStream().buffered()

        connection.gatt?.let { gatt ->
            // serves as header
            Log.i(TAG, "Write device info")
            writeDeviceInfo(
                deviceAddress = gatt.device.address,
                deviceName = gatt.device.name ?: "name-missing",
                deviceServices = gatt.services,
                scanRecord = connection.advertisement
            )

            Log.i(TAG, "Write recording info")
            writeRecordingInfo()

            // subscribe to each characteristic with notification
            gatt.services.forEach { service ->
                service.characteristics.forEach { characteristic ->
                    if (characteristic.hasNotify())
                        gatt.setCharacteristicNotification(characteristic, true)
                }
            }
        }

        recorderScope?.launch {
            connection.notifiedCharacteristic.collect { notifiedCharacteristic ->
                writeNotificationData(notifiedCharacteristic)
            }
        }

        recorderScope?.launch {
            connection.connectionStateFlow.collect { state ->
                writeConnectionState(state)
            }
        }
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

    private fun writeConnectionState(state: BleConnectionState) = when (state) {
        BleConnectionState.BLUETOOTH_OFF -> ConnectionState.BLUETOOTH_OFF
        BleConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
        BleConnectionState.DISCONNECTING -> ConnectionState.DISCONNECTING
        BleConnectionState.SYSTEM_ALREADY_CONNECTED -> ConnectionState.SYSTEM_ALREADY_CONNECTED
        BleConnectionState.CONNECTING -> ConnectionState.CONNECTING
        BleConnectionState.DISCONNECTED_RECONNECTING -> ConnectionState.DISCONNECTED_RECONNECTING
        BleConnectionState.CONNECTED -> ConnectionState.CONNECTED
        BleConnectionState.CONNECTED_READY -> ConnectionState.CONNECTED_READY
        else -> ConnectionState.UNKNOWN
    }.writeWireMessageTo(out)

    private fun Any.writeWireMessageTo(out: BufferedOutputStream?) {
        if (out == null) return
        val input = this

        val message = recordingMessageType {
            when (input) {
                is BleDeviceInfo -> bleDeviceInfo = input
                is GattNotification -> gattNotification = input
                is RecordingInfo -> recordingInfo = input
                is ConnectionState -> connectionState = input
                else -> error("Unsupported message type")
            }
        }

        message.writeDelimitedTo(out)
        out.flush()

        if (WRITES_LOGGING) Log.d(TAG, message.toString())
    }


    companion object {
        private const val TAG = "WheelBleRecorder"

        private var instance: WheelBleRecorder? = null
        fun getInstance(connection: WheelConnection, appStateStore: AppStateStore) =
            instance ?: WheelBleRecorder(connection, appStateStore).also { instance = it }

        private const val WRITES_LOGGING = false
    }
}
