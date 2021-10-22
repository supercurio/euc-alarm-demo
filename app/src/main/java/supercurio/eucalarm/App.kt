package supercurio.eucalarm

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class App : Application() {

    @Inject
    lateinit var notifications: Notifications

    override fun onCreate() {
        super.onCreate()

        notifications.createNotificationChannels()
    }
}
