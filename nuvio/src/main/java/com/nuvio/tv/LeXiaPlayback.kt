// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.settings.MemoryBudget

/**
 * LeXia's player opens a video the way NuvioTV's player does: through Nuvio's own
 * PlayerMediaSourceFactory (the stream's kind read from its address, file name or answer; HLS,
 * DASH and plain files; the stream's headers on every request), set up as Nuvio's player sets it
 * up with its default settings (PlayerRuntimeControllerInitialization).
 */
@UnstableApi
object LeXiaPlayback {
    @Volatile private var started = false

    /** One per playback, as in Nuvio's PlayerRuntimeController; [release] lets it go. */
    class Session(context: Context) {
        private val factory = PlayerMediaSourceFactory(context.applicationContext).apply {
            val settings = PlayerSettings()
            bufferEngineEnabled = settings.bufferEngineEnabled
            bufferBudgetManaged = settings.bufferBudgetManaged
            vodCacheEnabled = settings.vodCacheEnabled
            vodCacheSizeMode = settings.vodCacheSizeMode
            vodCacheSizeMb = settings.vodCacheSizeMb
            nativeEngineEnabled = settings.nuvioPerformanceModeEnabled
            nuvioPerformanceModeEnabled = settings.nuvioPerformanceModeEnabled
            if (settings.parallelNetworkEnabled) {
                useParallelConnections = settings.useParallelConnections
                parallelConnectionCount = settings.parallelConnectionCount
                parallelChunkSizeKb = settings.parallelChunkSizeKb
            } else {
                useParallelConnections = false
            }
        }

        fun mediaSource(context: Context, url: String, headers: Map<String, String>, filename: String? = null): MediaSource =
            factory.createMediaSource(context, url, headers, filename = filename)

        fun release() = factory.shutdown()
    }

    /** What NuvioApplication does once at start for the player's memory budget. */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            MemoryBudget.init(context.applicationContext)
            started = true
        }
    }
}
