// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.core.torrent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** LeXia plays no torrents. */
class TorrentService {
    val state: StateFlow<TorrentState> = MutableStateFlow(TorrentState.Idle)
    suspend fun startStream(infoHash: String, fileIdx: Int?, filename: String?, trackers: List<String>): String =
        throw UnsupportedOperationException("Torrent playback is not part of LeXia")
    fun stopStream() = Unit
}
