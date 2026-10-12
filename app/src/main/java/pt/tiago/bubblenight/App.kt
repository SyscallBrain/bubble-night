package pt.tiago.bubblenight

import android.app.Application
import pt.tiago.bubblenight.crash.CrashLog

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
