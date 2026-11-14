package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Selects a direct stream from installed add-ons, then hands it to the internal Media3 player. */
class MediaSourcePickerActivity : Activity() {

    companion object {
        private const val EXTRA_FILM = "media.pick"

        fun open(activity: Activity, film: Pick) {
            activity.startActivity(
                android.content.Intent(activity, MediaSourcePickerActivity::class.java)
                    .putExtra(EXTRA_FILM, film.toJson().toString())
            )
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private val repository by lazy { MediaAddonRepository(this) }
    private val nuvioRepository by lazy { NuvioJsRepository(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var film: Pick
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var busy = false
    private var currentEpisodeTitle = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        film = try {
            Pick.from(org.json.JSONObject(intent.getStringExtra(EXTRA_FILM) ?: "{}"))
        } catch (_: Exception) {
            finish()
            return
        }
        buildUi()
        loadContent()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(28), dp(44), dp(24))
            setBackgroundColor(Color.rgb(7, 9, 15))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(this).apply {
            text = if (film.isSeries) "DİZİYİ İZLE" else "FİLMİ İZLE"
            textSize = 12f
            letterSpacing = 0.14f
            setTextColor(Color.rgb(125, 151, 255))
            typeface = Typeface.DEFAULT_BOLD
        })
        heading.addView(TextView(this).apply {
            text = film.title
            textSize = 31f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        top.addView(heading, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val manage = action("Kaynakları yönet").apply {
            setOnClickListener {
                startActivity(android.content.Intent(this@MediaSourcePickerActivity, MediaAddonsActivity::class.java))
            }
        }
        top.addView(manage)
        root.addView(top)

        status = TextView(this).apply {
            text = "Kaynaklar hazırlanıyor…"
            textSize = 16f
            setTextColor(Color.rgb(170, 181, 199))
            setPadding(0, dp(14), 0, dp(10))
        }
        root.addView(status)

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
        // The remote starts on a button; a focused container would swallow every D-pad press.
        manage.requestFocus()
    }

    private fun loadContent() {
        if (film.id <= 0) {
            showMessage(
                "İçerik kimliği bulunamadı",
                "Kaynak araması için LeXia film kaydında TMDB kimliği bulunmalı."
            )
            return
        }
        val installed = repository.installedAddons()
        val nuvioInstalled = nuvioRepository.installedRepositories()
        if (installed.isEmpty() && nuvioInstalled.isEmpty()) {
            showNoAddons()
            return
        }
        if (film.isSeries) loadEpisodes() else loadMovieStreams()
    }

    private fun loadMovieStreams() {
        runTask("Film kaynakları aranıyor…", {
            // Stremio add-ons commonly expect IMDb IDs; Nuvio's getStreams contract expects a TMDB ID.
            val stremioMediaId = film.imdbId.ifBlank { "tmdb:${film.id}" }
            repository.fetchStreams("movie", stremioMediaId) +
                nuvioRepository.fetchStreams(film.id.toString(), "movie")
        }) { streams ->
            if (streams.isEmpty()) {
                showMessage(
                    "Oynatılabilir kaynak bulunamadı",
                    "Kurulu Stremio ve Nuvio JS eklentileri bu film için oynatılabilir bir HTTPS kaynağı döndürmedi."
                )
            } else {
                showStreams(streams, film.title, film.imdbId.ifBlank { "tmdb:${film.id}" })
            }
        }
    }

    private fun loadEpisodes() {
        runEpisodeTask("Dizi bölümleri aranıyor…", {
            repository.fetchEpisodes(film.id, film.imdbId)
        }) { episodes ->
            if (episodes.isEmpty()) {
                showManualEpisodeSearch()
            } else {
                status.text = "${episodes.size} bölüm bulundu"
                content.removeAllViews()
                addSectionTitle("SEZONLAR VE BÖLÜMLER")
                episodes.forEach { episode ->
                    val seasonLine = if (episode.season > 0 && episode.episode > 0) {
                        "${episode.season}. Sezon  ·  ${episode.episode}. Bölüm"
                    } else {
                        "Bölüm"
                    }
                    addCard(
                        episode.title,
                        seasonLine,
                        "Kaynakları ara",
                        primary = false
                    ) { loadEpisodeStreams(episode.id, episode.title, episode.season, episode.episode) }
                }
            }
        }
    }

    private fun showManualEpisodeSearch() {
        status.text = "Eklentiler bölüm listesi sağlamadı."
        content.removeAllViews()
        addMessageCard(
            "Sezon ve bölüm numarasıyla ara",
            "Dizi eklentilerinin bir kısmı bölüm kataloğu yerine doğrudan sezon/bölüm kimliğiyle kaynak döndürür."
        )
        val button = action("Bölüm numarası gir", primary = true).apply {
            setOnClickListener { showEpisodeNumberDialog() }
        }
        content.addView(button, fullWidthParams(top = 14))
    }

    private fun showEpisodeNumberDialog() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), 0)
        }
        val season = EditText(this).apply {
            hint = "Sezon numarası"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            setTextColor(Color.WHITE)
        }
        val episode = EditText(this).apply {
            hint = "Bölüm numarası"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            setTextColor(Color.WHITE)
        }
        fields.addView(season)
        fields.addView(episode)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Bölüm seç")
            .setView(fields)
            .setNegativeButton("İptal", null)
            .setPositiveButton("Kaynakları ara") { _, _ ->
                val s = season.text.toString().toIntOrNull()
                val e = episode.text.toString().toIntOrNull()
                if (s == null || e == null || s !in 1..100 || e !in 1..500) {
                    status.text = "Geçerli bir sezon ve bölüm numarası gir."
                } else {
                    val id = "${film.imdbId.ifBlank { "tmdb:${film.id}" }}:$s:$e"
                    loadEpisodeStreams(id, "$s. Sezon · $e. Bölüm", s, e)
                }
            }
            .create()
        dialog.show()
        dialog.window?.setLayout(dp(560), LinearLayout.LayoutParams.WRAP_CONTENT)
        season.requestFocus()
    }

