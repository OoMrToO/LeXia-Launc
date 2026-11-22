package tv.lexia.launcher

import android.content.Context
import android.util.Log
import com.nuvio.tv.LeXiaNuvio
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.data.local.PluginDataStore
import com.nuvio.tv.domain.model.LocalScraperResult
import com.nuvio.tv.domain.model.ScraperInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

internal data class NuvioScraperInfo(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val filename: String,
    val supportedTypes: List<String>,
    val enabled: Boolean,
    val hasSettings: Boolean,
    val limited: Boolean,
    val logo: String,
    val contentLanguage: List<String>,
    val formats: List<String>,
    val supportedPlatforms: List<String>,
    val disabledPlatforms: List<String>,
    val supportsExternalPlayer: Boolean
)

internal data class NuvioRepositoryInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val manifestUrl: String,
    val scrapers: List<NuvioScraperInfo>,
    val enabled: Boolean = true
)

/**
 * LeXia's door to Nuvio's own plugin system. Everything here is done by NuvioTV's files, copied
 * byte for byte under app/src/main/java/com/nuvio/tv (PluginManager, PluginRuntime,
 * PluginDataStore, the plugin models): adding, refreshing and removing repositories, turning
 * scrapers on and off, their settings, running them. This class only builds those objects once
 * and turns their answers into what LeXia's screens and player read.
 */
internal class NuvioJsRepository(context: Context) {

    private val appContext = context.applicationContext

    /** What the last source search ran into, for telling the viewer why nothing came. */
    class SearchNotes {
        /** Scrapers that were run. */
        val asked = AtomicInteger()
        /** Scrapers that gave at least one result. */
        val answered = AtomicInteger()
        /** Results that were not a video address (a magnet or torrent link, say). */
        val unplayable = AtomicInteger()
        /** Why scrapers failed, as Nuvio's own code logged it (read back from this app's log). */
        @Volatile var errors: List<String> = emptyList()
    }

    @Volatile var notes = SearchNotes()
        private set

    private val manager: PluginManager get() = nuvio(appContext)

    fun installedRepositories(): List<NuvioRepositoryInfo> = runBlocking {
        startMigration(appContext)
        // While repositories saved by older LeXia builds are being added to Nuvio's store, they
        // are shown as they were saved.
        if (migration?.isAlive == true) return@runBlocking oldRepositories(appContext)
        val repositories = manager.repositories.first()
        val scrapers = manager.scrapers.first()
        repositories.map { repo ->
            NuvioRepositoryInfo(
                id = repo.id,
                name = repo.name,
                version = "",
                description = repo.description.orEmpty(),
                manifestUrl = repo.url,
                scrapers = scrapers.filter { it.repositoryId == repo.id }.map(::info),
                enabled = repo.enabled
            )
        }
    }

    /** Nuvio's "Enable plugin providers globally" switch (PluginManager.pluginsEnabled). */
    fun pluginsEnabled(): Boolean = runBlocking { manager.pluginsEnabled.first() }

    fun setPluginsEnabled(enabled: Boolean) = runBlocking { manager.setPluginsEnabled(enabled) }

    /** Nuvio asks before switching on a "VideoEasy" scraper (PluginSafety.isVideoEasyScraper). */
    fun needsConfirmation(scraper: NuvioScraperInfo): Boolean =
        LeXiaNuvio.isVideoEasyScraper(scraper.id, scraper.name, scraper.filename)

    fun installedScraperCount(): Int = runBlocking { manager.scrapers.first().size }

    fun addRepository(address: String): NuvioRepositoryInfo {
        val repo = runBlocking { manager.addRepository(address) }.getOrThrow()
        return installedRepositories().firstOrNull { it.id == repo.id }
            ?: throw IllegalStateException("Depo eklenemedi.")
    }

    fun refreshRepository(repositoryId: String): NuvioRepositoryInfo {
        runBlocking { manager.refreshRepository(repositoryId) }.getOrThrow()
        return installedRepositories().firstOrNull { it.id == repositoryId }
            ?: throw IllegalStateException("Depo bulunamadı.")
    }

    fun removeRepository(repositoryId: String) = runBlocking { manager.removeRepository(repositoryId) }

    /** Scraper ids are Nuvio's own ("<repository id>:<scraper id>"). */
    fun setScraperEnabled(repositoryId: String, scraperId: String, enabled: Boolean) =
        runBlocking { manager.toggleScraper(scraperId, enabled) }

    fun setAllScrapersEnabled(repositoryId: String, enabled: Boolean) =
        runBlocking { manager.toggleAllScrapersForRepo(repositoryId, enabled) }

