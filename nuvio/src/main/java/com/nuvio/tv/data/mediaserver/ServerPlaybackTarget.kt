// LeXia stand-in: LeXia has no media servers (Plex/Jellyfin). Not Nuvio code.
package com.nuvio.tv.data.mediaserver

data class ServerPlaybackTarget(val mediaSourceId: String?) {
    fun key(): String = mediaSourceId.orEmpty()
}
