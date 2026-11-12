package tv.lexia.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.util.LruCache
import android.widget.ImageView

/** Bitmap cache with a size limit in megabytes, used like a map. */
class BitmapCache(megabytes: Int) {
    private val lru = object : LruCache<String, Bitmap>(maxOf(4, megabytes) * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    operator fun get(key: String): Bitmap? = lru.get(key)
    operator fun set(key: String, value: Bitmap) {
        lru.put(key, value)
    }

    fun containsKey(key: String) = lru.get(key) != null
    fun putAll(m: Map<String, Bitmap>) = m.forEach { (k, v) -> lru.put(k, v) }
    fun clear() = lru.evictAll()
}

/** Small shared picture cache and loader for TMDB images. */
object Img {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun clear() = cache.evictAll()

    /**
     * Pictures come in six at a time, not one thread each (a home screen asks for a hundred at once,
     * which made weak TVs stutter). The screen opened last goes first: a page in front never waits
     * behind the posters of the one under it. Within a screen, in the order asked.
     */
    private class Job(val rank: Int, val seq: Long, val run: Runnable) : Runnable, Comparable<Job> {
        override fun run() = run.run()
        override fun compareTo(other: Job) = if (rank != other.rank) other.rank.compareTo(rank) else seq.compareTo(other.seq)
    }

    private val pool = java.util.concurrent.ThreadPoolExecutor(
        6, 6, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.PriorityBlockingQueue<Runnable>()
    ).also { it.allowCoreThreadTimeOut(true) }
    private val ranks = java.util.WeakHashMap<Activity, Int>()
    private var nextRank = 0
    private val seq = java.util.concurrent.atomic.AtomicLong()

    /** Runs [job] off the main thread, in the picture queue of [a]. */
    fun run(a: Activity, job: Runnable) {
        val rank = synchronized(ranks) { ranks.getOrPut(a) { ++nextRank } }
        pool.execute(Job(rank, seq.incrementAndGet(), job))
    }

    /** Loads a TMDB image into [into]; [skipDark] drops logos too dark to see on the hero. */
    fun load(
        a: Activity,
        size: String,
        path: String,
        width: Int,
        into: ImageView,
        opaque: Boolean = true,
        skipDark: Boolean = false,
        done: (Boolean) -> Unit = {}
    ) {
        if (path.isEmpty()) {
            done(false)
            return
        }
        val url = "https://image.tmdb.org/t/p/$size$path"
        cache.get(url)?.let {
            into.setImageBitmap(it)
            done(true)
            return
        }
        into.tag = url
        // A soft light passes over the card while the picture is on its way.
        if (opaque) into.setImageDrawable(Fx.Shimmer(C.CARD, 0f))
        // Big pictures (the hero, a film page) don't wait behind a row of posters.
        val big = size == "original" || size == "w1280"
        val job = Runnable {
            // Gone from the screen, or given another picture, before its turn came.
            if (a.isFinishing || into.tag != url) {
                a.runOnUiThread { done(false) }
                return@Runnable
            }
            var bmp = Net.bitmap(url, width, opaque)
            if (bmp != null && skipDark && isDark(bmp)) bmp = null
            a.runOnUiThread {
                if (bmp != null) cache.put(url, bmp)
                // The view may have been reused for another picture meanwhile.
                if (into.tag == url && bmp != null) Fx.fadeIn(into, bmp)
                done(bmp != null)
            }
        }
        if (big) Thread(job).start() else run(a, job)
    }

    /** True for logos so dark they would vanish on the dark hero. */
    fun isDark(b: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(b, 32, 12, true)
        var sum = 0.0
        var n = 0
        for (x in 0 until s.width) for (y in 0 until s.height) {
            val p = s.getPixel(x, y)
            if (Color.alpha(p) < 100) continue
            sum += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)) / 255.0
            n++
        }
        if (s !== b) s.recycle()
        return n > 0 && sum / n < 0.35
    }
}
