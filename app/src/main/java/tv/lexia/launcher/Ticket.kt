package tv.lexia.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The Akşam planı as a cinema ticket: the film (poster, name, "Bu akşam 21:00", "Salon: Oturma
 * odası") and, past a dotted tear line, the stub with the time. Shown when the plan is made; at
 * the time, "Filmi başlat" tears the stub off and the film starts.
 */
internal object Ticket {

    private val GOLD = Color.parseColor("#FFD36B")

    /** The ticket: its body and its stub (the part that tears off). */
    class Parts(val view: LinearLayout, val stub: View)

    fun build(a: Activity, ui: Ui, film: Pick, at: Long): Parts {
        val h = 280
        val row = ui.hbox()
        row.clipChildren = false
        row.clipToPadding = false

        val body = ui.hbox()
        body.gravity = Gravity.CENTER_VERTICAL
        body.background = Shape(ui, cutRight = true)
        body.setPadding(ui.u(30), ui.u(30), ui.u(36), ui.u(30))
        body.elevation = ui.uf(24)
        val poster = ImageView(a)
        poster.scaleType = ImageView.ScaleType.CENTER_CROP
        poster.background = ui.round(C.CARD, 12)
        poster.clipToOutline = true
        body.addView(poster, ui.ll(ui.u(146), ui.u(219), right = 30))
        Img.load(a, "w342", film.poster.ifEmpty { film.backdrop }, ui.u(146), poster)
        val col = ui.vbox()
        col.addView(ui.label("SİNEMA BİLETİ", GOLD))
        val name = ui.text(film.title, 36, C.TEXT, 800)
        name.maxLines = 2
        name.letterSpacing = -0.01f
        col.addView(name, ui.ll(ui.u(460), ui.wrap, top = 10))
        col.addView(ui.text(MovieNight.day(at), 24, C.TEXT, 600), ui.ll(ui.wrap, ui.wrap, top = 14))
        col.addView(ui.text("Salon: Oturma odası  ·  Sıra: Kanepe", 19, C.TEXT2, 500), ui.ll(ui.wrap, ui.wrap, top = 8))
        body.addView(col, ui.ll(ui.wrap, ui.wrap))
        row.addView(body, ui.ll(ui.wrap, ui.u(h)))

        val stub = ui.vbox()
        stub.gravity = Gravity.CENTER
        stub.background = Shape(ui, cutLeft = true, dotted = true)
        stub.setPadding(ui.u(34), ui.u(24), ui.u(30), ui.u(24))
        stub.elevation = ui.uf(24)
        stub.addView(ui.label("SAAT", GOLD))
        val time = ui.text(MovieNight.time(at), 46, C.TEXT, 800)
        stub.addView(time, ui.ll(ui.wrap, ui.wrap, top = 6))
        val brand = ui.text("LeXia", 17, C.TEXT2, 700)
        brand.letterSpacing = 0.2f
        stub.addView(brand, ui.ll(ui.wrap, ui.wrap, top = 10))
        row.addView(stub, ui.ll(ui.u(210), ui.u(h)))
        return Parts(row, stub)
    }

    /** The stub tears off (a small turn and a drop), then [then]. */
    fun tear(stub: View, then: () -> Unit) {
        if (Fx.lite) {
            then()
            return
        }
        stub.pivotX = 0f
        stub.pivotY = 0f
        stub.animate().rotation(16f).translationY(stub.height * 0.5f).translationX(stub.width * 0.15f).alpha(0f)
            .setDuration(650).setInterpolator(android.view.animation.AccelerateInterpolator(1.6f))
            .withEndAction(then).start()
    }

    /** Right after the plan is made: the ticket, "İyi seyirler", and Tamam. */
    fun show(a: Activity, film: Pick, at: Long) {
        val ui = Ui(a)
        val d = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = FrameLayout(a)
        val blur = if (Fx.lite) null else try { Fx.snapshotBlur(a.window.decorView, 16, 3) } catch (e: Exception) { null }
        root.background = if (blur != null) android.graphics.drawable.LayerDrawable(
            arrayOf(android.graphics.drawable.BitmapDrawable(a.resources, blur), ColorDrawable(Color.argb(140, 0, 0, 0)))
        ) else ColorDrawable(Color.argb(170, 0, 0, 0))
        val box = ui.vbox()
        box.gravity = Gravity.CENTER_HORIZONTAL
        box.clipChildren = false
        box.clipToPadding = false
        val parts = build(a, ui, film, at)
        box.addView(parts.view, ui.ll(ui.wrap, ui.wrap))
        box.addView(ui.text("Biletin hazır. Saat gelince ekranda seni bekliyor olacak.", 24, C.TEXT2, 500), ui.ll(ui.wrap, ui.wrap, top = 40))
        val ok = ui.pill("Tamam", 64, 23, primary = true)
        ok.setOnClickListener { d.dismiss() }
        box.addView(ok, ui.ll(ui.wrap, ui.u(64), top = 30))
        root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.setOnClickListener { d.dismiss() }
        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        d.show()
        if (!Fx.lite) {
            val t = parts.view
            t.alpha = 0f
            t.translationY = -ui.uf(60)
            t.rotation = -3f
            t.animate().alpha(1f).translationY(0f).rotation(0f).setDuration(700)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f)).start()
        }
        ok.requestFocus()
    }

    /**
     * A ticket piece: warm dark paper with a soft gold rim; half-round notches cut where it tears
     * (the body's right corners, the stub's left corners); the stub's tear line dotted.
     */
    private class Shape(
        private val ui: Ui,
        private val cutRight: Boolean = false,
        private val cutLeft: Boolean = false,
        private val dotted: Boolean = false
    ) : Drawable() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, ui.uf(2))
            color = Color.argb(90, 255, 211, 107)
        }
        private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1f, ui.uf(3))
            color = Color.argb(120, 255, 211, 107)
            pathEffect = DashPathEffect(floatArrayOf(ui.uf(6), ui.uf(9)), 0f)
        }
        private val path = Path()

        override fun onBoundsChange(b: android.graphics.Rect) {
            super.onBoundsChange(b)
            val r = RectF(b)
            r.inset(rim.strokeWidth / 2, rim.strokeWidth / 2)
            val corner = ui.uf(22)
            val notch = ui.uf(18)
            path.reset()
            path.addRoundRect(r, corner, corner, Path.Direction.CW)
            val cut = Path()
            if (cutRight) {
                cut.addCircle(r.right, r.top, notch, Path.Direction.CW)
                cut.addCircle(r.right, r.bottom, notch, Path.Direction.CW)
            }
            if (cutLeft) {
                cut.addCircle(r.left, r.top, notch, Path.Direction.CW)
                cut.addCircle(r.left, r.bottom, notch, Path.Direction.CW)
            }
            path.op(cut, Path.Op.DIFFERENCE)
            fill.shader = LinearGradient(
                r.left, r.top, r.right, r.bottom,
                Color.parseColor("#F2302519"), Color.parseColor("#F2181310"), Shader.TileMode.CLAMP
            )
        }

        override fun draw(canvas: Canvas) {
            canvas.drawPath(path, fill)
            canvas.drawPath(path, rim)
            if (dotted) {
                val b = bounds
                val x = b.left + ui.uf(2)
                canvas.drawLine(x, b.top + ui.uf(24), x, b.bottom - ui.uf(24), dots)
            }
        }

        override fun setAlpha(alpha: Int) {
            fill.alpha = alpha
            rim.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            fill.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
