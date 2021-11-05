package supercurio.eucalarm

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import supercurio.eucalarm.log.AppLog
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var notifications: Notifications

    @Inject
    lateinit var appLog: AppLog

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "App onCreate")
        appLog.log("App creation")

        notifications.createNotificationChannels()
    }

    companion object {
        private const val TAG = "App"
    }

}
