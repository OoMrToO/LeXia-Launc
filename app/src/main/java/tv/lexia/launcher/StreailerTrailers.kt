package tv.lexia.launcher

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A film's Turkish trailer from the Streailer trailer add-on (Stremio stream add-on, set to tr-TR,
 * "internal" player): asked first, before the trailers of LeXia's own film list.
 *
 * What it really answers (checked 25 Kasım 2026, v1.5.0): one stream at most, `{"ytId": "…"}`, a
 * YouTube key, never a video file or subtitles. Where the key came from shows in the stream's name:
 * "🎬 Trailer" TMDB's Turkish-language videos, "🎬▶ Trailer" a YouTube search for
 * "<film> fragman türkçe" (its title is then the video's own), "🎬🇬🇧 Trailer" TMDB's English ones.
 * The key plays in LeXia's own trailer player through [YouTubeStreams], like every trailer.
 *
 * Everything here is quick or nothing: a few seconds at most, and any failure answers null so the
 * film's own trailers play. The add-on's answer is not trusted: only a well-formed YouTube key is kept.
 */
internal object StreailerTrailers {

    /** The add-on with LeXia's settings: Turkish, LeXia's own player, no YouTube link, no recaps. */
    private const val BASE = "https://streailer.elfhosted.com/" +
        "%7B%22language%22%3A%22tr-TR%22%2C%22playerTarget%22%3A%22internal%22%2C%22externalLink%22%3Afalse" +
        "%2C%22showRecap%22%3Afalse%2C%22onlyRecaps%22%3Afalse%7D"
    const val LANGUAGE = "tr-TR"

    /** Longest a trailer waits for the add-on before the film's own trailer plays. */
    const val WAIT_MS = 2_500L
    private const val FOUND_LIFE_MS = 7 * 24 * 60 * 60 * 1000L
    /** The add-on had no trailer: asked again after this (in memory only). */
    private const val EMPTY_LIFE_MS = 6 * 60 * 60 * 1000L
    /** The add-on did not answer (offline, slow, broken): left alone for a while. */
    private const val FAILED_LIFE_MS = 10 * 60 * 1000L
    private const val MAX_BYTES = 64 * 1024

    private val YT = Regex("^[A-Za-z0-9_-]{11}$")
    private val IMDB = Regex("^tt[0-9]{5,10}$")

    enum class Origin { TMDB_TURKISH, YOUTUBE_SEARCH, TMDB_ENGLISH }

    /** The add-on's trailer for one film. [language] as TrailerActivity reads it: "dub", "sub", "tr", "orig", "". */
    class Found(val key: String, val origin: Origin, val title: String, val at: Long) {
        val language: String get() {
            val t = title.lowercase(TR)
            return when {
                origin == Origin.TMDB_ENGLISH -> "orig"
                "altyaz" in t || "subtitle" in t -> "sub"
                "dubla" in t -> "dub"
                origin == Origin.TMDB_TURKISH -> "tr"
                "türkçe" in t || "turkish" in t -> "tr"
                else -> ""
            }
        }

        /** Turkish in some way (dubbed, Turkish subtitles, or listed by TMDB as Turkish). */
        val turkish: Boolean get() = language == "dub" || language == "sub" || language == "tr"

        /** What the trailer menu and badge call it. */
        val label: String get() = when (language) {
            "dub" -> "Türkçe dublaj fragman"
            "sub" -> "Türkçe altyazılı fragman"
            "tr" -> "Türkçe fragman"
            "orig" -> "Orijinal fragman"
            else -> "Fragman"
        }
    }

    private val TR = java.util.Locale("tr", "TR")

    /** Memory: film slot → answer (null = nothing) and until when it holds. */
    private val memory = HashMap<String, Pair<Long, Found?>>()
    private val asking = HashSet<String>()
    @Volatile private var disk: android.content.SharedPreferences? = null

    /** Replaced in tests; answers the add-on's text or null (no answer, error, too slow). */
    @Volatile internal var fetch: (String) -> String? = ::download
    @Volatile internal var now: () -> Long = System::currentTimeMillis

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(WAIT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(WAIT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(WAIT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }

    /** The add-on's address for [p], or null when the film has no id it knows. */
    internal fun address(p: Pick): String? {
        val type = if (p.isSeries) "series" else "movie"
        val id = when {
            IMDB.matches(p.imdbId) -> p.imdbId
            p.id > 0 -> "tmdb:${p.id}"
            else -> return null
        }
        return "$BASE/stream/$type/$id.json"
    }

    private fun slot(p: Pick): String? {
        val kind = if (p.isSeries) "tv" else "movie"
        return when {
            IMDB.matches(p.imdbId) -> "$kind|${p.imdbId}|$LANGUAGE"
            p.id > 0 -> "$kind|tmdb:${p.id}|$LANGUAGE"
            else -> null
        }
    }

    fun init(ctx: Context) {
        if (disk == null) disk = ctx.applicationContext.getSharedPreferences("streailer", Context.MODE_PRIVATE)
    }

    /** What is already known for [p], without asking anything: instant, safe on the main thread. */
    fun cached(p: Pick): Found? {
        val s = slot(p) ?: return null
        synchronized(memory) {
            memory[s]?.let { (until, found) -> if (now() < until) return found }
        }
        val saved = try { disk?.getString(s, null) } catch (_: Exception) { null } ?: return null
        val found = fromSaved(saved)
        if (found == null || now() - found.at >= FOUND_LIFE_MS) {
            try { disk?.edit()?.remove(s)?.apply() } catch (_: Exception) {}
            return null
        }
        synchronized(memory) { memory[s] = (found.at + FOUND_LIFE_MS) to found }
        return found
    }

    /** True when [p] has been asked (whatever the answer) and the answer still holds. */
    fun known(p: Pick): Boolean {
        val s = slot(p) ?: return true
        cached(p)
        return synchronized(memory) { memory[s]?.let { now() < it.first } == true }
    }

    /**
     * Asks the add-on for [p]'s trailer: at most [WAIT_MS], never on the main thread. A film
     * already asked answers from memory; the same film is never asked twice at once.
     */
    fun lookup(p: Pick): Found? {
        if (known(p)) return cached(p)
        val s = slot(p) ?: return null
        val address = address(p) ?: return null
        synchronized(asking) { if (!asking.add(s)) return cached(p) }
        try {
            val text = try { fetch(address) } catch (_: Exception) { null }
            if (text == null) {
                remember(s, null, FAILED_LIFE_MS)
                return null
            }
            val found = parse(text, now())
            if (found == null) {
                remember(s, null, EMPTY_LIFE_MS)
            } else {
                remember(s, found, FOUND_LIFE_MS)
                try { disk?.edit()?.putString(s, toSaved(found))?.apply() } catch (_: Exception) {}
            }
            return found
        } finally {
            synchronized(asking) { asking.remove(s) }
        }
    }

    /** Asks for [p] in the background (a film page or the home screen's film), so a trailer opens at once. */
    fun prefetch(p: Pick) {
        if (known(p) || slot(p) == null) return
        try { Thread { lookup(p) }.apply { isDaemon = true }.start() } catch (_: Throwable) {}
    }

    /** [key] would not play: it is forgotten for [p] and not offered again for a while. */
    fun markBad(p: Pick, key: String) {
        val s = slot(p) ?: return
        val current = cached(p) ?: return
        if (current.key != key) return
        remember(s, null, EMPTY_LIFE_MS)
        try { disk?.edit()?.remove(s)?.apply() } catch (_: Exception) {}
    }

    private fun remember(slot: String, found: Found?, life: Long) {
        synchronized(memory) {
            memory[slot] = (now() + life) to found
            while (memory.size > 300) memory.remove(memory.keys.first())
        }
    }

    /** The add-on's answer read defensively: the first stream with a well-formed YouTube key. */
    internal fun parse(text: String, at: Long): Found? = try {
        val streams = JSONObject(text).optJSONArray("streams")
        var result: Found? = null
        if (streams != null) for (i in 0 until minOf(streams.length(), 10)) {
            val s = streams.optJSONObject(i) ?: continue
            val key = s.optString("ytId").trim()
            if (!YT.matches(key)) continue
            val name = s.optString("name")
            // A season recap is not a trailer (recaps are switched off, but the add-on might change).
            if ("recap" in name.lowercase(TR)) continue
            val origin = when {
                "🇬🇧" in name -> Origin.TMDB_ENGLISH
                "▶" in name -> Origin.YOUTUBE_SEARCH
                else -> Origin.TMDB_TURKISH
            }
            val title = s.optString("title").take(200)
            // A YouTube search answer whose title does not say it is a trailer may be anything
            // (a review, a scene): not used.
            if (origin == Origin.YOUTUBE_SEARCH) {
                val t = title.lowercase(TR)
                if (listOf("fragman", "trailer", "teaser").none { it in t }) continue
            }
            result = Found(key, origin, title, at)
            break
        }
        result
    } catch (_: Exception) {
        null
    }

    private fun toSaved(f: Found): String = JSONObject()
        .put("k", f.key).put("o", f.origin.name).put("t", f.title).put("at", f.at).toString()

    private fun fromSaved(text: String): Found? = try {
        val o = JSONObject(text)
        val key = o.optString("k")
        val origin = Origin.entries.firstOrNull { it.name == o.optString("o") }
        if (!YT.matches(key) || origin == null) null else Found(key, origin, o.optString("t"), o.optLong("at"))
    } catch (_: Exception) {
        null
    }

    private fun download(address: String): String? = try {
        http.newCall(Request.Builder().url(address).header("User-Agent", "LeXia").build()).execute().use { r ->
            val body = r.body
            if (!r.isSuccessful || body == null) null
            else {
                val bytes = body.byteStream().use { it.readNBytesCompat(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) null else String(bytes, Charsets.UTF_8)
            }
        }
    } catch (_: Exception) {
        null
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < limit) {
            val n = read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    internal fun clearForTests() {
        synchronized(memory) { memory.clear() }
        try { disk?.edit()?.clear()?.commit() } catch (_: Exception) {}
    }

    /**
     * The trailer a film opens with, in this order: the add-on's Turkish-dubbed one, the film
     * list's dubbed one, the add-on's other Turkish one, the film list's Turkish-subtitled one, the
     * film's main trailer, and only when the film has none, the add-on's English one. [found] is
     * the add-on's answer (null when none yet), [bad] keys that would not play here.
     */
    fun choose(p: Pick, found: Found?, bad: Set<String> = emptySet()): String {
        fun ok(k: String?) = k != null && k.isNotBlank() && k !in bad
        val label = { v: Video -> v.label.lowercase(TR) }
        val listDub = p.trailers.firstOrNull { v -> "dubla" in label(v) && "altyaz" !in label(v) && ok(v.key) }?.key
        val listSub = p.trailers.firstOrNull { v -> "altyaz" in label(v) && ok(v.key) }?.key
        val f = found?.takeIf { ok(it.key) }
        // What the film list itself says of the add-on's key, when it has it too.
        val listed = f?.let { x -> p.trailers.firstOrNull { it.key == x.key } }?.let { label(it) }
        val fDub = f != null && (f.language == "dub" || (listed != null && "dubla" in listed && "altyaz" !in listed))
        return when {
            fDub -> f!!.key
            ok(listDub) -> listDub!!
            f != null && f.turkish -> f.key
            ok(listSub) -> listSub!!
            ok(p.trailer) -> p.trailer
            else -> p.trailers.firstOrNull { ok(it.key) }?.key ?: f?.key ?: p.trailer
        }
    }
}
