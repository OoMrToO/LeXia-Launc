// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (Plex, Jellyfin, Emby media servers). Not Nuvio code: keep it minimal.
package com.nuvio.tv.data.mediaserver

/** LeXia has no media servers: no native requests, no server sources. */
class ServerStreamSource(val name: String, val preferred: Boolean) {
    suspend fun load(): List<com.nuvio.tv.domain.model.Stream> = emptyList()
}

class ServerStreams {
    val revision: Int get() = 0
    fun isNativeRequest(videoId: String): Boolean = false
    fun sources(
        contentType: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false
    ): List<ServerStreamSource> = emptyList()
    fun preferredSourceNames(contentType: String, videoId: String): Set<String> = emptySet()
}

/** No server sessions: no stream is ever a server source, so the player never asks for one. */
class ServerPlayback {
    suspend fun prepare(target: ServerPlaybackTarget): ServerPlaybackSession =
        throw ServerException(ServerFailure.UNSUPPORTED)
    fun canFallback(url: String?): Boolean = false
    suspend fun fallback(url: String?): ServerPlaybackSession? = null
    suspend fun switchAudio(url: String?, audioStreamIndex: Int): ServerPlaybackSession? = null
    suspend fun switchSubtitle(url: String?, subtitleStreamIndex: Int?): ServerPlaybackSession? = null
    fun audioTracks(url: String?): List<ServerTrack> = emptyList()
    fun burnInSubtitles(url: String?): List<ServerTrack> = emptyList()
    fun session(url: String?): ServerPlaybackSession? = null
    suspend fun resumeState(url: String?): ServerUserState? = null
    fun isServerSource(url: String?): Boolean = false
    fun onPlaybackSnapshot(url: String?, positionMs: Long, isPlaying: Boolean, isLoading: Boolean, isEnded: Boolean) = Unit
    fun stop(url: String?) = Unit
}

class ServerCatalog {
    suspend fun details(ref: ServerItemRef): ServerItemDetails =
        throw ServerException(ServerFailure.UNSUPPORTED)

    companion object {
        fun isServerAddon(baseUrl: String?): Boolean = false
    }
}
