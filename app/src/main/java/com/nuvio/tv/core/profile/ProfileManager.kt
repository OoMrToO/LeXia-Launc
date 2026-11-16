// LeXia stand-in for a part of NuvioTV that the copied plugin files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.core.profile

import com.nuvio.tv.domain.model.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** LeXia has one profile: Nuvio's primary profile, id 1. */
class ProfileManager {
    private val primary = UserProfile(id = 1, name = "LeXia", avatarColorHex = "#FFFFFF")
    val activeProfileId: StateFlow<Int> = MutableStateFlow(1)
    val profiles: StateFlow<List<UserProfile>> = MutableStateFlow(listOf(primary))
    val activeProfile: UserProfile? get() = primary
}
