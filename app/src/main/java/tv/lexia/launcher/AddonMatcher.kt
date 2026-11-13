package tv.lexia.launcher

import android.content.Context
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Matches the rotating home-screen titles against addon catalogue metadata only.
 * It never calls addon stream/source resources.
 */
object AddonMatcher {
    data class Match(
        val repository: String,
        val addonName: String,
        val catalogType: String,
        val catalogName: String,
        val itemId: String,
        val itemType: String
    )

    private const val PREFS = "lexia_addon_matches"
    private const val DATA = "matches"
    private const val SIGNATURE = "signature"

    fun enabled(context: Context): Boolean = Prefs(context).addonMatchMode != "off"

    fun needsRefresh(context: Context, picks: List<Pick>): Boolean {
        val prefs = Prefs(context)
        val repositories = when (prefs.addonMatchMode) {
            "all" -> AddonLibrary.enabledRepositories(context)
            "selected" -> {
                val urls = AddonLibrary.enabledRepositories(context)
                listOf(prefs.addonMatchRepository.takeIf { it in urls } ?: urls.firstOrNull().orEmpty())
                    .filter { it.isNotBlank() }
            }
            else -> emptyList()
        }
        val signature = prefs.addonMatchMode + "|" + repositories.joinToString("|") + "#" + AddonLibrary.configurationSignature(context) + "#" +
            picks.map { it.key.ifBlank { it.title } }.sorted().joinToString("|")
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SIGNATURE, "") != signature
    }

    fun find(context: Context, pick: Pick): Match? {
        return try {
            val root = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(DATA, "{}") ?: "{}")
            val key = pick.key.ifBlank { pick.title }
            val item = root.optJSONObject(key) ?: return null
            Match(
                repository = item.optString("repository"),
                addonName = item.optString("addonName"),
                catalogType = item.optString("catalogType"),
                catalogName = item.optString("catalogName"),
                itemId = item.optString("itemId"),
                itemType = item.optString("itemType")
            ).takeIf { it.repository.isNotBlank() && it.itemId.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** Call from a worker thread. Replaces the cache for the supplied current home-screen titles. */
    fun refresh(context: Context, picks: List<Pick>) {
        val prefs = Prefs(context)
        val repositories = when (prefs.addonMatchMode) {
            "all" -> AddonLibrary.enabledRepositories(context)
            "selected" -> {
                val urls = AddonLibrary.enabledRepositories(context)
                listOf(prefs.addonMatchRepository.takeIf { it in urls } ?: urls.firstOrNull().orEmpty())
                    .filter { it.isNotBlank() }
            }
            else -> emptyList()
        }
        val signature = prefs.addonMatchMode + "|" + repositories.joinToString("|") + "#" + AddonLibrary.configurationSignature(context) + "#" +
            picks.map { it.key.ifBlank { it.title } }.sorted().joinToString("|")
        if (repositories.isEmpty()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(DATA, "{}").putString(SIGNATURE, signature).apply()
            return
        }

        val wanted = picks.filter { it.title.isNotBlank() }.distinctBy { it.key.ifBlank { it.title } }
        if (wanted.isEmpty()) return
        val found = LinkedHashMap<String, JSONObject>()

        for (repository in repositories) {
            val addon = runCatching { Addons.fetchManifest(repository) }.getOrNull() ?: continue
            if ("meta" !in addon.resources && addon.resources.isNotEmpty()) continue
            for (catalog in AddonLibrary.orderedCatalogs(context, repository, addon.catalogs).filter {
                AddonLibrary.isCatalogEnabled(context, repository, it)
            }.take(8)) {
                val expectedSeries = catalog.type.equals("series", true) ||
                    catalog.type.equals("tv", true)
                val expectedMovie = catalog.type.equals("movie", true)
                if (!expectedSeries && !expectedMovie) continue
                val candidates = wanted.filter { it.isSeries == expectedSeries }
                if (candidates.isEmpty()) continue
                val candidateNames = candidates.groupBy { normalize(it.title) }

                fun recordMatches(items: List<Addons.CatalogItem>) {
                    for (item in items) {
                        val normalized = normalize(item.name)
                        val pick = candidateNames[normalized]
                            ?.firstOrNull { sameYear(it.year, item.releaseInfo) } ?: continue
                        val key = pick.key.ifBlank { pick.title }
                        if (key !in found) {
                            found[key] = JSONObject()
                                .put("repository", repository)
                                .put("addonName", addon.name)
                                .put("catalogType", catalog.type)
                                .put("catalogName", catalog.name)
                                .put("itemId", item.id)
                                .put("itemType", item.type.ifBlank { catalog.type })
                        }
                    }
                }

                if ("search" in catalog.extras) {
                    // Use the addon search endpoint only when its manifest declares this extra.
                    for (pick in candidates) {
                        val results = runCatching {
                            Addons.fetchCatalog(addon, catalog, search = pick.title)
                        }.getOrNull().orEmpty()
                        recordMatches(results)
                    }
                } else {
                    // Backward-compatible bounded scan for catalogues without search support.
                    var skip = 0
                    var pageNumber = 0
                    while (pageNumber < 3) {
                        val page = runCatching { Addons.fetchCatalog(addon, catalog, skip) }.getOrNull() ?: break
                        recordMatches(page)
                        if (page.size < 100) break
                        skip += page.size
                        pageNumber++
                    }
                }
            }
        }

        val json = JSONObject()
        found.forEach { (key, value) -> json.put(key, value) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(DATA, json.toString()).putString(SIGNATURE, signature).apply()
    }

    private fun normalize(value: String): String {
        val plain = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase(Locale.ROOT)
            .replace('ı', 'i')
        return plain.replace("[^a-z0-9]".toRegex(), "")
    }

    private fun sameYear(pickYear: String, releaseInfo: String): Boolean {
        val a = Regex("(19|20)\\d{2}").find(pickYear)?.value
        val b = Regex("(19|20)\\d{2}").find(releaseInfo)?.value
        return a == null || b == null || a == b
    }
}
