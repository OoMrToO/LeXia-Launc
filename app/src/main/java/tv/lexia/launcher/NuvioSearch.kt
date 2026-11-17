package tv.lexia.launcher

import android.content.Context
import com.nuvio.tv.LeXiaNuvio
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.pluginBingeGroup
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * LeXia's source page asks NuvioTV's own search (StreamRepositoryImpl.getStreamsFromAllAddons:
 * the Stremio add-ons and the plugin repositories together, exactly as Nuvio's stream screen
 * does) and shows what comes back in LeXia's own list. Nothing of Nuvio's screens is opened.
 */
internal object NuvioSearch {

    /** What the last search ran into, for telling the viewer why nothing came. */
    class Notes {
        val providers = AtomicInteger()
        val torrents = AtomicInteger()
        val unplayable = AtomicInteger()
        @Volatile var error: String = ""
    }

    @Volatile var notes = Notes()
        private set

    /**
     * Searches [videoId] ("tt…", "tt…:1:2" or "tmdb:…"); [onUpdate] gets every source found so far
     * each time a provider answers (on the calling thread). Returns them all when every provider is done.
     */
    fun streams(
        context: Context,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        favoriteScraperId: String = "",
        onUpdate: (List<MediaStream>) -> Unit
    ): List<MediaStream> = runBlocking {
        val found = Notes()
        notes = found
        var latest: List<MediaStream> = emptyList()
        LeXiaNuvio.streamRepository(context)
            .getStreamsFromAllAddons(
                type, videoId, season, episode,
                // Nuvio's own "keep to this provider": it runs alone first; the other providers
                // run only when it finds nothing (PluginManager.executeScrapersStreaming).
                priorityBingeGroup = favoriteScraperId.takeIf { it.isNotBlank() }?.let { pluginBingeGroup(scraperId = it) },
                preferredPluginOnly = favoriteScraperId.isNotBlank()
            )
            .collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        found.providers.set(result.data.count { it.streams.isNotEmpty() })
                        var torrents = 0
                        var unplayable = 0
                        latest = result.data.flatMap { group ->
                            group.streams.mapNotNull { stream ->
                                when {
                                    stream.isTorrent() -> { torrents++; null }
                                    else -> toMediaStream(stream) ?: run { unplayable++; null }
                                }
                            }
                        }
                        found.torrents.set(torrents)
                        found.unplayable.set(unplayable)
                        if (latest.isNotEmpty()) onUpdate(latest)
                    }
                    is NetworkResult.Error -> found.error = result.message
                    NetworkResult.Loading -> Unit
                }
            }
        latest
    }

    /** A Nuvio stream as LeXia's list and player take it; null when it is no video address. */
    private fun toMediaStream(stream: Stream): MediaStream? {
        val url = stream.url?.takeIf { it.toHttpUrlOrNull() != null } ?: return null
        val name = stream.getDisplayName()
        val hints = stream.behaviorHints
        return MediaStream(
            addonName = stream.addonName,
            name = name.take(120),
            description = stream.getDisplayDescription()?.takeIf { it != name }.orEmpty().take(300),
            url = url,
            headers = hints?.proxyHeaders?.request.orEmpty(),
            subtitles = stream.subtitles.mapNotNull { sub ->
                if (sub.url.toHttpUrlOrNull() == null) null else MediaSubtitle(sub.url, sub.lang, sub.getDisplayLanguage())
            },
            quality = stream.quality.orEmpty(),
            fileName = hints?.filename.orEmpty(),
            sizeBytes = hints?.videoSize ?: 0L,
            audioDelayMs = hints?.audioDelayMs?.toLong(),
            audioDelayLanguage = hints?.audioDelayLanguage.orEmpty()
        )
    }
}
