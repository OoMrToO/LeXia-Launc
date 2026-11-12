package tv.lexia.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Sleep timer: when it ends, starts the system screensaver so the TV can go to sleep.
 * A minute before, a notice says so; any key on the remote then adds 30 minutes.
 */
object Sleep {
    private const val WARN_MS = 60_000L
    private const val EXTEND_MIN = 30

    private val handler = Handler(Looper.getMainLooper())
    private var endsAt = 0L
    private var task: Runnable? = null
    private var warnTask: Runnable? = null
    private var app: Context? = null

    /** True during the last minute, when a key press postpones the sleep. */
    @Volatile var warning = false
        private set

    val minutesLeft: Int
        get() = if (endsAt == 0L) 0 else (((endsAt - System.currentTimeMillis()) / 60000L) + 1).toInt().coerceAtLeast(0)

    /** "23:40" (or "11:40 ÖS"), when the timer runs out; empty when off. */
    fun endsText(clock24: Boolean): String {
        if (endsAt == 0L) return ""
        val f = if (clock24) "HH:mm" else "h:mm a"
        return SimpleDateFormat(f, Locale("tr", "TR")).format(Date(endsAt))
    }

    fun start(ctx: Context, minutes: Int) {
        cancel()
        if (minutes <= 0) return
        val a = ctx.applicationContext
        app = a
        val ms = minutes * 60000L
        endsAt = System.currentTimeMillis() + ms
        val r = Runnable {
            endsAt = 0L
            task = null
            warning = false
            fire(a)
        }
        task = r
        handler.postDelayed(r, ms)
        val w = Runnable { warn(a) }
        warnTask = w
        handler.postDelayed(w, (ms - WARN_MS).coerceAtLeast(0L))
    }

    fun cancel() {
        task?.let { handler.removeCallbacks(it) }
        warnTask?.let { handler.removeCallbacks(it) }
        task = null
        warnTask = null
        endsAt = 0L
        warning = false
    }

    /** Called for every remote key; in the last minute it postpones the sleep. */
    fun onKey() {
        if (!warning) return
        val a = app ?: return
        handler.post {
            if (!warning) return@post
            start(a, EXTEND_MIN)
            toast(a, "Uyku $EXTEND_MIN dakika ertelendi")
        }
    }

    private fun warn(a: Context) {
        warnTask = null
        if (endsAt == 0L) return
        warning = true
        toast(a, "TV 1 dakika içinde uykuya geçecek. Ertelemek için kumandada bir tuşa bas.")
    }

    private fun toast(a: Context, s: String) {
        try {
            Glass.toast(a, s, long = true)
        } catch (e: Exception) {
        }
    }

    private fun fire(app: Context) {
        // The Home button service may start activities from the background; fall back to the app.
        val ctx: Context = HomeButtonService.instance ?: app
        val dream = Intent(Intent.ACTION_MAIN)
            .setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(dream)
        } catch (e: Exception) {
            try {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
            }
        }
    }
}

/** Nightly sleep: at the chosen time the TV gets a one-minute notice, then goes to sleep. */
class NightSleep : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        trigger(ctx.applicationContext, intent.getLongExtra(DUE, 0L))
        schedule(ctx)
    }

    companion object {
        private const val DUE = "due"
        private val handler = Handler(Looper.getMainLooper())
        private var timer: Runnable? = null
        /** The nightly time already handled, so the alarm and the timer don't both start it. */
        @Volatile private var handled = 0L

        /**
         * Starts the one-minute countdown, but only when the TV is on and it's on time: an alarm
         * held back while the TV slept must not put it to sleep right after it is switched on.
         */
        private fun trigger(app: Context, due: Long) {
            if (due == 0L || due == handled) return
            val power = app.getSystemService(PowerManager::class.java)
            val late = System.currentTimeMillis() - due > 15 * 60_000L
            if (power?.isInteractive != true || late || Prefs(app).nightSleep < 0) return
            handled = due
            Sleep.start(app, 1)
        }

        /**
         * Sets (or clears) the next nightly sleep. A timer in the app hits the minute exactly;
         * the system alarm (which Android may delay by a few minutes) covers the app being closed.
         */
        fun schedule(ctx: Context) {
            val app = ctx.applicationContext
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            val min = Prefs(ctx).nightSleep
            val next = Calendar.getInstance()
            if (min >= 0) {
                next.set(Calendar.HOUR_OF_DAY, min / 60)
                next.set(Calendar.MINUTE, min % 60)
                next.set(Calendar.SECOND, 0)
                next.set(Calendar.MILLISECOND, 0)
                if (next.timeInMillis <= System.currentTimeMillis() + 5_000L) next.add(Calendar.DAY_OF_YEAR, 1)
            }
            val due = next.timeInMillis
            val i = Intent(ctx, NightSleep::class.java).putExtra(DUE, due)
            val pi = PendingIntent.getBroadcast(ctx, 41, i, flags)
            timer?.let { handler.removeCallbacks(it) }
            timer = null
            try {
                am.cancel(pi)
                if (min >= 0) am.setWindow(AlarmManager.RTC, due, 60_000L, pi)
            } catch (e: Exception) {
            }
            if (min >= 0) {
                val r = Runnable {
                    timer = null
                    trigger(app, due)
                    schedule(app)
                }
                timer = r
                handler.postDelayed(r, due - System.currentTimeMillis())
            }
        }

        /** "23:30" for the settings list. */
        fun label(min: Int): String = if (min < 0) "Kapalı" else String.format(Locale.US, "%02d:%02d", min / 60, min % 60)
    }
}
