package tv.lexia.launcher

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Where a trailer's picture and sound can be fetched directly, so it plays in LeXia's own player
 * with nothing of YouTube's page around it.
 *
 * [audio] is null when [video] already carries the sound (an HLS list or a single file).
 * [userAgent] is the one the addresses were given to; YouTube refuses them to another.
 */
internal class TrailerSource(
    val video: String,
    val audio: String?,
    val hls: Boolean,
    val userAgent: String,
    /** A Turkish subtitle file (WebVTT), when the trailer has one. */
    val turkishSubtitles: String?,
    val height: Int
)

/**
 * Asks YouTube's player service for a video's streams, the way YouTube's own apps do.
 *
 * This is not an official interface: YouTube changes it without notice and it then stops working
 * until LeXia is updated. Every failure here answers null, and the caller falls back to YouTube's
 * embedded player, so a trailer never depends on this alone.
 */
internal object YouTubeStreams {

    private class Client(val name: String, val number: String, val version: String, val userAgent: String, val context: JSONObject)

    private fun context(vararg pairs: Pair<String, Any>) = JSONObject().apply {
        pairs.forEach { (k, v) -> put(k, v) }
        put("hl", "tr")
        put("gl", "TR")
    }

    // The apps asked, in order; the first that hands out usable streams wins.
    private val clients = listOf(
        Client(
            "VISIONOS", "101", "1.02",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            context(
                "clientName" to "VISIONOS", "clientVersion" to "1.02", "deviceMake" to "Apple",
                "deviceModel" to "RealityDevice17,1", "osName" to "visionOS", "osVersion" to "26.5.23O471"
            )
        ),
        Client(
            "ANDROID", "3", "20.10.35",
            "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip",
            context(
                "clientName" to "ANDROID", "clientVersion" to "20.10.35", "osName" to "Android",
                "osVersion" to "14", "platform" to "MOBILE", "androidSdkVersion" to 34
            )
        ),
        Client(
            "IOS", "5", "20.10.1",
            "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
            context(
                "clientName" to "IOS", "clientVersion" to "20.10.1", "deviceModel" to "iPhone16,2",
                "osName" to "iPhone", "osVersion" to "17.4.0.21E219", "platform" to "MOBILE"
            )
        )
    )

