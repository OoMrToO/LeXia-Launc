// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.data.mediaserver

/** LeXia has no media servers (Plex, Jellyfin): no native requests, no server sources. */
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

class ServerFailure

fun Throwable.serverFailure(): ServerFailure = ServerFailure()

fun ServerFailure.messageRes(): Int = com.nuvio.tv.R.string.error_generic

class ServerPlaybackSession(
    val url: String,
    val headers: Map<String, String>,
    val subtitles: List<com.nuvio.tv.domain.model.Subtitle>
)

class ServerPlayback {
    suspend fun prepare(target: ServerPlaybackTarget): ServerPlaybackSession =
        throw UnsupportedOperationException("Media servers are not part of LeXia")
}

/** Never reached: ServerPlayback.prepare is never asked for a session. */
fun Throwable.serverPlaybackMessageRes(): Int = com.nuvio.tv.R.string.error_generic
