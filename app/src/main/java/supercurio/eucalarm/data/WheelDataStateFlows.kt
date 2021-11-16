package supercurio.eucalarm.data

import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WheelDataStateFlows @Inject constructor() : WheelDataInterface {

    /**
     * The last time wheel data was successfully updated
     * Useful to trigger refresh or logging on a series of data update
     * instead of for each data point separately
     */

    override var dataTimeMs: Long? = null

    val voltageFlow = MutableStateFlow<Double?>(null)
    override var voltage
        get() = voltageFlow.value
        set(value) {
            voltageFlow.value = value
        }

    val speedFlow = MutableStateFlow<Double?>(null)
    override var speed
        get() = speedFlow.value
        set(value) {
            speedFlow.value = value
        }

    val tripDistanceFlow = MutableStateFlow<Double?>(null)
    override var tripDistance
        get() = tripDistanceFlow.value
        set(value) {
            tripDistanceFlow.value = value
        }

    val totalDistanceFlow = MutableStateFlow<Double?>(null)
    override var totalDistance
        get() = totalDistanceFlow.value
        set(value) {
            totalDistanceFlow.value = value
        }

    val currentFlow = MutableStateFlow<Double?>(null)
    override var current
        get() = currentFlow.value
        set(value) {
            currentFlow.value = value
        }

    val temperatureFlow = MutableStateFlow<Double?>(null)
    override var temperature
        get() = temperatureFlow.value
        set(value) {
            temperatureFlow.value = value
        }

    val tiltFlow = MutableStateFlow<Double?>(null)
    override var tilt
        get() = tiltFlow.value
        set(value) {
            tiltFlow.value = value
        }

    /**
     * Beeper status
     */
    val beeperFlow = MutableStateFlow<Boolean?>(null)
    override var beeper
        get() = beeperFlow.value
        set(value) {
            beeperFlow.value = value
        }

    override fun gotNewData() = Unit

}
