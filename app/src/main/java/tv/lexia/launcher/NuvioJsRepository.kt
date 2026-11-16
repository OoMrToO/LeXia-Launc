package tv.lexia.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

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
 * Nuvio JS repositories, handled the way Nuvio's own PluginManager handles them
 * (NuvioTV app/src/full/java/com/nuvio/tv/core/plugin/PluginManager.kt at a3532b81):
 * the same address rules (any scheme to https, cutt.ly short codes, "/manifest.json" added),
 * every scraper's code downloaded when the repository is added or refreshed, the same default
 * on/off state, the same type rule, no platform filter, up to 10 scrapers at a time started 60 ms
 * apart, 120 s each, results without duplicates. The scrapers themselves run in Nuvio's own
 * runtime (NuvioPluginRuntime). Only the storage is LeXia's: a settings file instead of DataStore.
 */
internal class NuvioJsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val codeDirectory = File(appContext.filesDir, "nuvio_js_scrapers")
    /** Nuvio's own scraper runtime, copied unchanged (NuvioPluginRuntime.kt). */
    private val runtime = NuvioPluginRuntime()
    private val gson = com.google.gson.Gson()
    private val http = OkHttpClient.Builder()
        .dns(NuvioIPv4FirstDns())
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** What the last source search ran into, for telling the viewer why nothing came. */
    class SearchNotes {
        /** Scrapers that were run. */
        val asked = java.util.concurrent.atomic.AtomicInteger()
        /** Scrapers that gave at least one result. */
        val answered = java.util.concurrent.atomic.AtomicInteger()
        /** Results that were not a video address (a magnet or torrent link, say). */
        val unplayable = java.util.concurrent.atomic.AtomicInteger()
    }

    @Volatile var notes = SearchNotes()
        private set

    fun installedRepositories(): List<NuvioRepositoryInfo> {
        val raw = prefs.getString(KEY_REPOSITORIES, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::parseStoredRepository)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun installedScraperCount(): Int = installedRepositories().sumOf { it.scrapers.size }

    // ---------- Adding, refreshing, removing (PluginManager.addRepository and friends) ----------

    /** Adds a repository by its address or a cutt.ly short code, and downloads every scraper's code. */
    fun addRepository(address: String): NuvioRepositoryInfo {
        val resolved = if (isShortCode(address)) {
            resolveShortCode(address.trim()) ?: throw IllegalArgumentException("Kısa kod çözülemedi: $address")
        } else {
            sanitizeScheme(address).trimEnd('/')
        }
        val manifestUrl = canonicalizeManifestUrl(resolved)
        val existing = installedRepositories().firstOrNull { it.manifestUrl.equals(manifestUrl, ignoreCase = true) }
        val manifest = fetchManifest(manifestUrl)
            ?: throw IllegalArgumentException("Bu adreste bir Nuvio deposu bulunamadı.")
        val repository = repositoryFrom(manifestUrl, manifest, existing)
        saveRepositories(installedRepositories().filterNot { it.id == repository.id } + repository)
        downloadJsScrapers(repository, clearOld = existing != null)
        return repository
    }

    /** Fetches the manifest again and downloads every scraper's code again; on/off and settings stay. */
    fun refreshRepository(repositoryId: String): NuvioRepositoryInfo {
        val current = installedRepositories().firstOrNull { it.id == repositoryId }
            ?: throw IllegalArgumentException("Nuvio deposu bulunamadı.")
        val manifest = fetchManifest(current.manifestUrl)
            ?: throw IllegalArgumentException("Depo güncellenemedi: manifest alınamadı.")
        val repository = repositoryFrom(current.manifestUrl, manifest, current)
        saveRepositories(installedRepositories().map { if (it.id == repositoryId) repository else it })
        downloadJsScrapers(repository, clearOld = true)
        return repository
    }

    fun removeRepository(repositoryId: String) {
        saveRepositories(installedRepositories().filterNot { it.id == repositoryId })
        val settingsPrefix = "settings_" + repositoryId + "_"
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith(settingsPrefix) }.forEach(::remove)
        }.apply()
        codeDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith(repositoryId.take(16))) file.delete()
        }
    }

    fun scraperSettings(repositoryId: String, scraperId: String): String =
        prefs.getString("settings_" + repositoryId + "_" + scraperId, "{}") ?: "{}"

    fun setScraperSettings(repositoryId: String, scraperId: String, settingsJson: String) {
        val parsed = runCatching { JSONObject(settingsJson) }.getOrNull()
            ?: throw IllegalArgumentException("Ayarlar geçerli bir JSON nesnesi olmalı.")
        prefs.edit().putString("settings_" + repositoryId + "_" + scraperId, parsed.toString()).apply()
    }

    fun setRepositoryEnabled(repositoryId: String, enabled: Boolean) {
        saveRepositories(installedRepositories().map { if (it.id == repositoryId) it.copy(enabled = enabled) else it })
    }

    fun setScraperEnabled(repositoryId: String, scraperId: String, enabled: Boolean) {
        saveRepositories(installedRepositories().map { repo ->
            if (repo.id != repositoryId) repo
            else repo.copy(scrapers = repo.scrapers.map { if (it.id == scraperId) it.copy(enabled = enabled) else it })
        })
    }

    /** PluginManager.toggleAllScrapersForRepo. */
    fun setAllScrapersEnabled(repositoryId: String, enabled: Boolean) {
        saveRepositories(installedRepositories().map { repo ->
            if (repo.id != repositoryId) repo else repo.copy(scrapers = repo.scrapers.map { it.copy(enabled = enabled) })
        })
    }

    // ---------- Running (PluginManager.executeScrapers) ----------

    /**
     * Runs every enabled scraper that takes this type of title. [mediaId] is a TMDB id, or
     * "<tmdb id>:<season>:<episode>" for an episode. [onPartial] gets each scraper's sources as
     * soon as that scraper is done (on a background thread).
     */
    fun fetchStreams(
        mediaId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null,
        onPartial: ((List<MediaStream>) -> Unit)? = null
    ): List<MediaStream> = runBlocking {
        val idParts = mediaId.split(':')
        val hasEpisodeSuffix = idParts.size >= 3 &&
            idParts[idParts.lastIndex - 1].toIntOrNull() != null && idParts.last().toIntOrNull() != null
        val resolvedSeason = if (hasEpisodeSuffix) idParts[idParts.lastIndex - 1].toInt() else season
        val resolvedEpisode = if (hasEpisodeSuffix) idParts.last().toInt() else episode
        val tmdbId = (if (hasEpisodeSuffix) idParts.dropLast(2).joinToString(":") else mediaId).removePrefix("tmdb:")

        val scrapers = installedRepositories()
            .filter { it.enabled }
            .flatMap { repo -> repo.scrapers.map { repo to it } }
            .filter { (_, scraper) -> scraper.enabled && supportsType(scraper, mediaType) }

        val found = SearchNotes()
        notes = found
        found.asked.set(scrapers.size)
        if (scrapers.isEmpty()) return@runBlocking emptyList()
        Log.d(TAG, "Executing ${scrapers.size} scrapers for $mediaType:$tmdbId s=$resolvedSeason e=$resolvedEpisode")

        val semaphore = Semaphore(MAX_CONCURRENT_SCRAPERS)
        val results = coroutineScope {
            scrapers.mapIndexed { index, (repo, scraper) ->
                async(Dispatchers.IO) {
                    if (index > 0) delay(index * 60L)
                    semaphore.withPermit { runOne(repo, scraper, tmdbId, mediaType, resolvedSeason, resolvedEpisode, found) }
                        .also { part -> if (part.isNotEmpty()) onPartial?.invoke(part) }
                }
            }.awaitAll()
        }
        results.flatten().distinctBy { it.url }.take(MAX_RESULT_ITEMS)
    }

    private suspend fun runOne(
        repo: NuvioRepositoryInfo,
        scraper: NuvioScraperInfo,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        found: SearchNotes
    ): List<MediaStream> = try {
        withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
            val code = scraperCode(repo, scraper)
            if (code.isNullOrBlank()) {
                Log.w(TAG, "No code found for scraper: ${scraper.name}")
                return@withTimeoutOrNull emptyList()
            }
            val settings: Map<String, Any> = try {
                @Suppress("UNCHECKED_CAST")
                (gson.fromJson(scraperSettings(repo.id, scraper.id), Map::class.java) as? Map<String, Any>).orEmpty()
            } catch (_: Exception) {
                emptyMap()
            }
            val results = runtime.executePlugin(
                code = code,
                tmdbId = tmdbId,
                mediaType = jsPluginMediaType(mediaType, scraper.supportedTypes, season, episode),
                season = season,
                episode = episode,
                scraperId = "${repo.id}:${scraper.id}",
                scraperSettings = settings
            )
            if (results.isNotEmpty()) found.answered.incrementAndGet()
            results.mapNotNull { r -> toMediaStream(scraper, r) ?: run { found.unplayable.incrementAndGet(); null } }
        } ?: run {
            Log.w(TAG, "Scraper ${scraper.name} timed out after ${SCRAPER_TIMEOUT_MS}ms")
            emptyList()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Scraper ${scraper.name} failed: ${error.message}")
        emptyList()
    }

    /** A scraper result as the source list and LeXia's player take it; null when it is no video address. */
    private fun toMediaStream(scraper: NuvioScraperInfo, r: LocalScraperResult): MediaStream? {
        if (r.url.toHttpUrlOrNull() == null) return null
        val name = r.name.orEmpty()
        return MediaStream(
            addonName = scraper.name,
            // Scrapers put the provider and quality in "name" ("Vidsrc - 1080p") and the film or
            // file in "title"; both are shown.
            name = name.ifBlank { r.title }.ifBlank { scraper.name }.take(120),
            description = listOf(r.title.takeIf { it != name }.orEmpty(), r.quality.orEmpty())
                .filter { it.isNotBlank() }.distinct().joinToString(" · ").take(300),
            url = r.url,
            headers = r.headers.orEmpty(),
            subtitles = r.subtitles.mapNotNull { s ->
                if (s.url.toHttpUrlOrNull() == null) null else MediaSubtitle(s.url, s.lang, s.lang.uppercase())
            },
            quality = r.quality.orEmpty(),
            language = r.language.orEmpty(),
            server = r.provider.orEmpty(),
            size = r.size.orEmpty(),
            streamType = r.type.orEmpty(),
            seeders = r.seeders,
            peers = r.peers,
            infoHash = r.infoHash.orEmpty(),
            audioDelayMs = r.audioDelayMs?.toLong(),
            audioDelayLanguage = r.audioDelayLanguage.orEmpty()
        )
    }

    /** ScraperInfo.supportsType: "series", "show" and "other" count as "tv". */
    private fun supportsType(scraper: NuvioScraperInfo, type: String): Boolean {
        val wanted = normalizePluginType(type)
        return scraper.supportedTypes.ifEmpty { listOf("movie", "tv") }.any { normalizePluginType(it) == wanted }
    }

    private fun normalizePluginType(value: String): String =
        when (value.lowercase()) {
            "series", "show", "other" -> "tv"
            else -> value.lowercase()
        }

    // ---------- Manifest and code (PluginManager.fetchManifest / downloadJsScrapers) ----------

    private fun fetchManifest(url: String): JSONObject? = try {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch manifest: ${response.code}")
                null
            } else {
                JSONObject(response.body?.string() ?: return@use null)
                    .takeIf { it.optJSONArray("scrapers") != null }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error fetching manifest: ${e.message}")
        null
    }

    private fun repositoryFrom(manifestUrl: String, manifest: JSONObject, previous: NuvioRepositoryInfo?): NuvioRepositoryInfo {
        val raw = manifest.optJSONArray("scrapers") ?: JSONArray()
        val oldEnabled = previous?.scrapers?.associate { it.id to it.enabled }.orEmpty()
        val scrapers = (0 until raw.length()).mapNotNull { index ->
            val item = raw.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            val filename = item.optString("filename").trim()
            if (id.isEmpty() || filename.isEmpty()) return@mapNotNull null
            val manifestEnabled = item.optBoolean("enabled", true)
            // Nuvio leaves "videasy" scrapers off until the viewer turns them on.
            val defaultEnabled = manifestEnabled && listOf(id, name, filename).none { it.contains("videasy", ignoreCase = true) }
            NuvioScraperInfo(
                id = id,
                name = name.ifEmpty { id },
                description = item.optString("description"),
                version = item.optString("version"),
                author = item.optString("author"),
                filename = filename,
                supportedTypes = stringList(item.optJSONArray("supportedTypes")).ifEmpty { listOf("movie", "tv") },
                enabled = oldEnabled[id] ?: defaultEnabled,
                hasSettings = item.optBoolean("hasSettings", false),
                limited = item.optBoolean("limited", false),
                logo = item.optString("logo"),
                contentLanguage = stringList(item.optJSONArray("contentLanguage")),
                formats = stringList(item.optJSONArray("formats")).ifEmpty { stringList(item.optJSONArray("supportedFormats")) },
                supportedPlatforms = stringList(item.optJSONArray("supportedPlatforms")),
                disabledPlatforms = stringList(item.optJSONArray("disabledPlatforms")),
                supportsExternalPlayer = item.optBoolean("supportsExternalPlayer", false)
            )
        }
        return NuvioRepositoryInfo(
            id = previous?.id ?: sha256(manifestUrl),
            name = manifest.optString("name").ifBlank { previous?.name ?: "Nuvio" },
            version = manifest.optString("version"),
            description = manifest.optString("description"),
            manifestUrl = manifestUrl,
            scrapers = scrapers,
            enabled = previous?.enabled ?: true
        )
    }

    /** Downloads every scraper's code, ten at a time (PluginManager.downloadJsScrapers). */
    private fun downloadJsScrapers(repo: NuvioRepositoryInfo, clearOld: Boolean) = runBlocking {
        if (clearOld) codeDirectory.listFiles()?.forEach { if (it.name.startsWith(repo.id.take(16))) it.delete() }
        val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
        repo.scrapers.map { scraper ->
            async(Dispatchers.IO) { semaphore.withPermit { runCatching { downloadCode(repo, scraper) } } }
        }.awaitAll()
    }

    /** The scraper's saved code; downloaded now if it was never saved. */
    private fun scraperCode(repo: NuvioRepositoryInfo, scraper: NuvioScraperInfo): String? {
        val file = codeFile(repo, scraper)
        if (file.exists() && file.length() > 0) return file.readText(Charsets.UTF_8)
        return runCatching { downloadCode(repo, scraper) }.getOrNull()
    }

    private fun codeFile(repo: NuvioRepositoryInfo, scraper: NuvioScraperInfo) =
        File(codeDirectory, "${repo.id.take(16)}_${sha256(scraper.id).take(24)}.js")

    private fun downloadCode(repo: NuvioRepositoryInfo, scraper: NuvioScraperInfo): String {
        val baseUrl = repo.manifestUrl.substringBeforeLast("/")
        val codeUrl = if (scraper.filename.startsWith("http")) scraper.filename else "$baseUrl/${scraper.filename}"
        val request = Request.Builder().url(codeUrl).header("User-Agent", USER_AGENT).build()
        val code = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Failed to download scraper ${scraper.name}: ${response.code}")
            val body = response.body ?: throw IOException("Empty scraper ${scraper.name}")
            if (body.contentLength() > MAX_RESPONSE_SIZE) throw IOException("Scraper ${scraper.name} too large")
            body.string()
        }
        codeDirectory.mkdirs()
        codeFile(repo, scraper).writeText(code, Charsets.UTF_8)
        Log.d(TAG, "Downloaded scraper: ${scraper.name}")
        return code
    }

    // ---------- Addresses (PluginManager.sanitizeScheme / short codes / canonicalizeManifestUrl) ----------

    /** Any scheme other than http(s) ("stremio://", "nuvio://") becomes https://. */
    private fun sanitizeScheme(url: String): String {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd > 0) {
            val scheme = trimmed.substring(0, schemeEnd).lowercase()
            if (scheme != "http" && scheme != "https") return "https://${trimmed.substring(schemeEnd + 3)}"
            return trimmed
        }
        return "https://$trimmed"
    }

    /** "cspr", "0094": letters and digits only, no dot, slash or scheme. */
    private fun isShortCode(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.contains("://") || trimmed.contains("/") || trimmed.contains(".")) return false
        return trimmed.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }

    /** Where cutt.ly/<code> leads. */
    private fun resolveShortCode(code: String): String? {
        val shortUrl = "https://cutt.ly/$code"
        try {
            val noRedirect = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            noRedirect.newCall(Request.Builder().url(shortUrl).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                if (response.code in 301..302) {
                    response.header("Location")?.takeIf { it.isNotBlank() }?.let { return sanitizeScheme(it) }
                }
            }
        } catch (_: Exception) {
        }
        return try {
            http.newCall(Request.Builder().url(shortUrl).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                val finalUrl = response.request.url.toString()
                if (finalUrl != shortUrl && response.isSuccessful) sanitizeScheme(finalUrl) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun canonicalizeManifestUrl(url: String): String {
        val trimmed = sanitizeScheme(url).trimEnd('/')
        // An address that already names a .json file is used as it is.
        if (trimmed.substringAfterLast("/").endsWith(".json", ignoreCase = true)) return trimmed
        return "$trimmed/manifest.json"
    }

    // ---------- Storage (LeXia's own) ----------

    private fun parseStoredRepository(item: JSONObject): NuvioRepositoryInfo? {
        val id = item.optString("id")
        val manifestUrl = item.optString("manifestUrl")
        val name = item.optString("name")
        if (id.isBlank() || manifestUrl.isBlank() || name.isBlank()) return null
        val raw = item.optJSONArray("scrapers") ?: JSONArray()
        val scrapers = (0 until raw.length()).mapNotNull { index ->
            val scraper = raw.optJSONObject(index) ?: return@mapNotNull null
            NuvioScraperInfo(
                id = scraper.optString("id"),
                name = scraper.optString("name"),
                description = scraper.optString("description"),
                version = scraper.optString("version"),
                author = scraper.optString("author"),
                filename = scraper.optString("filename"),
                supportedTypes = stringList(scraper.optJSONArray("supportedTypes")),
                enabled = scraper.optBoolean("enabled", true),
                hasSettings = scraper.optBoolean("hasSettings", false),
                limited = scraper.optBoolean("limited", false),
                logo = scraper.optString("logo"),
                contentLanguage = stringList(scraper.optJSONArray("contentLanguage")),
                formats = stringList(scraper.optJSONArray("formats")),
                supportedPlatforms = stringList(scraper.optJSONArray("supportedPlatforms")),
                disabledPlatforms = stringList(scraper.optJSONArray("disabledPlatforms")),
                supportsExternalPlayer = scraper.optBoolean("supportsExternalPlayer", false)
            )
        }
        return NuvioRepositoryInfo(
            id = id,
            name = name,
            version = item.optString("version"),
            description = item.optString("description"),
            manifestUrl = manifestUrl,
            scrapers = scrapers,
            enabled = item.optBoolean("enabled", true)
        )
    }

    private fun saveRepositories(repositories: List<NuvioRepositoryInfo>) {
        val array = JSONArray()
        repositories.forEach { repo ->
            val scraperArray = JSONArray()
            repo.scrapers.forEach { scraper ->
                scraperArray.put(
                    JSONObject()
                        .put("id", scraper.id)
                        .put("name", scraper.name)
                        .put("description", scraper.description)
                        .put("version", scraper.version)
                        .put("author", scraper.author)
                        .put("filename", scraper.filename)
                        .put("supportedTypes", JSONArray(scraper.supportedTypes))
                        .put("enabled", scraper.enabled)
                        .put("hasSettings", scraper.hasSettings)
                        .put("limited", scraper.limited)
                        .put("logo", scraper.logo)
                        .put("contentLanguage", JSONArray(scraper.contentLanguage))
                        .put("formats", JSONArray(scraper.formats))
                        .put("supportedPlatforms", JSONArray(scraper.supportedPlatforms))
                        .put("disabledPlatforms", JSONArray(scraper.disabledPlatforms))
                        .put("supportsExternalPlayer", scraper.supportsExternalPlayer)
                )
            }
            array.put(
                JSONObject()
                    .put("id", repo.id)
                    .put("name", repo.name)
                    .put("version", repo.version)
                    .put("description", repo.description)
                    .put("manifestUrl", repo.manifestUrl)
                    .put("enabled", repo.enabled)
                    .put("scrapers", scraperArray)
            )
        }
        prefs.edit().putString(KEY_REPOSITORIES, array.toString()).apply()
    }

    private fun stringList(array: JSONArray?): List<String> =
        if (array == null) emptyList()
        else (0 until minOf(array.length(), 100))
            .map { array.optString(it).trim() }
            .filter { it.isNotEmpty() }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "NuvioJsRepository"
        const val PREFS_NAME = "nuvio_js_repositories"
        const val KEY_REPOSITORIES = "repositories_v1"
        const val USER_AGENT = "NuvioTV/1.0"
        // PluginManager's limits.
        const val MAX_CONCURRENT_SCRAPERS = 10
        const val MAX_PARALLEL_DOWNLOADS = 10
        const val SCRAPER_TIMEOUT_MS = 120_000L
        const val MAX_RESULT_ITEMS = 150
        const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024L
    }
}
