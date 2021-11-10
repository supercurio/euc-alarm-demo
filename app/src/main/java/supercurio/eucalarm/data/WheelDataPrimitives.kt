package supercurio.eucalarm.data

class WheelDataPrimitives(
    private val newDataCallback: ((wheelData: WheelDataInterface) -> Unit)? = null
) : WheelDataInterface {

    override var dataTimeMs: Long? = null
    override var voltage: Double? = null
    override var speed: Double? = null
    override var tripDistance: Double? = null
    override var totalDistance: Double? = null
    override var current: Double? = null
    override var temperature: Double? = null
    override var tilt: Double? = null
    override var beeper = false

    override fun gotNewData() {
        newDataCallback?.invoke(this)
    }
}
