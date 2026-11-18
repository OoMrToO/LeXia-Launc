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

    /**
     * Remembers where the viewer is. Answers true when that is the end of the film (the closing
     * credits count as the end): nothing is kept then, so the next start is from the beginning.
     */
    fun save(mediaKey: String, positionMs: Long, durationMs: Long): Boolean {
        if (mediaKey.isBlank()) return false
        changes++
        val position = positionMs.coerceAtLeast(0L)
        val duration = durationMs.coerceAtLeast(0L)
        if (duration > 0L && position >= duration - maxOf(COMPLETION_MARGIN_MS, duration * CREDITS_PERCENT / 100)) {
            clear(mediaKey)
            return true
        }
        if (position < MIN_RESUME_MS) {
            clear(mediaKey)
            return false
        }

        val key = prefix(mediaKey)
        preferences.edit()
            .putLong("${key}_position", position)
            .putLong("${key}_duration", duration)
            .putLong("${key}_updated", System.currentTimeMillis())
            .apply()
        return false
    }

    /** How far into it the viewer is, 0..1; null when nothing is kept (not started, or finished). */
    fun progress(mediaKey: String): Float? {
        val position = resumePositionMs(mediaKey)
        if (position <= 0L) return null
        val key = prefix(mediaKey)
        val duration = preferences.getLong("${key}_duration", 0L)
        return if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else null
    }

    /** When [mediaKey] was last played (epoch ms); 0 when never. */
    fun updatedAt(mediaKey: String): Long {
        if (mediaKey.isBlank()) return 0L
        val key = prefix(mediaKey)
        return preferences.getLong("${key}_updated", 0L)
    }

    /** The length the player last reported for [mediaKey]; 0 when unknown. */
    fun durationMs(mediaKey: String): Long {
        if (mediaKey.isBlank()) return 0L
        val key = prefix(mediaKey)
        return preferences.getLong("${key}_duration", 0L)
    }

    /** Watched to the end (the credits count). */
    fun isFinished(mediaKey: String): Boolean {
        if (mediaKey.isBlank()) return false
        val key = prefix(mediaKey)
        return preferences.getBoolean("${key}_done", false)
    }

    fun markFinished(mediaKey: String) {
        changes++
        if (mediaKey.isBlank()) return
        val key = prefix(mediaKey)
        preferences.edit().putBoolean("${key}_done", true).apply()
    }

    /**
     * The film or episode last played of a title ([watchKey] is the title's own key): the film
     * page offers to continue it.
     */
    fun saveLast(watchKey: String, mediaKey: String) {
        if (watchKey.isBlank() || mediaKey.isBlank()) return
        val key = prefix(watchKey)
        preferences.edit().putString("${key}_last", mediaKey).apply()
    }

    fun last(watchKey: String): String {
        if (watchKey.isBlank()) return ""
        val key = prefix(watchKey)
        return preferences.getString("${key}_last", null).orEmpty()
    }

    /** Sources were found for this title ([watchKey], the title's own key): its poster says so. */
    fun markAvailable(watchKey: String) {
        if (watchKey.isBlank()) return
        val key = prefix(watchKey)
        preferences.edit().putLong("${key}_found", System.currentTimeMillis()).apply()
    }

    /** Sources were found within the last week (links on these sites do not last much longer). */
    fun isAvailable(watchKey: String): Boolean {
        if (watchKey.isBlank()) return false
        val key = prefix(watchKey)
        val at = preferences.getLong("${key}_found", 0L)
        return at > 0L && System.currentTimeMillis() - at < TimeUnit.DAYS.toMillis(7)
    }

    /** How much later (or earlier, when negative) the viewer set this film's subtitles. */
    fun subtitleDelayMs(mediaKey: String): Long {
        if (mediaKey.isBlank()) return 0L
        val key = prefix(mediaKey)
        return preferences.getLong("${key}_subdelay", 0L)
    }

    fun saveSubtitleDelay(mediaKey: String, delayMs: Long) {
        if (mediaKey.isBlank()) return
        val key = prefix(mediaKey)
        if (delayMs == 0L) preferences.edit().remove("${key}_subdelay").apply()
        else preferences.edit().putLong("${key}_subdelay", delayMs).apply()
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

    companion object {
        /** Goes up whenever a place or a finish is written: posters showing progress redraw then. */
        @Volatile
        var changes = 0
            private set

        const val FILE_NAME = "media_playback"
        const val MIN_RESUME_MS = 5_000L
        const val COMPLETION_MARGIN_MS = 15_000L
        /** The last part of a film is its credits; leaving there is having watched it. */
        const val CREDITS_PERCENT = 6L
        val MAX_AGE_MS = TimeUnit.DAYS.toMillis(30)
    }
}
