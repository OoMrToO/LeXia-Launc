package tv.lexia.launcher

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class InstalledMediaAddon(
    val baseUrl: String,
    val id: String,
    val name: String,
    val version: String,
    val types: List<String>,
    val resources: List<String>,
    val idPrefixes: List<String> = emptyList(),
    /** Switched off in Ayarlar › Medya kaynakları: kept, but not asked (as in Nuvio). */
    val enabled: Boolean = true,
    val description: String = "",
    /** The add-on has a settings page of its own (behaviorHints.configurable). */
    val configurable: Boolean = false
) {
    /** The add-on's own settings page, opened in a browser. */
    val configureUrl: String
        get() {
            val q = baseUrl.indexOf('?')
            return if (q < 0) "$baseUrl/configure" else baseUrl.substring(0, q) + "/configure" + baseUrl.substring(q)
        }
}

internal data class MediaEpisode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val released: String = "",
    /** A still from the episode: a full address, or a TMDB path ("/abc.jpg"). */
    val thumbnail: String = "",
    val overview: String = "",
    /** Length in minutes; 0 when unknown. */
    val runtime: Int = 0,
    val rating: Double = 0.0
)

internal data class MediaSubtitle(
    val url: String,
    val language: String,
    val label: String
)

internal data class MediaStream(
    val addonName: String,
    val name: String,
    val description: String,
    val url: String,
    val headers: Map<String, String>,
    val subtitles: List<MediaSubtitle>,
    val quality: String = "",
    val language: String = "",
    val server: String = "",
    val size: String = "",
    val streamType: String = "",
    val seeders: Int? = null,
    val peers: Int? = null,
    val infoHash: String = "",
    val audioDelayMs: Long? = null,
    val audioDelayLanguage: String = "",
    /** The video's file name, when the add-on gives it ("Film.2024.1080p.WEB-DL.mkv"). */
    val fileName: String = "",
    /** The video's size in bytes; 0 when unknown. */
    val sizeBytes: Long = 0L
) {
    /** Resolution, HDR, sound and the rest, read from everything the source says about itself. */
    val tags: StreamTags by lazy {
        StreamTags.of(quality, name, description, fileName, language, size, sizeBytes = sizeBytes)
    }
}

/**
 * Minimal Stremio-compatible add-on client, intentionally isolated from launcher preferences.
 *
 * It implements the same manifest / meta / stream resource contract used by NuvioTV. Only direct
 * HTTP(S) streams are handed to Media3 in this first stage. Magnet/torrent, YouTube-only,
 * browser/external and debrid-client-resolve entries are not silently misrepresented as playable.
 */
