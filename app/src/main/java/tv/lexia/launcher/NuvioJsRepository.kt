package tv.lexia.launcher

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
    val formats: List<String>
)

internal data class NuvioRepositoryInfo(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val manifestUrl: String,
    val scrapers: List<NuvioScraperInfo>
)

/**
 * Installer and executor for Nuvio's JavaScript scraper-manifest format.
 *
 * Nuvio repositories are not Stremio add-ons: their manifest has a top-level "scrapers" array
 * and each scraper points to a JavaScript file exporting getStreams(). Code is downloaded lazily
 * and executed in NuvioJsRuntime's isolated QuickJS context.
 */
internal class NuvioJsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val codeDirectory = File(appContext.filesDir, "nuvio_js_scrapers")
    private val runtime = NuvioJsRuntime()
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

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

    /** Validates the Nuvio manifest and registers all scraper metadata. JS code is fetched on demand. */
    fun addRepository(address: String): NuvioRepositoryInfo {
        val manifestUrl = normalizeManifestUrl(address)
        val manifest = getJson(manifestUrl)
        val name = manifest.optString("name").trim()
        val version = manifest.optString("version").trim()
        val rawScrapers = manifest.optJSONArray("scrapers")
            ?: throw IllegalArgumentException("Bu bir Nuvio eklenti deposu değil: manifest içinde 'scrapers' listesi bulunamadı.")
        require(name.isNotBlank() && rawScrapers.length() > 0) {
            "Nuvio deposunda ad veya scraper listesi bulunamadı."
        }

        val scrapers = (0 until rawScrapers.length()).mapNotNull { index ->
            val item = rawScrapers.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val scraperName = item.optString("name").trim()
            val filename = item.optString("filename").trim()
            if (id.isBlank() || scraperName.isBlank() || filename.isBlank()) return@mapNotNull null
            NuvioScraperInfo(
                id = id,
                name = scraperName,
                description = item.optString("description"),
                version = item.optString("version").ifBlank { version },
                author = item.optString("author"),
                filename = filename,
                supportedTypes = stringList(item.optJSONArray("supportedTypes")).ifEmpty { listOf("movie", "tv") },
                enabled = item.optBoolean("enabled", true),
                hasSettings = item.optBoolean("hasSettings", false),
                limited = item.optBoolean("limited", false),
                logo = item.optString("logo"),
                contentLanguage = stringList(item.optJSONArray("contentLanguage")),
                formats = stringList(item.optJSONArray("formats")).ifEmpty {
                    stringList(item.optJSONArray("supportedFormats"))
                }
            )
        }
        require(scrapers.isNotEmpty()) { "Nuvio manifestinde geçerli bir scraper bulunamadı." }

        // Only accept relative paths or HTTPS absolute URLs. Never allow file:// or local paths.
        scrapers.forEach { resolveCodeUrl(manifestUrl, it.filename) }

        val repositoryId = sha256(manifestUrl)
        val previous = installedRepositories().firstOrNull { it.manifestUrl == manifestUrl }
        val oldEnabled = previous?.scrapers?.associate { it.id to it.enabled }.orEmpty()
        val mergedScrapers = scrapers.map { scraper ->
            scraper.copy(enabled = oldEnabled[scraper.id] ?: scraper.enabled)
        }
        val repository = NuvioRepositoryInfo(
            id = repositoryId,
            name = name,
            version = version,
            description = manifest.optString("description"),
            manifestUrl = manifestUrl,
            scrapers = mergedScrapers
        )
        val updated = installedRepositories().filterNot { it.manifestUrl == manifestUrl } + repository
        saveRepositories(updated)
        return repository
    }

    /** Re-fetches the manifest and scraper metadata while preserving each scraper toggle and saved settings. */
    fun refreshRepository(repositoryId: String): NuvioRepositoryInfo {
        val current = installedRepositories().firstOrNull { it.id == repositoryId }
            ?: throw IllegalArgumentException("Nuvio deposu bulunamadı.")
        return addRepository(current.manifestUrl)
    }

    fun removeRepository(repositoryId: String) {
        val remaining = installedRepositories().filterNot { it.id == repositoryId }
        saveRepositories(remaining)
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

    fun setScraperEnabled(repositoryId: String, scraperId: String, enabled: Boolean) {
        val updated = installedRepositories().map { repo ->
            if (repo.id != repositoryId) repo
            else repo.copy(scrapers = repo.scrapers.map { scraper ->
                if (scraper.id == scraperId) scraper.copy(enabled = enabled) else scraper
            })
        }
        saveRepositories(updated)
    }

    /**
     * Runs enabled Nuvio JS scrapers for the selected movie/episode.
     * Network and JavaScript work happens on background dispatchers, never on the UI thread.
     */
    fun fetchStreams(
        mediaId: String,
        mediaType: String,
        season: Int? = null,
        episode: Int? = null
    ): List<MediaStream> = runBlocking {
        val normalizedType = if (mediaType == "series" || mediaType == "tv") "tv" else "movie"
        // Stremio episode IDs commonly look like "tmdb:12345:1:2" or "tt1234567:1:2".
        // Nuvio's getStreams contract expects the content ID and season/episode as separate args.
        val idParts = mediaId.split(':')
        val hasEpisodeSuffix = normalizedType == "tv" && idParts.size >= 3 &&
            idParts[idParts.lastIndex - 1].toIntOrNull() != null &&
            idParts.last().toIntOrNull() != null
        val resolvedSeason = if (hasEpisodeSuffix) idParts[idParts.lastIndex - 1].toInt() else season
        val resolvedEpisode = if (hasEpisodeSuffix) idParts.last().toInt() else episode
        val contentId = if (hasEpisodeSuffix) idParts.dropLast(2).joinToString(":") else mediaId
        val nuvioMediaId = contentId.removePrefix("tmdb:")
        val scrapers = installedRepositories()
            .flatMap { repo -> repo.scrapers.map { repo to it } }
            .filter { (_, scraper) ->
                scraper.enabled && scraper.supportedTypes.any { normalizeType(it) == normalizedType }
            }
            .take(MAX_SCRAPERS_PER_SEARCH)

        if (scrapers.isEmpty()) return@runBlocking emptyList()
        if (BuildConfig.TMDB_API_KEY.isBlank()) {
            Log.w(TAG, "TMDB_API_KEY is empty; Nuvio scrapers that resolve title metadata through TMDB may return no streams.")
        }
        Log.i(TAG, "Starting ${scrapers.size} Nuvio scrapers for type=${normalizedType}, id=${nuvioMediaId}, season=${resolvedSeason}, episode=${resolvedEpisode}")

        val semaphore = Semaphore(MAX_PARALLEL_SCRAPERS)
        val results = coroutineScope {
            scrapers.map { (repo, scraper) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(SCRAPER_TIMEOUT_MS) {
                                val code = loadScraperCode(repo, scraper)
                                if (code.isBlank()) return@withTimeoutOrNull emptyList()
                                Log.d(TAG, "Executing Nuvio scraper ${scraper.name} (${scraper.id})")
                                val rawStreams = runtime.execute(
                                    code = code,
                                    mediaId = nuvioMediaId,
                                    mediaType = normalizedType,
                                    season = resolvedSeason,
                                    episode = resolvedEpisode,
                                    scraperId = scraper.id,
                                    settingsJson = scraperSettings(repo.id, scraper.id)
                                )
                                Log.i(TAG, "Nuvio scraper ${scraper.name} returned ${rawStreams.size} raw streams")
                                rawStreams.mapNotNull { result ->
                                    if (!isHttps(result.url)) {
                                        Log.w(TAG, "Rejected non-HTTPS stream from ${scraper.name}")
                                        return@mapNotNull null
                                    }
                                    MediaStream(
                                        addonName = scraper.name,
                                        name = result.title.ifBlank { scraper.name },
                                        description = listOf(result.quality, scraper.description)
                                            .filter { it.isNotBlank() }
                                            .distinct()
                                            .joinToString(" · ")
                                            .take(300),
                                        url = result.url,
                                        headers = result.headers,
                                        subtitles = result.subtitles.map {
                                            MediaSubtitle(it.url, it.language, it.language.uppercase())
                                        }
                                    )
                                }
                            } ?: run {
                                Log.w(TAG, "Nuvio scraper ${scraper.name} timed out after ${SCRAPER_TIMEOUT_MS}ms")
                                emptyList()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Log.w(TAG, "Nuvio scraper ${scraper.name} failed: ${error.message}")
                            emptyList()
                        }
                    }
                }
            }.flatMap { it.await() }
        }
        // Keep identical URLs from different scrapers: provider attribution is part of Nuvio's UI.
        results.distinctBy { "${it.addonName.lowercase()}|${it.url}" }.take(MAX_STREAMS)
    }

    private fun loadScraperCode(repo: NuvioRepositoryInfo, scraper: NuvioScraperInfo): String {
        val cacheKey = sha256("${repo.id}:${scraper.id}:${scraper.version}").take(40)
        val cached = File(codeDirectory, "${repo.id.take(16)}_$cacheKey.js")
        if (cached.exists() && cached.length() in 1..MAX_CODE_BYTES.toLong() &&
            System.currentTimeMillis() - cached.lastModified() < CODE_CACHE_TTL_MS
        ) {
            return cached.readText(Charsets.UTF_8)
        }

        val url = resolveCodeUrl(repo.manifestUrl, scraper.filename)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LeXia/NuvioJS")
            .header("Accept", "application/javascript, text/javascript, */*")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Scraper HTTP ${response.code}: ${scraper.name}")
            val body = response.body ?: throw IOException("Boş scraper dosyası: ${scraper.name}")
            if (body.contentLength() > MAX_CODE_BYTES) throw IOException("Scraper dosyası çok büyük: ${scraper.name}")
            val code = body.string()
            if (code.toByteArray(Charsets.UTF_8).size > MAX_CODE_BYTES) {
                throw IOException("Scraper dosyası çok büyük: ${scraper.name}")
            }
            codeDirectory.mkdirs()
            cached.writeText(code, Charsets.UTF_8)
            return code
        }
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LeXia/NuvioJS")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Manifest HTTP ${response.code}")
            val body = response.body ?: throw IOException("Manifest boş")
            if (body.contentLength() > MAX_MANIFEST_BYTES) throw IOException("Manifest dosyası çok büyük")
            val text = body.string()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_MANIFEST_BYTES) throw IOException("Manifest dosyası çok büyük")
            return JSONObject(text)
        }
    }

    private fun normalizeManifestUrl(address: String): String {
        var raw = address.trim()
        require(raw.isNotBlank()) { "Depo adresi boş olamaz." }
        require('?' !in raw && '#' !in raw) { "Yapılandırma sorgusu içeren depo URL'leri henüz desteklenmiyor." }
        if (!raw.endsWith(".json", true)) raw = raw.trimEnd('/') + "/manifest.json"
        val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("Geçerli bir HTTPS manifest adresi gir.")
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty()) {
            "Güvenlik nedeniyle yalnızca kimlik bilgisi içermeyen HTTPS depoları destekleniyor."
        }
        return url.toString()
    }

    private fun resolveCodeUrl(manifestUrl: String, filename: String): String {
        val manifest = manifestUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Geçersiz manifest URL")
        val resolved = if (filename.startsWith("https://", true)) {
            filename.toHttpUrlOrNull()
        } else {
            manifest.resolve(filename)
        } ?: throw IllegalArgumentException("Scraper dosya adresi çözümlenemedi: $filename")
        require(resolved.scheme == "https" && resolved.username.isEmpty() && resolved.password.isEmpty()) {
            "Scraper dosyaları yalnızca HTTPS üzerinden yüklenebilir."
        }
        return resolved.toString()
    }

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
                formats = stringList(scraper.optJSONArray("formats"))
            )
        }
        return NuvioRepositoryInfo(
            id = id,
            name = name,
            version = item.optString("version"),
            description = item.optString("description"),
            manifestUrl = manifestUrl,
            scrapers = scrapers
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
                )
            }
            array.put(
                JSONObject()
                    .put("id", repo.id)
                    .put("name", repo.name)
                    .put("version", repo.version)
                    .put("description", repo.description)
                    .put("manifestUrl", repo.manifestUrl)
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

    private fun normalizeType(value: String): String =
        when (value.lowercase()) {
            "tv", "series", "show" -> "tv"
            else -> value.lowercase()
        }

    private fun isHttps(raw: String): Boolean =
        raw.toHttpUrlOrNull()?.scheme == "https"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "NuvioJsRepository"
        const val PREFS_NAME = "nuvio_js_repositories"
        const val KEY_REPOSITORIES = "repositories_v1"
        const val MAX_MANIFEST_BYTES = 3 * 1024 * 1024
        const val MAX_CODE_BYTES = 2 * 1024 * 1024
        const val MAX_SCRAPERS_PER_SEARCH = 100
        const val MAX_PARALLEL_SCRAPERS = 8
        const val SCRAPER_TIMEOUT_MS = 65_000L
        const val MAX_STREAMS = 60
        val CODE_CACHE_TTL_MS = TimeUnit.HOURS.toMillis(6)
    }
}
