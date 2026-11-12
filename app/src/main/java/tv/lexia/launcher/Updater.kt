package tv.lexia.launcher

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from LeXia's own site, installed with PackageInstaller
 * so no browser or file manager is involved.
 */
object Updater {
    val current: Int = BuildConfig.BUILD_NO.toIntOrNull() ?: 0

    /** Newest build found online, 0 if unknown. */
    @Volatile var latest = 0
        private set
    @Volatile private var url: String? = null
    @Volatile var busy = false
        private set

    /** Set when we sent the user to grant install permission; resumes on return. */
    var waitingForPermission = false

    val available: Boolean get() = latest > current && url != null

    /** Looks up the newest build. Calls back on the UI thread with true if one is newer. */
    fun check(activity: Activity, done: (Boolean?) -> Unit) {
        Thread {
            var best = 0
            var link: String? = null
            // A test TV looks at the newest build ("test.txt"), which may not be released to everyone yet.
            val tester = Prefs(activity).tester
            fun look() {
                if (tester) {
                    for (host in Net.hosts()) {
                        val n = Net.get(host + "/test.txt?t=" + System.currentTimeMillis())?.trim()?.toIntOrNull() ?: 0
                        Net.answered(host, n > 0)
                        if (n > 0) {
                            best = n
                            link = host + "/apk/test.apk"
                            return
                        }
                    }
                }
                for (host in Net.hosts()) {
                    val n = Net.get(host + "/version.txt?t=" + System.currentTimeMillis())?.trim()?.toIntOrNull() ?: 0
                    Net.answered(host, n > 0)
                    if (n > 0) {
                        best = n
                        link = host + "/apk/app.apk"
                        return
                    }
                }
            }
            look()
            // One more try a moment later, for a short network hiccup.
            if (best == 0) {
                Thread.sleep(2000L)
                look()
            }
            // The owner can hold every TV at a build while a new one is checked (admin page). The site
            // only serves the newest APK, so a held TV simply sees no update. The setup build always updates.
            // The owner's settings are read fresh with every check (every 30 minutes and on each start),
            // so a forced update or an update hold reaches TVs within half an hour, not with the film list.
            Remote.refresh(activity)
            val cap = Remote.hold
            if (cap > 0 && best > cap && current > 0 && !BuildConfig.SETUP && !Remote.mustUpdate() && !tester) best = current
            if (best > 0) {
                latest = best
                url = link
            }
            activity.runOnUiThread { done(if (best == 0) null else available) }
        }.start()
    }

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /**
     * Explains how to let this app install updates. Some TVs (e.g. Grundig Google TV) send the
     * standard permission screen to a browser, so we show the path and open only the
     * main settings page, which every TV has.
     */
    fun askPermission(activity: Activity) {
        waitingForPermission = true
        android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Güncelleme izni gerekiyor")
            .setMessage(
                "LeXia'nın güncelleme kurabilmesi için bir kez izin vermen gerekiyor:\n\n" +
                    "Ayarlar › Uygulamalar › Güvenlik ve kısıtlamalar › Bilinmeyen kaynaklar › LeXia Launcher'ı aç.\n\n" +
                    "Sonra geri dön; güncelleme kendiliğinden başlar."
            )
            .setPositiveButton("Ayarları aç") { _, _ ->
                SystemSettings.open(activity, SystemSettings.Screen.ALL)
            }
            .setNegativeButton("Vazgeç") { _, _ -> waitingForPermission = false }
            .show()
    }

    /**
     * Downloads and installs the newest build. [progress] gets status text on the UI thread.
     * Android then shows its own "Update this app?" dialog.
     */
    fun install(activity: Activity, progress: (String) -> Unit) {
        val link = url ?: return
        if (busy) return
        if (!canInstall(activity)) {
            askPermission(activity)
            return
        }
        waitingForPermission = false
        busy = true
        progress("İndiriliyor…")
        Thread {
            try {
                val apk = download(link) { pct -> activity.runOnUiThread { progress("İndiriliyor %$pct") } }
                val installer = activity.packageManager.packageInstaller
                // Clear sessions left behind by earlier failed tries.
                for (old in installer.mySessions) {
                    try {
                        installer.abandonSession(old.sessionId)
                    } catch (e: Exception) {
                    }
                }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(activity.packageName)
                // Android 12+: an app that installed itself may update without the confirm dialog.
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("update.apk", 0, apk.size.toLong()).use { out ->
                        out.write(apk)
                        s.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val intent = Intent(activity, InstallReceiver::class.java)
                    val pi = PendingIntent.getBroadcast(activity, id, intent, flags)
                    s.commit(pi.intentSender)
                }
                activity.runOnUiThread { progress("Kuruluyor…") }
            } catch (e: Exception) {
                val why = when (e) {
                    is ServerError -> "Sunucu şu an yanıt vermiyor"
                    is java.net.UnknownHostException -> "İnternet bağlantısı yok"
                    is java.net.SocketTimeoutException -> "Bağlantı çok yavaş"
                    is java.io.IOException -> "Bağlantı koptu"
                    else -> "Kurulum hazırlanamadı"
                }
                activity.runOnUiThread {
                    progress("Tekrar dene")
                    Glass.toast(activity, "Güncelleme indirilemedi: $why. Birkaç dakika sonra “Tekrar dene”ye bas.", long = true)
                }
            } finally {
                busy = false
            }
        }.start()
    }

    private class ServerError(code: Int) : java.io.IOException("HTTP $code")

    /**
     * Downloads the APK fully. Tries the given link, then the plain file on the other host and on
     * the site, then the link once more; a host sometimes answers 5xx for a while.
     */
    private fun download(link: String, percent: (Int) -> Unit): ByteArray {
        var last: Exception = java.io.IOException("download failed")
        val file = if (link.endsWith("/apk/test.apk")) "/apk/test.apk" else "/apk/app.apk"
        val others = Net.hosts().map { it + file }.filter { it != link }
        val tries = listOf(link) + others + link
        for ((attempt, address) in tries.withIndex()) {
            if (attempt == tries.size - 1) Thread.sleep(3000L)
            try {
                val c = URL(address).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "LeXia")
                val code = c.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw ServerError(code)
                val total = c.contentLength.toLong()
                val out = java.io.ByteArrayOutputStream(if (total > 0) total.toInt() else 2 * 1024 * 1024)
                c.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct / 5 != shown) {
                                shown = pct / 5
                                percent(pct)
                            }
                        }
                    }
                }
                if (total > 0 && out.size().toLong() != total) throw java.io.IOException("short download")
                return out.toByteArray()
            } catch (e: Exception) {
                last = e
            }
        }
        throw last
    }

}
