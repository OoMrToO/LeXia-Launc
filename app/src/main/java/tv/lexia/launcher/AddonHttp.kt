package tv.lexia.launcher

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Small bounded HTTP client for user-configured addon manifests and catalog responses. */
internal object AddonHttp {
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun get(address: String): String {
        val uri = Uri.parse(address.trim())
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) { "Yalnızca HTTP(S) addon adresleri desteklenir." }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null) { "Addon adresi geçersiz." }
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "LeXia/Media")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            if (code in 300..399) throw IllegalArgumentException("Addon yönlendirme yaptı; doğrudan manifest adresini kullanın.")
            if (code !in 200..299) throw IllegalStateException("Sunucu HTTP $code yanıtı verdi.")
            val length = connection.contentLengthLong
            require(length <= MAX_BYTES) { "Yanıt boyutu sınırı aşıyor." }
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "Yanıt boyutu sınırı aşıyor." }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    fun catalogUrl(addon: AddonManifest, catalog: AddonCatalog, search: String = ""): String {
        val base = addon.baseUrl.trimEnd('/')
        val type = when (catalog.type) {
            AddonType.SERIES -> "series"
            AddonType.LIVE -> "channel"
            else -> "movie"
        }
        val path = catalog.endpoint.trim('/').takeIf { it.isNotBlank() && it != "catalog" }
            ?: "catalog/$type/${Uri.encode(catalog.id)}.json"
        val url = if (path.startsWith("http://") || path.startsWith("https://")) path else "$base/$path"
        return if (search.isBlank()) url else Uri.parse(url).buildUpon().appendQueryParameter("search", search).build().toString()
    }

    fun streamUrl(addon: AddonManifest, item: MediaCatalogItem): String {
        val type = if (item.isSeries) "series" else "movie"
        return "${addon.baseUrl.trimEnd('/')}/stream/$type/${Uri.encode(item.id)}.json"
    }

    fun streams(raw: String): List<Pair<String, String>> {
        val json = JSONObject(raw)
        val array = json.optJSONArray("streams") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val stream = array.optJSONObject(index) ?: return@mapNotNull null
            val url = stream.optString("url", stream.optString("externalUrl", "")).trim()
            val uri = Uri.parse(url)
            if (url.isBlank() || uri.scheme?.lowercase() !in setOf("http", "https", "content", "file")) return@mapNotNull null
            val label = stream.optString("name", stream.optString("title", "Kaynak ${index + 1}"))
            label to url
        }
    }
}
