package tv.lexia.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Net {
    /** Where the film list, the settings and updates come from; the build fills it in. */
    val SITE: String = BuildConfig.SITE

    /** The first LeXia's site: Listem's backups are still kept there, so a code from the old app works. */
    const val OLD_SITE = "https://lexialauncher.pages.dev"

    /** Kept as a list so a second address can be added later without touching the callers. */
    fun hosts(): List<String> = listOf(SITE)

    /** Nothing to remember with a single host. */
    fun answered(host: String, ok: Boolean) {}

    /** A small file ("/pick.json?t=1") from the site. */
    fun fromHosts(path: String): String? {
        for (h in hosts()) {
            val body = get(h + path)
            if (body != null) return body
        }
        return null
    }

    /** Folder for pictures kept on disk, so the screen fills even before the internet is up. */
    @Volatile var diskCache: File? = null
    private const val DISK_LIMIT = 80L * 1024 * 1024

    fun bytes(address: String): ByteArray? = try {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "LeXia")
        if (c.responseCode != HttpURLConnection.HTTP_OK) null else c.inputStream.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    fun get(address: String): String? = bytes(address)?.toString(Charsets.UTF_8)

    /** A short plain-text POST; true when the server took it. */
    fun postText(address: String, body: String): Boolean = try {
        val data = body.toByteArray(Charsets.UTF_8)
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", "LeXia")
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.setFixedLengthStreamingMode(data.size)
        c.outputStream.use { it.write(data) }
        c.responseCode in 200..299
    } catch (e: Exception) {
        false
    }

    /** An empty POST; true when the server took it. */
    fun post(address: String): Boolean = try {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("User-Agent", "LeXia")
        c.setFixedLengthStreamingMode(0)
        c.outputStream.close()
        c.responseCode in 200..299
    } catch (e: Exception) {
        false
    }

    /** TMDB pictures never change for a path, so they are read from disk when we have them. */
    private fun cachedBytes(address: String): ByteArray? {
        val dir = diskCache ?: return bytes(address)
        val f = File(dir, address.hashCode().toUInt().toString(16) + "_" + address.length)
        if (f.exists()) {
            f.setLastModified(System.currentTimeMillis())
            return try { f.readBytes() } catch (e: Exception) { null }
        }
        val data = bytes(address) ?: return null
        try {
            dir.mkdirs()
            f.writeBytes(data)
            trim(dir)
        } catch (e: Exception) {
        }
        return data
    }

    /** Keeps the picture folder under its size limit, dropping the least recently used first. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= DISK_LIMIT) break
            total -= f.length()
            f.delete()
        }
    }

    /** Decode an image, shrunk to roughly the given width. */
    fun bitmap(address: String, maxWidth: Int, opaque: Boolean): Bitmap? {
        val data = cachedBytes(address) ?: return null
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(data, 0, data.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= maxWidth) s *= 2
            val d = BitmapFactory.Options()
            d.inSampleSize = s
            if (opaque) d.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeByteArray(data, 0, data.size, d)
        } catch (e: Throwable) {
            null
        }
    }
}