internal class MediaAddonRepository(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    fun installedAddons(): List<InstalledMediaAddon> {
        val raw = prefs.getString(KEY_INSTALLED, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val baseUrl = item.optString("url")
                val id = item.optString("id")
                val name = item.optString("name")
                if (baseUrl.isBlank() || id.isBlank() || name.isBlank()) return@mapNotNull null
                InstalledMediaAddon(
                    baseUrl = baseUrl,
                    id = id,
                    name = name,
                    version = item.optString("version"),
                    types = strings(item.optJSONArray("types")),
                    resources = strings(item.optJSONArray("resources")),
                    idPrefixes = strings(item.optJSONArray("idPrefixes")),
                    enabled = item.optBoolean("enabled", true),
                    description = item.optString("description"),
                    configurable = item.optBoolean("configurable", false)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Downloads and validates the manifest before persisting the add-on. */
    fun addAddon(address: String): InstalledMediaAddon {
        val base = canonicalBase(address)
        val json = getJson(endpoint(base, "manifest.json"))
        val id = json.optString("id").trim()
        val name = json.optString("name").trim()
        val version = json.optString("version").trim()
        require(id.isNotEmpty() && name.isNotEmpty()) {
            "Manifest dosyasında eklenti adı veya kimliği bulunamadı."
        }

        // Any Stremio add-on is welcome, as in Nuvio: sources, episode lists (Cinemeta), subtitles.
        // What it can do is read from its manifest when it is asked.
        val types = strings(json.optJSONArray("types"))
        val resources = parseResourceNames(json.optJSONArray("resources"))

        val addon = InstalledMediaAddon(
            baseUrl = base,
            id = id,
            name = name,
            version = version.ifBlank { "—" },
            types = types,
            resources = resources,
            idPrefixes = strings(json.optJSONArray("idPrefixes")),
            description = json.optString("description").take(300),
            configurable = json.optJSONObject("behaviorHints")?.optBoolean("configurable", false) == true
        )
        // Added again (a new configuration): it keeps its place in the list.
        val current = installedAddons().toMutableList()
        val at = current.indexOfFirst { same(it.baseUrl, base) || it.id == addon.id && it.baseUrl.substringBefore('?') == base.substringBefore('?') }
        if (at >= 0) current[at] = addon.copy(enabled = current[at].enabled) else current.add(addon)
        saveInstalled(current)
        return addon
    }

    fun removeAddon(baseUrl: String) {
        saveInstalled(installedAddons().filterNot { same(it.baseUrl, baseUrl) })
    }

    fun setEnabled(baseUrl: String, enabled: Boolean) {
        saveInstalled(installedAddons().map { if (same(it.baseUrl, baseUrl)) it.copy(enabled = enabled) else it })
    }

    /** Moves an add-on up (-1) or down (+1): sources are listed in this order, as in Nuvio. */
    fun move(baseUrl: String, step: Int) {
        val list = installedAddons().toMutableList()
        val from = list.indexOfFirst { same(it.baseUrl, baseUrl) }
        val to = from + step
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        saveInstalled(list)
    }

    /** The add-ons that are switched on, in the viewer's order. */
    private fun activeAddons() = installedAddons().filter { it.enabled }

    private fun same(a: String, b: String) =
        runCatching { canonicalBase(a) }.getOrDefault(a).equals(runCatching { canonicalBase(b) }.getOrDefault(b), ignoreCase = true)

    /**
     * Queries installed meta resources for Stremio episode IDs. Those IDs are passed through to
     * /stream/series/{videoId}.json, so an add-on can use its own canonical episode identifier.
     */
    fun fetchEpisodes(tmdbId: Int, imdbId: String = ""): List<MediaEpisode> {
        require(tmdbId > 0) { "Bu içerikte TMDB kimliği bulunmuyor." }
        val lookupIds = listOfNotNull(
            imdbId.takeIf { IMDB_ID.matches(it) },
            "tmdb:$tmdbId"
        ).distinct()
        val result = LinkedHashMap<String, MediaEpisode>()

        // Nuvio's details source first (installed add-ons and Cinemeta, with pictures and summaries).
        try {
            NuvioMeta.episodes(appContext, lookupIds, activeAddons().map { it.baseUrl })
                .forEach { result.putIfAbsent(it.id, it) }
        } catch (_: Exception) {
        }

        for (addon in if (result.isEmpty()) activeAddons() else emptyList()) {
            if ("meta" !in addon.resources || !supports(addon, "series")) continue
            for (id in lookupIds) {
                if (!supportsId(addon, id)) continue
                val before = result.size
                try {
                    addEpisodes(getJson(endpoint(addon.baseUrl, "meta", "series", id)), result)
                } catch (_: Exception) {
                    // One offline or incompatible add-on must not block the other providers.
                }
                if (result.size > before) break
            }
        }
        // No installed add-on lists episodes: Cinemeta (the list Stremio and Nuvio use themselves),
        // then TMDB's season sizes. Typing season and episode numbers by hand is the last resort.
        val imdb = imdbId.takeIf { IMDB_ID.matches(it) } ?: imdbId("tv", tmdbId)
        if (result.isEmpty() && imdb.isNotEmpty()) {
            try {
                addEpisodes(getJson(CINEMETA.toHttpUrlOrNull()!!.newBuilder()
                    .addPathSegment("meta").addPathSegment("series").addPathSegment("$imdb.json").build()), result)
            } catch (_: Exception) {
            }
        }
        if (result.isEmpty()) {
            try {
                seasonsFromTmdb(tmdbId, imdb.ifEmpty { "tmdb:$tmdbId" }, result)
            } catch (_: Exception) {
            }
        }
        return result.values
            // Two add-ons name the same episode differently ("tt…:1:2", "tmdb:…:1:2"); one entry is enough.
            .distinctBy { if (it.season > 0 && it.episode > 0) "${it.season}:${it.episode}" else it.id }
            .sortedWith(compareBy<MediaEpisode> { it.season }.thenBy { it.episode }.thenBy { it.title })
            .take(MAX_EPISODES)
    }

    private fun addEpisodes(root: JSONObject, result: MutableMap<String, MediaEpisode>) {
        val meta = root.optJSONObject("meta") ?: root
        val videos = meta.optJSONArray("videos") ?: return
        for (i in 0 until videos.length()) {
            val item = videos.optJSONObject(i) ?: continue
            val videoId = item.optString("id").trim()
            if (videoId.isBlank() || videoId.length > 300) continue
            val season = item.optInt("season", 0)
            val episode = item.optInt("episode", item.optInt("number", 0))
            val title = item.optString("title").ifBlank { item.optString("name") }.takeIf { it != "null" }.orEmpty().ifBlank {
                if (season > 0 && episode > 0) "$season. Sezon · $episode. Bölüm" else "Bölüm"
            }.take(160)
            result.putIfAbsent(
                videoId,
                MediaEpisode(videoId, title, season.coerceAtLeast(0), episode.coerceAtLeast(0), item.optString("released"))
            )
        }
    }

    /** Every season's episodes by number alone, from how many TMDB says each season has. */
    /**
     * One season's episodes in Turkish from TMDB (name, summary, still, air date, length), by
     * episode number; empty when TMDB has no such season or there is no key.
     */
    fun tmdbSeason(tmdbId: Int, season: Int): Map<Int, MediaEpisode> {
        val key = BuildConfig.TMDB_API_KEY
        if (key.isBlank() || tmdbId <= 0 || season < 0) return emptyMap()
        val root = getJson(TMDB.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("tv").addPathSegment(tmdbId.toString())
            .addPathSegment("season").addPathSegment(season.toString())
            .addQueryParameter("api_key", key).addQueryParameter("language", "tr-TR").build())
        val list = root.optJSONArray("episodes") ?: return emptyMap()
        val result = HashMap<Int, MediaEpisode>()
        for (i in 0 until list.length()) {
            val e = list.optJSONObject(i) ?: continue
            val number = e.optInt("episode_number", 0).takeIf { it > 0 } ?: continue
            result[number] = MediaEpisode(
                id = "",
                title = e.optString("name"),
                season = season,
                episode = number,
                released = e.optString("air_date"),
                thumbnail = e.optString("still_path").takeIf { it.startsWith("/") }.orEmpty(),
                overview = e.optString("overview"),
                runtime = e.optInt("runtime", 0),
                rating = e.optDouble("vote_average", 0.0)
            )
        }
        return result
    }

    private fun seasonsFromTmdb(tmdbId: Int, seriesId: String, result: MutableMap<String, MediaEpisode>) {
        val key = BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return
        val show = getJson(TMDB.toHttpUrlOrNull()!!.newBuilder()
            .addPathSegment("tv").addPathSegment(tmdbId.toString())
            .addQueryParameter("api_key", key).addQueryParameter("language", "tr-TR").build())
        val seasons = show.optJSONArray("seasons") ?: return
        for (i in 0 until seasons.length()) {
            val item = seasons.optJSONObject(i) ?: continue
            val season = item.optInt("season_number", 0)
            if (season <= 0) continue
            for (episode in 1..item.optInt("episode_count", 0).coerceAtMost(500)) {
                val id = "$seriesId:$season:$episode"
                result.putIfAbsent(id, MediaEpisode(id, "$episode. Bölüm", season, episode))
            }
        }
    }

    /**
     * The IMDb id ("tt0133093") of a TMDB film ("movie") or series ("tv"); empty when it cannot be
     * found. Stremio add-ons look films up by it. Asked from TMDB once per title, then remembered.
     */
    fun imdbId(type: String, tmdbId: Int): String {
        if (tmdbId <= 0) return ""
        prefs.getString("imdb:$type:$tmdbId", "")?.takeIf { IMDB_ID.matches(it) }?.let { return it }
        val key = BuildConfig.TMDB_API_KEY
        if (key.isBlank()) return ""
        return try {
            val found = getJson(TMDB.toHttpUrlOrNull()!!.newBuilder()
                .addPathSegment(type).addPathSegment(tmdbId.toString()).addPathSegment("external_ids")
                .addQueryParameter("api_key", key).build()).optString("imdb_id")
            if (IMDB_ID.matches(found)) {
                prefs.edit().putString("imdb:$type:$tmdbId", found).apply()
                found
            } else ""
        } catch (_: Exception) {
            ""
        }
    }

    /** What the last source search ran into, for telling the viewer why nothing came. */
    class SearchNotes {
        /** Add-ons that were asked. */
        val asked = AtomicInteger()
        /** Add-ons that did not answer. */
        val failed = AtomicInteger()
        /** Sources that are torrents only (no address to play). */
        val torrents = AtomicInteger()
        /** Sources left out for another reason (not HTTPS, a web page, a YouTube id). */
        val unplayable = AtomicInteger()
    }

    @Volatile var notes = SearchNotes()
        private set

    /** Queries Stremio subtitle resources independently of stream resources. */
    fun fetchSubtitles(type: String, videoId: String): List<MediaSubtitle> {
        require(type == "movie" || type == "series") { "Desteklenmeyen içerik türü." }
        require(videoId.isNotBlank() && videoId.length <= 300) { "Geçersiz içerik kimliği." }

        val result = LinkedHashMap<String, MediaSubtitle>()
        for (addon in activeAddons()) {
            val resource = addon.resources.firstOrNull {
                it.equals("subtitles", true) || it.equals("subtitle", true)
            } ?: continue
            if (!supports(addon, type) || !supportsId(addon, videoId)) continue
            try {
                val root = getJson(endpoint(addon.baseUrl, resource, type, videoId))
                val subtitles = root.optJSONArray("subtitles") ?: continue
                for (i in 0 until minOf(subtitles.length(), MAX_SUBTITLES)) {
                    val item = subtitles.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (!isDirectHttpUrl(url)) continue
                    val language = item.optString("lang")
                        .ifBlank { item.optString("language") }
                        .ifBlank { "und" }.take(16)
                    val label = item.optString("label")
                        .ifBlank { item.optString("name") }
                        .ifBlank { language.uppercase() }.take(80)
                    result.putIfAbsent("$url|$language", MediaSubtitle(url, language, label))
                    if (result.size >= MAX_SUBTITLES) return result.values.toList()
                }
            } catch (_: Exception) {
                // A broken subtitle endpoint must not hide streams or other subtitle providers.
            }
        }
        return result.values.toList()
    }

    /**
     * Direct HTTP(S) stream choices from every installed add-on. The add-ons are asked at the
     * same time, so one slow add-on does not hold up the others; the answers keep the add-ons'
     * installed order.
     */
    fun fetchStreams(
        type: String,
        videoId: String,
        /** Called (on a background thread) with each add-on's streams as soon as that add-on answers. */
        onPartial: ((List<MediaStream>) -> Unit)? = null
    ): List<MediaStream> {
        require(type == "movie" || type == "series") { "Desteklenmeyen içerik türü." }
        require(videoId.isNotBlank() && videoId.length <= 300) { "Geçersiz içerik kimliği." }

        val addons = activeAddons().filter {
            "stream" in it.resources && supports(it, type) && supportsId(it, videoId)
        }
        val found = SearchNotes()
        notes = found
        if (addons.isEmpty()) return emptyList()
        found.asked.set(addons.size)
        val pool = Executors.newFixedThreadPool(minOf(addons.size, MAX_PARALLEL_ADDONS))
        try {
            val answers = addons.map { addon ->
                pool.submit(Callable {
                    // Keep the remaining providers available if one add-on fails.
                    try {
                        streamsOf(addon, type, videoId).also { part -> if (part.isNotEmpty()) onPartial?.invoke(part) }
                    } catch (_: Exception) {
                        found.failed.incrementAndGet()
                        emptyList()
                    }
                })
            }
            return answers.flatMap { it.get() }.take(MAX_STREAMS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun streamsOf(addon: InstalledMediaAddon, type: String, videoId: String): List<MediaStream> {
        val root = getJson(endpoint(addon.baseUrl, "stream", type, videoId))
        val streams = root.optJSONArray("streams") ?: return emptyList()
        val result = ArrayList<MediaStream>()
        for (i in 0 until streams.length()) {
            val item = streams.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (!isDirectHttpUrl(url)) {
                if (item.optString("infoHash").isNotBlank() || url.startsWith("magnet:", true)) notes.torrents.incrementAndGet()
                else notes.unplayable.incrementAndGet()
                continue
            }

            val behavior = item.optJSONObject("behaviorHints")
            val proxyHeaders = behavior?.optJSONObject("proxyHeaders")
            val headers = sanitizeHeaders(proxyHeaders?.optJSONObject("request"))
            val subtitles = parseSubtitles(item.optJSONArray("subtitles"))
            val label = item.optString("name").ifBlank {
                item.optString("title").ifBlank { addon.name }
            }.take(120)
            val description = item.optString("description").ifBlank {
                item.optString("title")
            }.take(300)

            result.add(
                MediaStream(
                    addonName = addon.name,
                    name = label,
                    description = description,
                    url = url,
                    headers = headers,
                    subtitles = subtitles,
                    // A JSON null reads as the text "null".
                    fileName = behavior?.optString("filename").orEmpty().takeIf { it != "null" }.orEmpty().take(300),
                    sizeBytes = behavior?.optLong("videoSize", 0L)?.coerceAtLeast(0L) ?: 0L
                )
            )
            if (result.size >= MAX_STREAMS) break
        }
        return result
    }

    private fun supports(addon: InstalledMediaAddon, type: String): Boolean =
        addon.types.isEmpty() || type in addon.types ||
            (type == "series" && "tv" in addon.types)

    private fun supportsId(addon: InstalledMediaAddon, videoId: String): Boolean {
        if (addon.idPrefixes.isEmpty()) return true
        val baseId = videoId.substringBefore(':')
        return addon.idPrefixes.any { prefix -> baseId.startsWith(prefix, ignoreCase = true) }
    }

    private fun parseSubtitles(raw: JSONArray?): List<MediaSubtitle> {
        if (raw == null) return emptyList()
        val result = ArrayList<MediaSubtitle>()
        for (i in 0 until minOf(raw.length(), 20)) {
            val item = raw.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (!isDirectHttpUrl(url)) continue
            val lang = item.optString("lang").ifBlank { "und" }.take(16)
            result.add(MediaSubtitle(url, lang, lang.uppercase()))
        }
        return result
    }

    private fun sanitizeHeaders(raw: JSONObject?): Map<String, String> {
        if (raw == null) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val keys = raw.keys()
        while (keys.hasNext() && result.size < MAX_HEADERS) {
            val key = keys.next()
            val value = raw.optString(key)
            if (!HEADER_NAME.matches(key) || value.isBlank() || value.length > 2048) continue
            if (key.equals("Host", true) || key.equals("Content-Length", true) || key.equals("Range", true)) continue
            if ('\r' in value || '\n' in value) continue
            result[key] = value
        }
        return result
    }

    private fun getJson(url: HttpUrl): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LeXia/MediaCenter")
            .header("Accept", "application/json")
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Sunucu HTTP ${response.code} döndürdü.")
            }
            val body = response.body ?: throw IOException("Sunucu boş yanıt döndürdü.")
            if (body.contentLength() > MAX_RESPONSE_BYTES) {
                throw IOException("Eklenti yanıtı çok büyük.")
            }
            val text = body.string()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
                throw IOException("Eklenti yanıtı çok büyük.")
            }
            return JSONObject(text)
        }
    }

    /**
     * "<base>/<parts>.json", as Nuvio asks it: a configured add-on keeps its settings in the
     * address, either in the path or after a "?", and the "?" part goes at the very end.
     */
    private fun endpoint(base: String, vararg parts: String): HttpUrl {
        val q = base.indexOf('?')
        val path = if (q < 0) base else base.substring(0, q)
        val query = if (q < 0) null else base.substring(q + 1).takeIf { it.isNotEmpty() }
        val builder = path.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("Geçerli bir eklenti adresi gir.")
        parts.forEachIndexed { i, part -> builder.addPathSegment(if (i == parts.lastIndex && !part.endsWith(".json")) "$part.json" else part) }
        if (query != null) builder.encodedQuery(query)
        return builder.build()
    }

    /** The add-on's address without "/manifest.json", kept with its "?…" settings (Nuvio's canonical form). */
    private fun canonicalBase(raw: String): String {
        var value = raw.trim()
        require(value.isNotEmpty()) { "Eklenti adresi boş olamaz." }
        // "stremio://…/manifest.json" is how add-on pages write their install link.
        if (value.startsWith("stremio://", ignoreCase = true)) value = "https://" + value.substring("stremio://".length)
        if (!value.contains("://")) value = "https://$value"
        value = value.substringBefore('#')
        val q = value.indexOf('?')
        var path = (if (q < 0) value else value.substring(0, q)).trimEnd('/')
        val query = if (q < 0) "" else value.substring(q)
        if (path.endsWith("/manifest.json", ignoreCase = true)) path = path.dropLast("/manifest.json".length).trimEnd('/')
        val url = path.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Geçerli bir eklenti adresi gir.")
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Kullanıcı adı/şifre içeren adresler kabul edilmiyor."
        }
        return path + query
    }

    /** A video or subtitle address the player can open: http or https, as Nuvio allows. */
    private fun isDirectHttpUrl(raw: String): Boolean {
        if (raw.isBlank() || raw.length > 4096) return false
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.username.isEmpty() && url.password.isEmpty()
    }

    private fun parseResourceNames(raw: JSONArray?): List<String> {
        if (raw == null) return emptyList()
        val result = ArrayList<String>()
        for (i in 0 until raw.length()) {
            when (val item = raw.opt(i)) {
                is String -> result.add(item)
                is JSONObject -> item.optString("name").takeIf { it.isNotBlank() }?.let(result::add)
            }
        }
        return result.distinct().take(30)
    }

    private fun saveInstalled(addons: List<InstalledMediaAddon>) {
        val array = JSONArray()
        addons.forEach { addon ->
            array.put(
                JSONObject()
                    .put("url", addon.baseUrl)
                    .put("id", addon.id)
                    .put("name", addon.name)
                    .put("version", addon.version)
                    .put("types", JSONArray(addon.types))
                    .put("resources", JSONArray(addon.resources))
                    .put("idPrefixes", JSONArray(addon.idPrefixes))
                    .put("enabled", addon.enabled)
                    .put("description", addon.description)
                    .put("configurable", addon.configurable)
            )
        }
        prefs.edit().putString(KEY_INSTALLED, array.toString()).apply()
    }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until minOf(array.length(), 50))
            .map { array.optString(it).trim() }
            .filter { it.isNotEmpty() }
    }

    private companion object {
        const val CINEMETA = "https://v3-cinemeta.strem.io"
        const val TMDB = "https://api.themoviedb.org/3"
        const val PREFS_NAME = "media_addons"
        const val KEY_INSTALLED = "installed_v1"
        const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
        const val MAX_STREAMS = 80
        const val MAX_PARALLEL_ADDONS = 4
        const val MAX_SUBTITLES = 40
        const val MAX_EPISODES = 2000
        const val MAX_HEADERS = 20
        val IMDB_ID = Regex("^tt[0-9]{7,10}$")
        val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}
