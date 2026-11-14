package tv.lexia.launcher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal catalog item model for movie/series list results coming from addon URL responses.
 * This is intentionally lightweight so LeXia can adopt addon data without a full refactor yet.
 */
data class MediaCatalogItem(
    val id: String,
    val title: String,
    val type: AddonType,
    val poster: String = "",
    val backdrop: String = "",
    val overview: String = "",
    val year: String = "",
    val metadata: Map<String, String> = emptyMap(),
    val streamUrl: String? = null,
    val trailerUrl: String? = null
) {
    val isSeries: Boolean get() = type == AddonType.SERIES

    companion object {
        fun fromJson(obj: JSONObject, fallbackType: AddonType = AddonType.MOVIE): MediaCatalogItem {
            val itemType = obj.optString("type", "").toAddonType().takeIf { it != AddonType.UNKNOWN } ?: fallbackType
            val idValue = obj.optString("id", obj.optString("slug", obj.optString("key", "${System.currentTimeMillis()}")))
            val titleValue = obj.optString("title", obj.optString("name", "Untitled"))
            val metadata = LinkedHashMap<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.opt(key)
                if (value is String && value.isNotBlank()) metadata[key] = value
            }
            return MediaCatalogItem(
                id = idValue,
                title = titleValue,
                type = itemType,
                poster = obj.optString("poster", obj.optString("image", "")),
                backdrop = obj.optString("backdrop", obj.optString("background", "")),
                overview = obj.optString("overview", obj.optString("description", "")),
                year = obj.optString("year", obj.optString("release", "")),
                metadata = metadata,
                streamUrl = obj.optString("streamUrl", obj.optString("stream_url", "")).takeIf { it.isNotBlank() },
                trailerUrl = obj.optString("trailerUrl", obj.optString("trailer_url", "")).takeIf { it.isNotBlank() }
            )
        }

        fun parseList(raw: String, fallbackType: AddonType = AddonType.MOVIE): List<MediaCatalogItem> {
            if (raw.isBlank()) return emptyList()
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("items") ?: obj.optJSONArray("results") ?: obj.optJSONArray("movies")
                ?: obj.optJSONArray("series") ?: JSONArray()
            val result = ArrayList<MediaCatalogItem>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                result += fromJson(item, fallbackType)
            }
            return result
        }
    }
}

fun MediaCatalogItem.toPick(): Pick = Pick(
    title = title,
    overview = overview,
    backdrop = backdrop,
    poster = poster,
    type = if (isSeries) "tv" else "movie",
    id = id.hashCode(),
    provider = "addon",
    where = listOf("Addon"),
    trailer = trailerUrl.orEmpty(),
    release = year,
    isSeries = isSeries
)
