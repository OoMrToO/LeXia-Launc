// LeXia stand-in for NuvioTV's Android TV "Watch Next" row (declined for LeXia). Not Nuvio code.
package com.nuvio.tv.core.recommendations

import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.MutableStateFlow

class TvRecommendationManager {
    suspend fun updateSingleWatchNextProgram(progress: WatchProgress) = Unit
    suspend fun onProgressRemoved(contentId: String) = Unit

    companion object {
        val isPlaybackActive = MutableStateFlow(false)
    }
}
