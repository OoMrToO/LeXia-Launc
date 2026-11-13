package tv.lexia.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Per-provider on/off switches, loaded away from the UI thread. */
class HomePlaybackProvidersActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var list: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 20).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            setBackgroundColor(Color.rgb(12, 14, 18))
        }
        root.addView(TextView(this).apply {
            text = "SAĞLAYICI YÖNETİMİ"; textSize = 25f
            setTextColor(Color.WHITE); setPadding(0, 0, 0, pad / 2)
        })
        status = TextView(this).apply {
            text = "Depolar yükleniyor…"; textSize = 16f
            setTextColor(0xFFB6C0CE.toInt()); setPadding(0, 0, 0, pad)
        }
        root.addView(status)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        load()
    }

    private fun load() {
        worker.execute {
            val rows = Addons.urls(this).flatMap { repository ->
                val addon = runCatching { Addons.fetchManifest(repository) }.getOrNull()
                addon?.takeIf { it.format == "nuvio-js" }?.scrapers.orEmpty().map { repository to it }
            }
            runOnUiThread {
                list.removeAllViews()
                status.text = if (rows.isEmpty()) "Nuvio sağlayıcısı bulunamadı. Önce Ayarlar › Eklentiler bölümünden depo ekle."
                    else "${rows.size} sağlayıcı · Açık olanlar ana ekrandaki İzle düğmesinde taranır."
                rows.forEach { (repository, provider) ->
                    val enabled = HomePlaybackPrefs.isProviderEnabled(this, repository, provider.id)
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(22, 18, 22, 18)
                        background = GradientDrawable().apply {
                            setColor(0xFF202630.toInt()); cornerRadius = 18f
                        }
                        isFocusable = true; isClickable = true
                    }
                    val title = TextView(this).apply {
                        text = provider.name + if (provider.enabled) "" else " · Depoda kapalı"
                        textSize = 19f; setTextColor(Color.WHITE)
                    }
                    val detail = TextView(this).apply {
                        text = repository.removePrefix("https://").removeSuffix("/") + "  ·  " +
                            (if (enabled && provider.enabled) "Açık" else "Kapalı")
                        textSize = 14f; setTextColor(0xFFB5BECC.toInt()); setPadding(0, 6, 0, 0)
                    }
                    row.addView(title); row.addView(detail)
                    row.setOnClickListener {
                        val next = !HomePlaybackPrefs.isProviderEnabled(this, repository, provider.id)
                        HomePlaybackPrefs.setProviderEnabled(this, repository, provider.id, next)
                        detail.text = repository.removePrefix("https://").removeSuffix("/") + "  ·  " +
                            (if (next && provider.enabled) "Açık" else "Kapalı")
                    }
                    val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 12
                    list.addView(row, lp)
                }
                list.getChildAt(0)?.requestFocus()
            }
        }
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
}
