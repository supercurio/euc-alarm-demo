package supercurio.eucalarm.oems

import supercurio.eucalarm.data.WheelDataInterface

class GotwayAndVeteranParser(val wheelData: WheelDataInterface) {

    private var gotwayParser = GotwayParser(wheelData)
    private var veteranParser = VeteranParser(wheelData)

    private var type = Type.Unidentified

    fun notificationData(data: ByteArray) {
        when (type) {
            Type.Unidentified -> {
                if (gotwayParser.findFrame(data)) type = Type.Gotway
                if (veteranParser.findFrame(data)) type = Type.Veteran
                type.brandName?.let { println("Identified wheel data format: $it") }
            }
            Type.Gotway -> gotwayParser.findFrame(data)
            Type.Veteran -> veteranParser.findFrame(data)
        }
    }

    enum class Type(val brandName: String? = null) {
        Unidentified,
        Gotway("Gotway/Begode/Extreme Bull"),
        Veteran("Veteran")
    }

    companion object {
        const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
        const val DATA_CHARACTERISTIC_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    }
}
