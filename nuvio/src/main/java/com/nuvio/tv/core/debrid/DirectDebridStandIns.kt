// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream

/** LeXia plays no debrid streams: nothing is ever resolved through a debrid service. */
class DirectDebridResolver {
    fun shouldResolveToPlayableStream(stream: Stream): Boolean = false
    suspend fun resolve(stream: Stream, season: Int?, episode: Int?): DirectDebridResolveResult =
        DirectDebridResolveResult.Error
}

class DirectDebridStreamPreparer {
    suspend fun prepare(
        streams: List<Stream>,
        season: Int?,
        episode: Int?,
        playerSettings: PlayerSettings,
        installedAddonNames: Set<String>,
        onPrepared: (original: Stream, prepared: Stream) -> Unit
    ) = Unit

    fun replacePreparedStream(groups: List<AddonStreams>, original: Stream, prepared: Stream): List<AddonStreams> = groups
}

/** As in Nuvio's TorboxDirectDebridResolver.kt. */
sealed class DirectDebridResolveResult {
    data class Success(
        val url: String,
        val filename: String?,
        val videoSize: Long?
    ) : DirectDebridResolveResult()

    data object MissingApiKey : DirectDebridResolveResult()
    data object NotCached : DirectDebridResolveResult()
    data object Stale : DirectDebridResolveResult()
    data object Error : DirectDebridResolveResult()
}

/** Without a debrid service the stream groups are shown as they came. */
class DebridStreamPresentation {
    suspend fun apply(groups: List<AddonStreams>, includeBadgeMatches: Boolean = true): List<AddonStreams> = groups

    fun apply(
        groups: List<AddonStreams>,
        settings: com.nuvio.tv.domain.model.DebridSettings,
        streamBadgeRules: com.nuvio.tv.core.streams.StreamBadgeRules = com.nuvio.tv.core.streams.StreamBadgeRules(),
        includeBadgeMatches: Boolean = true
    ): List<AddonStreams> = groups
}

/** No debrid account: availability is never checked. */
class LocalDebridAvailabilityService {
    suspend fun markChecking(groups: List<AddonStreams>): List<AddonStreams> = groups
    suspend fun annotateCachedAvailability(groups: List<AddonStreams>): List<AddonStreams> = groups
}
