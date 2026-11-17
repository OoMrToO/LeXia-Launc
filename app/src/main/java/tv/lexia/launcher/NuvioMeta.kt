package tv.lexia.launcher

import android.content.Context
import com.nuvio.tv.LeXiaNuvio
import com.nuvio.tv.core.network.NetworkResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A series' episodes from NuvioTV's details source (MetaRepositoryImpl.getMetaFromAllAddons: the
 * Stremio add-ons that give details, Cinemeta among them, as Nuvio's own series page asks), with
 * each episode's picture, summary, air date and length.
 */
internal object NuvioMeta {

    /** [ids] are tried in order ("tt…", then "tmdb:…"); empty when no add-on knows the series. */
    fun episodes(context: Context, ids: List<String>, addons: List<String>): List<MediaEpisode> = runBlocking {
        runCatching { LeXiaNuvio.setStremioAddons(context, addons) }
        val meta = LeXiaNuvio.metaRepository(context)
        for (id in ids) {
            val result = withTimeoutOrNull(20_000) {
                meta.getMetaFromAllAddons("series", id).first { it !is NetworkResult.Loading }
            }
            val videos = (result as? NetworkResult.Success)?.data?.videos.orEmpty()
            val found = videos.mapNotNull { v ->
                val season = v.season ?: return@mapNotNull null
                val episode = v.episode ?: return@mapNotNull null
                MediaEpisode(
                    id = v.id.ifBlank { "$id:$season:$episode" },
                    title = v.title.ifBlank { "$episode. Bölüm" },
                    season = season,
                    episode = episode,
                    released = v.released.orEmpty(),
                    thumbnail = v.thumbnail.orEmpty(),
                    overview = v.overview.orEmpty(),
                    runtime = v.runtime ?: 0,
                    rating = v.rating ?: 0.0
                )
            }
            if (found.isNotEmpty()) return@runBlocking found
        }
        emptyList()
    }
}
