// LeXia stand-in for a part of NuvioTV that the copied plugin files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.core.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** No Nuvio account in LeXia: never signed in, so the copied code never syncs. */
class AuthManager {
    val authState: StateFlow<String> = MutableStateFlow("SignedOut")
    val isAuthenticated: Boolean get() = false
}
