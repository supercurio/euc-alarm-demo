package supercurio.eucalarm.data

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow

class WheelData {

    /**
     * The last time wheel data was successfully updated
     * Useful to trigger refresh or logging on a series of data update
     * instead of for each data point separately
     */
    val lastUpdate = MutableStateFlow<Long?>(null)

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


    fun gotNewData() {
        lastUpdate.value = SystemClock.elapsedRealtime()
    }

    fun clear() {
        listOf(voltage, speed, tripDistance, totalDistance, current, temperature).forEach {
            it.value = null
        }

        beeper.value = false
    }
}
