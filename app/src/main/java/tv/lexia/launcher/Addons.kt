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
    data class Catalog(val type: String, val id: String, val name: String, val extras: Set<String> = emptySet())
    data class ScraperCatalog(val type: String, val id: String, val name: String, val extras: Set<String> = emptySet())
    data class ScraperProvider(
        val id: String,
        val name: String,
        val description: String,
        val filename: String,
        val version: String,
        val supportedTypes: List<String>,
        val formats: List<String>,
        val enabled: Boolean,
        val catalogs: List<ScraperCatalog>,
        val author: String = "",
        val logo: String = "",
        val contentLanguage: List<String> = emptyList(),
        val limited: Boolean = false,
        val disabledPlatforms: List<String> = emptyList()
    )
    data class Installed(
        val baseUrl: String,
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val types: List<String>,
        val catalogs: List<Catalog>,
        val resources: Set<String>,
        val format: String = "stremio",
        val scrapers: List<ScraperProvider> = emptyList()
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

    data class ProviderStream(val name: String, val title: String, val url: String, val quality: String, val format: String, val headers: Map<String, String>)
    data class ProviderSubtitle(val name: String, val language: String, val url: String, val format: String, val headers: Map<String, String>)
    data class ProviderResult(val streams: List<ProviderStream>, val subtitles: List<ProviderSubtitle>)

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
        val scrapersJson = json.optJSONArray("scrapers") ?: JSONArray()
        val scrapers = (0 until scrapersJson.length()).mapNotNull { i ->
            val item = scrapersJson.optJSONObject(i) ?: return@mapNotNull null
            val id = item.optString("id")
            val filename = item.optString("filename")
            if (id.isBlank() || filename.isBlank()) return@mapNotNull null
            val supported = item.optJSONArray("supportedTypes") ?: JSONArray()
            val formats = item.optJSONArray("formats") ?: JSONArray()
            val providerCatalogsJson = item.optJSONArray("catalogs") ?: JSONArray()
            val providerCatalogs = (0 until providerCatalogsJson.length()).mapNotNull { catalogIndex ->
                val catalog = providerCatalogsJson.optJSONObject(catalogIndex) ?: return@mapNotNull null
                val catalogId = catalog.optString("id")
                val catalogType = catalog.optString("type").ifBlank { catalog.optString("apiType") }
                if (catalogId.isBlank() || catalogType.isBlank()) return@mapNotNull null
                val extraJson = catalog.optJSONArray("extra") ?: JSONArray()
                val extraNames = (0 until extraJson.length()).mapNotNull { extraIndex ->
                    extraJson.optJSONObject(extraIndex)?.optString("name")?.takeIf { it.isNotBlank() }
                }.toSet()
                ScraperCatalog(
                    type = catalogType,
                    id = catalogId,
                    name = catalog.optString("name").ifBlank { catalogId },
                    extras = extraNames
                )
            }
            ScraperProvider(
                id = id,
                name = item.optString("name").ifBlank { id },
                description = item.optString("description"),
                filename = filename,
                version = item.optString("version"),
                supportedTypes = (0 until supported.length()).map { supported.optString(it) }.filter { it.isNotBlank() },
                formats = (0 until formats.length()).map { formats.optString(it) }.filter { it.isNotBlank() },
                enabled = item.optBoolean("enabled", true),
                catalogs = providerCatalogs,
                author = item.optString("author"),
                logo = item.optString("logo"),
                contentLanguage = item.optJSONArray("contentLanguage")?.let { values ->
                    (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() }
                }.orEmpty(),
                limited = item.optBoolean("limited", false),
                disabledPlatforms = item.optJSONArray("disabledPlatforms")?.let { values ->
                    (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() }
                }.orEmpty()
            )
        }
        val format = if (json.has("scrapers")) "nuvio-js" else "stremio"
        val catalogs = (0 until catalogsJson.length()).mapNotNull { i ->
            val c = catalogsJson.optJSONObject(i) ?: return@mapNotNull null
            val type = c.optString("type").ifBlank { c.optString("apiType") }
            val id = c.optString("id")
            if (type.isBlank() || id.isBlank()) null
            else {
                val extrasJson = c.optJSONArray("extra") ?: JSONArray()
                val extras = (0 until extrasJson.length()).mapNotNull { index ->
                    extrasJson.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
                }.toSet()
                Catalog(type, id, c.optString("name").ifBlank { id }, extras)
            }
        }
        return Installed(
            baseUrl = base.removeSuffix("/manifest.json"),
            id = json.optString("id").ifBlank { base },
            name = json.optString("name").ifBlank { base },
            version = json.optString("version"),
            description = json.optString("description"),
            types = types,
            catalogs = catalogs,
            resources = resourceSet,
            format = format,
            scrapers = scrapers
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
    fun fetchCatalog(addon: Installed, catalog: Catalog, skip: Int = 0, search: String = ""): List<CatalogItem> {
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
        val query = ArrayList<String>()
        if (skip > 0) query.add("skip=${skip.coerceAtMost(10000)}")
        if (search.isNotBlank()) {
            require(search.length <= 120) { "Arama metni çok uzun." }
            query.add("search=" + java.net.URLEncoder.encode(search.trim(), "UTF-8").replace("+", "%20"))
        }
        val suffix = if (query.isEmpty()) "" else "?" + query.joinToString("&")
        val url = addon.baseUrl.trimEnd('/') + "/catalog/${catalog.type}/$encodedCatalogId.json" + suffix
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

    /** Download the provider JavaScript entry file from its repository. */
    fun fetchScraperSource(addon: Installed, provider: ScraperProvider): String {
        require(addon.format == "nuvio-js") { "Bu depo Nuvio JavaScript deposu değil." }
        require(provider.enabled) { "Bu sağlayıcı manifestte devre dışı." }
        val filename = provider.filename.trim()
        require(filename.length in 1..240 && !filename.startsWith("/") && !filename.contains("..") &&
            !filename.contains('\\') && filename.matches(Regex("^[A-Za-z0-9_./-]+\\.js$"))) {
            "Sağlayıcı dosya yolu geçersiz."
        }
        val source = getText(addon.baseUrl.trimEnd('/') + "/" + filename)
        require(source.isNotBlank() && source.length <= 256 * 1024) {
            "Sağlayıcı JavaScript dosyası boş veya 256 KB sınırını aşıyor."
        }
        return source
    }

    fun parseProviderStreams(json: String): List<ProviderStream> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
            if (uri == null || !uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || !uri.userInfo.isNullOrBlank()) return@mapNotNull null
            val headersJson = item.optJSONObject("headers") ?: JSONObject()
            val headers = buildMap {
                val names = headersJson.names() ?: JSONArray()
                for (i in 0 until names.length()) {
                    val key = names.optString(i)
                    val value = headersJson.optString(key)
                    if (key.length <= 80 && value.length <= 2048 && !value.contains("\\r") && !value.contains("\\n")) put(key, value)
                }
            }
            ProviderStream(
                name = item.optString("name").ifBlank { "Sağlayıcı" },
                title = item.optString("title").ifBlank { item.optString("name").ifBlank { "Oynat" } },
                url = url, quality = item.optString("quality"),
                format = item.optString("format").ifBlank { item.optString("type") },
                headers = headers
            )
        }.take(30)
    }

    fun parseProviderResult(json: String): ProviderResult {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return ProviderResult(parseProviderStreams(json), emptyList())
        val streams = parseProviderStreams(root.optJSONArray("streams")?.toString() ?: "[]")
        val subtitlesJson = root.optJSONArray("subtitles") ?: JSONArray()
        val subtitles = (0 until subtitlesJson.length()).mapNotNull { index ->
            val item = subtitlesJson.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url")
            val uri = runCatching { android.net.Uri.parse(url) }.getOrNull()
            if (uri == null || !uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || !uri.userInfo.isNullOrBlank()) return@mapNotNull null
            val headersJson = item.optJSONObject("headers") ?: JSONObject()
            val headers = buildMap {
                val names = headersJson.names() ?: JSONArray()
                for (i in 0 until names.length()) {
                    val key = names.optString(i)
                    val value = headersJson.optString(key)
                    if (key.length <= 80 && value.length <= 2048 && !value.contains("\\r") && !value.contains("\\n")) put(key, value)
                }
            }
            val format = item.optString("format").ifBlank {
                url.substringBefore('?').substringAfterLast('.', "").lowercase()
            }
            if (format !in setOf("srt", "vtt", "webvtt")) return@mapNotNull null
            ProviderSubtitle(
                name = item.optString("name").ifBlank { item.optString("label").ifBlank { "Altyazı" } },
                language = item.optString("language").ifBlank { item.optString("lang") },
                url = url,
                format = format,
                headers = headers
            )
        }.take(20)
        return ProviderResult(streams, subtitles)
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
