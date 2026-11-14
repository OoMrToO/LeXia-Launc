package tv.lexia.launcher

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Isolated resume store for the media player.
 *
 * Launcher preferences are intentionally left untouched. The media layer can later project
 * these records into LeXia's "Son baktıkların" and "Kaldığın yerden devam et" shelves.
 */
internal class MediaPlaybackStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        FILE_NAME,
        Context.MODE_PRIVATE
    )

    fun resumePositionMs(mediaKey: String): Long {
        if (mediaKey.isBlank()) return 0L
        val key = prefix(mediaKey)
        val updatedAt = preferences.getLong("${key}_updated", 0L)
        if (updatedAt <= 0L || System.currentTimeMillis() - updatedAt > MAX_AGE_MS) {
            clear(mediaKey)
            return 0L
        }
        return preferences.getLong("${key}_position", 0L).coerceAtLeast(0L)
    }

    fun save(mediaKey: String, positionMs: Long, durationMs: Long) {
        if (mediaKey.isBlank()) return
        val position = positionMs.coerceAtLeast(0L)
        val duration = durationMs.coerceAtLeast(0L)
        if (position < MIN_RESUME_MS ||
            (duration > 0L && position >= duration - COMPLETION_MARGIN_MS)
        ) {
            clear(mediaKey)
            return
        }

        val key = prefix(mediaKey)
        preferences.edit()
            .putLong("${key}_position", position)
            .putLong("${key}_duration", duration)
            .putLong("${key}_updated", System.currentTimeMillis())
            .apply()
    }

    fun clear(mediaKey: String) {
        if (mediaKey.isBlank()) return
        val key = prefix(mediaKey)
        preferences.edit()
            .remove("${key}_position")
            .remove("${key}_duration")
            .remove("${key}_updated")
            .apply()
    }

    private fun prefix(mediaKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(mediaKey.toByteArray(Charsets.UTF_8))
        return "item_" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val FILE_NAME = "media_playback"
        const val MIN_RESUME_MS = 5_000L
        const val COMPLETION_MARGIN_MS = 15_000L
        val MAX_AGE_MS = TimeUnit.DAYS.toMillis(30)
    }
}
