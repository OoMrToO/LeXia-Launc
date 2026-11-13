package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/** Home-screen playback: resolves the selected Pick's TMDB id and scans enabled Nuvio providers. */
class HomePlaybackActivity : Activity() {
    companion object {
        private const val EXTRA_PICK = "home_playback_pick"
        fun open(activity: Activity, pick: Pick) {
            activity.startActivity(Intent(activity, HomePlaybackActivity::class.java)
                .putExtra(EXTRA_PICK, pick.toJson().toString()))
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var list: LinearLayout
    private lateinit var pick: Pick
    private var season = 1
    private var episode = 1
    private var activeProvider = 0
    private val providers = ArrayList<Pair<String, Pair<Addons.Installed, Addons.ScraperProvider>>>()
    private val results = ArrayList<SourceChoice>()

    private data class SourceChoice(
        val repository: String,
        val addon: Addons.Installed,
        val provider: Addons.ScraperProvider,
        val stream: Addons.ProviderStream,
        val subtitles: List<Addons.ProviderSubtitle>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pick = runCatching { Pick.from(JSONObject(intent.getStringExtra(EXTRA_PICK).orEmpty())) }.getOrElse {
            finish(); return
        }
        if (pick.id <= 0) {
            AlertDialog.Builder(this).setTitle("İçerik kimliği bulunamadı")
                .setMessage("Bu yapımın TMDB numarası ana ekranda yok. Ayrıntılar sayfasından tekrar deneyebilirsin.")
                .setPositiveButton("Tamam") { _, _ -> finish() }.show()
            return
        }
        buildUi()
        if (pick.isSeries) askEpisode()
        else beginScan()
    }

    private fun buildUi() {
        val pad = (resources.displayMetrics.density * 22).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(31, 35, 45), Color.rgb(9, 10, 14))).apply { cornerRadius = 0f }
        }
        titleView = TextView(this).apply {
            text = pick.title
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        root.addView(titleView)
        val meta = TextView(this).apply {
            text = listOf(pick.year, if (pick.isSeries) "Dizi" else "Film", "TMDB · ${pick.id}")
                .filter { it.isNotBlank() }.joinToString("  ·  ")
            textSize = 15f
            setTextColor(0xFF9DA7B7.toInt())
            setPadding(0, pad / 3, 0, pad / 2)
        }
        root.addView(meta)
        statusView = TextView(this).apply {
            text = "Sağlayıcılar hazırlanıyor…"
            textSize = 18f
            setTextColor(0xFFE5E9F0.toInt())
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(statusView)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val close = TextView(this).apply {
            text = "Kapat"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            isFocusable = true
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener { finish() }
            background = rounded(0xFF303744.toInt(), pad.toFloat())
        }
        root.addView(close, LinearLayout.LayoutParams(-2, -2))
        setContentView(root)
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    private fun askEpisode() {
        val s = EditText(this).apply {
            hint = "Sezon (1)"
            setSingleLine(true); inputType = 2; setText("1")
        }
        val e = EditText(this).apply {
            hint = "Bölüm (1)"
            setSingleLine(true); inputType = 2; setText("1")
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(s); addView(e)
        }
        AlertDialog.Builder(this).setTitle(pick.title)
            .setMessage("İzlemek istediğin sezon ve bölümü seç. TMDB numarası otomatik kullanılır.")
            .setView(panel).setNegativeButton("Vazgeç") { _, _ -> finish() }
            .setPositiveButton("Kaynakları bul") { _, _ ->
                season = s.text.toString().toIntOrNull()?.takeIf { it >= 0 } ?: 0
                episode = e.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 1
                beginScan()
            }.setOnCancelListener { finish() }.show()
    }

    private fun beginScan() {
        statusView.text = "Etkin sağlayıcılar taranıyor…"
        worker.execute {
            val mode = HomePlaybackPrefs.mode(this)
            val pinned = HomePlaybackPrefs.pinnedRepository(this)
            val repos = Addons.urls(this).filter { mode != "pinned" || it == pinned }
            for (repository in repos) {
                val addon = runCatching { Addons.fetchManifest(repository) }.getOrNull() ?: continue
                if (addon.format != "nuvio-js") continue
                for (provider in addon.scrapers) {
                    if (!provider.enabled || !HomePlaybackPrefs.isProviderEnabled(this, repository, provider.id)) continue
                    val supported = provider.supportedTypes
                    val mediaType = if (pick.isSeries) "tv" else "movie"
                    if (supported.isNotEmpty() && mediaType !in supported) continue
                    if (HomePlaybackPrefs.mode(this) == "pinned_provider" &&
                        HomePlaybackPrefs.pinnedProvider(this) != repository + "|" + provider.id) continue
                    providers.add(repository to (addon to provider))
                }
            }
            if (providers.isEmpty()) {
                runOnUiThread { statusView.text = "Etkin Nuvio sağlayıcısı bulunamadı. Ayarlar › Oynatma ve Sağlayıcılar bölümünü kontrol et." }
                return@execute
            }
            activeProvider = 0
            runOnUiThread { statusView.text = "0 / ${providers.size} sağlayıcı taranıyor…" }
            scanNext()
        }
    }

    private fun scanNext() {
        if (activeProvider >= providers.size) {
            renderResults()
            return
        }
        val (repository, pair) = providers[activeProvider++]
        val addon = pair.first
        val provider = pair.second
        statusView.text = "${activeProvider} / ${providers.size} · ${provider.name} taranıyor…"
        worker.execute {
            val source = runCatching { Addons.fetchScraperSource(addon, provider) }.getOrNull()
            if (source == null) {
                runOnUiThread { scanNext() }
                return@execute
            }
            val args = JSONObject().put("tmdbId", pick.id.toString())
                .put("mediaType", if (pick.isSeries) "tv" else "movie")
                .put("season", if (pick.isSeries) season else JSONObject.NULL)
                .put("episode", if (pick.isSeries) episode else JSONObject.NULL).toString()
            runOnUiThread {
                AddonSandbox.evaluate(this, source, repository, provider.id, args,
                    Addons.providerSettings(this, repository, provider.id)) { response ->
                    if (response.success) {
                        val parsed = runCatching { Addons.parseProviderResult(response.value) }.getOrNull()
                        parsed?.streams.orEmpty().forEach { stream ->
                            results.add(SourceChoice(repository, addon, provider, stream, parsed?.subtitles.orEmpty()))
                        }
                    }
                    scanNext()
                }
            }
        }
    }

    private fun renderResults() {
        list.removeAllViews()
        val quality = HomePlaybackPrefs.quality(this)
        val sorted = results.sortedByDescending { scoreQuality(it.stream.quality, quality) }
        statusView.text = if (sorted.isEmpty())
            "Kaynak bulunamadı. Sağlayıcılar için HTTPS alan adı izni gerekebilir."
        else "${sorted.size} kaynak bulundu · En uygun kalite üstte"
        sorted.forEachIndexed { index, item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 18, 24, 18)
                background = rounded(if (index == 0) 0xFF26384A.toInt() else 0xFF202630.toInt(), 18f)
                isFocusable = true
                isClickable = true
                setOnClickListener { chooseSubtitle(item) }
                setOnFocusChangeListener { v, focused ->
                    v.alpha = if (focused) 1f else 0.88f
                    v.scaleX = if (focused) 1.015f else 1f
                    v.scaleY = if (focused) 1.015f else 1f
                }
            }
            val name = TextView(this).apply {
                text = item.stream.title
                textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            }
            val detail = TextView(this).apply {
                text = listOf(item.provider.name, item.stream.quality, item.stream.format.uppercase())
                    .filter { it.isNotBlank() }.joinToString("   ·   ")
                textSize = 15f; setTextColor(0xFFB7C1D0.toInt()); setPadding(0, 8, 0, 0)
            }
            card.addView(name); card.addView(detail)
            val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = 12
            list.addView(card, lp)
            if (index == 0) card.requestFocus()
        }
    }

