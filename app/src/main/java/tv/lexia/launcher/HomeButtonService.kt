package tv.lexia.launcher

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Google TV ignores the default home app for the remote's Home button.
 * This service catches the Home button (and the stock home screen showing up)
 * and opens this launcher instead. It reads nothing else on screen.
 */
class HomeButtonService : AccessibilityService() {

    private val stockHomes = setOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher"
    )
    private var lastLaunch = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // In the sleep timer's last minute, any key postpones it (works inside other apps too).
        if (event.action == KeyEvent.ACTION_DOWN) Sleep.onKey()
        // The remote's settings key opens Google's settings panel, which lives in the stock
        // launcher; leave it alone until our home screen shows again.
        if (event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
            pause()
            return false
        }
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_UP) {
            paused = false
            goHome()
        }
        return true
    }

    /**
     * Every other home screen on this TV: Google's, and the box's own. Some boxes (Android 9 satellite
     * receivers) quietly make their own launcher the default again; LeXia comes back over it.
     */
    private val otherHomes: Set<String> by lazy {
        val found = try {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }
        } catch (e: Exception) {
            emptyList()
        }
        // The settings app's "FallbackHome" (shown while the TV starts) is not a home screen.
        stockHomes + found.filter { it != packageName && !it.contains("settings", ignoreCase = true) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (paused) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in otherHomes) return
        // A box's own home app may also be its live TV or satellite player: once opened from LeXia it
        // is the user's choice, never sent back. Google's home screens are always replaced.
        if (pkg !in stockHomes && pkg in opened) return
        // Settings, Wi-Fi and similar panels are shown by the stock launcher too; only its
        // home screen should bring us back.
        val cls = event.className?.toString()?.lowercase() ?: ""
        if (listOf("setting", "panel", "dashboard", "wifi", "network", "dialog").any { it in cls }) return
        goHome()
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // The service starts with the TV: bring LeXia up over the box's own home screen once.
        android.os.Handler(mainLooper).postDelayed({ BootReceiver.openAfterBoot(this) }, 1500)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun goHome() {
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 400) return
        lastLaunch = now
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (e: Exception) {
        }
    }

    companion object {
        @Volatile var instance: HomeButtonService? = null

        /** Apps the user opened from LeXia since it started; they are left alone. */
        private val opened = java.util.Collections.synchronizedSet(HashSet<String>())

        fun opened(pkg: String) {
            opened.add(pkg)
        }

        /** True while the user is in a settings screen we (or the remote) opened. */
        @Volatile var paused = false

        /** Stop sending the stock launcher's screens back to us until our home screen resumes. */
        fun pause() {
            paused = true
        }

        fun resume() {
            paused = false
        }

        fun isOn(ctx: Context): Boolean {
            val me = ComponentName(ctx, HomeButtonService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
