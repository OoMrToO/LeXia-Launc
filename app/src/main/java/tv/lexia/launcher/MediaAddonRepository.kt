package tv.lexia.launcher

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class InstalledMediaAddon(
    val baseUrl: String,
    val id: String,
    val name: String,
    val version: String,
    val types: List<String>,
    val resources: List<String>,
    val idPrefixes: List<String> = emptyList()
)

internal data class MediaEpisode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val released: String = ""
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
    val subtitles: List<MediaSubtitle>
)

/**
 * Minimal Stremio-compatible add-on client, intentionally isolated from launcher preferences.
 *
 * It implements the same manifest / meta / stream resource contract used by NuvioTV. Only direct
 * HTTP(S) streams are handed to Media3 in this first stage. Magnet/torrent, YouTube-only,
 * browser/external and debrid-client-resolve entries are not silently misrepresented as playable.
 */
internal class MediaAddonRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
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
                    idPrefixes = strings(item.optJSONArray("idPrefixes"))
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Downloads and validates the manifest before persisting the add-on. */
    fun addAddon(address: String): InstalledMediaAddon {
        val base = normalizeBaseUrl(address)
        val manifestUrl = base.newBuilder().addPathSegment("manifest.json").build()
        val json = getJson(manifestUrl)
        val id = json.optString("id").trim()
        val name = json.optString("name").trim()
        val version = json.optString("version").trim()
        require(id.isNotEmpty() && name.isNotEmpty()) {
            "Manifest dosyasında eklenti adı veya kimliği bulunamadı."
        }

        val types = strings(json.optJSONArray("types"))
        val resources = parseResourceNames(json.optJSONArray("resources"))
        require("stream" in resources) {
            "Bu eklenti video kaynağı sunmuyor (stream kaynağı yok)."
        }
        require(types.isEmpty() || types.any { it == "movie" || it == "series" || it == "tv" }) {
            "Eklenti film veya dizi desteği bildirmiyor."
        }

        val addon = InstalledMediaAddon(
            baseUrl = base.toString().trimEnd('/'),
            id = id,
            name = name,
            version = version.ifBlank { "—" },
            types = types,
            resources = resources,
            idPrefixes = strings(json.optJSONArray("idPrefixes"))
        )
        val current = installedAddons().filterNot { it.baseUrl == addon.baseUrl }.toMutableList()
        current.add(addon)
        saveInstalled(current)
        return addon
    }

    fun removeAddon(baseUrl: String) {
        val normalized = runCatching { normalizeBaseUrl(baseUrl).toString().trimEnd('/') }
            .getOrDefault(baseUrl.trim().trimEnd('/'))
        saveInstalled(installedAddons().filterNot { it.baseUrl == normalized })
    }

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

        for (addon in installedAddons()) {
            if ("meta" !in addon.resources || !supports(addon, "series")) continue
            for (id in lookupIds) {
                if (!supportsId(addon, id)) continue
                val before = result.size
                try {
                    val root = getJson(endpoint(addon.baseUrl, "meta", "series", id))
                    val meta = root.optJSONObject("meta") ?: root
                    val videos = meta.optJSONArray("videos") ?: continue
                    for (i in 0 until videos.length()) {
                        val item = videos.optJSONObject(i) ?: continue
                        val videoId = item.optString("id").trim()
                        if (videoId.isBlank() || videoId.length > 300) continue
                        val season = item.optInt("season", 0)
                        val episode = item.optInt("episode", 0)
                        val title = item.optString("title").ifBlank {
                            if (season > 0 && episode > 0) "$season. Sezon · $episode. Bölüm" else "Bölüm"
                        }.take(160)
                        result.putIfAbsent(
                            videoId,
                            MediaEpisode(videoId, title, season.coerceAtLeast(0), episode.coerceAtLeast(0), item.optString("released"))
                        )
                    }
                } catch (_: Exception) {
                    // One offline or incompatible add-on must not block the other providers.
                }
                if (result.size > before) break
            }
        }
        return result.values
            .sortedWith(compareBy<MediaEpisode> { it.season }.thenBy { it.episode }.thenBy { it.title })
            .take(MAX_EPISODES)
    }

    /** Returns direct HTTP(S) stream choices from every enabled installed add-on. */
    fun fetchStreams(type: String, videoId: String): List<MediaStream> {
        require(type == "movie" || type == "series") { "Desteklenmeyen içerik türü." }
        require(videoId.isNotBlank() && videoId.length <= 300) { "Geçersiz içerik kimliği." }

        val result = ArrayList<MediaStream>()
        for (addon in installedAddons()) {
            if ("stream" !in addon.resources || !supports(addon, type) || !supportsId(addon, videoId)) continue
            try {
                val root = getJson(endpoint(addon.baseUrl, "stream", type, videoId))
                val streams = root.optJSONArray("streams") ?: continue
                for (i in 0 until streams.length()) {
                    val item = streams.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    if (!isDirectHttpUrl(url)) continue

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
                            subtitles = subtitles
                        )
                    )
                    if (result.size >= MAX_STREAMS) return result
                }
            } catch (_: Exception) {
                // Keep the remaining providers available if one add-on fails.
            }
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

    private fun endpoint(baseUrl: String, resource: String, type: String, id: String): HttpUrl {
        val base = normalizeBaseUrl(baseUrl)
        return base.newBuilder()
            .addPathSegment(resource)
            .addPathSegment(type)
            .addPathSegment("$id.json")
            .build()
    }

    private fun normalizeBaseUrl(raw: String): HttpUrl {
        var value = raw.trim()
        require(value.isNotEmpty()) { "Eklenti adresi boş olamaz." }
        require('?' !in value && '#' !in value) {
            "İlk sürümde yapılandırma sorgusu içeren eklenti adresleri desteklenmiyor."
        }
        if (value.endsWith("/manifest.json", ignoreCase = true)) {
            value = value.dropLast("/manifest.json".length)
        }
        value = value.trimEnd('/')
        val url = value.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Geçerli bir HTTP(S) eklenti adresi gir.")
        require(url.scheme == "https") {
            "Güvenlik nedeniyle yalnızca HTTPS eklentileri destekleniyor."
        }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Kullanıcı adı/şifre içeren adresler kabul edilmiyor."
        }
        return url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build()
    }

    private fun isDirectHttpUrl(raw: String): Boolean {
        if (raw.isBlank() || raw.length > 4096) return false
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.scheme == "https" &&
            url.username.isEmpty() && url.password.isEmpty()
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
        const val PREFS_NAME = "media_addons"
        const val KEY_INSTALLED = "installed_v1"
        const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
        const val MAX_STREAMS = 80
        const val MAX_EPISODES = 200
        const val MAX_HEADERS = 20
        val IMDB_ID = Regex("^tt[0-9]{7,10}$")
        val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
    }
}
