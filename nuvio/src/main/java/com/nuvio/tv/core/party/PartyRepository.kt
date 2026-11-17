// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.core.party

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** LeXia has no watch parties: never in one, nothing shared. */
class PartyRepository {
    val state: StateFlow<PartyUiState> = MutableStateFlow(PartyUiState())
    val sharedStream: StateFlow<PartyStream?> = MutableStateFlow(null)
}
