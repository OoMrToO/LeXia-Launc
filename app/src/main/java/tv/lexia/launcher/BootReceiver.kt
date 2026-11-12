package tv.lexia.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Some boxes (hybrid satellite receivers especially) start their own home screen every time,
 * even when LeXia is the default. When the TV has started, open LeXia once.
 * Android 10 and newer only allow this while the Home button service is on; that service does
 * the same when it starts, so either way LeXia comes up.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val a = intent.action ?: return
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        openAfterBoot(ctx)
    }

    companion object {
        /** Boot time of the last start we opened LeXia for, so one start opens it only once. */
        @Volatile private var opened = 0L

        fun openAfterBoot(ctx: Context) {
            val prefs = Prefs(ctx)
            if (!prefs.bootOpen) return
            // Only in the first minutes after the TV started.
            if (SystemClock.elapsedRealtime() > 5 * 60 * 1000L) return
            val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            if (Math.abs(boot - opened) < 60000L) return
            opened = boot
            try {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            } catch (e: Exception) {
            }
        }
    }
}
