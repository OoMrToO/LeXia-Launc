package tv.lexia.launcher

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/**
 * Where a title was left, on its poster: a thin bar showing how much of the film (or of the
 * episode last played) is watched, and for a series the episode ("S2 · B5"). Read from the
 * player's record ([MediaPlaybackStore]): the same one the "Devam et" button uses.
 */
internal object WatchMarks {

    class State(
        /** How much of the film or episode is watched, 0..1; null when it was finished or never timed. */
        val fraction: Float?,
        /** "S2 · B5" for a series, the episode last played (or the next one after a finished one). */
        val episode: String?,
        /** The last thing played was watched to the end. */
        val finished: Boolean,
        /** When it was last played (epoch ms). */
        val at: Long
    )

    private val EPISODE = Regex(":(\\d+):(\\d+)$")

    /** Where [p] was left, or null if it was never played in LeXia. */
    fun of(store: MediaPlaybackStore, p: Pick): State? {
        val last = store.last(p.key)
        if (last.isBlank()) return null
        val finished = store.isFinished(last)
        val fraction = store.progress(last)?.takeIf { it > 0.01f && it < 0.995f }
        if (fraction == null && !finished) return null
        val m = EPISODE.find(last)
        val episode = if (p.isSeries && m != null) {
            val s = m.groupValues[1].toInt()
            val e = m.groupValues[2].toInt()
            if (finished && fraction == null) "S$s · B${e + 1}" else "S$s · B$e"
        } else null
        return State(fraction, episode, finished && fraction == null, store.updatedAt(last))
    }

    /**
     * The bar (and the episode) at the bottom of [card], a poster [widthDp] wide. [aboveStrip]
     * lifts them over a strip already there ("Yeni bölüm çıktı"). Nothing for a finished film.
     */
    fun add(ui: Ui, card: FrameLayout, state: State?, aboveStrip: Boolean = false) {
        state ?: return
        val lift = if (aboveStrip) ui.u(30) else 0
        if (state.episode != null) {
            val label = ui.text(if (state.fraction == null) "Sırada  ${state.episode}" else state.episode, 13, C.TEXT, 700)
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.background = ui.round(Color.argb(215, 10, 12, 18), 8)
            label.setPadding(ui.u(8), ui.u(3), ui.u(8), ui.u(3))
            val lp = ui.fl(ui.wrap, ui.wrap, Gravity.BOTTOM or Gravity.START)
            lp.setMargins(ui.u(10), 0, ui.u(10), lift + ui.u(if (state.fraction != null) 18 else 10))
            card.addView(label, lp)
        }
        val fraction = state.fraction ?: return
        // A dark track the width of the poster, the watched part filled in LeXia's blue.
        val track = FrameLayout(card.context)
        track.background = ui.round(Color.argb(150, 0, 0, 0), 3)
        val fill = View(card.context)
        fill.background = ui.round(Color.parseColor("#5C68F6"), 3)
        track.addView(fill, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        track.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val w = ((r - l) * fraction).toInt().coerceAtLeast(ui.u(6))
            if (fill.layoutParams.width != w) {
                fill.layoutParams = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.MATCH_PARENT)
            }
        }
        val lp = ui.fl(ui.match, ui.u(5), Gravity.BOTTOM)
        lp.setMargins(ui.u(10), 0, ui.u(10), lift + ui.u(8))
        card.addView(track, lp)
    }
}
