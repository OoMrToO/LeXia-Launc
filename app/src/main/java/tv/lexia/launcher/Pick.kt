package tv.lexia.launcher

import org.json.JSONArray
import org.json.JSONObject

class CastMember(val name: String, val role: String, val photo: String)

/** One of a film's trailers: the YouTube key and what it is ("Türkçe dublaj fragman", "Orijinal fragman"). */
class Video(val key: String, val label: String)

/** One film from pick.json, also stored in the watchlist. */
class Pick(
    val title: String,
    val meta: String,
    val overview: String,
    val backdrop: String,
    val reason: String,
    val trailer: String,
    val logo: String,
    val imdb: Double,
    val year: String,
    val genres: List<String>,
    val runtime: Int,
    val poster: String = "",
    val tagline: String = "",
    val director: String = "",
    val cast: List<CastMember> = emptyList(),
    val similar: List<Pick> = emptyList(),
    /** "movie" or "tv". */
    val kind: String = "movie",
    val seasons: Int = 0,
    /** Turkish cinema release date, yyyy-MM-dd, if known. */
    val release: String = "",
    /** TMDB id, 0 for entries saved by older versions. */
    val id: Int = 0,
    /** Age band: "7+", "13+", "18+", "Genel" or empty. */
    val cert: String = "",
    /** For "Sinema tarihinde bugün": how many years ago it came out today; 0 otherwise. */
    val years: Int = 0,
    /** For "Platformlarda yeni": the streaming platform (Netflix, Disney Plus...); empty otherwise. */
    val provider: String = "",
    /** Where it streams in Turkey with a subscription ("Netflix", "Prime Video"); empty if unknown. */
    val where: List<String> = emptyList(),
    /** Turkish digital release date (yyyy-MM-dd) for "Dijitalde" in the calendar; empty otherwise. */
    val digital: String = "",
    /** Every trailer the film has (dubbed, subtitled, original, teaser), the main one first; may be empty. */
    val trailers: List<Video> = emptyList(),
    /** Where it is free to watch in Turkey (free or with ads: tabii, puhutv...); empty if nowhere known. */
    val free: List<String> = emptyList(),
    /** IMDb identifier for Stremio-compatible media add-ons; absent on older feed records. */
    val imdbId: String = "",
    /** Original-language title, when the feed contains it. */
    val originalTitle: String = ""
) {
    val isSeries: Boolean get() = kind == "tv"

    val key: String get() = backdrop.ifEmpty { title }

    /** "2026 · 2 sa 25 dk · Dram, Aksiyon", or the stored line for older data. */
    fun details(maxGenres: Int = 3): String {
        val parts = ArrayList<String>()
        if (year.isNotEmpty()) parts.add(year)
        if (runtime > 0) parts.add(if (runtime >= 60) "${runtime / 60} sa ${runtime % 60} dk" else "$runtime dk")
        if (seasons > 0) parts.add("$seasons sezon")
        if (genres.isNotEmpty()) parts.add(genres.take(maxGenres).joinToString(", "))
        return if (parts.isNotEmpty()) parts.joinToString("   ·   ") else meta.replace("  ·  ", "   ·   ")
    }

    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("meta", meta).put("overview", overview)
        .put("backdrop", backdrop).put("reason", reason).put("trailer", trailer)
        .put("logo", logo).put("imdb", imdb).put("year", year)
        .put("genres", JSONArray(genres)).put("runtime", runtime)
        .put("poster", poster).put("tagline", tagline).put("director", director)
        .put("cast", JSONArray(cast.map { JSONObject().put("name", it.name).put("role", it.role).put("photo", it.photo) }))
        .put("similar", JSONArray(similar.map { it.toJson() }))
        .put("type", kind).put("seasons", seasons).put("release", release)
        .put("id", id).put("cert", cert).put("years", years).put("provider", provider)
        .put("where", JSONArray(where)).put("digital", digital)
        .put("trailers", JSONArray(trailers.map { JSONObject().put("k", it.key).put("n", it.label) }))
        .put("free", JSONArray(free)).put("imdb_id", imdbId)
        .put("original_title", originalTitle)

    companion object {
        private val trailerKey = Regex("^[A-Za-z0-9_-]{6,20}$")
        private val certText = Regex("^(Genel|\\d{1,2}\\+)$")
        private val providerName = Regex("^[\\p{L}0-9 +.&'-]{2,40}$")
        private val videoLabel = Regex("^[\\p{L}0-9 ·()+.,'-]{2,40}$")
        private val imdbIdPattern = Regex("^tt[0-9]{7,10}$")
        private val imagePath = Regex("^/[A-Za-z0-9_-]+\\.(jpg|jpeg|png)$")

        /** Only accept values that fit the YouTube and TMDB URLs they are pasted into. */
        private fun safe(v: String, re: Regex) = if (re.matches(v)) v else ""

        private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }

        fun from(x: JSONObject, depth: Int = 0): Pick {
            val c = x.optJSONArray("cast")
            val s = x.optJSONArray("similar")
            return Pick(
                x.optString("title"), x.optString("meta"), x.optString("overview"),
                safe(x.optString("backdrop"), imagePath), x.optString("reason"),
                safe(x.optString("trailer"), trailerKey),
                safe(x.optString("logo"), imagePath), x.optDouble("imdb", 0.0), x.optString("year"),
                strings(x.optJSONArray("genres")), x.optInt("runtime", 0),
                safe(x.optString("poster"), imagePath), x.optString("tagline"), x.optString("director"),
                (0 until (c?.length() ?: 0)).map {
                    val o = c!!.getJSONObject(it)
                    CastMember(o.optString("name"), o.optString("role"), safe(o.optString("photo"), imagePath))
                }.filter { it.name.isNotEmpty() },
                // Similar films go one level deep only.
                if (depth > 0) emptyList() else (0 until (s?.length() ?: 0)).map { from(s!!.getJSONObject(it), depth + 1) },
                if (x.optString("type") == "tv") "tv" else "movie",
                x.optInt("seasons", 0),
                x.optString("release").takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) } ?: "",
                x.optInt("id", 0),
                safe(x.optString("cert"), certText),
                x.optInt("years", 0).coerceIn(0, 150),
                x.optString("provider").take(40).takeIf { providerName.matches(it) } ?: "",
                strings(x.optJSONArray("where")).map { it.take(40) }.filter { providerName.matches(it) }.take(4),
                x.optString("digital").takeIf { Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) } ?: "",
                x.optJSONArray("trailers").let { v ->
                    (0 until (v?.length() ?: 0)).mapNotNull {
                        val o = v!!.optJSONObject(it) ?: return@mapNotNull null
                        val k = safe(o.optString("k"), trailerKey)
                        val n = o.optString("n").take(40)
                        if (k.isEmpty() || !videoLabel.matches(n)) null else Video(k, n)
                    }.distinctBy { it.key }.take(5)
                },
                strings(x.optJSONArray("free")).map { it.take(40) }.filter { providerName.matches(it) }.take(3),
                safe(x.optString("imdb_id"), imdbIdPattern),
                x.optString("original_title")
            )
        }
    }
}
