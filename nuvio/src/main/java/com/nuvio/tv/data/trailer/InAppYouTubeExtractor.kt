// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.data.trailer

/** Streams that are only a YouTube id are not resolved here (LeXia's own trailer player does that). */
class InAppYouTubeExtractor {
    suspend fun extractSingleUrl(youtubeUrl: String): String? = null
}
