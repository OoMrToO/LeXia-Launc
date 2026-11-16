// LeXia stand-in for a part of NuvioTV that the copied plugin files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.core.plugin.cloudstream

import com.nuvio.tv.core.plugin.TestDiagnostics
import com.nuvio.tv.domain.model.ExternalPluginEntry
import com.nuvio.tv.domain.model.LocalScraperResult
import java.io.File

/**
 * CloudStream (.cs3) repositories are not part of LeXia: an address that is not a Nuvio JS
 * repository is simply not recognised, exactly as Nuvio does when no parser accepts it.
 */
data class ExternalRepoParseResult(
    val name: String,
    val description: String?,
    val plugins: List<ExternalPluginEntry>
)

class ExternalRepoParser {
    suspend fun tryParse(url: String): ExternalRepoParseResult? = null
}

class ExternalExtensionLoader {
    suspend fun downloadExtension(scraperId: String, downloadUrl: String): File? = null
    fun ensureExtractorsLoaded(scraperIds: List<String>, diagnostics: TestDiagnostics? = null) {}
    fun deleteExtension(scraperId: String) {}
    fun evictCache(scraperId: String) {}
}

class ExternalExtensionRunner {
    suspend fun execute(scraperId: String, tmdbId: String, mediaType: String, season: Int?, episode: Int?): List<LocalScraperResult> = emptyList()
    suspend fun executeWithDiagnostics(
        scraperId: String, tmdbId: String, mediaType: String, season: Int?, episode: Int?, diagnostics: TestDiagnostics
    ): List<LocalScraperResult> = emptyList()
}

enum class TvType { Movie, TvSeries, Anime, Others }

fun TvType.toNuvioType(): String = when (this) {
    TvType.Movie -> "movie"
    else -> "tv"
}

fun tvTypeFromString(value: String): TvType? = TvType.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