    private fun scoreQuality(value: String, preference: String): Int {
        val q = value.lowercase()
        val rank = when {
            "2160" in q || "4k" in q -> 4
            "1080" in q || "fhd" in q -> 3
            "720" in q || "hd" in q -> 2
            "480" in q || "sd" in q -> 1
            else -> 0
        }
        return when (preference) { "4k" -> rank * 10; "1080" -> (if (rank == 3) 50 else rank * 10); "720" -> (if (rank == 2) 50 else rank * 10); else -> rank * 10 }
    }

    private fun chooseSubtitle(item: SourceChoice) {
        val options = listOf("Altyazısız") + item.subtitles.map {
            it.name + if (it.language.isBlank()) "" else " · " + it.language
        }
        AlertDialog.Builder(this).setTitle("Altyazı seç")
            .setItems(options.toTypedArray()) { _, index ->
                play(item, if (index == 0) null else item.subtitles[index - 1])
            }.setNegativeButton("Geri", null).show()
    }

    private fun play(item: SourceChoice, subtitle: Addons.ProviderSubtitle?) {
        startActivity(Intent(this, AddonPlayerActivity::class.java).apply {
            putExtra(AddonPlayerActivity.EXTRA_URL, item.stream.url)
            putExtra(AddonPlayerActivity.EXTRA_HEADERS, JSONObject(item.stream.headers).toString())
            putExtra(AddonPlayerActivity.EXTRA_SERIES_ID, item.addon.baseUrl + "|" + pick.id)
            putExtra(AddonPlayerActivity.EXTRA_SEASON, if (pick.isSeries) season else 0)
            putExtra(AddonPlayerActivity.EXTRA_EPISODE, if (pick.isSeries) episode else 1)
            if (subtitle != null) {
                putExtra(AddonPlayerActivity.EXTRA_SUBTITLE_URL, subtitle.url)
                putExtra(AddonPlayerActivity.EXTRA_SUBTITLE_MIME, if (subtitle.format == "srt")
                    androidx.media3.common.MimeTypes.APPLICATION_SUBRIP else androidx.media3.common.MimeTypes.TEXT_VTT)
                putExtra(AddonPlayerActivity.EXTRA_SUBTITLE_LANGUAGE, subtitle.language.ifBlank { "und" })
                putExtra(AddonPlayerActivity.EXTRA_SUBTITLE_HEADERS, JSONObject(subtitle.headers).toString())
            }
        })
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}

/** Small persistent preferences for home playback. */
object HomePlaybackPrefs {
    private const val FILE = "lexia_home_playback"
    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)

