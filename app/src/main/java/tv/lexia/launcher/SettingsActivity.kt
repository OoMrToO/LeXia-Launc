package tv.lexia.launcher

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

/** tvOS style settings: big icon and description on the left, list on the right. */
class SettingsActivity : Activity() {

    private abstract class Item
    private class Header(val text: String) : Item()
    private class Note(val text: String, val code: Boolean = false) : Item()
    private class Row(
        val title: String,
        val desc: String = "",
        val icon: Drawable? = null,
        val chevron: Boolean = false,
        val value: () -> String = { "" },
        val action: (() -> Unit)? = null,
        /** Named choices; left/right steps through them, OK opens the list (or flips two). */
        val choices: List<String>? = null,
        val selected: () -> Int = { 0 },
        val pick: (Int) -> Unit = {}
    ) : Item()

    /**
     * One settings page. [keys] are the settings "Varsayılana döndür" puts back; [preview] is
     * "saver" or "home" for a live picture on the left instead of the big icon.
     */
    private class Page(
        val title: String,
        val iconRes: Int,
        val keys: List<String> = emptyList(),
        val preview: String = "",
        val items: () -> List<Item>
    )

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var bigIcon: ImageView
    private lateinit var pageTitle: TextView
    private lateinit var descText: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    /** The list with the white plate behind it, which glides from row to row. */
    private lateinit var listBox: FrameLayout
    private lateinit var plate: View
    private lateinit var rootView: LinearLayout
    private var baseBackground: android.graphics.drawable.Drawable? = null
    private lateinit var previewBox: FrameLayout
    private lateinit var preview: Preview

