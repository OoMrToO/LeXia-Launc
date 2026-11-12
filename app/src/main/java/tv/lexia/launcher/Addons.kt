package tv.lexia.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
        require(url.startsWith("https://")) { "Güvenlik için yalnızca HTTPS adresleri kabul edilir." }
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
        val resources = JSONArray(json.optString("resources", "[]").let {
            if (it.startsWith("[")) it else "[]"
        })
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

    /** Loads a single standard meta resource; it never requests stream/source resources. */
    fun fetchMeta(addon: Installed, type: String, id: String): CatalogItem {
        require("meta" in addon.resources || addon.resources.isEmpty()) {
            "Bu eklenti metadata kaynağı sunmuyor."
        }
        require(type.matches(Regex("^[A-Za-z0-9_-]{1,32}$"))) { "İçerik türü geçersiz." }
        require(id.isNotBlank() && id.length <= 256 && !id.contains('/')) { "İçerik kimliği geçersiz." }
        val encodedId = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")
        val address = addon.baseUrl.trimEnd('/') + "/meta/$type/$encodedId.json"
        val root = JSONObject(getText(address))
        val m = root.optJSONObject("meta") ?: root
        val genres = m.optJSONArray("genres")?.let { a ->
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.take(12)
        }.orEmpty()
        return CatalogItem(
            id = m.optString("id", id),
            type = m.optString("type", type),
            name = m.optString("name"),
            poster = m.optString("poster"),
            background = m.optString("background"),
            description = m.optString("description"),
            releaseInfo = m.optString("releaseInfo"),
            imdbRating = m.optDouble("imdbRating", 0.0),
            genres = genres,
            runtime = m.optString("runtime").filter { it.isDigit() }.toIntOrNull() ?: 0,
            seasons = m.optInt("seasonCount", 0)
        )
    }

    /** Fetches only catalogue metadata. Addon stream/source resources are never requested. */
    fun fetchCatalog(addon: Installed, catalog: Catalog, skip: Int = 0): List<CatalogItem> {
        require("catalog" in addon.resources || addon.resources.isEmpty()) {
            "Bu eklenti katalog kaynağı sunmuyor."
        }
        val url = addon.baseUrl.trimEnd('/') +
            "/catalog/${catalog.type}/${catalog.id}.json" +
            if (skip > 0) "?skip=$skip" else ""
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
                releaseInfo = m.optString("releaseInfo")
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
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            connection.setRequestProperty("Accept", "application/json")
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            require(code in 200..299) { "Sunucu HTTP $code yanıtı verdi." }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