    private fun loadEpisodeStreams(videoId: String, title: String, season: Int = 0, episode: Int = 0) {
        currentEpisodeTitle = title
        // Stremio keeps its provider-specific episode ID; Nuvio receives TMDB ID + S/E separately.
        val nuvioEpisodeId = if (film.id > 0 && season > 0 && episode > 0) {
            "${film.id}:$season:$episode"
        } else {
            film.id.toString()
        }
        runTask("Kaynaklar aranıyor · $title", {
            repository.fetchStreams("series", videoId) +
                nuvioRepository.fetchStreams(nuvioEpisodeId, "tv")
        }) { streams ->
            if (streams.isEmpty()) {
                showMessage(
                    "Bu bölüm için kaynak bulunamadı",
                    "Başka bir eklentiyi deneyebilir veya farklı bir bölüm seçebilirsin. Nuvio JS scraper'ları da bu aramada çalıştırıldı."
                )
                val back = action("Bölüm listesine dön").apply {
                    setOnClickListener { loadEpisodes() }
                }
                content.addView(back, fullWidthParams(top = 14))
            } else {
                showStreams(streams, "${film.title} · $title", videoId)
            }
        }
    }

    private fun showStreams(streams: List<MediaStream>, playbackTitle: String, mediaKey: String) {
        // Keep each add-on / Nuvio scraper visually separate, like Nuvio's provider list.
        val providers = streams.groupBy { stream ->
            stream.addonName.trim().ifBlank { "Diğer kaynaklar" }
        }
        status.text = "${streams.size} doğrudan oynatılabilir kaynak · ${providers.size} sağlayıcı"
        content.removeAllViews()
        addSectionTitle("YAYIN KAYNAKLARI")

        var globalIndex = 0
        providers.forEach { (providerName, providerStreams) ->
            addSectionTitle("${providerName.uppercase()}  ·  ${providerStreams.size} KAYNAK")
            providerStreams.forEach { stream ->
                val details = listOf(stream.description)
                    .filter { it.isNotBlank() && it != stream.name }
                    .distinct()
                    .joinToString("  ·  ")
                addCard(
                    stream.name.ifBlank { "Kaynak ${globalIndex + 1}" },
                    details,
                    if (stream.subtitles.isEmpty()) "Oynat" else "Oynat  ·  ${stream.subtitles.size} altyazı",
                    primary = globalIndex == 0
                ) {
                    try {
                        PlayerActivity.open(
                            this,
                            stream.url,
                            playbackTitle,
                            mediaKey,
                            stream.headers,
                            stream.subtitles.map {
                                SubtitleTrack(it.url, language = it.language, label = it.label)
                            },
                            watchKey = film.key,
                            markWatchedOnCompletion = !film.isSeries
                        )
                    } catch (e: Exception) {
                        status.text = e.message ?: "Oynatıcı açılamadı."
                    }
                }
                globalIndex++
            }
        }
        if (film.isSeries) {
            val back = action("Bölüm listesine dön").apply {
                setOnClickListener { loadEpisodes() }
            }
            content.addView(back, fullWidthParams(top = 12))
        }
    }

