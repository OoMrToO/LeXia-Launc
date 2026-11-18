// LeXia stand-in for NuvioTV's trailer finder (used by its post-play recommendations). Not Nuvio code.
package com.nuvio.tv.data.trailer

class TrailerService {
    suspend fun getTrailerPlaybackSource(
        title: String,
        year: String? = null,
        tmdbId: String? = null,
        type: String? = null,
        ignoreUseTrailersGate: Boolean = false
    ): TrailerPlaybackSource? = null
}
