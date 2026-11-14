package tv.lexia.launcher

import android.content.Context
import org.json.JSONArray

class AddonStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("lexia_addons", Context.MODE_PRIVATE)

    fun installed(): List<AddonManifest> {
        val raw = prefs.getString("manifests", "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<AddonManifest>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                result += AddonManifest.fromJson(obj)
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Fetching is deliberately done by the caller off the UI thread. */
    fun addFromUrl(manifestUrl: String): AddonManifest {
        require(isValidManifestUrl(manifestUrl)) { "Addon adresi geçersiz." }
        val manifest = AddonManifest.fromManifestUrl(manifestUrl, AddonHttp.get(manifestUrl))
        require(manifest.catalogs.isNotEmpty()) { "Manifest katalog içermiyor." }
        save(manifest)
        return manifest
    }

    fun save(manifest: AddonManifest) {
        val current = installed().filterNot { it.normalizedId == manifest.normalizedId || it.baseUrl == manifest.baseUrl }
        val next = (current + manifest).sortedBy { it.name.lowercase() }
        val arr = JSONArray()
        next.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("manifests", arr.toString()).apply()
    }

    fun remove(id: String) {
        val filtered = installed().filterNot { it.normalizedId == id }
        val arr = JSONArray()
        filtered.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("manifests", arr.toString()).apply()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val updated = installed().map { if (it.normalizedId == id) it.copy(enabled = enabled) else it }
        val arr = JSONArray()
        updated.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("manifests", arr.toString()).apply()
    }

    fun enabledAddons(): List<AddonManifest> = installed().filter { it.enabled }
    fun findById(id: String): AddonManifest? = installed().firstOrNull { it.normalizedId == id }
    fun catalogFor(manifestId: String, catalogId: String): AddonCatalog? = findById(manifestId)?.catalogs?.firstOrNull { it.id == catalogId }

    fun isValidManifestUrl(url: String): Boolean {
        val value = url.trim()
        return value.startsWith("http://", true) || value.startsWith("https://", true)
    }
}

fun AddonManifest.catalogsFor(type: AddonType): List<AddonCatalog> = catalogs.filter { it.type == type }
fun AddonManifest.catalogsForHome(): List<AddonCatalog> = catalogs.filter { it.isHomeVisible }
fun AddonManifest.searchCatalogs(): List<AddonCatalog> = catalogs.filter { it.type == AddonType.SEARCH || it.isSearchOnly }
