// LeXia stand-in for a part of NuvioTV that the copied files refer to but LeXia does not
// have (accounts, sync, profiles, CloudStream extensions, Hilt). Not Nuvio code: keep it minimal.
package com.nuvio.tv.ui.screens.player

import android.app.ActivityManager
import android.content.Context

/** Nuvio's player tuning. LeXia plays in its own player, so only what Nuvio's settings store reads is here. */
object NuvioExoPlayerPerformanceHelper {
    const val DEFAULT_NUVIO_TARGET_BUFFER_MB = 175
    const val DEFAULT_NUVIO_BACK_BUFFER_MS = 15_000

    fun getDevicePhysicalRamBytes(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0L
        return ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.totalMem
    }

    fun getSafeNativeMemoryLimitMb(context: Context): Int = 200
}
