package supercurio.eucalarm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import supercurio.eucalarm.appstate.AppStateStore
import supercurio.eucalarm.appstate.ClosedState
import supercurio.eucalarm.ble.WheelBleRecorder
import supercurio.eucalarm.ble.WheelBleSimulator
import supercurio.eucalarm.ble.WheelConnection
import supercurio.eucalarm.data.WheelDataStateFlows
import supercurio.eucalarm.feedback.AlertFeedback
import supercurio.eucalarm.power.PowerManagement
import supercurio.eucalarm.service.AppService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLifecycle @Inject constructor(@ApplicationContext private val context: Context) {

    @Inject
    lateinit var appStateStore: AppStateStore

    @Inject
    lateinit var wheelData: WheelDataStateFlows

    @Inject
    lateinit var powerManagement: PowerManagement

    @Inject
    lateinit var wheelConnection: WheelConnection

    @Inject
    lateinit var alert: AlertFeedback

    @Inject
    lateinit var wheelBleRecorder: WheelBleRecorder

    @Inject
    lateinit var simulator: WheelBleSimulator

    @Inject
    lateinit var notifications: Notifications

    private var state = false

    fun on() {
        if (state) return
        state = true

        wheelData.clear()
        notifications.muted = false
        alert.setup()

        AppService.enable(context, true)
    }


    fun off() {
        if (state) return
        state = false

        notifications.muted = true

        simulator.shutdown()
        wheelConnection.shutdown()
        powerManagement.releaseAll()
        wheelData.clear()
        wheelBleRecorder.shutDown()
        alert.shutdown()
        AppService.enable(context, false)
        appStateStore.setState(ClosedState)
    }

}
