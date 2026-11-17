// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.core.player

/** As in Nuvio's ExternalPlaybackTracker.kt (the fields only). */
data class ExternalPlaybackMetadata(
    val contentId: String,
    val contentType: String,
    val contentName: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val videoId: String,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val year: String?,
    val profileId: Int
) {
    fun buildPlayerTitle(includeEpisodeTitle: Boolean = false): String = contentName
}

class ExternalNextEpisodeSnapshot

/** LeXia plays in its own player: no outside player is ever started or followed. */
class ExternalPlaybackTracker {
    val isTracking: Boolean get() = false
    val isAutoLaunch: Boolean get() = false
    val isOwningTorrentStream: Boolean get() = false
    fun updateAutoNextOverlayStatus(message: String?, progress: Float?) = Unit
    fun releaseAutoNextOverlay(forceRelease: Boolean = false) = Unit
    fun isAutoNextContinuationAborted(): Boolean = false
    fun consumeAbortedAutoNextContinuation() = Unit
    fun dismissOverlayOnly() = Unit
    fun stopTracking() = Unit

    suspend fun launchPlayer(
        metadata: ExternalPlaybackMetadata,
        url: String,
        title: String?,
        headers: Map<String, String>?,
        resumePositionMs: Long = 0L,
        startFromBeginning: Boolean = false,
        subtitles: List<SubtitleInput>? = null,
        autoLaunch: Boolean = false,
        nextEpisodeSnapshot: ExternalNextEpisodeSnapshot? = null,
        cloudSessionToken: String? = null,
        ownsTorrentStream: Boolean = false,
        context: android.content.Context
    ): Boolean = false

    suspend fun resolveNextEpisodeSnapshot(
        metadata: ExternalPlaybackMetadata,
        videos: List<com.nuvio.tv.domain.model.Video>,
        preferredVideoId: String? = null
    ): ExternalNextEpisodeSnapshot = ExternalNextEpisodeSnapshot()
}

object ZidooPlayerMonitor {
    fun isZidooDevice(): Boolean = false
}
