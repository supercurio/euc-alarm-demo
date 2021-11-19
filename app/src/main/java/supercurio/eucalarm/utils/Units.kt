package supercurio.eucalarm.utils

object Units {
    val Double.kmToMi get() = this * KM_TO
    private const val KM_TO = 15625.0 / 25146.0
}
