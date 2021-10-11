package supercurio.eucalarm.data

interface WheelDataInterface {

    /**
     *  Millisecond precision time for the last data update
     */
    var dataTimeMs: Long?

    /**
     *  Volts
     */
    var voltage: Double?

    /**
     * km/h
     */
    var speed: Double?

    /**
     * kilometer
     */
    var tripDistance: Double?

    /**
     * kilometer
     */
    var totalDistance: Double?

    /**
     * amperes
     */
    var current: Double?

    /**
     * °C
     */
    var temperature: Double?

    /**
     * Beeper status
     */
    var beeper: Boolean


    fun gotNewData(end: Boolean = false)

    fun clear() {
        voltage = null
        speed = null
        tripDistance = null
        totalDistance = null
        current = null
        temperature = null

        beeper = false
    }
}
