package tv.lexia.launcher

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps the last crash on the device so it can be read in Settings › Hakkında. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Guard.started(this)
        com.nuvio.tv.LeXiaImages.install(this)
        Net.diskCache = File(cacheDir, "img")
        // Off the main thread: a crash from last time, and the setup build's "I was opened".
        Thread {
            Remote.sendCrash(this)
            if (BuildConfig.SETUP) Remote.ping(this)
        }.start()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val lines = sw.toString().lines().take(40).joinToString("\n")
                val time = SimpleDateFormat("d MMMM HH:mm", Locale("tr", "TR")).format(Date())
                crashFile(this).writeText("$time  ·  #${BuildConfig.BUILD_NO}\n\n$lines")
            } catch (x: Throwable) {
            }
            Guard.crashed(this)
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")
    }
}
