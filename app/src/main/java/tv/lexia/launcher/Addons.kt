package tv.lexia.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * LeXia's metadata-only addon layer.
 *
 * It reads standard addon manifests and catalogue metadata. It deliberately does not
 * download or execute addon code and does not call stream/source endpoints.
 */
object Addons {
    data class Catalog(val type: String, val id: String, val name: String)
    data class Installed(
        val baseUrl: String,
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val types: List<String>,
        val catalogs: List<Catalog>,
        val resources: Set<String>
    )

    data class EpisodeItem(
        val id: String,
        val title: String,
        val season: Int,
        val episode: Int,
        val released: String,
        val thumbnail: String
    )

    data class MetaDetails(val item: CatalogItem, val episodes: List<EpisodeItem>)

    data class CatalogItem(
        val id: String,
        val type: String,
        val name: String,
        val poster: String,
        val background: String,
        val description: String,
        val releaseInfo: String,
        val imdbRating: Double = 0.0,
        val genres: List<String> = emptyList(),
        val runtime: Int = 0,
        val seasons: Int = 0
    )

    private const val PREFS = "lexia_addons"
    private const val URLS = "repository_urls"
    private const val TIMEOUT = 12_000

    fun urls(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(URLS, emptySet()).orEmpty().toList().sorted()

    fun add(context: Context, raw: String) {
        val url = normalize(raw)
        require(url.startsWith("https://", true)) { "Güvenlik için yalnızca HTTPS adresleri kabul edilir." }
        val values = urls(context).toMutableSet()
        values.add(url)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(URLS, values).apply()
    }

    fun remove(context: Context, url: String) {
        val values = urls(context).toMutableSet()
        values.remove(url)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(URLS, values).apply()
    }

    fun fetchManifest(raw: String): Installed {
        val base = normalize(raw)
        val manifestUrl = if (base.endsWith("/manifest.json")) base else "$base/manifest.json"
        val json = JSONObject(getText(manifestUrl))
        val resources = json.optJSONArray("resources") ?: JSONArray()
        val resourceSet = mutableSetOf<String>()
        for (i in 0 until resources.length()) {
            val value = resources.opt(i)
            when (value) {
                is String -> resourceSet.add(value)
                is JSONObject -> value.optString("name").takeIf { it.isNotBlank() }?.let(resourceSet::add)
            }
        }
        val typesJson = json.optJSONArray("types") ?: json.optJSONArray("supportedTypes") ?: JSONArray()
        val types = (0 until typesJson.length()).map { typesJson.optString(it) }.filter { it.isNotBlank() }
        val catalogsJson = json.optJSONArray("catalogs") ?: JSONArray()
        val catalogs = (0 until catalogsJson.length()).mapNotNull { i ->
            val c = catalogsJson.optJSONObject(i) ?: return@mapNotNull null
            val type = c.optString("type").ifBlank { c.optString("apiType") }
            val id = c.optString("id")
            if (type.isBlank() || id.isBlank()) null
            else Catalog(type, id, c.optString("name").ifBlank { id })
        }
        return Installed(
            baseUrl = base.removeSuffix("/manifest.json"),
            id = json.optString("id").ifBlank { base },
            name = json.optString("name").ifBlank { base },
            version = json.optString("version"),
            description = json.optString("description"),
            types = types,
            catalogs = catalogs,
            resources = resourceSet
        )
    }

    /** Loads metadata and its embedded episode descriptors with one network request. */
    fun fetchMetaDetails(addon: Installed, type: String, id: String): MetaDetails {
        require("meta" in addon.resources || addon.resources.isEmpty()) {
            "Bu eklenti metadata kaynağı sunmuyor."
        }
        require(type.matches(Regex("^[A-Za-z0-9_-]{1,32}$"))) { "İçerik türü geçersiz." }
        require(id.isNotBlank() && id.length <= 256 && !id.contains('/')) { "İçerik kimliği geçersiz." }
        val encodedId = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        val address = addon.baseUrl.trimEnd('/') + "/meta/$type/$encodedId.json"
        val root = JSONObject(getText(address))
        val meta = root.optJSONObject("meta") ?: root
        val genres = meta.optJSONArray("genres")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
                .filter { it.isNotBlank() }.take(12)
        }.orEmpty()
        val videos = meta.optJSONArray("videos") ?: JSONArray()
        val episodes = (0 until videos.length()).mapNotNull { index ->
            val video = videos.optJSONObject(index) ?: return@mapNotNull null
            val season = video.optInt("season", -1)
            val number = video.optInt("episode", -1)
            if (season < 0 || number < 1) return@mapNotNull null
            EpisodeItem(
                id = video.optString("id"),
                title = video.optString("title").ifBlank { "Bölüm $number" },
                season = season,
                episode = number,
                released = video.optString("released"),
                thumbnail = video.optString("thumbnail")
            )
        }.sortedWith(compareBy<EpisodeItem> { it.season }.thenBy { it.episode })
        val seasonCount = meta.optInt("seasonCount", 0).takeIf { it > 0 }
            ?: episodes.map { it.season }.distinct().size
        val item = CatalogItem(
            id = meta.optString("id").ifBlank { id },
            type = meta.optString("type").ifBlank { type },
            name = meta.optString("name"),
            poster = meta.optString("poster"),
            background = meta.optString("background"),
            description = meta.optString("description"),
            releaseInfo = meta.optString("releaseInfo"),
            imdbRating = meta.optDouble("imdbRating", 0.0),
            genres = genres,
            runtime = meta.optString("runtime").substringBefore(' ').toIntOrNull() ?: 0,
            seasons = seasonCount
        )
        return MetaDetails(item, episodes)
    }

