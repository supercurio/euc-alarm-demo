package supercurio.eucalarm.ble

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.*
import supercurio.eucalarm.oems.GotwayWheel
import supercurio.eucalarm.power.PowerManagement
import supercurio.eucalarm.utils.RecordingProvider
import supercurio.eucalarm.utils.TimeUtils
import supercurio.eucalarm.utils.toHexString
import supercurio.wheeldata.recording.BleAdvertisement
import supercurio.wheeldata.recording.RecordingMessageType
import java.util.*

class WheelBleSimulator(context: Context, private val powerManagement: PowerManagement) {

    private val simulatorScope = MainScope() + CoroutineName(TAG)
    private val btManager by lazy { context.getSystemService<BluetoothManager>()!! }

    private lateinit var server: SuspendingGattServer
    private lateinit var input: RecordingProvider

    private var characteristicsKeys: CharacteristicsKeys? = null
    private var originalBtName: String? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connectedDevice: BluetoothDevice? = null
    private var advertisement: BleAdvertisement? = null
    private var doReplay = false

    fun start(context: Context, recordingProvider: RecordingProvider) {
        powerManagement.getLock(TAG)
        input = recordingProvider

        characteristicsKeys = CharacteristicsKeys()

        server = SuspendingGattServer(context, gattServerCallback)
        readDeviceInfo().let { deviceInfo ->
            characteristicsKeys?.fromDeviceInfo(deviceInfo)

            originalBtName = btManager.adapter.name
            btManager.adapter.name = deviceInfo.name

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

                simulatorScope.launch { server.addService(srv) }
            }

            advertisement = deviceInfo.advertisement
            startAdvertising()
        }
    }

    private fun readDeviceInfo() = RecordingMessageType
        .parseDelimitedFrom(input.inputStream)
        .bleDeviceInfo

    private fun startAdvertising() {
        Log.i(TAG, "Start advertising")
        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .build()
        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid.fromString(GotwayWheel.SERVICE_UUID))
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

        advertiser = btManager.adapter.bluetoothLeAdvertiser.apply {
            startAdvertising(advertiseSettings, advertiseData, advertiserCallback)
        }
    }

    private fun stopAdvertising() {
        Log.i(TAG, "Stop advertising")
        advertiser?.stopAdvertising(advertiserCallback)
    }

    private suspend fun mockReplay(device: BluetoothDevice) {
        var value = 1.toByte()

        val characteristic = server.services.mapNotNull { service ->
            service.characteristics.find { char ->
                char.uuid.toString() == GotwayWheel.DATA_CHARACTERISTIC_UUID
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
        Log.i(TAG, "Replay")
        input.reset()
        doReplay = true
        val nanoStart = SystemClock.elapsedRealtimeNanos()

        while (input.available() > 0 && doReplay) {
            val message = RecordingMessageType.parseDelimitedFrom(input.inputStream)

            when {
                message.hasGattNotification() -> {
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
            server.sendSuccess(device, requestId)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            Log.i(TAG, "onCharacteristicReadRequest")
            server.sendSuccess(device, requestId)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            server.sendSuccess(device, requestId)
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

            simulatorScope.launch {
                connectedDevice = device
                try {
                    replay(device)
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
            server.sendSuccess(device, requestId)
        }
    }

    private val advertiserCallback = object : AdvertiseCallback() {}

    fun stop() {
        stopReplay()
        input.close()
        stopAdvertising()
        server.clearServices()
        server.close()
        characteristicsKeys = null
        originalBtName?.let { btManager.adapter.name = it }
        powerManagement.removeLock(TAG)
    }

    fun shutdown() {
        Log.i(TAG, "Shutdown")
        simulatorScope.cancel()
        instance = null
    }

    companion object {
        private const val TAG = "WheelBleSimulator"
        private val SKIP_SERVICES = listOf(
            "00001800-0000-1000-8000-00805f9b34fb", // Generic Access
            "00001801-0000-1000-8000-00805f9b34fb", // Generic Attribute
            "0000180a-0000-1000-8000-00805f9b34fb", // Device Information
        )

        private const val ADVERTISE_SERVICE_DATA = false

        private var instance: WheelBleSimulator? = null
        fun getInstance(context: Context, powerManagement: PowerManagement) =
            WheelBleSimulator(context, powerManagement).also { instance = it }
    }
}
