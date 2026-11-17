// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.SubtitleRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Where a film was left is kept by LeXia's own player, so Nuvio's watch history stays empty. */
class LeXiaWatchProgressRepository : WatchProgressRepository {
    override val allProgress: Flow<List<WatchProgress>> = flowOf(emptyList())
    override val continueWatching: Flow<List<WatchProgress>> = flowOf(emptyList())
    override val watchedItems: Flow<List<WatchedItem>> = flowOf(emptyList())
    override fun getProgress(contentId: String): Flow<WatchProgress?> = flowOf(null)
    override fun getEpisodeProgress(contentId: String, season: Int, episode: Int): Flow<WatchProgress?> = flowOf(null)
    override fun getAllEpisodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> = flowOf(emptyMap())
    override fun getAiredEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())
    override fun observeNextUpSeeds(): Flow<List<WatchProgress>> = flowOf(emptyList())
    override fun observeRemoteProgressLoaded(): Flow<Boolean> = flowOf(true)
    override fun observeOptimisticContinueWatchingUpdates(): Flow<WatchProgress> = emptyFlow()
    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress
    override fun isWatched(contentId: String, videoId: String?, season: Int?, episode: Int?): Flow<Boolean> = flowOf(false)
    override fun observeWatchedMovieIds(): Flow<Set<String>> = flowOf(emptySet())
    override suspend fun getWatchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> = emptyMap()
    override suspend fun getShowIdSiblings(): Map<String, Set<String>> = emptyMap()
    override suspend fun saveProgress(progress: WatchProgress, syncRemote: Boolean) = Unit
    override suspend fun saveProgressBatch(progressList: List<WatchProgress>, syncRemote: Boolean) = Unit
    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) = Unit
    override suspend fun removeFromHistory(contentId: String, videoId: String?, season: Int?, episode: Int?) = Unit
    override suspend fun markAsCompleted(progress: WatchProgress, broadcastTrackingHistory: Boolean) = Unit
    override suspend fun markAsCompletedBatch(progressList: List<WatchProgress>) = Unit
    override suspend fun removeFromHistoryBatch(contentId: String, videoId: String?, episodes: List<Triple<Int, Int, String?>>) = Unit
    override suspend fun clearAll() = Unit
    override fun isDroppedShow(contentId: String): Boolean = false
    override fun hasActiveTrackingProgressProvider(): Boolean = false
    override fun activeProviderOwnsCompletedHistoryProjection(): Boolean = false
    override fun activeProviderContinueWatchingCutoffEpochMs(daysCap: Int, nowEpochMs: Long): Long? = null
    override fun shouldUseAsNextUpSeed(progress: WatchProgress, nowEpochMs: Long): Boolean = false
    override suspend fun normalizeParentContentId(parentContentId: String, videoId: String?): String = parentContentId
}

/** Film and episode details are LeXia's own (its film list), so Nuvio's meta lookups find nothing. */
class LeXiaMetaRepository : MetaRepository {
    private fun none(): Flow<NetworkResult<Meta>> = flowOf(NetworkResult.Error("Not available in LeXia"))
    override fun getMeta(addonBaseUrl: String, type: String, id: String): Flow<NetworkResult<Meta>> = none()
    override fun getMetaFromAllAddons(type: String, id: String, sourceAddonBaseUrl: String?): Flow<NetworkResult<Meta>> = none()
    override fun getMetaFromPrimaryAddon(type: String, id: String): Flow<NetworkResult<Meta>> = none()
    override fun getCachedMeta(type: String, id: String): Meta? = null
    override fun clearCache() = Unit
}

/** Only asked when handing a stream to an outside player, which LeXia never does. */
class LeXiaSubtitleRepository : SubtitleRepository {
    override suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)?,
        onSubtitlesEmitted: ((List<Subtitle>) -> Unit)?
    ): List<Subtitle> = emptyList()
}
