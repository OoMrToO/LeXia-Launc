// LeXia stand-ins for NuvioTV services its player calls but LeXia does not have (Trakt, MDBList).
// Not Nuvio code: they answer as NuvioTV does when signed out or switched off.
package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.MDBListRatingsResult
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview

class TraktEpisodeMappingService {
    internal suspend fun isTraktAuthenticated(): Boolean = false
    internal suspend fun prefetchEpisodeMapping(
        contentId: String?,
        contentType: String?,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): EpisodeMappingEntry? = null
}

class TraktRelatedService {
    suspend fun getRelated(
        meta: Meta,
        fallbackItemId: String? = null,
        fallbackItemType: String? = null,
        forceRefresh: Boolean = false
    ): List<MetaPreview> = emptyList()
}

class MDBListRepository {
    fun isAvailable(settings: MDBListSettings): Boolean = false
    suspend fun getRatingsForMeta(
        meta: Meta,
        fallbackItemId: String,
        fallbackItemType: String
    ): MDBListRatingsResult? = null
}
