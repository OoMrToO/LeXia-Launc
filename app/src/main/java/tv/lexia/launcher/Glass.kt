package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * LeXia's small messages ("Listeme eklendi", "Yedeklendi"): a frosted glass strip that rises at the
 * bottom of the screen and fades, instead of Android's grey box. Without a screen of ours to show
 * it on (a service, a closing screen), Android's own message is used.
 */
object Glass {
    private var shown: WeakReference<View>? = null

    fun toast(ctx: Context, text: CharSequence, long: Boolean = false) {
        val a = ctx as? Activity
        val root = a?.takeIf { !it.isFinishing && !it.isDestroyed }?.findViewById<ViewGroup>(android.R.id.content) as? FrameLayout
        if (a == null || root == null || !a.hasWindowFocus() && !root.isAttachedToWindow) {
            Toast.makeText(ctx, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            return
        }
        shown?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val ui = Ui(a)
        val strip = ui.hbox()
        strip.setPadding(ui.u(20), ui.u(14), ui.u(30), ui.u(14))
        strip.background = ui.round(Color.argb(222, 30, 30, 38), 40, 1, Color.argb(46, 255, 255, 255))
        strip.elevation = ui.uf(30)
        val dot = View(a)
        dot.background = ui.gradient(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            Color.parseColor("#FFB36B"), Color.parseColor("#FF4D8D"), Color.parseColor("#8A6CFF"), radius = 9
        )
        strip.addView(dot, ui.ll(ui.u(18), ui.u(18), right = 16))
        val t = ui.text(text, 23, C.TEXT, 600)
        t.maxWidth = ui.u(1300)
        t.maxLines = 2
        strip.addView(t)
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = ui.u(60)
        root.addView(strip, lp)
        shown = WeakReference(strip)
        strip.alpha = 0f
        strip.translationY = ui.uf(40)
        strip.animate().alpha(1f).translationY(0f).setDuration(D.MED).setInterpolator(DecelerateInterpolator(2f)).start()
        strip.postDelayed({
            strip.animate().alpha(0f).translationY(ui.uf(20)).setDuration(D.MED)
                .withEndAction { (strip.parent as? ViewGroup)?.removeView(strip) }.start()
        }, if (long) 3600L else 2200L)
    }
}
