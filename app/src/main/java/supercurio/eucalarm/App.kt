package supercurio.eucalarm

import android.app.Application

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        Notifications.createNotificationChannels(applicationContext)

//        ForegroundService.enable(context = applicationContext, true)
    }
}
