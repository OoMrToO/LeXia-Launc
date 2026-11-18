// LeXia stand-in for NuvioTV's debrid cloud library. Not Nuvio code: LeXia has no debrid.
package com.nuvio.tv.core.cloud

class CloudLibraryRepository {
    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult = CloudLibraryPlaybackResult.NotPlayable
}
