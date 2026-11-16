package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * What plays a film and where its sources are managed. In this build that is LeXia's own source
 * list, player and add-on screen. The build that carries Nuvio inside replaces this file with one
 * that opens Nuvio's screens instead (nuvio-bridge/Media.kt, see scripts/merge_nuvio.py).
 */
internal object Media {
    /** Nuvio is inside this app and does the playing. */
    const val NUVIO = false

    /** "Oynat" on a film page. */
    fun play(activity: Activity, film: Pick) = MediaSourcePickerActivity.open(activity, film)

    /** Ayarlar › Medya kaynakları. */
    fun openSources(activity: Activity) {
        activity.startActivity(Intent(activity, MediaAddonsActivity::class.java))
    }

    fun sourcesSummary(context: Context): String =
        "${MediaAddonRepository(context).installedAddons().size + NuvioJsRepository(context).installedRepositories().size} kurulu"
}
