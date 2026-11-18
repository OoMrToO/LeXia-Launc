// LeXia stand-ins for NuvioTV settings stores its player reads but LeXia does not have.
// Not Nuvio code: the values are NuvioTV's defaults.
package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.EpisodeOptionsOverlayStyle
import com.nuvio.tv.domain.model.HomeImdbRatingsVisibility
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** NuvioTV's home/detail layout settings, at their defaults. */
class LayoutPreferenceDataStore {
    val blurUnwatchedEpisodes: Flow<Boolean> = flowOf(false)
    val episodeOptionsOverlayStyle: Flow<EpisodeOptionsOverlayStyle> = flowOf(EpisodeOptionsOverlayStyle.BLUR)
    val homeImdbRatingsVisibility: Flow<HomeImdbRatingsVisibility> = flowOf(HomeImdbRatingsVisibility.SHOW_ALL)
    val hideUnreleasedContent: Flow<Boolean> = flowOf(false)
    fun randomEpisodeEnabledForProfile(profileId: Int): Flow<Boolean> = flowOf(false)
}

/** No Trakt account in LeXia. */
class TraktAuthDataStore {
    val isAuthenticated: Flow<Boolean> = flowOf(false)
    val isEffectivelyAuthenticated: Flow<Boolean> = isAuthenticated
}
