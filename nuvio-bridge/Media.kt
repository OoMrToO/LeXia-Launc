package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The combined build's door to playing: Nuvio is inside the app and does it all (add-ons,
 * sources, torrents, its own player). LeXia only hands the title over.
 * Replaces app/src/main/java/tv/lexia/launcher/Media.kt (see scripts/merge_nuvio.py).
 */
internal object Media {
    const val NUVIO = true
    private const val NUVIO_SCREEN = "com.nuvio.tv.MainActivity"

    /** "Oynat": Nuvio's page of this film or series, where its sources are listed and played. */
    fun play(activity: Activity, film: Pick) {
        val kind = if (film.isSeries) "series" else "movie"
        val address = when {
            film.imdbId.isNotBlank() -> "nuvio://imdb/$kind/${film.imdbId}"
            film.id > 0 -> "nuvio://tmdb/$kind/${film.id}"
            else -> {
                Glass.toast(activity, "Bu yapımın kimliği bilinmiyor")
                return
            }
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)).setClassName(activity, NUVIO_SCREEN))
        } catch (e: Exception) {
            Glass.toast(activity, "Nuvio açılamadı")
        }
    }

    /** Ayarlar › Medya kaynakları: Nuvio itself; its add-ons are under its own settings. */
    fun openSources(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_MAIN).setClassName(activity, NUVIO_SCREEN))
        } catch (e: Exception) {
            Glass.toast(activity, "Nuvio açılamadı")
        }
    }

    fun sourcesSummary(context: Context): String = "Nuvio"
}