    fun mode(context: android.content.Context) = prefs(context).getString("mode", "all") ?: "all"
    fun setMode(context: android.content.Context, value: String) = prefs(context).edit().putString("mode", value).apply()
    fun quality(context: android.content.Context) = prefs(context).getString("quality", "1080") ?: "1080"
    fun setQuality(context: android.content.Context, value: String) = prefs(context).edit().putString("quality", value).apply()
    fun language(context: android.content.Context) = prefs(context).getString("language", "tr") ?: "tr"
    fun setLanguage(context: android.content.Context, value: String) = prefs(context).edit().putString("language", value).apply()
    fun pinnedRepository(context: android.content.Context) = prefs(context).getString("repository", "") ?: ""
    fun setPinnedRepository(context: android.content.Context, value: String) = prefs(context).edit().putString("repository", value).apply()
    fun pinnedProvider(context: android.content.Context) = prefs(context).getString("provider", "") ?: ""
    fun setPinnedProvider(context: android.content.Context, value: String) = prefs(context).edit().putString("provider", value).apply()
    fun isProviderEnabled(context: android.content.Context, repository: String, id: String): Boolean =
        prefs(context).getBoolean("enabled:$repository|$id", true)
    fun setProviderEnabled(context: android.content.Context, repository: String, id: String, enabled: Boolean) =
        prefs(context).edit().putBoolean("enabled:$repository|$id", enabled).apply()
}
