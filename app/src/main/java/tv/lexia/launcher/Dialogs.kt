package tv.lexia.launcher

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Small TV-friendly input dialogs. */
object Dialogs {
    /** Asks for one line of text, on the LeXia glass window; OK on the keyboard confirms. */
    fun input(
        a: Activity, title: String, start: String, numeric: Boolean = false,
        subtitle: String = "", hint: String = "", done: (String) -> Unit
    ) {
        val (d, ui, card) = sheet(a, 0, title, subtitle)
        val e = field(a, ui, start, hint, numeric, secret = numeric)
        card.addView(e, ui.ll(ui.match, ui.wrap, left = 24, right = 24, top = 30))
        val ok = {
            d.dismiss()
            done(e.text.toString().trim())
        }
        bar(ui, card, "Tamam" to ok, "Vazgeç" to { d.dismiss() })
        e.setOnEditorActionListener { _, id, _ ->
            if (id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { ok(); true } else false
        }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        show(d, card, e)
    }

    /** A text box on dark glass: a white edge while the remote is on it. */
    fun field(a: Activity, ui: Ui, start: String = "", hint: String = "", numeric: Boolean = false, secret: Boolean = false): EditText {
        val e = EditText(a)
        e.setText(start)
        e.setSelection(start.length)
        e.isSingleLine = true
        e.hint = hint
        e.setTextColor(C.TEXT)
        e.setHintTextColor(C.TEXT3)
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.uf(28))
        e.typeface = ui.font(500)
        e.setPadding(ui.u(28), ui.u(20), ui.u(28), ui.u(20))
        e.inputType = when {
            numeric && secret -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            numeric -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        e.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        val idle = ui.round(Color.argb(26, 255, 255, 255), 18, 1, Color.argb(40, 255, 255, 255))
        val on = ui.round(Color.argb(40, 255, 255, 255), 18, 2, Color.WHITE)
        e.background = idle
        e.setOnFocusChangeListener { _, f -> e.background = if (f) on else idle }
        return e
    }

    /**
     * A message on the LeXia glass window with one or two buttons (the first is the main one).
     * Back or a press outside counts as [cancel]. Long texts scroll.
     */
    fun message(
        a: Activity, title: String, text: String, primary: String = "Tamam", secondary: String? = null,
        cancel: () -> Unit = {}, onSecondary: () -> Unit = cancel, onPrimary: () -> Unit = {}
    ) {
        val (d, ui, card) = sheet(a, 0, title, "", wide = text.length > 400)
        var chose = false
        d.setOnDismissListener { if (!chose) cancel() }
        val long = text.length > 600
        val t = ui.text(text, if (long) 19 else 23, C.TEXT2, 400)
        t.setLineSpacing(0f, 1.25f)
        if (long) {
            val scroll = ScrollView(a)
            scroll.isVerticalScrollBarEnabled = false
            // Focusable, so the arrows scroll the text before they move on to the buttons.
            scroll.isFocusable = true
            scroll.addView(t)
            card.addView(scroll, ui.ll(ui.match, ui.u(520), left = 24, right = 24, top = 20))
        } else {
            card.addView(t, ui.ll(ui.match, ui.wrap, left = 24, right = 24, top = 20))
        }
        val buttons = ArrayList<Pair<String, () -> Unit>>()
        buttons.add(primary to { chose = true; d.dismiss(); onPrimary() })
        if (secondary != null) buttons.add(secondary to { chose = true; d.dismiss(); onSecondary() })
        val first = bar(ui, card, *buttons.toTypedArray())
        show(d, card, first)
    }

    /**
     * A window with fields or rows of its own: [build] fills the content and returns what the
     * remote starts on; [ok] runs on the main button and says whether the window may close.
     */
    fun form(
        a: Activity, title: String, subtitle: String, okLabel: String, wide: Boolean = false,
        build: (Ui, LinearLayout) -> View?, ok: () -> Boolean
    ) {
        val (d, ui, card) = sheet(a, 0, title, subtitle, wide)
        val content = ui.vbox()
        content.clipChildren = false
        content.clipToPadding = false
        val focus = build(ui, content)
        card.addView(content, ui.ll(ui.match, ui.wrap, left = 24, right = 24, top = 24))
        bar(ui, card, okLabel to { if (ok()) d.dismiss() }, "Vazgeç" to { d.dismiss() })
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        show(d, card, focus)
    }

    /** The window's buttons in a row, the first one the main one; returns the first. */
    private fun bar(ui: Ui, card: LinearLayout, vararg buttons: Pair<String, () -> Unit>): View? {
        val row = ui.hbox()
        var first: View? = null
        for ((i, b) in buttons.withIndex()) {
            val p = ui.pill(b.first, 64, 23, primary = i == 0)
            p.setOnClickListener { b.second() }
            row.addView(p)
            if (i == 0) first = p
        }
        (row.getChildAt(row.childCount - 1)?.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = 0
        card.addView(row, ui.ll(ui.wrap, ui.wrap, left = 24, top = 34))
        return first
    }

    /** Picks one item from a list. */
    fun choose(a: Activity, title: String, items: List<String>, done: (Int) -> Unit) =
        list(a, title, items.map { it to "" }, done = done)

    /** Picks one item from a list, with the current one marked and selected. */
    fun chooseOne(a: Activity, title: String, items: List<String>, current: Int, done: (Int) -> Unit) =
        list(a, title, items.map { it to "" }, current = current, done = done)

    fun confirm(a: Activity, title: String, message: String, done: () -> Unit) {
        val (d, ui, card) = sheet(a, 0, title, message)
        val bar = ui.hbox()
        val yes = ui.pill("Evet", 64, 23, primary = true)
        val no = ui.pill("Vazgeç", 64, 23)
        yes.setOnClickListener { d.dismiss(); done() }
        no.setOnClickListener { d.dismiss() }
        bar.addView(yes)
        bar.addView(no)
        (no.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = 0
        card.addView(bar, ui.ll(ui.wrap, ui.wrap, left = 24, top = 34))
        show(d, card, yes)
    }

    /**
     * The LeXia list window: an optional icon and a title, a quiet line under it, then one row per
     * choice with an optional value on the right (e.g. "7 film"). The selected row is a white plate
     * with dark text; the current choice carries a check. Back closes it.
     */
    fun list(
        a: Activity, title: String, rows: List<Pair<String, String>>, subtitle: String = "",
        icon: Int = 0, current: Int = -1, cancel: () -> Unit = {}, done: (Int) -> Unit
    ) {
        val (d, ui, card) = sheet(a, icon, title, subtitle)
        var picked = false
        d.setOnDismissListener { if (!picked) cancel() }
        val box = ui.vbox()
        box.clipChildren = false
        box.clipToPadding = false
        box.setPadding(ui.u(14), ui.u(6), ui.u(14), ui.u(6))
        var focus: View? = null
        for ((i, r) in rows.withIndex()) {
            val row = ui.hbox()
            row.isFocusable = true
            // A mouse selects the line it points at, like the arrow keys.
            row.setOnHoverListener { h, e ->
                if (e.action == android.view.MotionEvent.ACTION_HOVER_ENTER && !h.isFocused) h.requestFocusFromTouch()
                false
            }
            row.isClickable = true
            row.setPadding(ui.u(26), 0, ui.u(26), 0)
            val t = ui.text(r.first, 27, C.TEXT, 500)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            row.addView(t, ui.ll(0, ui.wrap, weight = 1f))
            val v = ui.text(r.second, 23, C.TEXT3, 500)
            if (r.second.isNotEmpty()) row.addView(v, ui.ll(ui.wrap, ui.wrap, left = 20))
            val check = ImageView(a)
            check.setImageResource(R.drawable.ic_check)
            check.setColorFilter(C.TEXT, PorterDuff.Mode.SRC_IN)
            if (i == current) row.addView(check, ui.ll(ui.u(28), ui.u(28), left = 20))
            row.setOnFocusChangeListener { _, f ->
                row.background = if (f) ui.round(Color.WHITE, D.PLATE) else null
                t.setTextColor(if (f) C.INK else C.TEXT)
                t.typeface = ui.font(if (f) 600 else 500)
                v.setTextColor(if (f) Color.argb(153, 11, 11, 13) else C.TEXT3)
                check.setColorFilter(if (f) C.INK else C.TEXT, PorterDuff.Mode.SRC_IN)
                ui.lift(row, f, 1.025f, 10)
            }
            row.setOnClickListener {
                picked = true
                d.dismiss()
                done(i)
            }
            box.addView(row, ui.ll(ui.match, ui.u(76), bottom = 6))
            if (i == current || (focus == null && current !in rows.indices)) focus = row
        }
        // Long lists scroll; the window never grows past the screen.
        val scroll = ScrollView(a)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.addView(box)
        val h = minOf(rows.size * 82 + 12, 660)
        card.addView(scroll, ui.ll(ui.match, ui.u(h), left = -14, right = -14, top = 20))
        show(d, card, focus)
    }

    /** A full-screen window with the dark glass card in the middle and its heading. */
    private fun sheet(a: Activity, icon: Int, title: String, subtitle: String, wide: Boolean = false): Triple<Dialog, Ui, LinearLayout> {
        val ui = Ui(a)
        val d = Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val root = FrameLayout(a)
        root.setBackgroundColor(Color.argb(150, 0, 0, 0))
        root.setOnClickListener { d.dismiss() }
        val card = ui.vbox()
        card.isClickable = true
        card.clipChildren = false
        card.clipToPadding = false
        card.background = ui.round(Color.parseColor("#FA1C1C21"), D.PANEL, 1, Color.argb(30, 255, 255, 255))
        card.elevation = ui.uf(40)
        card.setPadding(ui.u(50), ui.u(56), ui.u(50), ui.u(46))
        val head = ui.hbox()
        if (icon != 0) {
            val i = ImageView(a)
            i.setImageResource(icon)
            i.setColorFilter(C.TEXT, PorterDuff.Mode.SRC_IN)
            head.addView(i, ui.ll(ui.u(40), ui.u(40), right = 18))
        }
        head.addView(ui.text(title, 42, C.TEXT, 700))
        card.addView(head, ui.ll(ui.wrap, ui.wrap, left = 24))
        if (subtitle.isNotEmpty()) {
            val st = ui.text(subtitle, 21, C.TEXT3, 400)
            st.setLineSpacing(0f, 1.2f)
            card.addView(st, ui.ll(ui.match, ui.wrap, left = 24, right = 24, top = 12))
        }
        root.addView(card, FrameLayout.LayoutParams(ui.u(if (wide) 1100 else 760), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        // The navigation tick, like everywhere else.
        val sound = Prefs(a).navSound
        root.viewTreeObserver.addOnGlobalFocusChangeListener { old, new -> if (sound && old != null && new != null) Sound.play() }
        return Triple(d, ui, card)
    }

    private fun show(d: Dialog, card: View, focus: View?) {
        d.show()
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(D.MED).start()
        focus?.requestFocus()
    }

    /** Runs [ok] right away if there is no PIN, otherwise after the right PIN. */
    fun pin(a: Activity, ok: () -> Unit, cancel: () -> Unit = {}) {
        val pin = Prefs(a).pin
        if (pin.isEmpty()) {
            ok()
            return
        }
        val (d, ui, card) = sheet(a, R.drawable.ic_lock, "PIN girin", "")
        val e = field(a, ui, "", "PIN", numeric = true, secret = true)
        card.addView(e, ui.ll(ui.match, ui.wrap, left = 24, right = 24, top = 30))
        var done = false
        d.setOnDismissListener { if (!done) cancel() }
        val check = {
            done = true
            d.dismiss()
            if (e.text.toString() == pin) ok() else {
                Glass.toast(a, "PIN yanlış")
                cancel()
            }
        }
        bar(ui, card, "Tamam" to check, "Vazgeç" to { d.dismiss() })
        e.setOnEditorActionListener { _, id, _ ->
            if (id == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { check(); true } else false
        }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        show(d, card, e)
    }
}

/** Set by settings to ask the home screen to drop its cached pictures. */
object Cache {
    @Volatile var clearRequested = false
}

/** Set by settings to start the screensaver as soon as the home screen shows. */
object Saver {
    @Volatile var previewRequested = false
}

/** Genres the home screen's "Sana göre" rows show right now, for their names in the settings. */
object GenreRows {
    @Volatile var names: List<String> = emptyList()
}
