package tv.lexia.launcher

import org.json.JSONArray
import org.json.JSONObject

enum class AddonType {
    MOVIE,
    SERIES,
    LIVE,
    SEARCH,
    UNKNOWN
}

fun String.toAddonType(): AddonType = when (lowercase()) {
    "movie", "movies", "film", "films" -> AddonType.MOVIE
    "series", "tv", "show", "shows" -> AddonType.SERIES
    "live", "channel", "channels" -> AddonType.LIVE
    "search" -> AddonType.SEARCH
    else -> AddonType.UNKNOWN
}

data class AddonCatalog(
    val id: String,
    val name: String,
    val type: AddonType,
    val endpoint: String,
    val isSearchOnly: Boolean = false,
    val isHomeVisible: Boolean = false,
    val requiresQuery: Boolean = false
) {
    companion object {
        fun fromJson(obj: JSONObject): AddonCatalog {
            val typeValue = obj.optString("type", obj.optString("kind", "movie")).trim()
            return AddonCatalog(
                id = obj.optString("id", obj.optString("name", "catalog")).trim().ifBlank { "catalog" },
                name = obj.optString("name", obj.optString("title", "Catalog")).trim().ifBlank { "Catalog" },
                type = typeValue.toAddonType(),
                endpoint = obj.optString("endpoint", obj.optString("path", obj.optString("url", "/catalog"))).trim().ifBlank { "/catalog" },
                isSearchOnly = obj.optBoolean("searchOnly", obj.optBoolean("search_only", false)) || (
                    obj.optString("search", "").equals("true", true)
                ),
                isHomeVisible = obj.optBoolean("showInHome", obj.optBoolean("home", false)),
                requiresQuery = obj.optBoolean("requiresQuery", obj.optBoolean("search", false))
            )
        }
    }
}

data class AddonManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String?,
    val logo: String?,
    val background: String?,
    val baseUrl: String,
    val catalogs: List<AddonCatalog>,
    val enabled: Boolean = true,
    val configuredAt: Long = System.currentTimeMillis()
) {
    val normalizedId: String get() = id.trim().ifBlank { "addon" }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("description", description ?: "")
        put("logo", logo ?: "")
        put("background", background ?: "")
        put("baseUrl", baseUrl)
        put("enabled", enabled)
        put("configuredAt", configuredAt)
        put("catalogs", JSONArray().apply {
            catalogs.forEach { catalog ->
                put(catalog.toJson())
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): AddonManifest {
            val baseUrl = json.optString("baseUrl", json.optString("base_url", "")).trim()
            val catalogsArr = json.optJSONArray("catalogs") ?: JSONArray()
            val catalogs = ArrayList<AddonCatalog>()
            for (i in 0 until catalogsArr.length()) {
                val entry = catalogsArr.optJSONObject(i) ?: continue
                catalogs += AddonCatalog.fromJson(entry)
            }

            return AddonManifest(
                id = json.optString("id", json.optString("name", "addon")).trim().ifBlank { "addon" },
                name = json.optString("name", json.optString("title", "Addon")).trim().ifBlank { "Addon" },
                version = json.optString("version", "1.0").trim().ifBlank { "1.0" },
                description = json.optString("description", "").takeIf { it.isNotBlank() },
                logo = json.optString("logo", "").takeIf { it.isNotBlank() },
                background = json.optString("background", "").takeIf { it.isNotBlank() },
                baseUrl = baseUrl,
                catalogs = catalogs,
                enabled = json.optBoolean("enabled", true),
                configuredAt = json.optLong("configuredAt", System.currentTimeMillis())
            )
        }

        fun fromManifestUrl(manifestUrl: String, rawJson: String): AddonManifest {
            val json = JSONObject(rawJson)
            val baseUrl = manifestUrl.substringBeforeLast('/')
            return fromJson(json).copy(baseUrl = baseUrl)
        }
    }
}

private fun AddonCatalog.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("type", when (type) {
        AddonType.MOVIE -> "movie"
        AddonType.SERIES -> "series"
        AddonType.LIVE -> "live"
        AddonType.SEARCH -> "search"
        AddonType.UNKNOWN -> "unknown"
    })
    put("endpoint", endpoint)
    put("searchOnly", isSearchOnly)
    put("showInHome", isHomeVisible)
    put("requiresQuery", requiresQuery)
}