    private var updateState = ""
    private val stack = ArrayList<Page>()
    private val returnFocus = ArrayList<Int>()
    private val rows = ArrayList<View>()
    private val refreshers = ArrayList<() -> Unit>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)

        val root = ui.hbox()
        root.background = ui.gradient(
            GradientDrawable.Orientation.TL_BR,
            Color.parseColor("#22262E"), Color.parseColor("#0E1014"), Color.BLACK
        )

        val left = ui.vbox()
        left.gravity = Gravity.CENTER
        left.setPadding(ui.u(80), 0, ui.u(40), 0)
        bigIcon = ImageView(this)
        bigIcon.setColorFilter(Color.argb(235, 255, 255, 255), PorterDuff.Mode.SRC_IN)
        left.addView(bigIcon, ui.ll(ui.u(280), ui.u(280)))
        previewBox = FrameLayout(this)
        previewBox.visibility = View.GONE
        left.addView(previewBox, ui.ll(ui.u(Preview.W), ui.u(Preview.H)))
        preview = Preview(this, ui, prefs)
        pageTitle = ui.text("", 46, C.TEXT, 600)
        pageTitle.gravity = Gravity.CENTER
        left.addView(pageTitle, ui.ll(ui.wrap, ui.wrap, top = 44))
        descText = ui.text("", 23, C.TEXT2, 400)
        descText.gravity = Gravity.CENTER
        descText.maxWidth = ui.u(560)
        descText.setLineSpacing(0f, 1.2f)
        descText.visibility = View.GONE
        left.addView(descText, ui.ll(ui.wrap, ui.wrap, top = 22))
        root.addView(left, ui.ll(0, ui.match, weight = 0.42f))

        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        scroll.clipChildren = false
        scroll.isSmoothScrollingEnabled = true
        list = ui.vbox()
        list.clipChildren = false
        list.clipToPadding = false
        list.setPadding(ui.u(24), ui.u(120), ui.u(110), ui.u(200))
        listBox = FrameLayout(this)
        listBox.clipChildren = false
        listBox.clipToPadding = false
        plate = View(this)
        plate.background = ui.round(Color.WHITE, 22)
        plate.alpha = 0f
        plate.elevation = ui.uf(8)
        listBox.addView(plate, FrameLayout.LayoutParams(0, 0))
        listBox.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        list.elevation = ui.uf(9)
        scroll.addView(listBox)
        root.addView(scroll, ui.ll(0, ui.match, weight = 0.58f))

        // Frosted glass: the blurred home screen behind the settings.
        Fx.backdrop?.let {
            root.background = android.graphics.drawable.LayerDrawable(
                arrayOf(
                    android.graphics.drawable.BitmapDrawable(resources, it),
                    ui.gradient(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        Color.argb(205, 10, 11, 14), Color.argb(230, 6, 7, 9), Color.argb(242, 0, 0, 0)
                    )
                )
            )
        }
        rootView = root
        baseBackground = root.background
        setContentView(root)
        Sound.attach(this)
        Dialogs.pin(this, { push(mainPage()) }, { finish() })
    }

    // ---------- Navigation ----------

    private fun push(p: Page) {
        if (stack.isNotEmpty()) returnFocus.add(focusedRow())
        stack.add(p)
        if (stack.size == 1) render(0, true) else slideOut(1) { render(0, true) }
    }

    private fun focusedRow(): Int = rows.indexOfFirst { it.hasFocus() }.coerceAtLeast(0)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (stack.size > 1) {
            stack.removeAt(stack.size - 1)
            val back = returnFocus.removeAt(returnFocus.size - 1)
            slideOut(-1) { render(back, true) }
        } else {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun render(focus: Int, animate: Boolean) {
        val page = stack.last()
        tone(page)
        plate.animate().cancel()
        plate.alpha = 0f
        plateOn = null
        pageTitle.text = page.title
        bigIcon.setImageResource(page.iconRes)
        bigIcon.setColorFilter(Color.argb(235, 255, 255, 255), PorterDuff.Mode.SRC_IN)
        bigIcon.visibility = if (page.preview.isEmpty()) View.VISIBLE else View.GONE
        previewBox.visibility = if (page.preview.isEmpty()) View.GONE else View.VISIBLE
        showPreview()
        list.removeAllViews()
        rows.clear()
        refreshers.clear()
        val items = page.items().toMutableList()
        if (page.keys.isNotEmpty()) {
            items.add(Header("SIFIRLA"))
            items.add(
                Row(
                    "Bu sayfayı varsayılana döndür",
                    "Yalnızca bu sayfadaki ayarlar ilk hâline döner. Diğer sayfalar, Listem ve PIN olduğu gibi kalır.",
                    action = {
                        Dialogs.confirm(this, "${page.title} varsayılana dönsün mü?", "Bu sayfadaki ayarlar ilk hâline döner.") {
                            prefs.resetKeys(page.keys)
                            afterChange()
                            Glass.toast(this, "Varsayılana döndü")
                            render(focusedRow(), false)
                        }
                    }
                )
            )
        }
        for (item in items) {
            when (item) {
                is Header -> {
                    val t = ui.label(item.text, C.TEXT3)
                    list.addView(t, ui.ll(ui.wrap, ui.wrap, left = 30, top = if (list.childCount == 0) 0 else 40, bottom = 12))
                }
                is Note -> list.addView(noteView(item), ui.ll(ui.match, ui.wrap, bottom = 10))
                is Row -> list.addView(rowView(item, page), ui.ll(ui.match, ui.wrap, bottom = 6))
            }
        }
        refreshValues()
        val target = rows.getOrNull(focus.coerceIn(0, maxOf(0, rows.size - 1)))
        target?.requestFocus()
        if (animate) {
            if (focus == 0) scroll.scrollTo(0, 0)
            // Going deeper the new page comes in from the right; going back, from the left.
            val dist = if (Fx.lite) 30f else 160f
            for (v in listOf<View>(listBox, pageTitle)) {
                v.alpha = 0f
                v.translationX = ui.uf(dist) * slideDir
                v.animate().alpha(1f).translationX(0f).setDuration(D.MED + 60)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            }
            slideDir = 1
        }
    }

    /** +1 when a page opens (comes from the right), -1 when going back. */
    private var slideDir = 1

    /** The page on screen slides out the other way first, then [then] builds the next one. */
    private fun slideOut(dir: Int, then: () -> Unit) {
        if (Fx.lite || list.childCount == 0) {
            slideDir = dir
            then()
            return
        }
        for (v in listOf<View>(listBox, pageTitle)) v.animate().alpha(0f).translationX(-ui.uf(120) * dir).setDuration(140).start()
        list.postDelayed({
            slideDir = dir
            then()
        }, 140)
    }

    private fun refreshValues() {
        for (r in refreshers) r()
        showPreview()
    }

    /** Rebuilds the live picture on the left, if this page has one. */
    private fun showPreview() {
        val kind = stack.lastOrNull()?.preview ?: ""
        if (kind.isEmpty()) {
            preview.stop()
            previewBox.removeAllViews()
            return
        }
        previewBox.removeAllViews()
        previewBox.addView(if (kind == "saver") preview.saver() else preview.home(), ui.fl(ui.match, ui.match))
    }

    /** Settings that take effect outside this screen right away. */
    private fun afterChange() {
        Fx.lite = prefs.lite
        NightSleep.schedule(this)
    }

    private fun rowView(r: Row, page: Page): View {
        // The row: a line (title, value) and, while selected, its description just under it.
        val v = ui.vbox()
        v.setPadding(ui.u(30), 0, ui.u(30), 0)
        v.isFocusable = true
        v.isClickable = true
        // A mouse (or an air remote's pointer) selects the row it points at, like the arrow keys do.
        v.setOnHoverListener { h, e ->
            if (e.action == android.view.MotionEvent.ACTION_HOVER_ENTER && !h.isFocused) h.requestFocusFromTouch()
            false
        }
        val line = ui.hbox()
        if (r.icon != null) {
            val iv = ImageView(this)
            iv.setImageDrawable(r.icon)
            line.addView(iv, ui.ll(ui.u(48), ui.u(48), right = 22))
        }
        val title = ui.text(r.title, 28, C.TEXT, 500)
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        line.addView(title, ui.ll(0, ui.wrap, weight = 1f))
        val value = ui.text("", 26, C.TEXT2, 400)
        value.maxLines = 1
        line.addView(value, ui.ll(ui.wrap, ui.wrap, left = 20))
        // Three or four short choices show side by side while selected: "Yumuşak · Klasik · Kapalı".
        val seg = ui.hbox()
        seg.visibility = View.GONE
        seg.setPadding(ui.u(5), ui.u(5), ui.u(5), ui.u(5))
        seg.background = ui.round(Color.argb(20, 11, 11, 13), 30)
        line.addView(seg, ui.ll(ui.wrap, ui.wrap, left = 20))
        val chev = ui.text(if (r.chevron) "›" else "", 36, C.TEXT3, 400)
        line.addView(chev, ui.ll(ui.wrap, ui.wrap, left = if (r.chevron) 18 else 0))
        v.addView(line, ui.ll(ui.match, ui.u(88)))
        val desc = ui.text(r.desc, 20, Color.argb(160, 11, 11, 13), 400)
        desc.setLineSpacing(0f, 1.25f)
        desc.visibility = View.GONE
        v.addView(desc, ui.ll(ui.match, ui.wrap, bottom = 24, top = -6))

        val segFits = r.choices?.let { c -> c.size in 3..4 && c.sumOf { it.length } <= 34 } == true
        var focused = false
        // On the white plate the text must be dark and strong enough to read from the sofa.
        val inkSoft = Color.argb(190, 11, 11, 13)
        fun buildSeg() {
            seg.removeAllViews()
            val c = r.choices ?: return
            val on = r.selected()
            for ((i, n) in c.withIndex()) {
                val t = ui.text(n, 22, if (i == on) Color.WHITE else Color.argb(115, 11, 11, 13), 600)
                t.setPadding(ui.u(20), ui.u(8), ui.u(20), ui.u(8))
                if (i == on) t.background = ui.round(C.INK, 24)
                seg.addView(t)
            }
        }
        fun show() {
            val s = r.value()
            if (focused && segFits) {
                value.visibility = View.GONE
                seg.visibility = View.VISIBLE
                buildSeg()
                return
            }
            seg.visibility = View.GONE
            value.visibility = View.VISIBLE
            // Choice rows show their arrows only while selected, like tvOS.
            value.text = if (focused && r.choices != null && s.isNotEmpty()) "‹   $s   ›" else s
        }
        fun style() {
            v.background = null
            title.setTextColor(if (focused) C.INK else C.TEXT)
            title.typeface = ui.font(if (focused) 700 else 500)
            value.setTextColor(if (focused) C.INK else C.TEXT2)
            value.typeface = ui.font(if (focused) 600 else 400)
            chev.setTextColor(if (focused) inkSoft else C.TEXT3)
            desc.visibility = if (focused && r.desc.isNotEmpty()) View.VISIBLE else View.GONE
            show()
        }
        style()
        v.setOnFocusChangeListener { _, f ->
            focused = f
            style()
            if (f) {
                glideTo(v)
                if (r.icon != null) {
                    bigIcon.clearColorFilter()
                    bigIcon.setImageDrawable(r.icon)
                } else {
                    bigIcon.setImageResource(page.iconRes)
                    bigIcon.setColorFilter(Color.argb(235, 255, 255, 255), PorterDuff.Mode.SRC_IN)
                }
            } else if (plateOn === v) {
                // Focus left the list rows (a note, a dialog): the plate fades until a row takes it.
                v.post { if (plateOn === v && !v.hasFocus()) plate.animate().alpha(0f).setDuration(D.FAST).start() }
            }
        }
        val choices = r.choices
        fun choose(i: Int) {
            if (choices == null || i == r.selected()) return
            r.pick(i)
            afterChange()
            refreshValues()
            // The new value rolls in from below, like the digits of the home screen clock.
            val target = if (seg.visibility == View.VISIBLE) seg else value
            target.translationY = ui.uf(22)
            target.alpha = 0f
            target.animate().translationY(0f).alpha(1f).setDuration(D.MED)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
        v.setOnClickListener {
            if (choices != null) {
                if (choices.size == 2) {
                    choose(1 - r.selected().coerceIn(0, 1))
                } else {
                    Dialogs.chooseOne(this, r.title, choices, r.selected()) { choose(it) }
                }
            } else {
                r.action?.invoke()
                afterChange()
                refreshValues()
            }
        }
        if (choices != null) {
            v.setOnKeyListener { _, code, e ->
                if (e.action != android.view.KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val d = when (code) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                    else -> return@setOnKeyListener false
                }
                val next = r.selected() + d
                if (next in choices.indices) {
                    choose(next)
                    Sound.play()
                } else {
                    // Already at the end: a small nudge instead of nothing.
                    v.animate().translationX(ui.uf(8 * d)).setDuration(70).withEndAction {
                        v.animate().translationX(0f).setDuration(120).start()
                    }.start()
                }
                true
            }
        }
        refreshers.add { show() }
        rows.add(v)
        return v
    }

    /** The row the plate sits behind. */
    private var plateOn: View? = null

    /** The white plate glides to [row] and takes its size (it grows when the description opens). */
    private fun glideTo(row: View) {
        row.post {
            if (!row.isAttachedToWindow) return@post
            val lp = plate.layoutParams as FrameLayout.LayoutParams
            val x = row.left.toFloat()
            val y = row.top.toFloat()
            val first = plate.alpha < 0.05f || plateOn == null || Fx.lite
            plateOn = row
            if (lp.width != row.width || lp.height != row.height) {
                if (first) {
                    lp.width = row.width
                    lp.height = row.height
                    plate.layoutParams = lp
                } else {
                    val w0 = lp.width
                    val h0 = lp.height
                    val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
                    a.duration = 220
                    a.interpolator = android.view.animation.DecelerateInterpolator(2f)
                    a.addUpdateListener {
                        val t = it.animatedValue as Float
                        lp.width = (w0 + (row.width - w0) * t).toInt()
                        lp.height = (h0 + (row.height - h0) * t).toInt()
                        plate.layoutParams = lp
                    }
                    a.start()
                }
            }
            plate.animate().cancel()
            if (first) {
                plate.translationX = x
                plate.translationY = y
                plate.animate().alpha(1f).setDuration(D.FAST).start()
            } else {
                plate.animate().translationX(x).translationY(y).alpha(1f).setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            }
        }
    }

    /** Each page a faint light of its own (Görünüm violet, Filmler red...); the main page none. */
    private fun tone(page: Page) {
        val c = when {
            page.title.startsWith("Görünüm") -> Color.parseColor("#8A6CFF")
            page.title.startsWith("Filmler") -> Color.parseColor("#E5484D")
            page.title.startsWith("Platformlar") -> Color.parseColor("#00A8E1")
            page.title.startsWith("Uygulamalar") -> Color.parseColor("#F5A524")
            page.title.startsWith("Ekran koruyucu") -> Color.parseColor("#3B5BDB")
            page.title.startsWith("Güvenlik") -> Color.parseColor("#34C759")
            page.title.startsWith("Ana ekran satırları") -> Color.parseColor("#FF4D8D")
            else -> 0
        }
        val base = baseBackground ?: return
        if (c == 0) {
            rootView.background = base
            return
        }
        val g = GradientDrawable()
        g.gradientType = GradientDrawable.RADIAL_GRADIENT
        g.colors = intArrayOf(Color.argb(70, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(0, Color.red(c), Color.green(c), Color.blue(c)))
        g.gradientRadius = resources.displayMetrics.widthPixels * 0.55f
        g.setGradientCenter(0.18f, 0.5f)
        rootView.background = android.graphics.drawable.LayerDrawable(arrayOf(base, g))
    }

    private fun noteView(n: Note): View {
        val t = ui.text(n.text, if (n.code) 21 else 23, if (n.code) C.TEXT else C.TEXT2, 400)
        t.setLineSpacing(0f, 1.25f)
        t.setPadding(ui.u(30), ui.u(22), ui.u(30), ui.u(22))
        if (n.code) {
            t.typeface = Typeface.MONOSPACE
            t.background = ui.round(Color.argb(40, 255, 255, 255), 16)
        }
        // Focusable so the remote can scroll through long help text.
        t.isFocusable = true
        val idle = t.background
        t.setOnFocusChangeListener { _, f ->
            t.background = if (f) ui.round(Color.argb(if (n.code) 60 else 28, 255, 255, 255), 16, 2, C.TEXT3) else idle
            if (f) setDesc("")
        }
        rows.add(t)
        return t
    }

    private fun setDesc(s: String) {
        if (descText.visibility != View.VISIBLE) return
        if (descText.text.toString() == s) return
        descText.animate().cancel()
        descText.alpha = 0f
        descText.text = s
        descText.animate().alpha(1f).setDuration(D.FAST).start()
    }

    // ---------- Pages ----------

    private fun onOff(v: Boolean) = if (v) "Açık" else "Kapalı"


    /** An on/off row: left/right or OK switches it. */
    private fun toggle(title: String, desc: String, get: () -> Boolean, set: (Boolean) -> Unit) = Row(
        title, desc,
        value = { onOff(get()) },
        choices = listOf("Kapalı", "Açık"),
        selected = { if (get()) 1 else 0 },
        pick = { set(it == 1) }
    )

    /** A row with named values: left/right steps, OK opens the list. */
    private fun <T> choice(title: String, desc: String, values: List<T>, names: List<String>, get: () -> T, set: (T) -> Unit) = Row(
        title, desc,
        value = { names.getOrElse(values.indexOf(get())) { names.first() } },
        choices = names,
        selected = { values.indexOf(get()).coerceAtLeast(0) },
        pick = { set(values[it]) }
    )

    private fun sub(title: String, desc: String, page: () -> Page, value: () -> String = { "" }) =
        Row(title, desc, chevron = true, value = value, action = { push(page()) })

    private fun mainPage() = Page("Ayarlar", R.drawable.ic_gear) {
        listOf(
            Header("ANA EKRAN"),
            Row(
                "Varsayılan launcher yap",
                "TV açıldığında ve Ana Sayfa tuşuna basıldığında bu ekran gelsin.",
                value = { if (isDefault()) "Varsayılan" else "Ayarla" },
                action = { makeDefault() }
            ),
            Row(
                "Ana Sayfa tuşunu yönlendir",
                "Google TV, Ana Sayfa tuşunu kendi ekranına bağlar. Bir kez açınca tuş LeXia'yı açar; " +
                    "adım adım gösterir.",
                value = { onOff(HomeButtonService.isOn(this)) },
                action = { if (BuildConfig.SETUP) openAccessibility() else HomeGuide.open(this) }
            ),
            sub("Google TV için kurulum", "TV izin vermezse bilgisayardan bir kez yapılacak adımlar.", { helpPage() }),

            Header("KİŞİSELLEŞTİR"),
            sub("Görünüm", "Arka plan, kart boyutu, karşılama, saat ve hava durumu.", { lookPage() }),
            sub("İçerik ve Keşif", "Film önerileri, türler, yayın platformları ve ana ekran keşif satırları.", { discoveryPage() }),
            sub("Oynatma ve Takip", "Kaynak seçimi, ses ve altyazı dili, fragmanlar ve izleme takibi.", { playbackTrackingPage() }),
            sub("Filmler", "Günün filmleri, sevdiğin türler ve geçiş süresi.", { filmsPage() }),
            Row(
                "Medya kaynakları",
                "Stremio eklentileri ve Nuvio JS depoları: ekle, aç ya da kapat, dene, kaldır.",
                chevron = true,
                value = { Media.sourcesSummary(this) },
                action = { Media.openSources(this) }
            ),
            sub("Platformlar", "Netflix, Disney+ gibi hangi platformların yeni yapımları gelsin.", { platformsPage() },
                value = { prefs.platforms.size.let { if (it == 0) "Hepsi" else "$it seçili" } }),
            sub("Ana ekran satırları", "Top 10, sinema ve Listem satırlarının sırası; istemediğini gizle.", { rowsPage() }),
            sub("Uygulamalar", "Ana ekrana ekle ya da çıkar, yeri, sırası ve TV açılınca ne olacağı.", { appsPage() }),
            sub("Ekran koruyucu ve uyku", "Ne zaman başlasın, nasıl görünsün, uyku zamanlayıcısı ve gece uykusu.", { sleepPage() },
                value = { if (Sleep.minutesLeft > 0) "Uyku ${Sleep.endsText(prefs.clock24)}" else "" }),

            Header("SİSTEM"),
            sub("Güvenlik", "Ayarları ve uygulama silmeyi PIN ile kilitle.", { safetyPage() },
                value = { if (prefs.pin.isEmpty()) "" else "Kilitli" }),
            Row(
                "Güncellemeleri denetle",
                "Yüklü sürüm: #${Updater.current}. Yeni sürüm varsa indirip buradan kurar.",
                value = {
                    when {
                        updateState.isNotEmpty() -> updateState
                        Updater.available -> "Yeni sürüm #${Updater.latest}  ·  Kur"
                        else -> ""
                    }
                },
                action = { update() }
            ),
            toggle(
                "Otomatik güncelleme",
                "Yeni sürüm çıkınca kendiliğinden iner; sana yalnızca “Yükle”ye basmak kalır.",
                { prefs.autoUpdate }, { prefs.autoUpdate = it }
            ),
            sub("Bakım", "Önbelleği temizle ya da ayarları sıfırla.", { carePage() }),
            sub("Hakkında", "Sürüm ve film listesi bilgileri.", { aboutPage() })
        )
    }

    private fun discoveryPage() = Page("İçerik ve Keşif", R.drawable.ic_film,
        keys = listOf("picks", "rotate", "rotateSec", "cinema", "genres", "platforms", "heroSource", "rowOrder", "hiddenRows")
    ) {
        listOf(
            Header("KEŞİF"),
            sub("Film önerileri", "Günün filmleri, tür tercihleri ve fragman akışı.", { filmsPage() }),
            sub("Yayın platformları", "Hangi platformların yeni yapımlarını ana ekranda görmek istediğini seç.", { platformsPage() }),
            sub("Ana ekran satırları", "Keşif satırlarının sırasını değiştir veya istemediklerini gizle.", { rowsPage() }),
            Note("Tür tercihleri ve platform seçimleri öneri sıralamasını etkiler. Hiçbir tür veya platform seçilmezse katalog daha geniş tutulur.")
        )
    }

    private fun playbackTrackingPage() = Page(
        "Oynatma ve Takip", R.drawable.ic_tv,
        keys = listOf("playAutoSource", "playAudioLang", "playSubLang", "playSubSmart", "playSubSize", "trailerInApp", "trailerNative")
    ) {
        // With Nuvio inside, sources, sound and subtitles are set in Nuvio's own settings.
        (if (Media.NUVIO) emptyList<Item>() else listOf<Item>(
            Header("KAYNAK"),
            toggle(
                "En iyi kaynağı kendiliğinden aç",
                "Oynat'a basınca bu TV'ye en uygun kaynak sormadan başlar; bir dizide sonraki bölümler aynı kaynaktan devam eder. " +
                    "Başka bir kaynak seçmek için oynatıcıdan Geri ile çık: kaynak listesi açılır. Kapalıyken kaynağı her seferinde listeden seçersin.",
                { prefs.playAutoSource }, { prefs.playAutoSource = it }
            ),
            Header("SES VE ALTYAZI"),
            choice(
                "Ses dili",
                "Film birden çok dilde ses taşıyorsa hangisiyle başlasın. Orijinal: dosyanın ilk sesi. Oynatıcıdaki Ses düğmesiyle film sırasında değiştirebilirsin.",
                listOf("", "tr", "en", "orig"), listOf("TV'nin dili", "Türkçe", "İngilizce", "Orijinal"),
                { prefs.playAudioLang }, { prefs.playAudioLang = it }
            ),
            choice(
                "Altyazı dili",
                "Film bu dilde altyazı taşıyorsa kendiliğinden açılır. Oynatıcıdaki Altyazı düğmesiyle film sırasında değiştirebilirsin.",
                listOf("", "tr", "en", "off"), listOf("TV'nin dili", "Türkçe", "İngilizce", "Kapalı"),
                { prefs.playSubLang }, { prefs.playSubLang = it }
            ),
            toggle(
                "Ses aynı dildeyse altyazı açılmasın",
                "Örneğin Türkçe dublajlı bir filmde Türkçe altyazı kendiliğinden açılmaz. Kapalıyken altyazı her zaman açılır.",
                { prefs.playSubSmart }, { prefs.playSubSmart = it }
            ),
            choice(
                "Altyazı boyutu",
                "Oynatıcıdaki Altyazı ayarı düğmesinden de değişir; orada altyazının zamanlaması da düzeltilebilir.",
                listOf(0, 1, 2, 3), listOf("Küçük", "Normal", "Büyük", "Çok büyük"),
                { prefs.playSubSize }, { prefs.playSubSize = it }
            ),
            Note("Harfleri bozuk gelen Türkçe altyazılar (ş, ğ, ı yerine başka işaretler) oynatılırken kendiliğinden düzeltilir."),
        )) + listOf<Item>(
            Header("FRAGMANLAR"),
            toggle("Fragmanları LeXia içinde oynat", "Desteklenen YouTube fragmanları uygulamadan çıkmadan oynatılır.",
                { prefs.trailerInApp }, { prefs.trailerInApp = it }),
            toggle(
                "Fragmanlar YouTube çerçevesi olmadan oynasın",
                "Fragman doğrudan LeXia'nın oynatıcısında, YouTube'un yazıları ve önerileri olmadan oynar. " +
                    "YouTube bir şey değiştirirse bu yol çalışmayabilir; o zaman fragman kendiliğinden YouTube'un gömülü oynatıcısında açılır. " +
                    "Fragmanlarda sorun görürsen kapat.",
                { prefs.trailerNative }, { prefs.trailerNative = it }
            ),
            Header("İZLEME TAKİBİ"),
            Row("İzlenen içerikler", "Tamamlanan film ve bölümler izlenenler listesine kaydedilir.", value = { "${prefs.watched.size} içerik" }),
            Row("Listem", "Daha sonra izlemek üzere kaydettiğin içerikler.", value = { "${prefs.watchlist.size} içerik" }),
            Row("Son baktıkların", "Son açtığın film ve diziler.", value = { "${prefs.recent.size} içerik" }),
            Row("Hatırlatmalar", "Vizyon veya yayın tarihi için eklediğin hatırlatmalar.", value = { "${prefs.reminders.size} içerik" })
        )
    }
    private fun lookPage() = Page(
        "Görünüm", R.drawable.ic_brush,
        keys = listOf("bg", "card", "appShape", "heroSource", "greet", "clock24", "weather", "ambience", "navSound", "soundSet", "morningCard", "lite", "bigText", "glass"),
        preview = "home"
    ) {
        listOf(
            Header("EKRAN"),
            choice(
                "Arka plan", "Film görselleri ya da sade, koyu bir degrade.",
                listOf("film", "plain"), listOf("Film görselleri", "Sade"),
                { prefs.background }, { prefs.background = it }
            ),
            choice(
                "Ana ekrandaki filmler",
                "Günün filmleri: her gün seçilen 20 film. Sinemada şimdi: Türkiye'de vizyondakiler. Platformlarda yeni: " +
                    "Netflix, Disney+, Prime Video gibi platformlara yeni gelenler; TV'nde uygulaması kurulu olan platformlar önce. Karışık: hepsi sırayla.",
                listOf("picks", "cinema", "platform", "mix"), listOf("Günün filmleri", "Sinemada şimdi", "Platformlarda yeni", "Karışık"),
                { prefs.heroSource }, { prefs.heroSource = it }
            ),
            choice(
                "Uygulama kartları", "Çok uygulaman varsa küçük kartlarla hepsi tek bakışta görünür.",
                listOf(0, 1, 2), listOf("Küçük", "Orta", "Büyük"),
                { prefs.cardSize }, { prefs.cardSize = it }
            ),
            choice(
                "Uygulama şekli",
                "Yuvarlatılmış ya da köşeli kart uygulamanın afişini gösterir; yuvarlak ve kare uygulamanın simgesini, daha az yer kaplar.",
                listOf("card", "sharp", "circle", "square"), listOf("Yuvarlatılmış", "Köşeli", "Yuvarlak", "Kare"),
                { prefs.appShape }, { prefs.appShape = it }
            ),
            choice(
                "Cam",
                "Yan panellerin, menülerin ve Tüm uygulamalar'ın arkasında film görselinin ne kadar görüneceği.",
                listOf(0, 1, 2), listOf("Koyu", "Orta", "Şeffaf"),
                { prefs.glass }, { prefs.glass = it }
            ),
            toggle(
                "Büyük yazı",
                "Bütün yazılar biraz büyür; uzaktan okumak kolaylaşır. Ana ekran yeniden çizilir.",
                { prefs.bigText }, { prefs.bigText = it }
            ),
            Header("ÜST ÇUBUK"),
            toggle("Karşılama", "Sol üstte “Günaydın”, “İyi akşamlar” gibi selamlama.", { prefs.greet }, { prefs.greet = it }),
            Row(
                "Adın",
                "Karşılamada adınla seslenir: “Günaydın, Ahmet”.",
                value = { prefs.name.ifEmpty { "Yok" } },
                action = { Dialogs.input(this, "Adın", prefs.name) { prefs.name = it; refreshValues() } }
            ),
            choice(
                "Saat biçimi", "24 saat (21:30) ya da 12 saat (9:30 ÖS).",
                listOf(true, false), listOf("24 saat", "12 saat"),
                { prefs.clock24 }, { prefs.clock24 = it }
            ),
            toggle("Hava durumu", "Saatin yanında hava ikonu ve derece.", { prefs.weather }, { prefs.weather = it }),
            toggle(
                "Hava ambiyansı", "Dışarıda yağmur ya da kar varsa ekranda çok hafif yağmur damlaları veya kar taneleri.",
                { prefs.ambience }, { prefs.ambience = it }
            ),
            Row(
                "Konum",
                "Otomatik konum bazen yanlış şehir bulur. Şehrini yazıp seçebilirsin.",
                value = { prefs.city.ifEmpty { "Otomatik" } },
                action = { chooseCity() }
            ),
            Header("HİS"),
            choice(
                "Sesler",
                "Gezinirken hafif bir “tık”, film sayfası açılırken yumuşak bir geçiş sesi, geri dönerken alçak bir ton, Listem'e eklerken küçük bir zil. Ses TV'nin sesine göre ayarlanır.",
                listOf("soft", "classic", "off"), listOf("Yumuşak", "Klasik", "Kapalı"),
                { if (!prefs.navSound) "off" else prefs.soundSet },
                {
                    prefs.navSound = it != "off"
                    if (it != "off") prefs.soundSet = it
                    Sound.play()
                }
            ),
            toggle(
                "Günaydın kartı",
                "Sabah ilk açılışta birkaç saniyelik özet: hava, bugün çıkan bölümler, vizyona girenler, akşamki film gecesi.",
                { prefs.morningCard }, { prefs.morningCard = it }
            ),
            toggle(
                "Hafif mod",
                "Yavaş TV'ler için: eğilme, ışık süpürmesi, bulanık cam, yavaş yakınlaşma ve yağmur/kar kapanır. Ekran daha akıcı olur.",
                { prefs.lite }, { prefs.lite = it }
            )
        )
    }

    private val allGenres = Prefs.GENRES

    private fun filmsPage() = Page(
        "Filmler", R.drawable.ic_film,
        keys = listOf("picks", "rotate", "rotateSec", "cinema", "genres")
    ) {
        val items = ArrayList<Item>()
        items.add(Header("GÜNÜN FİLMLERİ"))
        items.add(
            toggle(
                "Öneriler", "Her gün haftanın IMDb puanı yüksek popüler filmleri ana ekranda gösterilir.",
                { prefs.showPicks }, { prefs.showPicks = it }
            )
        )
        items.add(toggle("Otomatik geçiş", "Filmler kendiliğinden değişsin mi?", { prefs.autoRotate }, { prefs.autoRotate = it }))
        items.add(
            choice(
                "Geçiş süresi", "Bir sonraki filme kaç saniyede geçilsin.",
                listOf(10, 15, 30), listOf("10 sn", "15 sn", "30 sn"),
                { prefs.rotateSec }, { prefs.rotateSec = it }
            )
        )
        items.add(
            toggle(
                "Sinema köşesi", "“Şu an sinemalarda” ve “Yakında vizyonda” satırları.",
                { prefs.cinema }, { prefs.cinema = it }
            )
        )
        items.add(Header("FRAGMANLAR"))
        items.add(
            toggle(
                "Fragmanlar LeXia'da oynasın",
                "Fragman YouTube uygulamasına geçmeden, tam ekran burada oynar. OK durdurur, sağ/sol 10 sn sarar. " +
                    "İzin vermeyen fragmanlar yine YouTube'da açılır. Kapalıyken her fragman YouTube'da açılır.",
                { prefs.trailerInApp }, { prefs.trailerInApp = it }
            )
        )
        items.add(Header("SEVDİĞİN TÜRLER"))
        items.add(Note("Hiçbiri seçili değilse tüm türler gösterilir. Seçtiğin türlerde yeterli film yoksa yine tüm filmler gelir."))
        for (g in allGenres) {
            items.add(
                Row(
                    g,
                    "OK ile bu türü seç ya da kaldır.",
                    value = { if (g in prefs.genres) "✓" else "" },
                    action = {
                        val s = prefs.genres.toMutableSet()
                        if (!s.remove(g)) s.add(g)
                        prefs.genres = s
                    }
                )
            )
        }
        items
    }

    /** Platforms in today's film list: which ones the home screen, the screensaver and the bell follow. */
    private fun platformsPage() = Page("Platformlar", R.drawable.ic_tv, keys = listOf("platforms")) {
        val items = ArrayList<Item>()
        val names = try {
            val a = org.json.JSONObject(java.io.File(filesDir, "pick.json").readText()).optJSONArray("platforms")
            (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it).optString("provider") }.filter { it.isNotEmpty() }.distinct()
        } catch (e: Exception) {
            emptyList()
        }
        items.add(Row("Bu ay hangi platform?", "Listem'deki filmlerin çoğu hangi platformdaysa bu ay ona abone ol, sonra öbürüne geç: hepsine birden ödeme.",
            chevron = true, action = { PlatformMonthActivity.open(this) }))
        items.add(Note("Ana ekrandaki filmler, ekran koruyucu, zildeki bildirimler ve hafta sonu rehberi seçtiğin " +
            "platformların yeni yapımlarını gösterir. Hiçbirini seçmezsen hepsi gelir; TV'nde uygulaması kurulu olanlar önde."))
        items.add(
            choice(
                "Ana ekrandaki filmler",
                "Karışık: günün filmleri, sinemadakiler ve platformlara yeni gelenler sırayla.",
                listOf("mix", "picks", "cinema", "platform"), listOf("Karışık", "Günün filmleri", "Sinemada şimdi", "Platformlarda yeni"),
                { prefs.heroSource }, { prefs.heroSource = it }
            )
        )
        items.add(Header("PLATFORMLARIM"))
        if (names.isEmpty()) {
            items.add(Note("Bugünün listesi henüz gelmedi; birazdan tekrar bak."))
        }
        for (name in names.sorted()) {
            val installed = Apps.forPlatform(this, name) != null
            items.add(
                Row(
                    name,
                    if (installed) "Uygulaması bu TV'de kurulu. OK ile seç ya da kaldır." else "OK ile seç ya da kaldır.",
                    value = { if (name in prefs.platforms) "✓" else "" },
                    action = {
                        val set = prefs.platforms.toMutableSet()
                        if (!set.remove(name)) set.add(name)
                        prefs.platforms = set
                    }
                )
            )
        }
        items
    }

    private fun rowsPage() = Page("Ana ekran satırları", R.drawable.ic_list, keys = listOf("rowOrder", "hiddenRows")) {
        val items = ArrayList<Item>()
        items.add(Header("SIRALAMA"))
        items.add(Note("İlk satır uygulamaların hemen altında durur. Ana ekranda bir afişe basılı tutup “Satırı taşı” ile de yerini değiştirebilirsin. " +
            "Üç “Sana göre” satırının türleri Ayarlar › Filmler › Sevdiğin türler'den gelir; seçmezsen en çok beğendiğin türlerden ve her gün değişen türlerden seçilir."))
        val order = prefs.rowOrder
        order.forEachIndexed { i, id ->
            val name = rowName(id)
            items.add(
                Row(
                    "${i + 1}.  $name",
                    "OK ile yukarı ya da aşağı taşı, gizle veya göster.",
                    value = { if (id in prefs.hiddenRows) "Gizli" else "Görünür" },
                    action = { editRow(id, i, order.size) }
                )
            )
        }
        items
    }

    /** "Sana göre: Komedi" for the genre rows once the home screen has chosen them. */
    private fun rowName(id: String): String {
        val g = when (id) { "g1" -> 0; "g2" -> 1; "g3" -> 2; else -> -1 }
        val genre = if (g >= 0) GenreRows.names.getOrNull(g) else null
        return if (genre != null) "Sana göre: $genre" else Prefs.ROWS[id] ?: id
    }

    /** Menu for one row on the rows page; keeps focus on that row after moving it. */
    private fun editRow(id: String, index: Int, count: Int) {
        val name = rowName(id)
        val options = ArrayList<Pair<String, () -> Int>>()
        if (index > 0) options.add("Yukarı taşı" to { prefs.moveRow(id, -1); index - 1 })
        if (index < count - 1) options.add("Aşağı taşı" to { prefs.moveRow(id, 1); index + 1 })
        options.add((if (id in prefs.hiddenRows) "Göster" else "Gizle") to { prefs.toggleRow(id); index })
        Dialogs.choose(this, name, options.map { it.first }) { k ->
            val at = options[k].second()
            // The note above the rows is focusable too, so rows start at 1.
            render(at + 1, false)
        }
    }

    private fun appsPage() = Page(
        "Uygulamalar", R.drawable.ic_apps,
        keys = listOf("recent", "appSort", "startApp", "order", "hidden", "fav", "knownTv", "appsSide", "bootOpen")
    ) {
        val pm = packageManager
        val items = ArrayList<Item>()
        items.add(Header("DÜZEN"))
        items.add(
            choice(
                "Uygulamaların yeri",
                "Filmin altında: ana ekranda bir satır. Solda gizli: ekranda yalnızca film ve satırlar kalır; " +
                    "kumandada en soldayken bir kez daha sola basınca uygulamalar soldan açılır.",
                listOf(false, true), listOf("Filmin altında", "Solda gizli"),
                { prefs.appsSide }, { prefs.appsSide = it }
            )
        )
        items.add(
            choice(
                "Sıralama",
                "Benim sıram: senin dizdiğin gibi. Son kullanılan önde: en son açtıkların başta. Saate göre: o saatlerde " +
                    "en çok açtıkların başa gelir; akşam film uygulaması, sabah haberler gibi. Kullandıkça öğrenir.",
                listOf("manual", "recent", "time"), listOf("Benim sıram", "Son kullanılan önde", "Saate göre"),
                { prefs.appSort }, { prefs.appSort = it }
            )
        )
        items.add(
            Row(
                "TV açılınca başlat",
                "TV açıldığında doğrudan bu uygulama başlar; ana ekran bir tuş uzakta kalır.",
                value = {
                    val k = prefs.startApp
                    if (k.isEmpty()) "Yok" else Apps.all(this).firstOrNull { it.key == k }?.label ?: "Yok"
                },
                action = { chooseStartApp() }
            )
        )
        items.add(
            toggle(
                "TV açılınca LeXia açılsın",
                "Bazı kutular (özellikle uydu alıcılı olanlar) her açılışta kendi ana ekranını getirir. " +
                    "Açıkken LeXia, TV açıldıktan birkaç saniye sonra kendini öne getirir.",
                { prefs.bootOpen }, { prefs.bootOpen = it }
            )
        )
        items.add(
            Row(
                "Sıralamayı sıfırla",
                "Uygulamalar alfabetik sıraya döner.",
                action = {
                    prefs.order = emptyList()
                    Glass.toast(this, "Sıralama sıfırlandı")
                }
            )
        )
        items.add(Header("ANA EKRANDAKİ UYGULAMALAR"))
        val all = Apps.all(this).sortedBy { it.label.lowercase() }
        Apps.favorites(prefs, all)
        for (a in all) {
            items.add(
                Row(
                    a.label,
                    "OK ile ana ekrana ekle ya da çıkar." +
                        (if (a.tv) "" else " Bu, TV için yapılmamış bir uygulama; kumandayla kullanımı zor olabilir."),
                    icon = try { a.info.loadIcon(pm) } catch (e: Exception) { null },
                    value = { if (a.key in (prefs.favorites ?: emptySet())) "Ana ekranda" else "—" },
                    action = { prefs.toggleFavorite(a.key) }
                )
            )
        }
        items
    }

    private fun sleepPage() = Page(
        "Ekran koruyucu ve uyku", R.drawable.ic_moon,
        keys = listOf("saverMin", "saverContent", "saverSec", "saverClock", "saverWeather", "saverHold", "nightSleep"),
        preview = "saver"
    ) {
        listOf(
            Header("EKRAN KORUYUCU"),
            Row(
                "Şimdi önizle",
                "Ekran koruyucu hemen başlar; nasıl göründüğünü beklemeden gör. Kumandada bir tuşa basınca kapanır.",
                chevron = true,
                action = { previewSaver() }
            ),
            choice(
                "Başlama süresi", "Bu kadar süre tuşa basılmazsa yazılar kaybolur, filmler ve saat tam ekran akar.",
                listOf(1, 2, 5, 10, 0), listOf("1 dk", "2 dk", "5 dk", "10 dk", "Kapalı"),
                { prefs.saverMin }, { prefs.saverMin = it }
            ),
            choice(
                "İçerik",
                "Karışık: sinemadaki ve yakında vizyona girecek filmler, aralarında “Sinema tarihinde bugün” " +
                    "(yıllar önce tam bugün gösterime girmiş bir film) ve platformlara yeni gelenler.",
                listOf("mix", "cinema", "platform", "history", "picks"),
                listOf("Karışık", "Sinema köşesi", "Platformlarda yeni", "Sinema tarihinde bugün", "Günün filmleri"),
                { prefs.saverContent }, { prefs.saverContent = it }
            ),
            choice(
                "Film değişim hızı", "Ekran koruyucuda her film ekranda ne kadar kalsın.",
                listOf(10, 20, 30, 60), listOf("10 sn", "20 sn", "30 sn", "1 dk"),
                { prefs.saverSec }, { prefs.saverSec = it }
            ),
            choice(
                "Saat", "Ekran koruyucudaki saatin büyüklüğü; istersen gizle.",
                listOf(2, 1, 0), listOf("Büyük", "Normal", "Gizli"),
                { prefs.saverClock }, { prefs.saverClock = it }
            ),
            toggle(
                "Hava durumu",
                "Saatin yanında hava ikonu ve derece. Görünüm › Hava durumu açıkken gösterilir.",
                { prefs.saverWeather }, { prefs.saverWeather = it }
            ),
            choice(
                "Açık kalma süresi",
                "Ekran koruyucu başladıktan sonra bu süre boyunca TV'nin kendi ekran koruyucusu (Google görselleri) araya girmez. " +
                    "Süre dolunca TV kendi ekran koruyucusuna ve uyku ayarına döner. “Kumandaya basılana kadar” seçilirse " +
                    "filmler sen tuşa basana kadar akar; TV kendiliğinden uykuya geçmez.",
                listOf(15, 30, 60, 120, -1, 0),
                listOf("15 dk", "30 dk", "1 saat", "2 saat", "Kumandaya basılana kadar", "TV ayarına bırak"),
                { prefs.saverHoldMin }, { prefs.saverHoldMin = it }
            ),
            Header("UYKU"),
            Row(
                "Uyku zamanlayıcısı",
                "Süre dolunca TV uykuya geçer. Bir dakika önce ekranda uyarı çıkar; o sırada kumandada bir tuşa basarsan 30 dakika ertelenir.",
                value = {
                    if (Sleep.minutesLeft > 0) "Kapanış ${Sleep.endsText(prefs.clock24)}  ·  ${Sleep.minutesLeft} dk kaldı" else "Kapalı"
                },
                action = {
                    val opts = listOf(0, 30, 60, 90, 120)
                    val names = listOf("Kapalı", "30 dakika", "1 saat", "1,5 saat", "2 saat")
                    Dialogs.choose(this, "Uyku zamanlayıcısı", names) { i ->
                        if (opts[i] == 0) Sleep.cancel() else Sleep.start(this, opts[i])
                        refreshValues()
                    }
                }
            ),
            choice(
                "Her gece uyku",
                "Unutsan bile TV her gece bu saatte uykuya geçer. Bir dakika önce uyarı çıkar; bir tuşa basarsan 30 dakika ertelenir.",
                listOf(-1, 22 * 60 + 30, 23 * 60, 23 * 60 + 30, 0, 30, 60, 90, 120),
                listOf(-1, 22 * 60 + 30, 23 * 60, 23 * 60 + 30, 0, 30, 60, 90, 120).map { NightSleep.label(it) },
                { prefs.nightSleep }, { prefs.nightSleep = it }
            )
        )
    }

    /** Back to the home screen, where the screensaver starts at once. */
    private fun previewSaver() {
        Saver.previewRequested = true
        Fx.backdrop = null
        val home = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(home)
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun safetyPage() = Page("Güvenlik", R.drawable.ic_lock) {
        listOf(
            Header("AYAR KİLİDİ"),
            Row(
                "PIN",
                "PIN koyunca ayarlara girmek ve uygulama silmek için PIN sorulur.",
                value = { if (prefs.pin.isEmpty()) "Yok" else "Açık" },
                action = {
                    if (prefs.pin.isEmpty()) {
                        Dialogs.input(this, "4 haneli PIN belirle", "", numeric = true) { a ->
                            if (a.length != 4) {
                                Glass.toast(this, "PIN 4 haneli olmalı")
                                return@input
                            }
                            Dialogs.input(this, "PIN'i tekrar gir", "", numeric = true) { b ->
                                if (a == b) {
                                    prefs.pin = a
                                    Glass.toast(this, "PIN kaydedildi")
                                } else {
                                    Glass.toast(this, "PIN'ler uyuşmadı")
                                }
                                refreshValues()
                            }
                        }
                    } else {
                        Dialogs.confirm(this, "PIN kaldırılsın mı?", "Ayarlar kilitsiz kalır.") {
                            prefs.pin = ""
                            refreshValues()
                        }
                    }
                }
            )
        )
    }

    private fun carePage() = Page("Bakım", R.drawable.ic_build) {
        listOf(
            Header("LİSTE YEDEĞİ"),
            Row(
                "Listemi yedekle",
                "Listem, izlediklerin, beğendiklerin ve hatırlatmaların bir kodla saklanır. Yeni TV'de ya da sıfırladıktan sonra kodu gir, hepsi geri gelsin. İsim ya da hesap yok; yedek şifreli, kodu bilmeyen içini göremez. LeXia 3 ay hiç açılmazsa yedek silinir; TV'yi kullandıkça yedeğin kendiliğinden yenilenir.",
                value = { Backup.code(this) ?: "Kapalı" },
                action = { backupNow() }
            ),
            Row(
                "Yedekten geri yükle",
                "Başka bir TV'deki ya da önceki kurulumdaki kodu gir. Son 3 ay içinde kullanılmış yedekler geri yüklenebilir. Bu TV'deki listeler yedekteki ile değişir.",
                action = { backupRestore() }
            ),
            Row(
                "Yedeği sil",
                "Sitedeki yedek silinir ve bu TV yedeklemeyi bırakır. Bu TV'deki listelerin yerinde kalır.",
                value = { if (Backup.code(this) == null) "" else "Sil" },
                action = { backupDelete() }
            ),
            Header("BAKIM"),
            Row(
                "Önbelleği temizle",
                "İndirilen film görsellerini ve geçici dosyaları siler; ana ekran onları yeniden indirir.",
                action = {
                    var freed = 0L
                    cacheDir.walkBottomUp().forEach {
                        if (it != cacheDir) {
                            if (it.isFile) freed += it.length()
                            it.delete()
                        }
                    }
                    Cache.clearRequested = true
                    Glass.toast(this, "Temizlendi (${freed / 1024} KB)")
                }
            ),
            Row(
                "Ayarları sıfırla",
                "Sıralama, Listem, PIN ve tüm tercihler ilk hâline döner.",
                action = {
                    Dialogs.confirm(this, "Her şey sıfırlansın mı?", "Bu işlem geri alınamaz.") {
                        prefs.reset()
                        Sleep.cancel()
                        Cache.clearRequested = true
                        Glass.toast(this, "Ayarlar sıfırlandı")
                        render(0, true)
                    }
                }
            ),
            Header("KALDIRMA"),
            Row(
                "LeXia'yı kaldır",
                "TV ana ekransız kalmasın diye önce TV'nin kendi ana ekranı seçilir, sonra LeXia kaldırılır.",
                value = { if (isDefault()) "1. adım: ana ekran" else "Kaldır" },
                action = { uninstallSteps() }
            )
        )
    }

    /** Turns backups on (first press) or shows the code big, with when it was last saved. */
    private fun backupNow() {
        val code = Backup.code(this)
        if (code == null) {
            Glass.toast(this, "Yedek hazırlanıyor…")
            Backup.start(this) { ok ->
                runOnUiThread {
                    render(focusedRow(), false)
                    if (ok) showBackupCode() else Glass.toast(this, "Yedeklenemedi; internet bağlantısını kontrol edip yeniden dene", long = true)
                }
            }
            return
        }
        showBackupCode()
    }

    private fun showBackupCode() {
        val code = Backup.code(this) ?: return
        val at = Backup.lastAt(this)
        val last = if (at == 0L) "Henüz gönderilmedi" else "Son yedek: " + java.text.SimpleDateFormat("d MMMM HH:mm", java.util.Locale("tr", "TR")).format(java.util.Date(at))
        Dialogs.list(
            this, code, listOf("Tamam" to "", "Şimdi yedekle" to last),
            subtitle = "Bu kodu bir yere yaz; başka bir TV'de “Yedekten geri yükle”ye girince listelerin geri gelir. LeXia 3 ay hiç açılmazsa yedek silinir.",
            icon = R.drawable.ic_list
        ) { i ->
            if (i == 1) Thread {
                val ok = Backup.upload(this)
                runOnUiThread { Glass.toast(this, if (ok) "Yedeklendi" else "Yedeklenemedi; internet bağlantısını kontrol et") }
            }.start()
        }
    }

    private fun backupRestore() {
        Dialogs.input(this, "Yedek kodu (LX-XXXX-XXXX)", "") { raw ->
            val code = Backup.normalize(raw)
            if (code == null) {
                Glass.toast(this, "Kod LX ile başlar ve 8 harf/rakamdan oluşur", long = true)
                return@input
            }
            Dialogs.confirm(this, "Listeler geri yüklensin mi?", "Bu TV'deki Listem, izlediklerin ve beğendiklerin yedektekiyle değişir.") {
                Glass.toast(this, "Yedek aranıyor…")
                Thread {
                    val ok = Backup.restoreNow(this, code)
                    runOnUiThread {
                        Glass.toast(this, if (ok) "Listelerin geri geldi" else "Bu kodla yedek bulunamadı (kod yanlış ya da 3 ay kullanılmadığı için silinmiş)", long = true)
                        render(focusedRow(), false)
                    }
                }.start()
            }
        }
    }

    private fun backupDelete() {
        if (Backup.code(this) == null) return
        Dialogs.confirm(this, "Yedek silinsin mi?", "Sitedeki yedek silinir; bu TV'deki listelerin yerinde kalır.") {
            Thread {
                val ok = Backup.delete(this)
                runOnUiThread {
                    Glass.toast(this, if (ok) "Yedek silindi" else "Silinemedi; internet bağlantısını kontrol et")
                    render(focusedRow(), false)
                }
            }.start()
        }
    }

    /**
     * Uninstalling the default home app can leave a box on a black screen. First the box's own home
     * screen is chosen in its settings; back here, the second press removes LeXia.
     */
    private fun uninstallSteps() {
        if (isDefault()) {
            Dialogs.confirm(
                this, "Önce ana ekranı değiştir",
                "Açılan ekranda TV'nin kendi ana ekranını seç. Sonra geri gelip “LeXia'yı kaldır”a yeniden bas."
            ) {
                // Keep the Home button service from bringing LeXia back while the stock one is chosen.
                HomeButtonService.pause()
                for (action in listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)) {
                    try {
                        startActivity(Intent(action))
                        return@confirm
                    } catch (e: Exception) {
                    }
                }
                Glass.toast(this, "Ayarlar açılamadı")
            }
            return
        }
        Dialogs.confirm(this, "LeXia kaldırılsın mı?", "Ayarların ve Listem silinir. Ana Sayfa tuşu hizmeti de birlikte kalkar.") {
            Dialogs.pin(this, {
                HomeButtonService.pause()
                try {
                    startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Glass.toast(this, "Kaldırma ekranı açılamadı")
                }
            })
        }
    }

    private fun aboutPage() = Page("Hakkında", R.drawable.ic_info) {
        listOf(
            Header("LEXIA LAUNCHER"),
            Row(
                "Yüklü sürüm",
                if (prefs.tester) "Bu TV test cihazı: yeni sürümler herkesten önce gelir. Kapatmak için OK'e 7 kez bas."
                else "TV'de kurulu olan sürüm.",
                value = { "#${BuildConfig.BUILD_NO}" + if (prefs.tester) "  ·  Test cihazı" else "" },
                action = { versionTap() }
            ),
            Row(
                "Bu sürümde neler var",
                "Son güncellemeyle gelen yenilikler.",
                chevron = true,
                action = { WhatsNew.show(this, ui) }
            ),
            Row("Film listesi", "Günün filmleri her sabah yenilenir.", value = { dayText(prefs.pickDate) }),
            Row(
                "En yeni sürüm",
                "Yayındaki en yeni sürüm. Yeni bir sürüm varsa OK ile kurulur.",
                value = {
                    when {
                        // While downloading/installing (or after a failure) show the progress here too.
                        Updater.busy || updateState in listOf("Kuruluyor…", "Tekrar dene", "Bağlantı yok") -> updateState
                        Updater.latest <= 0 -> "—"
                        Updater.available -> "#${Updater.latest}  ·  Yeni, kur"
                        else -> "#${Updater.latest}  ·  Güncel"
                    }
                },
                action = { update() }
            ),
            Row(
                "Kurulum sihirbazı",
                "İlk açılıştaki adım adım kurulumu yeniden başlatır.",
                chevron = true,
                action = {
                    prefs.setupStarted = false
                    prefs.setupDone = false
                    startActivity(Intent(this, SetupActivity::class.java))
                }
            ),
            Row(
                "Son hata",
                "Uygulama bir kez kapandıysa nedeni burada saklanır. OK ile göster.",
                value = {
                    val f = App.crashFile(this)
                    if (f.exists()) f.readLines().firstOrNull()?.substringBefore("  ·") ?: "Var" else "Yok"
                },
                action = { showCrash() }
            ),
            Note("© 2026 LeXia Launcher. Tüm hakları saklıdır; uygulama ve tasarımı izinsiz kopyalanamaz, değiştirilip dağıtılamaz.\n" +
                "Film bilgileri ve görseller TMDB'den, puanlar IMDb'den alınır. Yazı tipi: Inter (SIL Open Font License).")
        )
    }

    private fun chooseCity() = CityPicker.choose(this, prefs) { refreshValues() }

    private var taps = 0
    private var lastTap = 0L

    /** Seven presses in a row on "Yüklü sürüm" make this TV a test TV (or a normal one again). */
    private fun versionTap() {
        val now = System.currentTimeMillis()
        taps = if (now - lastTap < 1500) taps + 1 else 1
        lastTap = now
        if (taps in 4..6) {
            Glass.toast(this, "${7 - taps} kez daha")
        }
        if (taps < 7) return
        taps = 0
        prefs.tester = !prefs.tester
        Glass.toast(this, if (prefs.tester) "Bu TV artık test cihazı: yeni sürümler önce buraya gelir"
            else "Test cihazı kapatıldı: güncellemeler herkesle birlikte gelir", long = true)
        // Redraw this page so the row shows the new state.
        stack[stack.size - 1] = aboutPage()
        render(focusedRow(), false)
    }

    /** "2026-11-08" as "8 Kasım 2026". */
    private fun dayText(d: String): String = try {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(d)!!
        java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale("tr", "TR")).format(day)
    } catch (e: Exception) {
        "—"
    }

    private fun showCrash() {
        val f = App.crashFile(this)
        if (!f.exists()) {
            Glass.toast(this, "Kayıtlı hata yok")
            return
        }
        val text = try { f.readText() } catch (e: Exception) { "" }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Son hata")
            .setMessage(text)
            .setPositiveButton("Sil") { _, _ ->
                f.delete()
                refreshValues()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun chooseStartApp() {
        val apps = Apps.all(this)
        Dialogs.choose(this, "TV açılınca başlat", listOf("Yok") + apps.map { it.label }) { i ->
            prefs.startApp = if (i == 0) "" else apps[i - 1].key
            refreshValues()
        }
    }

    private fun helpPage() = Page("Ana ekran kurulumu", R.drawable.ic_tv) {
        val ip = localIp() ?: "<TV-IP>"
        listOf(
            Row(
                "Varsayılan yapmayı dene",
                "Cihaz izin veriyorsa bu launcher hemen varsayılan olur.",
                value = { if (isDefault()) "Bu launcher" else "" },
                action = { makeDefault() }
            ),
            Header("GOOGLE TV VE ANDROID TV"),
            Note(
                "Google TV ve bazı Android TV modelleri ana ekranın ayarlardan değiştirilmesine izin vermez. " +
                    "Bu durumda aynı ağdaki bir bilgisayardan aşağıdaki adımları bir kez uygula."
            ),
            Note(
                "1. Ayarlar › Sistem › Hakkında › Android TV OS derlemesi satırına 7 kez bas.\n" +
                    "2. Geliştirici seçeneklerinde USB hata ayıklamayı aç (varsa Kablosuz hata ayıklamayı da)."
            ),
            Row(
                "Geliştirici seçeneklerini aç",
                "Hata ayıklama ayarlarının bulunduğu menü.",
                chevron = true,
                action = { system(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
            ),
            Note("3. Bilgisayarda şu komutları çalıştır:"),
            Note(
                "adb connect $ip:5555\n" +
                    "adb shell cmd package set-home-activity tv.lexia.launcher/.MainActivity",
                true
            ),
            Note(
                "Kablosuz hata ayıklama kullanıyorsan, TV ekranında yazan IP ve bağlantı noktasını kullan.\n\n" +
                    "Ana Sayfa tuşu hâlâ eski ekranı açıyorsa eski launcher'ı kapat:"
            ),
            Note(
                "adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx\n" +
                    "adb shell pm disable-user --user 0 com.google.android.tvlauncher",
                true
            ),
            Note(
                "Geri almak için aynı komutlarda “disable-user --user 0” yerine “enable” yaz. " +
                    "Eski launcher kapatılınca Google TV'nin kendi öneri ekranı da kapanır."
            )
        )
    }

    // ---------- Default launcher ----------

    private fun isDefault(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun makeDefault() {
        if (isDefault()) {
            Glass.toast(this, "Zaten varsayılan ana ekran")
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val rm = getSystemService(RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 7)
                    return
                }
            } catch (e: Exception) {
            }
        }
        HomeButtonService.pause()
        for (action in listOf(Settings.ACTION_HOME_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (e: Exception) {
            }
        }
        Glass.toast(this, "Bu cihaz izin vermiyor. Kurulum adımlarını izle.", long = true)
        if (stack.last().iconRes != R.drawable.ic_tv) push(helpPage())
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        refreshValues()
        if (requestCode == 7 && !isDefault()) {
            Glass.toast(this, "Ayarlanamadı. Kurulum adımlarını izle.", long = true)
            if (stack.last().iconRes != R.drawable.ic_tv) push(helpPage())
        }
    }

    override fun onDestroy() {
        preview.stop()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (stack.isNotEmpty()) refreshValues()
        if (Updater.waitingForPermission && Updater.canInstall(this)) update()
    }

    private fun setUpdate(s: String) {
        updateState = s
        refreshValues()
    }

    private fun update() {
        if (Updater.busy) return
        if (Updater.available) {
            Updater.install(this) { setUpdate(it) }
            return
        }
        setUpdate("Denetleniyor…")
        Updater.check(this) { newer ->
            when (newer) {
                null -> setUpdate("Bağlantı yok")
                true -> setUpdate("Yeni sürüm #${Updater.latest}  ·  Kur")
                false -> setUpdate("Güncel")
            }
        }
    }

    private fun openAccessibility() = SystemSettings.openAccessibility(this)

    private fun system(action: String) {
        HomeButtonService.pause()
        try {
            startActivity(Intent(action))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Glass.toast(this, "Bu ayar cihazda yok")
            }
        }
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
