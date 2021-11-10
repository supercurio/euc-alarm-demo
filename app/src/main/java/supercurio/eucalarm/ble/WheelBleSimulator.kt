package supercurio.eucalarm.ble

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import supercurio.eucalarm.ble.wrappers.SuspendingGattServer
import supercurio.eucalarm.oems.GotwayAndVeteranParser
import supercurio.eucalarm.power.PowerManagement
import supercurio.eucalarm.utils.RecordingProvider
import supercurio.eucalarm.utils.TimeUtils
import supercurio.eucalarm.utils.btManager
import supercurio.eucalarm.utils.toHexString
import supercurio.wheeldata.recording.BleAdvertisement
import supercurio.wheeldata.recording.RecordingMessageType
import java.io.InputStream
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WheelBleSimulator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val powerManagement: PowerManagement
) {

    private var simulatorScope: CoroutineScope? = null

    private var server: SuspendingGattServer? = null
    private var input: RecordingProvider? = null

    private var characteristicsKeys: CharacteristicsKeys? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null
    private var advertisement: BleAdvertisement? = null
    private var doReplay = false

    var isSupported = detectSupport()

    fun start(context: Context, recordingProvider: RecordingProvider) {
        if (!isSupported) return

        simulatorScope = CoroutineScope(Dispatchers.Default) + CoroutineName(TAG)

        val intentFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.i(TAG, "Intent: ${intent.action}")
            }

        }
        context.registerReceiver(receiver, intentFilter)

        powerManagement.getLock(TAG)
        input = recordingProvider

        characteristicsKeys = CharacteristicsKeys()

        server = SuspendingGattServer(context, gattServerCallback)
        readDeviceInfo(recordingProvider.inputStream).let { deviceInfo ->
            characteristicsKeys?.fromDeviceInfo(deviceInfo)

            Log.i(TAG, "Device Info: $deviceInfo")

            deviceInfo.gattServicesList.forEach { service ->
                Log.i(TAG, "Found service: ${service.uuid} type: ${service.type}")

                if (SKIP_SERVICES.contains(service.uuid)) return@forEach

                val srv = BluetoothGattService(
                    UUID.fromString(service.uuid),
                    service.type
                )

                service.gattCharacteristicsMap.values.forEach { characteristic ->
                    val char = BluetoothGattCharacteristic(
                        UUID.fromString(characteristic.uuid),
                        characteristic.properties,
                        BluetoothGattCharacteristic.PERMISSION_READ or
                                BluetoothGattCharacteristic.PERMISSION_WRITE
                    )

                    characteristic.gattDescriptorsList.forEach { descriptor ->
                        val desc = BluetoothGattDescriptor(
                            UUID.fromString(descriptor.uuid),
                            BluetoothGattDescriptor.PERMISSION_READ or
                                    BluetoothGattDescriptor.PERMISSION_WRITE
                        )
                        char.addDescriptor(desc)
                    }
                    srv.addCharacteristic(char)
                }

                simulatorScope?.launch { server?.addService(srv) }
            }

            advertisement = deviceInfo.advertisement
            startAdvertising()
        }
    }

    fun stop() {
        stopReplay()
        input?.close()
        input = null
        stopAdvertising()
        connectedDevice?.let { server?.cancelConnection(it) }
        connectedDevice = null
        server?.clearServices()
        server?.close()
        server = null
        characteristicsKeys = null
        powerManagement.removeLock(TAG)
    }

    fun detectSupport(): Boolean {
        isSupported = (context.btManager.adapter.bluetoothLeAdvertiser != null)
        return isSupported
    }

    fun shutdown() {
        stop()
        Log.i(TAG, "Shutdown")
        characteristicsKeys = null
        advertiser = null
        simulatorScope?.cancel()
    }

    private fun readDeviceInfo(inputStream: InputStream) =
        RecordingMessageType
            .parseDelimitedFrom(inputStream)
            .bleDeviceInfo

    private fun startAdvertising() {
        Log.i(TAG, "Start advertising")
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid.fromString(GotwayAndVeteranParser.SERVICE_UUID))
            .apply {
                advertisement?.let { advertisement ->
                    advertisement.manufacturerDataMap.forEach { (id, bytes) ->
                        addManufacturerData(id, bytes.toByteArray())
                    }
                    // FIXME: fails to advertise when adding service data
                    if (ADVERTISE_SERVICE_DATA)
                        advertisement.serviceDataMap.forEach { (uuid, bytes) ->
                            addServiceData(ParcelUuid.fromString(uuid), bytes.toByteArray())
                        }
                }
            }.build()

        val adapter = context.btManager.adapter
        advertiser = adapter.bluetoothLeAdvertiser?.apply {
            Log.i(
                TAG, "Adapter isMultipleAdvertisementSupported: " +
                        "${adapter.isMultipleAdvertisementSupported}"
            )

            startAdvertising(advertiseSettings, advertiseData, advertiserCallback)
        }
    }

    private fun stopAdvertising() {
        Log.i(TAG, "Stop advertising")
        advertiser?.stopAdvertising(advertiserCallback)
    }

    private suspend fun mockReplay(device: BluetoothDevice) {
        val server = server ?: return
        var value = 1.toByte()

        val characteristic = server.services.mapNotNull { service ->
            service.characteristics.find { char ->
                char.uuid.toString() == GotwayAndVeteranParser.DATA_CHARACTERISTIC_UUID
            }
        }.first()

        while (true) {
            value = (((value + 1) % Byte.MAX_VALUE).toByte())
            characteristic.value = byteArrayOf(value)
            server.notifyCharacteristicChanged(device, characteristic, true)
            Log.i(TAG, "value: $value")
        }
    }

    private suspend fun replay(device: BluetoothDevice) {
        val characteristicsKeys = characteristicsKeys ?: return
        val input = input ?: return
        Log.i(TAG, "Replay")
        input.reset()
        doReplay = true
        val nanoStart = SystemClock.elapsedRealtimeNanos()

        while (input.available() > 0 && doReplay) {
            val message = RecordingMessageType.parseDelimitedFrom(input.inputStream)

            when {
                message.hasGattNotification() -> {
                    val server = server ?: return
                    val notification = message.gattNotification
                    val characteristic = characteristicsKeys.getCharacteristic(
                        server.services,
                        notification.characteristicKey
                    ) ?: continue

                    characteristic.value = notification.bytes.toByteArray()

                    TimeUtils.delayFromNotification(nanoStart, notification)

                    server.notifyCharacteristicChanged(device, characteristic, false)
                }
            }
        }
    }

    private fun stopReplay() {
        doReplay = false
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val newStateText = when (newState) {
                BluetoothGattServer.STATE_CONNECTING -> "STATE_CONNECTING"
                BluetoothGattServer.STATE_CONNECTED -> "STATE_CONNECTED"
                BluetoothGattServer.STATE_DISCONNECTING -> "STATE_DISCONNECTING"
                BluetoothGattServer.STATE_DISCONNECTED -> "STATE_DISCONNECTED"
                else -> ""
            }
            Log.i(TAG, "Device: $device, status: $status, newState: $newStateText")

            if (newState == BluetoothGattServer.STATE_DISCONNECTED && connectedDevice == device)
                stopReplay()
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.i(TAG, "onCharacteristicWriteRequest")
            server?.sendSuccess(device, requestId)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.i(TAG, "onCharacteristicReadRequest")
            server?.sendSuccess(device, requestId)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            server?.sendSuccess(device, requestId)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            Log.i(TAG, "onDescriptorWriteRequest value: ${value?.toHexString()}")

            simulatorScope?.launch {
                connectedDevice = device
                try {
                    replay(device)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
            server?.sendSuccess(device, requestId)
        }
    }

    private val advertiserCallback = object : AdvertiseCallback() {}

    companion object {
        private const val TAG = "WheelBleSimulator"
        private val SKIP_SERVICES = listOf(
            "00001800-0000-1000-8000-00805f9b34fb", // Generic Access
            "00001801-0000-1000-8000-00805f9b34fb", // Generic Attribute
            "0000180a-0000-1000-8000-00805f9b34fb", // Device Information
        )

        private const val ADVERTISE_SERVICE_DATA = false
    }
}