    private fun showNoAddons() {
        status.text = "Henüz medya kaynağı eklenmedi."
        content.removeAllViews()
        addMessageCard(
            "LeXia'ya kaynak ekle",
            "Film ve dizi kaynaklarını bulmak için önce uyumlu bir eklenti kur. Eklenti adresi manifest üzerinden doğrulanır."
        )
        val button = action("Eklenti yöneticisini aç", primary = true).apply {
            setOnClickListener {
                startActivity(android.content.Intent(this@MediaSourcePickerActivity, MediaAddonsActivity::class.java))
            }
        }
        content.addView(button, fullWidthParams(top = 14))
    }

    private fun showMessage(title: String, description: String) {
        content.removeAllViews()
        addMessageCard(title, description)
        val manager = action("Kaynakları yönet").apply {
            setOnClickListener {
                startActivity(android.content.Intent(this@MediaSourcePickerActivity, MediaAddonsActivity::class.java))
            }
        }
        content.addView(manager, fullWidthParams(top = 14))
    }

    private fun runTask(
        message: String,
        task: () -> List<MediaStream>,
        onSuccess: (List<MediaStream>) -> Unit
    ) {
        status.text = message
        content.removeAllViews()
        if (busy) return
        busy = true
        worker.execute {
            try {
                val result = task()
                runOnUiThread {
                    busy = false
                    if (!isFinishing) onSuccess(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    if (!isFinishing) {
                        showMessage("Kaynak araması tamamlanamadı", e.message ?: "Ağ veya eklenti yanıtı hatası.")
                    }
                }
            }
        }
    }

    private fun runEpisodeTask(
        message: String,
        task: () -> List<MediaEpisode>,
        onSuccess: (List<MediaEpisode>) -> Unit
    ) {
        status.text = message
        content.removeAllViews()
        if (busy) return
        busy = true
        worker.execute {
            try {
                val result = task()
                runOnUiThread {
                    busy = false
                    if (!isFinishing) onSuccess(result)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy = false
                    if (!isFinishing) showManualEpisodeSearch()
                }
            }
        }
    }

    private fun addSectionTitle(title: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.15f
            setTextColor(Color.rgb(127, 151, 255))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(12))
        })
    }

    private fun addMessageCard(title: String, description: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(21), dp(22), dp(21))
            background = panelBackground()
        }
        card.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = description
            textSize = 16f
            setTextColor(Color.rgb(174, 183, 198))
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(card, fullWidthParams())
    }

    private fun addCard(title: String, description: String, actionLabel: String, primary: Boolean, onClick: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(15), dp(20), dp(15))
            background = panelBackground()
            isFocusable = true
            isClickable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 19f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        if (description.isNotBlank()) {
            texts.addView(TextView(this).apply {
                text = description
                textSize = 14f
                setTextColor(Color.rgb(153, 165, 185))
                maxLines = 2
                setPadding(0, dp(5), 0, 0)
            })
        }
        card.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(TextView(this).apply {
            text = actionLabel
            textSize = 13f
            setTextColor(if (primary) Color.rgb(179, 195, 255) else Color.rgb(185, 193, 208))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        card.setOnFocusChangeListener { view, focused ->
            view.background = if (focused) focusedBackground() else panelBackground()
            view.scaleX = if (focused) 1.012f else 1f
            view.scaleY = if (focused) 1.012f else 1f
        }
        content.addView(card, fullWidthParams(bottom = 10))
        // A fresh list: the remote lands on its first entry.
        if (content.childCount == 1) card.post { card.requestFocus() }
    }

    private fun action(label: String, primary: Boolean = false): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setTextColor(Color.WHITE)
        setPadding(dp(18), dp(12), dp(18), dp(12))
        val idle = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (primary) Color.rgb(64, 87, 172) else Color.rgb(34, 39, 51))
            setStroke(dp(1), if (primary) Color.rgb(126, 150, 255) else Color.rgb(62, 71, 91))
        }
        // Focused: white with dark text, so the selected button is unmistakable from the sofa.
        val focusedLook = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.WHITE)
        }
        background = idle
        setOnFocusChangeListener { view, focused ->
            view.background = if (focused) focusedLook else idle
            (view as TextView).setTextColor(if (focused) Color.rgb(7, 9, 15) else Color.WHITE)
            view.scaleX = if (focused) 1.04f else 1f
            view.scaleY = if (focused) 1.04f else 1f
        }
    }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(15).toFloat()
        setColor(Color.rgb(20, 24, 34))
        setStroke(dp(1), Color.rgb(41, 48, 64))
    }

    private fun focusedBackground() = GradientDrawable().apply {
        cornerRadius = dp(15).toFloat()
        setColor(Color.rgb(35, 44, 69))
        setStroke(dp(2), Color.rgb(126, 150, 255))
    }

    private fun fullWidthParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            if (top > 0) topMargin = dp(top)
            if (bottom > 0) bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
