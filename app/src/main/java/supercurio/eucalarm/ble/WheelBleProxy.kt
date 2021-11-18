package supercurio.eucalarm.ble

import android.bluetooth.*
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import supercurio.eucalarm.GeneralConfig
import supercurio.eucalarm.ble.wrappers.BluetoothAdapterLock
import supercurio.eucalarm.ble.wrappers.GattServer
import supercurio.eucalarm.di.CoroutineScopeProvider
import supercurio.eucalarm.parsers.GotwayAndVeteranParser
import supercurio.eucalarm.utils.BluetoothUtils
import supercurio.eucalarm.utils.BluetoothUtils.CLIENT_CHARACTERISTIC_CONFIG
import supercurio.eucalarm.utils.toHexString
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WheelBleProxy @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connection: WheelConnection,
    private val bluetoothAdapterLock: BluetoothAdapterLock,
    private val generalConfig: GeneralConfig,
    private val scopes: CoroutineScopeProvider,
) {
    private val _status = MutableStateFlow(false)
    val status = _status.asStateFlow()

    private var proxyScope: CoroutineScope? = null
    private var server: GattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private var srcCharacteristics = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private var srcDescriptors = ConcurrentHashMap<String, BluetoothGattDescriptor>()

    private var proxyCharacteristics = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private var proxyDescriptors = ConcurrentHashMap<String, BluetoothGattDescriptor>()

    private val connectedDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val devicesReceivingStatus = ConcurrentHashMap<String, BluetoothDevice>()

    private var proxyStatusCharacteristic: BluetoothGattCharacteristic? = null

    private val isSupported get() = adapter.bluetoothLeAdvertiser != null

    fun setup() {
        scopes.app.launch {
            connection.connectionStateFlow.collect { connectionState ->
                if (!generalConfig.wheelProxy) return@collect

                when (connectionState) {
                    BleConnectionState.CONNECTED_READY -> {
                        // refresh services characteristics and descriptors from the new gatt client
                        if (status.value) cacheSrcServices()
                        notifyProxyStatus()

                        enable(true)
                    }

                    BleConnectionState.DISCONNECTED,
                    BleConnectionState.BLUETOOTH_OFF -> enable(false)

                    else -> notifyProxyStatus()
                }
            }
        }
    }

    fun start() {
        if (!isSupported) return

        _status.value = true
        Log.i(TAG, "start")
        server = GattServer(context, callback, bluetoothAdapterLock)

        proxyScope = CoroutineScope(Dispatchers.Default) + CoroutineName(TAG)

        cacheSrcServices()
        addProxyServices()
        addCustomService()
        advertise()

        proxyScope?.launch {
            connection.notifiedCharacteristic.collect { notifiedCharacteristic ->
                val proxyChar = proxyCharacteristics[notifiedCharacteristic.uuid] ?: return@collect
                connectedDevices.values.forEach { device ->
                    proxyChar.value = notifiedCharacteristic.value.copyOf()
                    server?.notifyCharacteristicChanged(device, proxyChar, false)
                }
            }
        }
    }

    fun stop() {
        Log.i(TAG, "Stop")
        connectedDevices.clear()
        proxyScope?.cancel()
        proxyScope = null

        notifyProxyStatus(status = PROXY_STATUS_STOPPING)
        proxyStatusCharacteristic = null

        stopAdvertising()

        server?.disconnectAllDevices()
        server?.clearServices()
        server?.close()

        srcCharacteristics.clear()
        srcDescriptors.clear()
        proxyCharacteristics.clear()
        proxyDescriptors.clear()

        server = null
        _status.value = false
    }

    fun shutdown() {
        stop()
    }

    private fun enable(status: Boolean) {
        if (_status.value == status) return
        if (status) start() else stop()
    }

    private fun cacheSrcServices() {
        val gattClient = connection.gattClient ?: return
        gattClient.services.forEach { service ->
            service.characteristics.forEach { char ->
                srcCharacteristics[char.uuid.toString()] = char
                char.descriptors.forEach { desc ->
                    srcDescriptors[desc.uuid.toString()] = desc
                }
            }
        }
    }

    private fun addProxyServices() {
        val gattClient = connection.gattClient ?: return

        // mirror services
        gattClient.services.forEach { service ->

            if (RESTRICTED_SERVICES.contains(service.uuid.toString())) {
                Log.i(TAG, "Ignoring restricted service: ${service.uuid}")
                return@forEach
            }

            // each service
            val proxyService = BluetoothGattService(service.uuid, service.type)
            service.characteristics.forEach { char ->
                // each characteristic

                val proxyChar =
                    BluetoothGattCharacteristic(
                        char.uuid, char.properties,
                        BluetoothGattDescriptor.PERMISSION_READ or
                                BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                char.descriptors.forEach { desc ->
                    // each descriptor

                    val proxyDesc = BluetoothGattDescriptor(
                        desc.uuid,
                        // set as read-write since the API always returns 0
                        BluetoothGattDescriptor.PERMISSION_READ or
                                BluetoothGattDescriptor.PERMISSION_WRITE
                    )

                    proxyChar.addDescriptor(proxyDesc)

                    proxyDescriptors[desc.uuid.toString()] = proxyDesc
                }
                proxyService.addCharacteristic(proxyChar)

                proxyCharacteristics[char.uuid.toString()] = proxyChar
            }

            Log.d(TAG, "Adding proxy service: ${proxyService.uuid}")
            server?.addService(proxyService)?.let { status ->
                Log.i(TAG, "Service added: ${BluetoothUtils.toServiceAddedStatus(status)}")
            }
        }
    }

    private fun addCustomService() {
        val customService = BluetoothGattService(
            CUSTOM_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val statusChar = BluetoothGattCharacteristic(
            CUSTOM_PROXY_STATUS,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        statusChar.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ or
                        BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )

        proxyStatusCharacteristic = statusChar
        customService.addCharacteristic(statusChar)

        listOf(
            CUSTOM_CHAR_ORIGINAL_NAME,
            CUSTOM_ORIGINAL_MAC_ADDR
        ).forEach { uuid ->
            customService.addCharacteristic(
                BluetoothGattCharacteristic(
                    uuid,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
            )
        }

        server?.addService(customService)
    }

    private fun advertise() {

        val proxyName = connection.deviceName + PROXY_SUFFIX
        if (adapter.name == proxyName) {
            startAdvertising()
            return
        }


        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
        }
        context.registerReceiver(btNameChangeReceiver, filter)

        generalConfig.originalBluetoothName = adapter.name
        adapter.name = proxyName
    }

    private val btNameChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED) {
                context.unregisterReceiver(this)
                intent.extras?.getString(BluetoothAdapter.EXTRA_LOCAL_NAME)?.let { name ->
                    Log.i(TAG, "Bluetooth name changed to $name")
                    startAdvertising()
                }
            }
        }
    }

    private fun startAdvertising() {
        Log.i(TAG, "Start advertising")

        val advertiseSettings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .apply {
                connection.advertisement?.let { scanRecord ->
                    // the original scan record is available when finding the wheel
                    scanRecord.serviceUuids.forEach { addServiceUuid(it) }

                    // FIXME: fails to advertise when adding service data?
                    if (ADVERTISE_SERVICE_DATA)
                        scanRecord.serviceData.forEach { (serviceDataUuid, serviceData) ->
                            addServiceData(serviceDataUuid, serviceData)
                        }

                } ?: run {
                    // Making up a static advertisement for a Gotway wheel.
                    // TODO: Needs improvement
                    addServiceUuid(ParcelUuid.fromString(GotwayAndVeteranParser.SERVICE_UUID))
                }
            }.build()

        advertiser = adapter.bluetoothLeAdvertiser?.apply {
            startAdvertising(advertiseSettings, advertiseData, advertiserCallback)
        }
    }


    private fun stopAdvertising() {
        Log.i(TAG, "Stop advertising")

        generalConfig.originalBluetoothName?.let { adapter.name = it }
        advertiser?.stopAdvertising(advertiserCallback)
        advertiser = null
    }

    private fun logConnectedDevices() {
        Log.i(TAG, "Connected devices:\n  ${connectedDevices.values.joinToString("\n  ")}")
    }

    /*
     *  Main callback
     */

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothGattServer.STATE_CONNECTED ->
                    if (server?.isConnected(device) == true &&
                        device.address != connection.device?.address
                    ) {
                        connectedDevices[device.address] = device
                        Log.i(TAG, "Device added: $device")
                        logConnectedDevices()
                    }

                BluetoothGattServer.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device.address)
                    devicesReceivingStatus.remove(device.address)
                    Log.i(TAG, "Device removed: $device")
                    logConnectedDevices()
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            proxyDesc: BluetoothGattDescriptor
        ) {
            Log.v(TAG, "onDescriptorReadRequest for ${proxyDesc.uuid}")

            val gattClient = connection.gattClient ?: return
            val srcDescriptor = srcDescriptors[proxyDesc.uuid.toString()] ?: return

            val value = gattClient.readDescriptor(srcDescriptor)
            Log.i(TAG, "Got descriptor value from client ${value.toHexString()}")
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            proxyDesc: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.v(
                TAG, "onDescriptorWriteRequest for ${proxyDesc.uuid}, on " +
                        "${proxyDesc.characteristic.uuid}"
            )

            when (proxyDesc.characteristic.uuid) {
                CUSTOM_PROXY_STATUS -> {
                    val enabled = listOf(
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    )

                    if (enabled.any { it.contentEquals(value) }) {
                        Log.v(TAG, "Enable status notifications for $device")
                        devicesReceivingStatus[device.address] = device
                        notifyProxyStatus(device)
                    } else {
                        Log.v(TAG, "Disable status notifications for $device")
                        devicesReceivingStatus.remove(device.address)
                    }
                }
            }

            val gattClient = connection.gattClient ?: return
            val srcDescriptor = srcDescriptors[proxyDesc.uuid.toString()] ?: return

            srcDescriptor.value = value
            gattClient.writeDescriptor(srcDescriptor)
            server?.sendSuccess(device, requestId)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            proxyChar: BluetoothGattCharacteristic
        ) {
            Log.v(TAG, "#$requestId Read characteristic ${proxyChar.uuid}")

            when (proxyChar.uuid) {
                CUSTOM_CHAR_ORIGINAL_NAME -> {
                    server?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        connection.deviceName.toByteArray()
                    )
                    return
                }
                CUSTOM_ORIGINAL_MAC_ADDR -> {
                    server?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        connection.device?.address?.toByteArray()
                    )
                    return
                }
                CUSTOM_PROXY_STATUS -> {
                    server?.sendResponse(
                        device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                        byteArrayOf(connectionStateToProxyStatus)
                    )
                    return
                }
            }

            val gattClient = connection.gattClient ?: return
            val srcChar = srcCharacteristics[proxyChar.uuid.toString()] ?: return

            val value = gattClient.readCharacteristic(srcChar)
            Log.i(TAG, "#$requestId Got characteristic value from client ${value.toHexString()}")
            proxyChar.value = value
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            proxyChar: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "Write characteristic ${proxyChar.uuid}: ${value.toHexString()}")

            val gattClient = connection.gattClient ?: return
            val srcChar = srcCharacteristics[proxyChar.uuid.toString()] ?: return

            srcChar.value = value
            gattClient.writeCharacteristic(srcChar)
            server?.sendSuccess(device, requestId)
        }
    }

    private fun notifyProxyStatus(device: BluetoothDevice? = null, status: Byte? = null) {
        val devices = device?.let { listOf(it) } ?: devicesReceivingStatus.values
        proxyStatusCharacteristic?.let {
            devices.forEach { device ->
                it.value = byteArrayOf(status ?: connectionStateToProxyStatus)
                server?.notifyCharacteristicChanged(device, it, true)
            }
        }
    }


    private val connectionStateToProxyStatus
        get() = when (connection.connectionState) {
            BleConnectionState.CONNECTED_READY -> PROXY_STATUS_WHEEL_CONNECTION_ACTIVE
            else -> PROXY_STATUS_WHEEL_CONNECTION_INACTIVE
        }

    private val adapter get() = context.getSystemService<BluetoothManager>()!!.adapter

    private val advertiserCallback = BluetoothUtils.getAdvertiserCallback(TAG)

    companion object {
        const val PROXY_SUFFIX = " Proxy"

        private const val TAG = "WheelBleProxy"
        private const val ADVERTISE_SERVICE_DATA = false

        private val RESTRICTED_SERVICES = listOf(
            "00001800-0000-1000-8000-00805f9b34fb", // Generic Access
            "00001801-0000-1000-8000-00805f9b34fb", // Generic Attribute
        )

        private val CUSTOM_SERVICE_UUID = BluetoothUtils.customGattUuid(0x1900)
        private val CUSTOM_PROXY_STATUS = BluetoothUtils.customGattUuid(0x2C00)
        private val CUSTOM_CHAR_ORIGINAL_NAME = BluetoothUtils.customGattUuid(0x2C01)
        private val CUSTOM_ORIGINAL_MAC_ADDR = BluetoothUtils.customGattUuid(0x2C02)

        private const val PROXY_STATUS_WHEEL_CONNECTION_INACTIVE = 0.toByte()
        private const val PROXY_STATUS_WHEEL_CONNECTION_ACTIVE = 1.toByte()
        private const val PROXY_STATUS_STOPPING = 2.toByte()
    }
}
