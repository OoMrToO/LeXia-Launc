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

/** TV-remote-friendly manager for Stremio add-ons and Nuvio JavaScript scraper repositories. */
class MediaAddonsActivity : Activity() {

    private val repository by lazy { MediaAddonRepository(this) }
    private val nuvioRepository by lazy { NuvioJsRepository(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        renderAddons()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(44), dp(28), dp(44), dp(28))
            setBackgroundColor(Color.rgb(9, 11, 17))
        }

        val header = TextView(this).apply {
            text = "MEDYA KAYNAKLARI"
            textSize = 13f
            letterSpacing = 0.14f
            setTextColor(Color.rgb(125, 151, 255))
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(header)

        val title = TextView(this).apply {
            text = "Eklentiler"
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(title)

        val intro = TextView(this).apply {
            text = "Uyumlu bir Stremio kaynak eklentisinin manifest adresini ekle. LeXia, manifesti doğrular ve film/dizi kaynaklarını buradan arar."
            textSize = 17f
            setTextColor(Color.rgb(174, 182, 197))
            setLineSpacing(0f, 1.2f)
        }
        root.addView(intro)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(12))
        }
        val add = action("＋  Eklenti ekle", primary = true).apply {
            setOnClickListener { showAddDialog() }
        }
        controls.addView(add)
        val close = action("Kapat").apply {
            setOnClickListener { finish() }
        }
        val closeParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(12) }
        controls.addView(close, closeParams)
        root.addView(controls)

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.rgb(170, 180, 198))
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(status)

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        scroll.addView(list)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)
        // The remote starts on the first button; a focused container would swallow every D-pad press.
        add.requestFocus()
    }

    private fun renderAddons() {
        list.removeAllViews()
        val addons = repository.installedAddons()
        val nuvioRepos = nuvioRepository.installedRepositories()
        val nuvioCount = nuvioRepos.sumOf { it.scrapers.size }
        status.text = "${addons.size} Stremio eklentisi  ·  ${nuvioRepos.size} Nuvio deposu  ·  $nuvioCount JS scraper"

        if (addons.isEmpty() && nuvioRepos.isEmpty()) {
            list.addView(
                infoCard(
                    "İlk kaynağını ekle",
                    "Özel veya topluluk tarafından sunulan uyumlu eklentilerin adresini buraya ekleyebilirsin."
                )
            )
        }

        nuvioRepos.forEach { repo ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(17), dp(22), dp(17))
                background = panelBackground()
            }
            card.addView(TextView(this).apply {
                text = repo.name
                textSize = 21f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = "NUVIO JS  ·  ${repo.scrapers.size} scraper  ·  v${repo.version}"
                textSize = 14f
                setTextColor(Color.rgb(125, 151, 255))
                setPadding(0, dp(5), 0, dp(4))
            })
            card.addView(TextView(this).apply {
                text = repo.manifestUrl
                textSize = 13f
                setTextColor(Color.rgb(133, 164, 225))
                maxLines = 2
            })
            card.addView(TextView(this).apply {
                text = repo.scrapers.take(8).joinToString("  ·  ") { it.name } +
                    if (repo.scrapers.size > 8) "  ·  +${repo.scrapers.size - 8}" else ""
                textSize = 13f
                setTextColor(Color.rgb(155, 165, 184))
                setPadding(0, dp(7), 0, 0)
                maxLines = 2
            })
            val scraperHeading = TextView(this).apply {
                text = "SAĞLAYICILAR"
                textSize = 13f
                setTextColor(Color.rgb(125, 151, 255))
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(14), 0, dp(6))
            }
            card.addView(scraperHeading)
            repo.scrapers.forEach { scraper ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(8), dp(4), dp(8))
                }
                val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                details.addView(TextView(this).apply {
                    text = scraper.name
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                })
                details.addView(TextView(this).apply {
                    val flags = mutableListOf<String>()
                    if (scraper.hasSettings) flags.add("Ayarları var")
                    if (scraper.limited) flags.add("Sınırlı")
                    if (scraper.formats.isNotEmpty()) flags.add(scraper.formats.joinToString("/"))
                    if (scraper.contentLanguage.isNotEmpty()) flags.add(scraper.contentLanguage.joinToString("/"))
                    if (scraper.supportedTypes.isNotEmpty()) flags.add(scraper.supportedTypes.joinToString("/"))
                    if (scraper.supportedPlatforms.isNotEmpty()) flags.add(scraper.supportedPlatforms.joinToString("/"))
                    if (scraper.disabledPlatforms.isNotEmpty()) flags.add("Hariç: ${scraper.disabledPlatforms.joinToString("/")}")
                    if (scraper.supportsExternalPlayer) flags.add("Harici oynatıcı")
                    if (scraper.author.isNotBlank()) flags.add("© ${scraper.author}")
                    text = flags.joinToString(" · ").ifBlank { "Nuvio JS sağlayıcısı" }
                    textSize = 12f
                    setTextColor(Color.rgb(150, 160, 180))
                })
                row.addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(action("Test et").apply {
                    setOnClickListener { showScraperTestDialog(repo.id, scraper) }
                })
                if (scraper.hasSettings) {
                    row.addView(action("Ayarlar").apply {
                        setOnClickListener { showScraperSettings(repo.id, scraper) }
                    })
                }
                val toggle = action(if (scraper.enabled) "Açık" else "Kapalı", primary = scraper.enabled).apply {
                    setOnClickListener {
                        nuvioRepository.setScraperEnabled(repo.id, scraper.id, !scraper.enabled)
                        renderAddons()
                    }
                }
                row.addView(toggle)
                card.addView(row)
            }
            val repoActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val allEnabled = repo.scrapers.all { it.enabled }
            repoActions.addView(action(if (allEnabled) "Tümünü kapat" else "Tümünü aç").apply {
                setOnClickListener {
                    repo.scrapers.forEach { nuvioRepository.setScraperEnabled(repo.id, it.id, !allEnabled) }
                    renderAddons()
                }
            })
            repoActions.addView(action("Depoyu güncelle").apply {
                setOnClickListener { refreshNuvioRepository(repo.id, repo.name) }
            })
            repoActions.addView(action("Depoyu kaldır").apply {
                setOnClickListener { confirmRemoveNuvio(repo.id, repo.name) }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            card.addView(repoActions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addCardToList(card)
        }

        addons.forEach { addon ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(17), dp(22), dp(17))
                background = panelBackground()
            }
            val name = TextView(this).apply {
                text = addon.name
                textSize = 21f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            card.addView(name)
            val version = TextView(this).apply {
                text = "Sürüm ${addon.version}  ·  ${addon.types.joinToString().ifBlank { "tür bilgisi yok" }}"
                textSize = 14f
                setTextColor(Color.rgb(143, 155, 176))
                setPadding(0, dp(5), 0, dp(4))
            }
            card.addView(version)
            val url = TextView(this).apply {
                text = addon.baseUrl
                textSize = 13f
                setTextColor(Color.rgb(133, 164, 225))
                maxLines = 2
            }
            card.addView(url)
            val remove = action("Eklentiyi kaldır").apply {
                setOnClickListener { confirmRemove(addon.baseUrl, addon.name) }
            }
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            card.addView(remove, p)
            addCardToList(card)
        }
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = "https://sunucu-adresi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Kaynak eklentisi ekle")
            .setMessage("Stremio manifest adresini veya Nuvio JS deposunun manifest.json adresini gir.")
            .setView(input)
            .setNegativeButton("İptal", null)
            .setPositiveButton("Doğrula ve ekle", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = input.text?.toString().orEmpty().trim()
                if (address.isBlank()) {
                    input.error = "Adres gerekli"
                    return@setOnClickListener
                }
                if (busy) return@setOnClickListener
                busy = true
                status.text = "Manifest doğrulanıyor…"
                dialog.dismiss()
                worker.execute {
                    try {
                        val addon = try {
                            repository.addAddon(address)
                        } catch (_: Exception) {
                            val nuvio = nuvioRepository.addRepository(address)
                            runOnUiThread {
                                busy = false
                                status.text = "${nuvio.name} eklendi · ${nuvio.scrapers.size} Nuvio JS scraper"
                                renderAddons()
                            }
                            return@execute
                        }
                        runOnUiThread {
                            busy = false
                            status.text = "${addon.name} Stremio eklentisi eklendi."
                            renderAddons()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            busy = false
                            status.text = e.message ?: "Eklenti eklenemedi."
                        }
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(620), LinearLayout.LayoutParams.WRAP_CONTENT)
        input.requestFocus()
    }

    private fun confirmRemove(url: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Eklenti kaldırılsın mı?")
            .setMessage("$name kaynak listesinden kaldırılacak.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaldır") { _, _ ->
                repository.removeAddon(url)
                renderAddons()
            }
            .show()
    }

    private fun showScraperSettings(repositoryId: String, scraper: NuvioScraperInfo) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            maxLines = 12
            setText(nuvioRepository.scraperSettings(repositoryId, scraper.id))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            typeface = Typeface.MONOSPACE
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${scraper.name} · Ayarlar")
            .setMessage("Scraper ayarlarını JSON nesnesi olarak düzenle. Kaydedilen değerler kaynak aramasında JavaScript motoruna aktarılır.")
            .setView(input)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaydet", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    nuvioRepository.setScraperSettings(repositoryId, scraper.id, input.text.toString())
                    dialog.dismiss()
                    status.text = "${scraper.name} ayarları kaydedildi."
                } catch (error: Exception) {
                    input.error = error.message ?: "Geçersiz JSON"
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(680), LinearLayout.LayoutParams.WRAP_CONTENT)
        input.requestFocus()
    }

    private fun showScraperTestDialog(repositoryId: String, scraper: NuvioScraperInfo) {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), 0)
        }
        val typeChoices = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val movieChoice = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Film"; isChecked = true; setTextColor(Color.WHITE) }
        val tvChoice = android.widget.RadioButton(this).apply { id = View.generateViewId(); text = "Dizi"; setTextColor(Color.WHITE) }
        typeChoices.addView(movieChoice)
        typeChoices.addView(tvChoice)
        fields.addView(typeChoices)
        val input = EditText(this).apply {
            hint = "TMDB içerik kimliği · Örn. 550"
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val season = EditText(this).apply {
            hint = "Sezon numarası"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        val episode = EditText(this).apply {
            hint = "Bölüm numarası"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }
        typeChoices.setOnCheckedChangeListener { _, checkedId ->
            val isTv = checkedId == tvChoice.id
            season.visibility = if (isTv) View.VISIBLE else View.GONE
            episode.visibility = if (isTv) View.VISIBLE else View.GONE
        }
        fields.addView(input)
        fields.addView(season)
        fields.addView(episode)
        val dialog = AlertDialog.Builder(this)
            .setTitle("${scraper.name} · Sağlayıcı testi")
            .setMessage("Yalnızca seçilen scraper çalıştırılır. Film veya dizi için TMDB kimliğini gir.")
            .setView(fields)
            .setNegativeButton("İptal", null)
            .setPositiveButton("Testi başlat", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mediaId = input.text?.toString()?.trim().orEmpty()
                val isTv = tvChoice.isChecked
                val seasonNumber = if (isTv) season.text?.toString()?.toIntOrNull() else null
                val episodeNumber = if (isTv) episode.text?.toString()?.toIntOrNull() else null
                if (mediaId.isBlank()) {
                    input.error = "TMDB kimliği gerekli"
                    return@setOnClickListener
                }
                if (isTv && (seasonNumber == null || seasonNumber !in 1..100 || episodeNumber == null || episodeNumber !in 1..500)) {
                    season.error = if (seasonNumber == null || seasonNumber !in 1..100) "Geçerli sezon gir" else null
                    episode.error = if (episodeNumber == null || episodeNumber !in 1..500) "Geçerli bölüm gir" else null
                    return@setOnClickListener
                }
                if (busy) return@setOnClickListener
                busy = true
                status.text = "${scraper.name} test ediliyor…"
                dialog.dismiss()
                worker.execute {
                    val result = runCatching {
                        nuvioRepository.testScraper(
                            repositoryId, scraper.id, mediaId,
                            mediaType = if (isTv) "tv" else "movie",
                            season = seasonNumber, episode = episodeNumber
                        )
                    }
                    runOnUiThread {
                        busy = false
                        val report = result.getOrNull()
                        val message = if (report != null) {
                            "${report.message}\n\nSüre: ${report.elapsedMs} ms\nDönen kaynak: ${report.streamCount}"
                        } else {
                            result.exceptionOrNull()?.message ?: "Sağlayıcı testi başarısız."
                        }
                        status.text = "${scraper.name}: $message"
                        AlertDialog.Builder(this@MediaAddonsActivity)
                            .setTitle(if (report?.successful == true) "Sağlayıcı testi başarılı" else "Sağlayıcı testi tamamlandı")
                            .setMessage(message)
                            .setPositiveButton("Tamam", null)
                            .show()
                    }
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(620), LinearLayout.LayoutParams.WRAP_CONTENT)
        input.requestFocus()
    }
    private fun refreshNuvioRepository(repositoryId: String, name: String) {
        if (busy) return
        busy = true
        status.text = "$name deposu güncelleniyor…"
        worker.execute {
            try {
                val updated = nuvioRepository.refreshRepository(repositoryId)
                runOnUiThread {
                    busy = false
                    status.text = "${updated.name} güncellendi · ${updated.scrapers.size} scraper"
                    renderAddons()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    busy = false
                    status.text = error.message ?: "Depo güncellenemedi."
                }
            }
        }
    }

    private fun confirmRemoveNuvio(repositoryId: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle("Nuvio deposu kaldırılsın mı?")
            .setMessage("$name deposu ve indirilen scraper önbelleği kaldırılacak.")
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaldır") { _, _ ->
                nuvioRepository.removeRepository(repositoryId)
                renderAddons()
            }
            .show()
    }

    private fun infoCard(title: String, description: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = panelBackground()
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 21f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = description
            textSize = 16f
            setTextColor(Color.rgb(169, 179, 195))
            setPadding(0, dp(7), 0, 0)
        })
        return box
    }

    private fun addCardToList(view: View) {
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
        list.addView(view, params)
    }

    private fun action(label: String, primary: Boolean = false): TextView = TextView(this).apply {
        text = label
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setTextColor(Color.WHITE)
        setPadding(dp(20), dp(13), dp(20), dp(13))
        val idle = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (primary) Color.rgb(66, 91, 180) else Color.rgb(34, 39, 51))
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
            (view as TextView).setTextColor(if (focused) Color.rgb(9, 11, 17) else Color.WHITE)
            view.scaleX = if (focused) 1.04f else 1f
            view.scaleY = if (focused) 1.04f else 1f
        }
    }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Color.rgb(20, 24, 34))
        setStroke(dp(1), Color.rgb(42, 49, 65))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