    /** Convenience accessors retained for callers that need only one part of the response. */
    fun fetchMeta(addon: Installed, type: String, id: String): CatalogItem =
        fetchMetaDetails(addon, type, id).item

    fun fetchEpisodes(addon: Installed, type: String, id: String): List<EpisodeItem> =
        fetchMetaDetails(addon, type, id).episodes

    /** Fetches only catalogue metadata. Addon stream/source resources are never requested. */
    fun fetchCatalog(addon: Installed, catalog: Catalog, skip: Int = 0): List<CatalogItem> {
        require("catalog" in addon.resources || addon.resources.isEmpty()) {
            "Bu eklenti katalog kaynağı sunmuyor."
        }
        require(catalog.type.matches(Regex("^[A-Za-z0-9_-]{1,32}$"))) {
            "Katalog türü geçersiz."
        }
        require(catalog.id.isNotBlank() && catalog.id.length <= 128) {
            "Katalog kimliği geçersiz."
        }
        val encodedCatalogId = java.net.URLEncoder.encode(catalog.id, "UTF-8").replace("+", "%20")
        val url = addon.baseUrl.trimEnd('/') +
            "/catalog/${catalog.type}/$encodedCatalogId.json" +
            if (skip > 0) "?skip=${skip.coerceAtMost(10000)}" else ""
        val metas = JSONObject(getText(url)).optJSONArray("metas") ?: JSONArray()
        return (0 until metas.length()).mapNotNull { i ->
            val m = metas.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id")
            if (id.isBlank()) return@mapNotNull null
            CatalogItem(
                id = id,
                type = m.optString("type", catalog.type),
                name = m.optString("name"),
                poster = m.optString("poster"),
                background = m.optString("background"),
                description = m.optString("description"),
                releaseInfo = m.optString("releaseInfo"),
                imdbRating = m.optDouble("imdbRating", 0.0),
                genres = m.optJSONArray("genres")?.let { genres ->
                    (0 until genres.length()).map { genres.optString(it) }
                        .filter { it.isNotBlank() }.take(12)
                }.orEmpty(),
                runtime = m.optString("runtime").toIntOrNull() ?: 0,
                seasons = m.optInt("seasonCount", 0)
            )
        }
    }

    private fun normalize(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.startsWith("https://", true)) { "Eklenti adresi HTTPS olmalı." }
        require(value.length <= 2048 && !value.any { it.isWhitespace() }) { "Eklenti adresi geçersiz." }
        return value.removeSuffix("/manifest.json")
    }

    private fun getText(address: String): String {
        var current = URL(address)
        repeat(6) { redirectCount ->
            require(current.protocol.equals("https", true)) {
                "Güvenlik nedeniyle HTTPS dışına yönlendirme kabul edilmez."
            }
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = TIMEOUT
                connection.readTimeout = TIMEOUT
                connection.setRequestProperty("Accept", "application/json")
                connection.instanceFollowRedirects = false
                val code = connection.responseCode
                if (code in 200..299) {
                    val output = ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 4 * 1024 * 1024) {
                                "Yanıt boyutu 4 MB sınırını aşıyor."
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                    return output.toString(Charsets.UTF_8.name())
                }
                if (code in 301..308) {
                    require(redirectCount < 5) { "Çok fazla yönlendirme." }
                    val location = connection.getHeaderField("Location")
                        ?: error("Yönlendirme adresi eksik.")
                    current = URL(current, location)
                    require(current.protocol.equals("https", true)) {
                        "HTTP adresine yönlendirme güvenlik nedeniyle engellendi."
                    }
                } else {
                    error("Sunucu HTTP $code yanıtı verdi.")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Yönlendirme tamamlanamadı.")
    }
}