    fun scraperSettings(repositoryId: String, scraperId: String): String =
        JSONObject(runBlocking { store(appContext).getScraperSettings(scraperId) }).toString()

    fun setScraperSettings(repositoryId: String, scraperId: String, settingsJson: String) {
        val json = runCatching { JSONObject(settingsJson) }.getOrNull()
            ?: throw IllegalArgumentException("Ayarlar geçerli bir JSON nesnesi olmalı.")
        val map = json.keys().asSequence().associateWith { key -> json.get(key) }
        runBlocking { store(appContext).setScraperSettings(scraperId, map) }
    }


    /**
     * The last errors Nuvio's copied plugin code wrote to the log (it logs them and goes on, so
     * they are not passed back). An app may read its own log lines.
     */
    fun nuvioErrorsNow(): List<String> = nuvioErrors()

    private fun nuvioErrors(): List<String> = try {
        val process = ProcessBuilder(
            "logcat", "-d", "-t", "600", "--pid=${android.os.Process.myPid()}",
            "-s", "PluginRuntime:W", "PluginManager:W"
        ).redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().readLines()
        process.destroy()
        lines.mapNotNull { line ->
            val at = line.indexOf(": ").takeIf { it >= 0 } ?: return@mapNotNull null
            line.substring(at + 2).trim().takeIf { it.isNotEmpty() && !it.startsWith("at ") }
        }.map { it.take(160) }.distinct().takeLast(4)
    } catch (_: Exception) {
        emptyList()
    }

    private fun info(s: ScraperInfo) = NuvioScraperInfo(
        id = s.id,
        name = s.name,
        description = s.description,
        version = s.version,
        author = "",
        filename = s.filename,
        supportedTypes = s.supportedTypes,
        enabled = s.enabled,
        hasSettings = false,
        limited = false,
        logo = s.logo.orEmpty(),
        contentLanguage = s.contentLanguage,
        formats = s.formats.orEmpty(),
        supportedPlatforms = emptyList(),
        disabledPlatforms = emptyList(),
        supportsExternalPlayer = false
    )


    companion object {
        private const val TAG = "NuvioJsRepository"
        private const val OLD_PREFS = "nuvio_js_repositories"
        private const val OLD_KEY = "repositories_v1"

        @Volatile private var migration: Thread? = null

        /**
         * Repositories added before LeXia used Nuvio's own system are added again through it, once,
         * off the main thread (Nuvio downloads every scraper's code when a repository is added).
         */
        private fun startMigration(context: Context) = synchronized(this) {
            if (migration != null) return@synchronized
            val old = context.applicationContext.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE)
            val urls = oldRepositories(context).map { it.manifestUrl }
            if (urls.isEmpty()) {
                migration = Thread {}
                return@synchronized
            }
            migration = Thread {
                runBlocking {
                    urls.forEach { url ->
                        nuvio(context).addRepository(url).onFailure { Log.w(TAG, "Old repository not added again: ${it.message}") }
                    }
                }
                old.edit().remove(OLD_KEY).apply()
            }.also { it.start() }
        }

        /** What older LeXia builds saved, read only to show it while it is being moved. */
        private fun oldRepositories(context: Context): List<NuvioRepositoryInfo> {
            val raw = context.applicationContext.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE).getString(OLD_KEY, null)
                ?: return emptyList()
            return try {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { i ->
                    val repo = array.optJSONObject(i) ?: return@mapNotNull null
                    val url = repo.optString("manifestUrl").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val list = repo.optJSONArray("scrapers") ?: JSONArray()
                    NuvioRepositoryInfo(
                        id = repo.optString("id"),
                        name = repo.optString("name") + " (güncelleniyor…)",
                        version = "",
                        description = "",
                        manifestUrl = url,
                        scrapers = (0 until list.length()).mapNotNull { j ->
                            val sc = list.optJSONObject(j) ?: return@mapNotNull null
                            NuvioScraperInfo(
                                id = sc.optString("id"), name = sc.optString("name"), description = sc.optString("description"),
                                version = "", author = "", filename = "", supportedTypes = emptyList(),
                                enabled = sc.optBoolean("enabled", true), hasSettings = false, limited = false, logo = "",
                                contentLanguage = emptyList(), formats = emptyList(), supportedPlatforms = emptyList(),
                                disabledPlatforms = emptyList(), supportsExternalPlayer = false
                            )
                        }
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        /** Nuvio's PluginDataStore and PluginManager, made once for the app (com.nuvio.tv.LeXiaNuvio). */
        private fun store(context: Context): PluginDataStore = LeXiaNuvio.pluginDataStore(context)

        private fun nuvio(context: Context): PluginManager = LeXiaNuvio.pluginManager(context)
    }
}
