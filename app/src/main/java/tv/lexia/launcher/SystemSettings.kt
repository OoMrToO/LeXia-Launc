package tv.lexia.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/**
 * Opens Android TV settings screens. TVs differ (Grundig's Google TV ignores some standard
 * actions), so each screen tries the TV settings app first, then the standard action, then
 * the main settings page.
 */
object SystemSettings {
    private const val TV = "com.android.tv.settings"

    enum class Screen { NETWORK, BLUETOOTH, DISPLAY_SOUND, ALL }

    private fun cls(name: String) = Intent().setClassName(TV, name)

    private fun candidates(s: Screen): List<Intent> = when (s) {
        Screen.NETWORK -> listOf(
            Intent(Settings.ACTION_WIFI_SETTINGS),
            cls("com.android.tv.settings.connectivity.NetworkActivity")
        )
        Screen.BLUETOOTH -> listOf(
            cls("com.android.tv.settings.accessories.AddAccessoryActivity"),
            cls("com.android.tv.settings.connecteddevices.ConnectedDevicesActivity"),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        )
        Screen.DISPLAY_SOUND -> listOf(
            cls("com.android.tv.settings.device.displaysound.DisplaySoundActivity"),
            cls("com.android.tv.settings.device.display.DisplayActivity"),
            cls("com.android.tv.settings.device.sound.SoundActivity"),
            Intent(Settings.ACTION_SOUND_SETTINGS)
        )
        Screen.ALL -> emptyList()
    } + listOf(cls("com.android.tv.settings.MainSettings"), Intent(Settings.ACTION_SETTINGS))

    /**
     * Accessibility settings. TVs differ: some (e.g. Grundig Google TV) take the standard request
     * without error but show nothing. So the TV settings app's own screens go first, and when LeXia
     * still has the screen 1.5 s after a try, the next way is tried. Last: the main settings and the path.
     */
    fun openAccessibility(ctx: Context) {
        HomeButtonService.pause()
        // The TV's own accessibility screens, found by name: their class names differ between TVs,
        // so a fixed list misses some (a Grundig opened only the main settings).
        val tries = (found(ctx, "accessib") + listOf(
            cls("com.android.tv.settings.accessibility.AccessibilityActivity"),
            cls("com.android.tv.settings.system.AccessibilityActivity"),
            Intent().setClassName("com.google.android.tv.settings", "com.android.tv.settings.accessibility.AccessibilityActivity"),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )).distinctBy { it.component?.flattenToString() ?: it.action.orEmpty() }
            .onEach { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .filter { it.resolveActivity(ctx.packageManager) != null }
        val a = ctx as? android.app.Activity
        fun fallback() {
            Glass.toast(ctx, "Cihaz Tercihleri (ya da Sistem) › Erişilebilirlik › “LeXia Ana Sayfa tuşu”nu aç.", long = true)
            open(ctx, Screen.ALL)
        }
        fun attempt(i: Int) {
            if (i >= tries.size) {
                fallback()
                return
            }
            val ok = try {
                ctx.startActivity(tries[i])
                true
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                attempt(i + 1)
                return
            }
            // Without a screen to watch there is no way to tell; trust the first one that starts.
            if (a == null) return
            a.window.decorView.postDelayed({
                if (!a.isFinishing && a.hasWindowFocus()) attempt(i + 1)
            }, 1500)
        }
        attempt(0)
    }

    /** Screens of the TV's settings apps that other apps may open and whose name has [word] in it. */
    private fun found(ctx: Context, word: String): List<Intent> {
        val out = ArrayList<Intent>()
        for (pkg in listOf(TV, "com.google.android.tv.settings")) {
            val screens = try {
                ctx.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES).activities
            } catch (e: Exception) {
                null
            } ?: continue
            for (screen in screens) {
                if (!screen.exported || !screen.enabled) continue
                if (word in screen.name.lowercase()) out.add(Intent().setClassName(pkg, screen.name))
            }
        }
        return out
    }

    fun open(ctx: Context, s: Screen) {
        // Google TV shows these panels from its own launcher; keep the Home button service quiet.
        HomeButtonService.pause()
        for (i in candidates(s)) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (i.resolveActivity(ctx.packageManager) == null) continue
            try {
                ctx.startActivity(i)
                return
            } catch (e: Exception) {
            }
        }
        Glass.toast(ctx, "TV ayarları açılamadı")
    }
}
