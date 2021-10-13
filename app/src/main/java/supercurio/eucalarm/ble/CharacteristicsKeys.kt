package supercurio.eucalarm.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.util.Log
import supercurio.wheeldata.recording.BleDeviceInfo

class CharacteristicsKeys {

    private val uuidToKey = hashMapOf<String, Int>()

    // only a cache
    private val keyToCharacteristicCache = hashMapOf<Int, BluetoothGattCharacteristic?>()

    fun fromDeviceInfo(bleDeviceInfo: BleDeviceInfo) {
        uuidToKey.clear()
        uuidToKey += bleDeviceInfo
            .gattServicesList
            .flatMap { it.gattCharacteristicsMap.entries }
            .associateBy({ it.value.uuid }) { it.key }
    }

    fun getCharacteristic(
        services: List<BluetoothGattService>,
        characteristicKey: Int
    ): BluetoothGattCharacteristic? = keyToCharacteristicCache.getOrPut(characteristicKey) {
        val uuid = uuidToKey
            .filterValues { it == characteristicKey }
            .keys
            .firstOrNull() ?: return null

        services.mapNotNull { service ->
            service.characteristics.find { char ->
                char.uuid.toString() == uuid
            }
        }.firstOrNull()
    }

    operator fun set(charUUID: String, characteristicKey: Int) {
        uuidToKey[charUUID] = characteristicKey
    }

    operator fun get(charUUID: String) = uuidToKey[charUUID]

    fun clear() {
        uuidToKey.clear()
        keyToCharacteristicCache.clear()
    }

    fun list() {
        Log.i(TAG, "uuidToKey: $uuidToKey")
        Log.i(TAG, "keyToCharacteristicCache: $keyToCharacteristicCache")
    }

    companion object {
        private const val TAG = "CharacteristicsKeys"
    }
}
