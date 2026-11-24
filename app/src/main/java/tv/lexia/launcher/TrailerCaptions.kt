package tv.lexia.launcher

import android.graphics.Color
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/**
 * How a trailer's subtitles look, on the trailer page and on the home screen's full-screen
 * trailer: a little smaller than the player's default, white with a soft shadow instead of a hard
 * outline, as on Apple TV.
 */
internal object TrailerCaptions {
    fun style(v: SubtitleView, ui: Ui) {
        v.setApplyEmbeddedStyles(false)
        v.setApplyEmbeddedFontSizes(false)
        v.setStyle(
            CaptionStyleCompat(
                Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.argb(210, 0, 0, 0), ui.font(600)
            )
        )
        v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 0.8f)
    }
}
