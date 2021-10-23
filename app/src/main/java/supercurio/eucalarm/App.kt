package supercurio.eucalarm

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var notifications: Notifications

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "App onCreate")

        notifications.createNotificationChannels()
    }

    companion object {
        private const val TAG = "App"
    }

}
