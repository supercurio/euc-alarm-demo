package supercurio.eucalarm.data

import kotlinx.coroutines.flow.MutableStateFlow

class WheelData {
    /**
     *  Volts
     */
    val voltage = MutableStateFlow<Double?>(null)

    /**
     * km/h
     */
    val speed = MutableStateFlow<Double?>(null)

    /**
     * kilometer
     */
    val tripDistance = MutableStateFlow<Double?>(null)

    /**
     * kilometer
     */
    val totalDistance = MutableStateFlow<Double?>(null)

    /**
     * amperes
     */
    val current = MutableStateFlow<Double?>(null)

    /**
     * °C
     */
    val temperature = MutableStateFlow<Double?>(null)

    /**
     * Beeper status
     */
    val beeper = MutableStateFlow(false)


    fun clear() {
        listOf(voltage, speed, tripDistance, totalDistance, current, temperature).forEach {
            it.value = null
        }

        beeper.value = false
    }
}
