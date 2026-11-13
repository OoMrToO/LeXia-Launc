package tv.lexia.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent repository, catalogue and collection settings backed by LeXia's SharedPreferences. */
object AddonLibrary {
    data class Collection(val id: String, val name: String, val catalogKeys: List<String>)
    private const val PREFS = "lexia_addons"
    private const val REPOSITORY_ORDER = "repository_order"
    private const val DISABLED_REPOSITORIES = "disabled_repositories"
    private const val CATALOG_ORDER = "catalog_order:"
    private const val DISABLED_CATALOGS = "disabled_catalogs"
    private const val COLLECTIONS = "user_collections"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun repositories(context: Context): List<String> {
        val installed = Addons.urls(context)
        val saved = prefs(context).getString(REPOSITORY_ORDER, "").orEmpty().split("\n")
            .filter { it in installed }.distinct().toMutableList()
        installed.filterNot { it in saved }.forEach { saved.add(it) }
        return saved
    }
    fun enabledRepositories(context: Context): List<String> {
        val disabled = prefs(context).getStringSet(DISABLED_REPOSITORIES, emptySet()).orEmpty()
        return repositories(context).filterNot { it in disabled }
    }
    fun isRepositoryEnabled(context: Context, repository: String): Boolean =
        repository !in prefs(context).getStringSet(DISABLED_REPOSITORIES, emptySet()).orEmpty()
    fun setRepositoryEnabled(context: Context, repository: String, enabled: Boolean) {
        val disabled = prefs(context).getStringSet(DISABLED_REPOSITORIES, emptySet()).orEmpty().toMutableSet()
        if (enabled) disabled.remove(repository) else disabled.add(repository)
        prefs(context).edit().putStringSet(DISABLED_REPOSITORIES, disabled).apply()
    }
    fun moveRepository(context: Context, repository: String, direction: Int) {
        val order = repositories(context).toMutableList()
        val from = order.indexOf(repository)
        val to = from + direction
        if (from < 0 || to !in order.indices) return
        val item = order.removeAt(from)
        order.add(to, item)
        prefs(context).edit().putString(REPOSITORY_ORDER, order.joinToString("\n")).apply()
    }
    fun catalogKey(repository: String, catalog: Addons.Catalog): String =
        repository.trimEnd('/') + "|" + catalog.type + "|" + catalog.id
    fun orderedCatalogs(context: Context, repository: String, catalogs: List<Addons.Catalog>): List<Addons.Catalog> {
        val saved = prefs(context).getString(CATALOG_ORDER + repository, "").orEmpty().split("\n").filter { it.isNotBlank() }
        val byKey = catalogs.associateBy { catalogKey(repository, it) }
        val ordered = saved.mapNotNull { byKey[it] }.toMutableList()
        catalogs.filterNot { it in ordered }.forEach { ordered.add(it) }
        return ordered
    }
    fun isCatalogEnabled(context: Context, repository: String, catalog: Addons.Catalog): Boolean =
        catalogKey(repository, catalog) !in prefs(context).getStringSet(DISABLED_CATALOGS, emptySet()).orEmpty()
    fun setCatalogEnabled(context: Context, repository: String, catalog: Addons.Catalog, enabled: Boolean) {
        val disabled = prefs(context).getStringSet(DISABLED_CATALOGS, emptySet()).orEmpty().toMutableSet()
        val key = catalogKey(repository, catalog)
        if (enabled) disabled.remove(key) else disabled.add(key)
        prefs(context).edit().putStringSet(DISABLED_CATALOGS, disabled).apply()
    }
    fun moveCatalog(context: Context, repository: String, catalogs: List<Addons.Catalog>, catalog: Addons.Catalog, direction: Int) {
        val order = orderedCatalogs(context, repository, catalogs).toMutableList()
        val from = order.indexOfFirst { catalogKey(repository, it) == catalogKey(repository, catalog) }
        val to = from + direction
        if (from < 0 || to !in order.indices) return
        val item = order.removeAt(from)
        order.add(to, item)
        prefs(context).edit().putString(CATALOG_ORDER + repository,
            order.joinToString("\n") { catalogKey(repository, it) }).apply()
    }
    fun configurationSignature(context: Context): String {
        val relevant = prefs(context).all.filterKeys {
            it == REPOSITORY_ORDER || it == DISABLED_REPOSITORIES || it == DISABLED_CATALOGS || it.startsWith(CATALOG_ORDER)
        }.toSortedMap()
        return relevant.entries.joinToString("|") { (key, value) ->
            val stable = if (value is Set<*>) value.map { it.toString() }.sorted().joinToString(",") else value.toString()
            "$key=$stable"
        }
    }
    fun collections(context: Context): List<Collection> = runCatching {
        val array = JSONArray(prefs(context).getString(COLLECTIONS, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id")
            val name = item.optString("name")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            val keys = item.optJSONArray("catalogs") ?: JSONArray()
            Collection(id, name, (0 until keys.length()).map { keys.optString(it) }.filter { it.isNotBlank() }.distinct())
        }
    }.getOrDefault(emptyList())
    fun createCollection(context: Context, name: String): Collection {
        val clean = name.trim().take(48)
        require(clean.isNotBlank()) { "Koleksiyon adı boş olamaz." }
        require(collections(context).none { it.name.equals(clean, true) }) { "Bu isimde bir koleksiyon zaten var." }
        val item = Collection(java.util.UUID.randomUUID().toString(), clean, emptyList())
        saveCollections(context, collections(context) + item)
        return item
    }
    fun renameCollection(context: Context, id: String, name: String) {
        val clean = name.trim().take(48)
        require(clean.isNotBlank()) { "Koleksiyon adı boş olamaz." }
        require(collections(context).none { it.id != id && it.name.equals(clean, true) }) { "Bu isimde bir koleksiyon zaten var." }
        saveCollections(context, collections(context).map { if (it.id == id) it.copy(name = clean) else it })
    }
    fun deleteCollection(context: Context, id: String) {
        saveCollections(context, collections(context).filterNot { it.id == id })
    }
    fun addCatalogToCollection(context: Context, id: String, key: String) {
        saveCollections(context, collections(context).map {
            if (it.id == id && key !in it.catalogKeys) it.copy(catalogKeys = it.catalogKeys + key) else it
        })
    }
    fun removeCatalogFromCollection(context: Context, id: String, key: String) {
        saveCollections(context, collections(context).map {
            if (it.id == id) it.copy(catalogKeys = it.catalogKeys - key) else it
        })
    }
    private fun saveCollections(context: Context, values: List<Collection>) {
        val array = JSONArray()
        values.forEach { item ->
            array.put(JSONObject().put("id", item.id).put("name", item.name).put("catalogs", JSONArray(item.catalogKeys)))
        }
        check(prefs(context).edit().putString(COLLECTIONS, array.toString()).commit()) { "Koleksiyon değişiklikleri kaydedilemedi." }
    }
}
