package supercurio.eucalarm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus
import supercurio.eucalarm.appstate.AppStateStore
import supercurio.eucalarm.appstate.ClosedState
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

    private var state = false

    fun on() {
        if (state) return
        wheelData.clear()
        Notifications.muted = false
        alert.setup()

        AppService.enable(context, true)
    }


    fun off() {
        if (state) return
        Notifications.muted = true

        wheelConnection.shutdown()
        powerManagement.releaseAll()
        wheelData.clear()
        alert.shutdown()
        AppService.enable(context, false)
        appStateStore.setState(ClosedState)
    }
}
