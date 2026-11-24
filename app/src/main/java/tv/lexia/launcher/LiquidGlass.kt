package tv.lexia.launcher

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.DecelerateInterpolator

/**
 * LeXia's glass, after Apple TV's Liquid Glass: a translucent plate with a soft sheen at its top
 * and a thin rim of light that is bright on one side and fades round the edge. When the remote
 * moves, the light glides a little along the rim ([sweep]). [fill] 0 draws only the sheen and the
 * rim, to lay over a panel that has its own colour. Still on weak TVs (Fx.lite).
 *
 * [radii]: the eight corner radii in pixels (as GradientDrawable's), or null for [radius] all round.
 */
class LiquidGlass(
    private val fill: Int,
    private val radius: Float,
    private val rim: Float,
    private val radii: FloatArray? = null,
    /** How strong the light is (1 for panels and windows, less for small buttons). */
    private val strength: Float = 1f
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = rim
    }
    private val path = Path()
    private val box = RectF()
    private var alphaAll = 255

    /** Where the light is round the rim: 0..1 is a whole turn (it starts at the top left). */
    var light = 0.62f
        set(v) {
            field = v
            shade()
            invalidateSelf()
        }

    private var glide: ValueAnimator? = null

    /** The light glides along the rim: forward ([dir] > 0) or back. */
    fun sweep(dir: Int = 1) {
        if (Fx.lite) return
        glide?.cancel()
        val from = light
        glide = ValueAnimator.ofFloat(from, from + 0.08f * (if (dir < 0) -1 else 1)).apply {
            duration = 700
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { light = it.animatedValue as Float }
            start()
        }
    }

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        box.set(bounds)
        box.inset(rim / 2f, rim / 2f)
        path.reset()
        if (radii != null) path.addRoundRect(box, radii, Path.Direction.CW)
        else path.addRoundRect(box, radius, radius, Path.Direction.CW)
        sheenPaint.shader = LinearGradient(
            0f, box.top, 0f, box.top + box.height() * 0.45f,
            Color.argb((34 * strength).toInt(), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        shade()
    }

    /** The rim's light: bright where [light] points, dim on the far side, a faint second glint opposite. */
    private fun shade() {
        if (box.isEmpty) return
        val a = (light * 2 * Math.PI).toFloat()
        val cx = box.centerX()
        val cy = box.centerY()
        val r = Math.hypot(box.width().toDouble(), box.height().toDouble()).toFloat() / 2f
        val dx = -kotlin.math.cos(a) * r
        val dy = -kotlin.math.sin(a) * r
        fun white(alpha: Float) = Color.argb((alpha * strength).toInt().coerceIn(0, 255), 255, 255, 255)
        rimPaint.shader = LinearGradient(
            cx + dx, cy + dy, cx - dx, cy - dy,
            intArrayOf(white(170f), white(40f), white(12f), white(70f)),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (box.isEmpty) return
        if (Color.alpha(fill) > 0) canvas.drawPath(path, fillPaint)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(box, sheenPaint)
        canvas.restore()
        canvas.drawPath(path, rimPaint)
    }

    override fun getAlpha() = alphaAll

    override fun setAlpha(alpha: Int) {
        alphaAll = alpha
        fillPaint.alpha = Color.alpha(fill) * alpha / 255
        sheenPaint.alpha = alpha
        rimPaint.alpha = alpha
        shade()
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