    private const val BROWSER = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    private const val PUBLIC_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val VISITOR_LIFE_MS = 6 * 60 * 60 * 1000L
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val KEY = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"")
    private val VISITOR = Regex("\"VISITOR_DATA\":\"([^\"]+)\"")

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var apiKey = PUBLIC_KEY
    @Volatile private var visitor = ""
    @Volatile private var visitorAt = 0L

    /** Found sources, kept a while: a trailer watched again (or readied ahead in a marathon) starts at once. */
    private val cache = LinkedHashMap<String, Pair<Long, TrailerSource>>()
    private const val SOURCE_LIFE_MS = 30 * 60 * 1000L

    /**
     * The best source for this video on this TV, or null. Blocks; call it off the main thread.
     * [maxHeight] is the tallest picture worth fetching; [canDecode] says whether the TV plays a
     * codec ("avc1", "vp9", "av01").
     */
    fun find(id: String, maxHeight: Int, canDecode: (String) -> Boolean, hdr: Boolean = false): TrailerSource? {
        if (!ID.matches(id)) return null
        // The same trailer can be wanted at another size (home screen, trailer page) or in HDR.
        val slot = "$id|$maxHeight|$hdr"
        synchronized(cache) {
            cache[slot]?.let { (at, source) -> if (System.currentTimeMillis() - at < SOURCE_LIFE_MS) return source }
        }
        return try {
            refreshVisitor(force = false)
            var source = askClients(id, maxHeight, canDecode, hdr)
            if (source == null && visitor.isNotEmpty()) {
                // "Sign in" from every app usually means the visitor id went stale.
                refreshVisitor(force = true)
                source = askClients(id, maxHeight, canDecode, hdr)
            }
            if (source != null) synchronized(cache) {
                cache[slot] = System.currentTimeMillis() to source
                while (cache.size > 12) cache.remove(cache.keys.first())
            }
            source
        } catch (_: Exception) {
            null
        }
    }

    /** A source that would not play is not offered again. */
    fun forget(id: String) {
        synchronized(cache) { cache.keys.filter { it.startsWith("$id|") }.forEach { cache.remove(it) } }
    }

    private fun askClients(id: String, maxHeight: Int, canDecode: (String) -> Boolean, hdr: Boolean): TrailerSource? {
        for (client in clients) {
            if (Thread.currentThread().isInterrupted) return null
            val answer = try {
                player(id, client)
            } catch (_: Exception) {
                continue
            }
            pick(answer, client.userAgent, maxHeight, canDecode, hdr)?.let { return it }
        }
        return null
    }

    /** The visitor id YouTube gives every new browser; the player service wants one. */
    private fun refreshVisitor(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && visitor.isNotEmpty() && now - visitorAt < VISITOR_LIFE_MS) return
        try {
            val request = Request.Builder()
                .url("https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=en")
                .header("User-Agent", BROWSER)
                .header("Accept-Language", "en-US,en;q=0.9")
                // Skips the cookie question some countries get instead of the page.
                .header("Cookie", "SOCS=CAI")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val html = response.body?.source()?.let { source ->
                    source.request(1_500_000)
                    source.buffer.clone().readUtf8(minOf(source.buffer.size, 1_500_000))
                }.orEmpty()
                KEY.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { apiKey = it }
                VISITOR.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let {
                    visitor = it
                    visitorAt = now
                }
            }
        } catch (_: Exception) {
            // Without a visitor id some apps still answer; the caller simply tries.
        }
    }

    private fun player(id: String, client: Client): JSONObject {
        val body = JSONObject()
            .put("videoId", id)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("context", JSONObject().put("client", client.context))
            .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$apiKey&prettyPrint=false")
            .header("User-Agent", client.userAgent)
            .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
            .header("Origin", "https://www.youtube.com")
            .header("X-YouTube-Client-Name", client.number)
            .header("X-YouTube-Client-Version", client.version)
            .apply { if (visitor.isNotEmpty()) header("X-Goog-Visitor-Id", visitor) }
            .post(body.toString().toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "player service answered ${response.code}" }
            val text = response.body?.string().orEmpty()
            check(text.length <= 3_000_000) { "player answer is too large" }
            return JSONObject(text)
        }
    }

    // ---------- Choosing among what the service lists ----------

    private fun items(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun text(o: JSONObject, name: String): String =
        if (o.isNull(name)) "" else o.optString(name)

    private fun codecOf(mime: String): String {
        val codecs = mime.substringAfter("codecs=\"", "").substringBefore('"').lowercase()
        return when {
            codecs.startsWith("avc1") -> "avc1"
            codecs.startsWith("vp9") || codecs.startsWith("vp09") -> "vp9"
            codecs.startsWith("av01") -> "av01"
            else -> codecs.substringBefore('.')
        }
    }

    private fun isHdr(format: JSONObject): Boolean {
        val transfer = format.optJSONObject("colorInfo")?.let { text(it, "transferCharacteristics") }.orEmpty()
        return "SMPTEST2084" in transfer || "ARIB_STD_B67" in transfer || text(format, "qualityLabel").contains("HDR", true)
    }

    /**
     * Reads one answer of the player service. Preference: separate picture and sound (the sharpest
     * the TV can decode, not taller than [maxHeight]), then an HLS list, then a single small file.
     */
    internal fun pick(
        answer: JSONObject,
        userAgent: String,
        maxHeight: Int,
        canDecode: (String) -> Boolean,
        /** The screen shows HDR (and the picture goes to a surface that can): HDR pictures first. */
        hdr: Boolean = false
    ): TrailerSource? {
        val status = answer.optJSONObject("playabilityStatus")?.let { text(it, "status") }.orEmpty()
        if (status.isNotEmpty() && status != "OK") return null
        val data = answer.optJSONObject("streamingData") ?: return null
        val subtitles = turkishSubtitles(answer)
        val adaptive = items(data.optJSONArray("adaptiveFormats")).filter { text(it, "url").startsWith("https://") }

        val pictures = adaptive
            .filter { text(it, "mimeType").startsWith("video/") && (hdr || !isHdr(it)) && it.optInt("height") > 0 }
            .filter { canDecode(codecOf(text(it, "mimeType"))) }
        val sounds = adaptive.filter { text(it, "mimeType").startsWith("audio/") }
            // A trailer with several dubs lists each; the one marked as the video's own is wanted.
            .filter { it.optJSONObject("audioTrack")?.optBoolean("audioIsDefault", true) ?: true }
            .filter { !it.optBoolean("isDrc", false) }
        if (pictures.isNotEmpty() && sounds.isNotEmpty()) {
            val fitting = pictures.filter { it.optInt("height") <= maxHeight && it.optInt("width") <= maxHeight * 16 / 9 }
            val picture = (fitting.ifEmpty { pictures.filter { it.optInt("height") == pictures.minOf { p -> p.optInt("height") } } })
                .sortedWith(
                    compareByDescending<JSONObject> { if (hdr && isHdr(it)) 1 else 0 }
                        .thenByDescending { it.optInt("height") }
                        // 60 frames a second doubles the work for no gain in a trailer.
                        .thenBy { if (it.optInt("fps", 30) > 30) 1 else 0 }
                        .thenBy { when (codecOf(text(it, "mimeType"))) { "avc1" -> 0; "vp9" -> 1; else -> 2 } }
                        .thenByDescending { it.optLong("bitrate") }
                ).first()
            val sound = sounds.sortedWith(
                compareBy<JSONObject> { if (text(it, "mimeType").startsWith("audio/mp4")) 0 else 1 }
                    .thenByDescending { it.optLong("bitrate") }
            ).first()
            return TrailerSource(text(picture, "url"), text(sound, "url"), false, userAgent, subtitles, picture.optInt("height"))
        }

        val list = text(data, "hlsManifestUrl")
        if (list.startsWith("https://")) return TrailerSource(list, null, true, userAgent, subtitles, 0)

        val single = items(data.optJSONArray("formats"))
            .filter { text(it, "url").startsWith("https://") && text(it, "mimeType").startsWith("video/mp4") }
            .maxByOrNull { it.optInt("height") }
            ?: return null
        return TrailerSource(text(single, "url"), null, false, userAgent, subtitles, single.optInt("height"))
    }

    /** The trailer's own Turkish subtitles (not machine-made ones), as a WebVTT address. */
    private fun turkishSubtitles(answer: JSONObject): String? {
        val tracks = answer.optJSONObject("captions")
            ?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks")
        val all = items(tracks)
        val own = all.firstOrNull {
            text(it, "languageCode").startsWith("tr", ignoreCase = true) && text(it, "kind") != "asr"
        }
        // No Turkish subtitles of its own: another language's, translated to Turkish by YouTube
        // (English first, people-made before machine-made).
        // YouTube's own speech captions in Turkish mean the trailer speaks Turkish (dubbed): no subtitles.
        val spokenTurkish = all.any { text(it, "kind") == "asr" && text(it, "languageCode").startsWith("tr", ignoreCase = true) }
        val other = if (own != null || spokenTurkish) null else all.sortedWith(compareBy(
            { if (text(it, "kind") == "asr") 1 else 0 },
            { if (text(it, "languageCode").startsWith("en", ignoreCase = true)) 0 else 1 }
        )).firstOrNull()
        val track = own ?: other ?: return null
        val base = text(track, "baseUrl")
        if (!base.startsWith("https://")) return null
        val clean = base.replace(Regex("[&?]fmt=[^&]*"), "").replace(Regex("[&?]tlang=[^&]*"), "")
        val sep = if ('?' in clean) "&" else "?"
        return clean + sep + "fmt=vtt" + (if (own == null) "&tlang=tr" else "")
    }
}
