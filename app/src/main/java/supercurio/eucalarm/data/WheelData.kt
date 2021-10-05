package supercurio.eucalarm.data

import kotlinx.coroutines.flow.MutableStateFlow

class WheelData {
    var voltage: Double? = null // Volts
    var speed: Double? = null // km/h
    var tripDistance: Double? = null // kilometer
    var totalDistance: Double? = null // kilometer
    var current: Double? = null // amperes
    var temperature: Double? = null // °C
    var beeper = false

    val timestamp = MutableStateFlow(0L)
}
