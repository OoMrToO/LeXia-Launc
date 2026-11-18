// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.core.party

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** LeXia has no watch parties: never in one, nothing shared, every call does nothing. */
class PartyRepository {
    val state: StateFlow<PartyUiState> = MutableStateFlow(PartyUiState())
    val playback: StateFlow<PartyPlayback?> = MutableStateFlow(null)
    val events: SharedFlow<PartyEvent> = MutableSharedFlow()
    val notices: SharedFlow<PartyNotice> = MutableSharedFlow()
    val sharedStream: StateFlow<PartyStream?> = MutableStateFlow(null)
    val available: StateFlow<Boolean> = MutableStateFlow(false)
    val canUse: StateFlow<Boolean> = MutableStateFlow(false)
    val profile: StateFlow<PartyProfile> = MutableStateFlow(PartyProfile(name = "", imageUrl = null, color = null))

    fun nowMs(): Long = System.currentTimeMillis()
    fun refreshAvailability() = Unit
    suspend fun create(media: PartyMedia): PartyError? = PartyError.Network
    fun leave() = Unit
    fun end() = Unit
    fun removeMember(memberId: String) = Unit
    fun setHostOnly(enabled: Boolean) = Unit
    fun setSharing(enabled: Boolean) = Unit
    fun shareStream(stream: PartyStream?) = Unit
    fun publishPlayback(paused: Boolean, positionMs: Long, rate: Float) = Unit
    fun publishMedia(media: PartyMedia, positionMs: Long) = Unit
    fun setLocalStatus(status: PartyMemberStatus, mediaKey: String?, behindMs: Long? = null) = Unit
}
