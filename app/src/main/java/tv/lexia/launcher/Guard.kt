package tv.lexia.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Crash shield. A TV whose launcher crashes at every start is stuck: the home screen never comes up
 * and LeXia cannot update itself to the fix. So crashes soon after start are counted:
 *  - after the 2nd in a row, the next start clears what can safely be rebuilt (the saved film list,
 *    the owner's settings copy, image cache, small "seen" markers). Listem, reminders, watched/liked,
 *    PIN, name, city and choices stay.
 *  - after the 3rd, the home screen opens [SafeActivity] instead: a plain page that can still look for
 *    and install an update, or try again.
 * A start that stays up for a while sets the count back to zero; a new build always starts clean.
 * Kept in its own small file, so a broken main settings file cannot break the shield too.
 */
object Guard {
    private const val FILE = "lexia_guard"
    /** A crash within this long of the process start counts as "LeXia does not open". */
    private const val EARLY_MS = 30_000L
    private var startedAt = 0L

    private fun store(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun count(ctx: Context): Int = try {
        store(ctx).getInt("crashes", 0)
    } catch (e: Exception) {
        0
    }

    /** From App.onCreate: a new build starts clean; after two quick crashes, clear rebuildable data. */
    fun started(ctx: Context) {
        startedAt = SystemClock.elapsedRealtime()
        try {
            val st = store(ctx)
            if (st.getString("build", "") != BuildConfig.BUILD_NO) {
                st.edit().clear().putString("build", BuildConfig.BUILD_NO).commit()
                return
            }
            if (st.getInt("crashes", 0) >= 2 && !st.getBoolean("repaired", false)) {
                st.edit().putBoolean("repaired", true).commit()
                repair(ctx)
            }
        } catch (e: Exception) {
        }
    }

    /** From the crash handler: written at once (commit), the process is about to end. */
    fun crashed(ctx: Context) {
        if (SystemClock.elapsedRealtime() - startedAt > EARLY_MS) return
        try {
            val st = store(ctx)
            st.edit().putInt("crashes", st.getInt("crashes", 0) + 1).commit()
        } catch (e: Exception) {
        }
    }

    /** The home screen has been up for a while: all is well. */
    fun ok(ctx: Context) {
        if (count(ctx) == 0) return
        try {
            store(ctx).edit().putInt("crashes", 0).putBoolean("repaired", false).apply()
        } catch (e: Exception) {
        }
    }

    /** Three quick crashes in a row: show the plain page instead of the home screen. */
    fun safe(ctx: Context) = count(ctx) >= 3

    private fun repair(ctx: Context) {
        try {
            java.io.File(ctx.filesDir, "pick.json").delete()
            java.io.File(ctx.filesDir, "remote.json").delete()
            ctx.cacheDir.walkBottomUp().forEach { if (it != ctx.cacheDir) it.delete() }
        } catch (e: Exception) {
        }
        try {
            val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
            val e = p.edit()
            for (k in listOf(
                "recentFilms", "trailerAt", "nowStreaming", "platformSeen", "morningSeen", "listNudgeWeek",
                "epSeen", "noticesRead", "movieNight", "movieNightShown", "heroTurn", "remoteSeen", "pickDate"
            )) e.remove(k)
            // "recent" is a switch; anything else stored there is left over from a bad build.
            if (p.all["recent"] != null && p.all["recent"] !is Boolean) e.remove("recent")
            e.commit()
        } catch (e: Exception) {
        }
    }
}

/** What a TV sees after LeXia failed to open three times: update, try again, or the TV's settings. */
class SafeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var update: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Deliberately plain: none of the home screen's drawing, so whatever broke it is not used here.
        val d = resources.displayMetrics.widthPixels / 1920f
        fun px(v: Int) = (v * d).toInt()
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_VERTICAL
        col.setPadding(px(160), 0, px(160), 0)
        col.setBackgroundColor(Color.parseColor("#0B0D14"))
        fun text(s: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        col.addView(text("LEXIA LAUNCHER", 20, Color.parseColor("#FF8FB8"), true))
        col.addView(text("LeXia açılırken sorun çıktı", 60, Color.WHITE, true), lp(px(14)))
        col.addView(
            text(
                "Listem ve ayarların yerinde duruyor. Düzeltilmiş sürüm geldiyse buradan kurabilirsin; " +
                    "kurulunca LeXia kendiliğinden açılır.",
                26, Color.argb(190, 255, 255, 255)
            ),
            lp(px(20))
        )
        status = text("", 24, Color.argb(160, 255, 255, 255))
        col.addView(status, lp(px(30)))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        update = button("Güncellemeyi denetle") { look() }
        row.addView(update, blp())
        row.addView(button("Yeniden dene") { retry() }, blp())
        row.addView(button("TV ayarları") { SystemSettings.open(this, SystemSettings.Screen.ALL) }, blp())
        col.addView(row, lp(px(36)))
        setContentView(col)
        update.requestFocus()
        look()
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.topMargin = top }

    private fun blp() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
        it.rightMargin = (24 * resources.displayMetrics.widthPixels / 1920f).toInt()
    }

    private fun button(label: String, click: () -> Unit): TextView {
        val s = resources.displayMetrics.widthPixels / 1920f
        val b = TextView(this)
        b.text = label
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * s)
        b.setPadding((34 * s).toInt(), (18 * s).toInt(), (34 * s).toInt(), (18 * s).toInt())
        b.isFocusable = true
        b.isClickable = true
        fun paint(f: Boolean) {
            b.background = GradientDrawable().apply {
                cornerRadius = 40 * s
                setColor(if (f) Color.WHITE else Color.argb(40, 255, 255, 255))
            }
            b.setTextColor(if (f) Color.BLACK else Color.WHITE)
        }
        paint(false)
        b.setOnFocusChangeListener { _, f -> paint(f) }
        b.setOnClickListener { click() }
        return b
    }

    private fun look() {
        if (Updater.available) {
            install()
            return
        }
        status.text = "Yeni sürüm aranıyor…"
        Updater.check(this) { found ->
            when (found) {
                true -> {
                    status.text = "Yeni sürüm bulundu: #${Updater.latest}"
                    update.text = "Şimdi güncelle"
                }
                false -> status.text = "Şu an daha yeni sürüm yok. Biraz sonra yeniden dene."
                null -> status.text = "İnternete ulaşılamadı."
            }
        }
    }

    private fun install() {
        Updater.install(this) { status.text = it }
    }

    private fun retry() {
        Guard.ok(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (Updater.waitingForPermission && Updater.canInstall(this)) install()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Nothing behind this page; staying is better than an empty screen.
    }
}
