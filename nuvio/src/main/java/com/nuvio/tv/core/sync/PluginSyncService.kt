// LeXia stand-in for a part of NuvioTV that the copied plugin files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.core.sync

/** No Nuvio account in LeXia: nothing is pushed anywhere. */
class PluginSyncService {
    suspend fun pushToRemote(): Result<Unit> = Result.success(Unit)
}
