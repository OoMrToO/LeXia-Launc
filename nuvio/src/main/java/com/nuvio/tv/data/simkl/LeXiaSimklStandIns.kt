// LeXia stand-ins for NuvioTV's Simkl account and recommendations. Not Nuvio code.
package com.nuvio.tv.data.simkl

import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SimklAuthRepository {
    val state: StateFlow<SimklAuthState> = MutableStateFlow(SimklAuthState())
}

class SimklRelatedService {
    suspend fun getRelated(
        meta: Meta,
        fallbackItemId: String? = null,
        fallbackItemType: String? = null,
        forceRefresh: Boolean = false
    ): List<MetaPreview> = emptyList()
}
