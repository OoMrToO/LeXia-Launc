package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** TV-friendly manager for HTTPS addon repositories and their metadata catalogues. */
class AddonManagerActivity : Activity() {
    companion object {
        private const val EXTRA_MATCH_REPOSITORY = "addon_match_repository"
        private const val EXTRA_MATCH_TYPE = "addon_match_type"
        private const val EXTRA_MATCH_ID = "addon_match_id"
        private const val EXTRA_MATCH_TMDB_ID = "addon_match_tmdb_id"
        private const val EXTRA_AUTO_TITLE = "addon_auto_title"
        private const val EXTRA_AUTO_TMDB_ID = "addon_auto_tmdb_id"
        private const val EXTRA_AUTO_TYPE = "addon_auto_type"

        fun openAutoSearch(context: android.content.Context, title: String, tmdbId: Int, mediaType: String) {
            context.startActivity(android.content.Intent(context, AddonManagerActivity::class.java).apply {
                putExtra(EXTRA_AUTO_TITLE, title)
                putExtra(EXTRA_AUTO_TMDB_ID, tmdbId)
                putExtra(EXTRA_AUTO_TYPE, mediaType)
            })
        }

        fun openMatch(context: android.content.Context, repository: String, type: String, itemId: String, tmdbId: Int = 0) {
            context.startActivity(android.content.Intent(context, AddonManagerActivity::class.java).apply {
                putExtra(EXTRA_MATCH_REPOSITORY, repository)
                putExtra(EXTRA_MATCH_TYPE, type)
                putExtra(EXTRA_MATCH_ID, itemId)
                putExtra(EXTRA_MATCH_TMDB_ID, tmdbId)
            })
        }
    }

    private data class ProviderOption(
        val repository: String,
        val addon: Addons.Installed,
        val provider: Addons.ScraperProvider,
        val latencyMs: Long = 0L
    )

    private data class FoundProviderStream(
        val option: ProviderOption,
        val stream: Addons.ProviderStream,
        val subtitles: List<Addons.ProviderSubtitle>
    )

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var urlInput: EditText
    private lateinit var addonList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 20).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            setBackgroundColor(android.graphics.Color.rgb(12, 14, 19))
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = false
        }
        val title = TextView(this).apply {
            text = "Eklentiler"
            textSize = 30f
            letterSpacing = -0.02f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF5F7FA.toInt())
        }
        root.addView(title)
        val subtitle = TextView(this).apply {
            text = "İçerik kaynaklarını ve sağlayıcı depolarını yönet"
            textSize = 15f
            setTextColor(0xFF9BA7B8.toInt())
            setPadding(0, 4, 0, pad)
        }
        root.addView(subtitle)

        val installCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = panelBackground()
        }
        installCard.addView(TextView(this).apply {
            text = "Eklenti yükle"
            textSize = 19f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF5F7FA.toInt())
        })
        installCard.addView(TextView(this).apply {
            text = "Stremio veya Nuvio uyumlu HTTPS depo adresini ekle."
            textSize = 13f
            setTextColor(0xFF9BA7B8.toInt())
            setPadding(0, 5, 0, pad / 2)
        })
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        urlInput = EditText(this).apply {
            hint = "https://ornek.com"
            setSingleLine(true)
            textSize = 15f
            setTextColor(0xFFF5F7FA.toInt())
            setHintTextColor(0xFF778294.toInt())
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF111722.toInt())
                cornerRadius = (pad / 2).toFloat()
                setStroke(1, 0xFF394352.toInt())
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    addRepositoryFromInput()
                    true
                } else false
            }
        }
        inputRow.addView(urlInput, LinearLayout.LayoutParams(0, pad * 2, 1f))
        val add = Button(this).apply {
            text = "Ekle"
            isAllCaps = false
            textSize = 15f
            minHeight = pad * 2
            setTextColor(0xFFFFFFFF.toInt())
            background = buttonBackground(0xFF6750A4.toInt())
            setOnClickListener { addRepositoryFromInput() }
            applyRemoteFocusStyle()
        }
        inputRow.addView(add, LinearLayout.LayoutParams(-2, pad * 2).apply { leftMargin = pad / 2 })
        installCard.addView(inputRow)
        root.addView(installCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = pad / 2 })

        status = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB8BEC9.toInt())
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(status)

        val section = TextView(this).apply {
            text = "YÜKLÜ EKLENTİLER"
            textSize = 17f
            letterSpacing = 0.04f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFF5F7FA.toInt())
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(section)
        val collectionsButton = Button(this).apply {
            text = "▦  Koleksiyonları yönet"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFFE9E4FF.toInt())
            background = buttonBackground(0xFF29243A.toInt())
            setOnClickListener { manageCollections() }
            applyRemoteFocusStyle()
        }
        root.addView(collectionsButton, LinearLayout.LayoutParams(-1, pad * 2).apply { bottomMargin = pad / 2 })
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        addonList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isFocusable = false }
        scroll.addView(addonList)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val history = Button(this).apply {
            text = "◷  İzleme geçmişi"
            isAllCaps = false
            textSize = 14f
            setTextColor(0xFFE9E4FF.toInt())
            background = buttonBackground(0xFF29243A.toInt())
            setOnClickListener { showWatchHistory() }
            applyRemoteFocusStyle()
        }
        root.addView(history, LinearLayout.LayoutParams(-1, pad * 2).apply { topMargin = pad / 2 })
        setContentView(root)
        render(addonList)
        urlInput.requestFocus()
        val autoTmdbId = intent.getIntExtra(EXTRA_AUTO_TMDB_ID, 0)
        if (autoTmdbId > 0) {
            startAutoSearch(
                intent.getStringExtra(EXTRA_AUTO_TITLE).orEmpty(),
                autoTmdbId,
                intent.getStringExtra(EXTRA_AUTO_TYPE).orEmpty()
            )
        }
        val matchedRepository = intent.getStringExtra(EXTRA_MATCH_REPOSITORY).orEmpty()
        val matchedType = intent.getStringExtra(EXTRA_MATCH_TYPE).orEmpty()
        val matchedId = intent.getStringExtra(EXTRA_MATCH_ID).orEmpty()
        val matchedTmdbId = intent.getIntExtra(EXTRA_MATCH_TMDB_ID, 0)
        if (matchedRepository.isNotBlank() && matchedType.isNotBlank() && matchedId.isNotBlank()) {
            openMatchedItem(matchedRepository, matchedType, matchedId, matchedTmdbId)
        }
    }

    private fun openMatchedItem(repository: String, type: String, itemId: String, tmdbId: Int) {
        status.text = "Eklentideki içerik yükleniyor…"
        worker.execute {
            try {
                val addon = Addons.fetchManifest(repository)
                val details = Addons.fetchMetaDetails(addon, type, itemId)
                val meta = details.item
                val isSeries = meta.type.equals("series", true) || meta.type.equals("tv", true) ||
                    type.equals("series", true) || type.equals("tv", true)
                val progressKey = if (tmdbId > 0) tmdbId.toString() else meta.id.ifBlank { itemId }
                val progressId = addon.baseUrl + "|" + progressKey
                val film = Pick(
                    title = meta.name.ifBlank { itemId },
                    meta = listOf(meta.releaseInfo, meta.genres.take(3).joinToString(", "))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    overview = meta.description,
                    backdrop = meta.background.ifBlank { meta.poster },
                    reason = "Ana ekranda eşleşti · " + addon.name,
                    trailer = "",
                    logo = "",
                    imdb = meta.imdbRating,
                    year = meta.releaseInfo.take(4),
                    genres = meta.genres,
                    runtime = meta.runtime,
                    poster = meta.poster,
                    kind = if (isSeries) "tv" else "movie",
                    seasons = meta.seasons
                )
                runOnUiThread {
                    status.text = "Eşleşme bulundu: " + film.title
                    if (isSeries) showSeriesOverview(film, addon.name, progressId, details.episodes, addon, tmdbId)
                    else if (tmdbId > 0 && addon.scrapers.isNotEmpty()) {
                        chooseProviderAndSearch(addon, tmdbId, "movie", null, null, film.title)
                    } else askMovieSource(film, progressId)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = e.message ?: "Eklenti içeriği açılamadı."
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun panelBackground(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF1A202A.toInt())
            cornerRadius = resources.displayMetrics.density * 18f
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), 0xFF303B49.toInt())
        }

    private fun buttonBackground(color: Int, stroke: Int = 0xFF3A4554.toInt()) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = resources.displayMetrics.density * 12f
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
        }

    private fun Button.applyRemoteFocusStyle() {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnFocusChangeListener { view, focused ->
            view.background = buttonBackground(
                if (focused) 0xFF3A3156.toInt() else 0xFF292F3A.toInt(),
                if (focused) 0xFFB9A5FF.toInt() else 0xFF3A4554.toInt()
            )
        }
    }

    private fun addRepositoryFromInput() {
        val raw = urlInput.text.toString().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, "Önce eklenti adresini gir.", Toast.LENGTH_SHORT).show()
            urlInput.requestFocus()
            return
        }
        try {
            Addons.add(this, raw)
            urlInput.setText("")
            render(addonList)
            Toast.makeText(this, "Eklenti adresi eklendi.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            status.text = e.message ?: "Adres eklenemedi."
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_BUTTON_A)) {
            val focused = currentFocus
            if (focused is Button && focused.isEnabled) {
                focused.performClick()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun render(list: LinearLayout) {
        list.removeAllViews()
        val urls = AddonLibrary.repositories(this)
        status.text = if (urls.isEmpty()) "Henüz eklenti eklenmedi." else "${AddonLibrary.enabledRepositories(this).size}/${urls.size} depo etkin"
        urls.forEachIndexed { repositoryIndex, address ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val inset = (resources.displayMetrics.density * 20).toInt()
                setPadding(inset, inset, inset, inset)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1B2029.toInt())
                    cornerRadius = resources.displayMetrics.density * 18f
                    setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), 0xFF343C49.toInt())
                }
                isFocusable = false
                descendantFocusability = android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS
            }
            val heading = TextView(this).apply {
                text = "BAĞLI DEPO"
                textSize = 11f
                letterSpacing = 0.12f
                setTextColor(0xFF9BA7B8.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            card.addView(heading)
            val label = TextView(this).apply {
                text = address
                textSize = 15f
                setTextColor(0xFFF1F4F8.toInt())
                maxLines = 2
                setPadding(0, (resources.displayMetrics.density * 7).toInt(), 0, (resources.displayMetrics.density * 12).toInt())
            }
            card.addView(label)
            val connectionPrefs = getSharedPreferences("addon_connection_status", MODE_PRIVATE)
            val lastSuccess = connectionPrefs.getLong("success:$address", 0L)
            val lastError = connectionPrefs.getString("error:$address", null)
            val lastCheck = if (lastSuccess > 0L) SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(lastSuccess)) else "Henüz test edilmedi"
            val connectionLine = TextView(this).apply {
                text = when {
                    lastError != null -> "● Bağlantı hatası · $lastError"
                    lastSuccess > 0L -> {
                        val latency = connectionPrefs.getLong("latency:$address", 0L)
                        "● Bağlantı başarılı" + (if (latency > 0L) " · ${latency} ms" else "") + " · Son kontrol: $lastCheck"
                    }
                    else -> "● Bağlantı durumu bilinmiyor"
                }
                textSize = 12f
                setTextColor(if (lastError != null) 0xFFFFB4AB.toInt() else if (lastSuccess > 0L) 0xFF8ED6A5.toInt() else 0xFF9BA7B8.toInt())
                setPadding(0, 0, 0, (resources.displayMetrics.density * 12).toInt())
            }
            card.addView(connectionLine)
            val repositoryControls = LinearLayout(this).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            fun controlButton(labelText: String, color: Int, action: () -> Unit): Button = Button(this).apply {
                text = labelText
                isAllCaps = false
                textSize = 12f
                minHeight = (resources.displayMetrics.density * 42).toInt()
                setTextColor(0xFFFFFFFF.toInt())
                background = buttonBackground(color)
                setOnClickListener { action() }
                applyRemoteFocusStyle()
            }
            val enabled = AddonLibrary.isRepositoryEnabled(this, address)
            repositoryControls.addView(controlButton(if (enabled) "Etkin" else "Devre dışı", if (enabled) 0xFF315B4A.toInt() else 0xFF59433A.toInt()) {
                AddonLibrary.setRepositoryEnabled(this@AddonManagerActivity, address, !enabled)
                render(list)
            })
            repositoryControls.addView(controlButton("↑", 0xFF394B66.toInt()) {
                AddonLibrary.moveRepository(this@AddonManagerActivity, address, -1)
                render(list)
            }.apply { isEnabled = repositoryIndex > 0 })
            repositoryControls.addView(controlButton("↓", 0xFF394B66.toInt()) {
                AddonLibrary.moveRepository(this@AddonManagerActivity, address, 1)
                render(list)
            }.apply { isEnabled = repositoryIndex < urls.lastIndex })
            card.addView(repositoryControls)
            val actions = LinearLayout(this).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            val test = Button(this).apply {
                text = "Bağlantıyı test et"
                isAllCaps = false
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF315B4A.toInt())
                    cornerRadius = resources.displayMetrics.density * 12f
                }
                background = buttonBackground(0xFF315B4A.toInt())
                setOnClickListener { testRepository(address, connectionLine) }
                applyRemoteFocusStyle()
            }
            val inspect = Button(this).apply {
                text = "Katalogları yükle"
                isAllCaps = false
                textSize = 13f
                setTextColor(0xFFFFFFFF.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF394B66.toInt())
                    cornerRadius = resources.displayMetrics.density * 12f
                }
                background = buttonBackground(0xFF394B66.toInt())
                setOnClickListener { loadCatalogs(address) }
                applyRemoteFocusStyle()
            }
            val remove = Button(this).apply {
                text = "Kaldır"
                isAllCaps = false
                textSize = 13f
                setTextColor(0xFFFFB4AB.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF392629.toInt())
                    cornerRadius = resources.displayMetrics.density * 12f
                }
                setOnClickListener {
                    Addons.remove(this@AddonManagerActivity, address)
                    render(list)
                }
                applyRemoteFocusStyle()
            }
            actions.addView(test)
            actions.addView(inspect)
            actions.addView(remove)
            card.addView(actions)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (resources.displayMetrics.density * 14).toInt() })
            loadProviderCards(address, card)
        }
    }

    /** Render Nuvio scraper providers inside each installed repository card. */
    private fun loadProviderCards(repository: String, card: LinearLayout) {
        val density = resources.displayMetrics.density
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((density * 12).toInt(), (density * 12).toInt(), (density * 12).toInt(), (density * 8).toInt())
            background = buttonBackground(0xFF141A24.toInt(), 0xFF303B49.toInt())
        }
        section.addView(TextView(this).apply {
            text = "SAĞLAYICILAR"
            textSize = 12f
            letterSpacing = 0.1f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFFB9A5FF.toInt())
        })
        section.addView(TextView(this).apply {
            text = "Sağlayıcı listesi yükleniyor…"
            textSize = 13f
            setTextColor(0xFF9BA7B8.toInt())
        })
        card.addView(section, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (density * 12).toInt() })
        worker.execute {
            val result = runCatching { Addons.fetchManifest(repository) }
            runOnUiThread {
                section.removeAllViews()
                section.addView(TextView(this).apply {
                    text = "SAĞLAYICILAR"
                    textSize = 12f
                    letterSpacing = 0.1f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(0xFFB9A5FF.toInt())
                })
                val addon = result.getOrNull()
                if (addon == null) {
                    section.addView(TextView(this).apply {
                        text = "Sağlayıcı listesi alınamadı: " + (result.exceptionOrNull()?.message ?: "Manifest okunamadı")
                        textSize = 13f
                        setTextColor(0xFFFFB4AB.toInt())
                    })
                    return@runOnUiThread
                }
                if (addon.format != "nuvio-js") {
                    section.addView(TextView(this).apply {
                        text = "Bu depo Stremio kataloğu sunuyor; Nuvio sağlayıcı listesi içermiyor. " + addon.catalogs.size + " katalog mevcut."
                        textSize = 13f
                        setTextColor(0xFFB8BEC9.toInt())
                    })
                    return@runOnUiThread
                }
                section.addView(TextView(this).apply {
                    text = addon.scrapers.size.toString() + " sağlayıcı bulundu"
                    textSize = 13f
                    setTextColor(0xFF9BA7B8.toInt())
                    setPadding(0, (density * 4).toInt(), 0, (density * 8).toInt())
                })
                if (addon.scrapers.isEmpty()) {
                    section.addView(TextView(this).apply {
                        text = "Manifestte sağlayıcı tanımı yok."
                        setTextColor(0xFFFFD27A.toInt())
                    })
                }
                addon.scrapers.forEach { provider ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((density * 10).toInt(), (density * 9).toInt(), (density * 10).toInt(), (density * 9).toInt())
                        background = buttonBackground(0xFF202938.toInt(), 0xFF354154.toInt())
                    }
                    row.addView(TextView(this).apply {
                        text = provider.name
                        textSize = 15f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setTextColor(0xFFF1F4F8.toInt())
                    })
                    row.addView(TextView(this).apply {
                        text = (if (provider.supportedTypes.isEmpty()) "Film / Dizi" else provider.supportedTypes.joinToString(" · ")) +
                            "  |  " + provider.catalogs.size + " katalog" +
                            (if (provider.version.isNotBlank()) "  |  v" + provider.version else "")
                        textSize = 12f
                        setTextColor(0xFF9BA7B8.toInt())
                        setPadding(0, (density * 3).toInt(), 0, (density * 6).toInt())
                    })
                    row.addView(TextView(this).apply {
                        text = provider.description.ifBlank { "Açıklama bulunmuyor." }
                        textSize = 12f
                        maxLines = 2
                        setTextColor(0xFFB8BEC9.toInt())
                    })
                    val enabled = Addons.isProviderEnabled(this, repository, provider)
                    val controls = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                    fun providerButton(label: String, action: () -> Unit) = Button(this).apply {
                        text = label
                        isAllCaps = false
                        textSize = 12f
                        minHeight = (density * 40).toInt()
                        setTextColor(0xFFFFFFFF.toInt())
                        background = buttonBackground(0xFF394B66.toInt())
                        setOnClickListener { action() }
                        applyRemoteFocusStyle()
                    }
                    controls.addView(providerButton(if (enabled) "Etkin" else "Devre dışı") {
                        Addons.setProviderEnabled(this@AddonManagerActivity, repository, provider, !enabled)
                        loadProviderCards(repository, card)
                    })
                    controls.addView(providerButton("Ayarlar") {
                        AlertDialog.Builder(this@AddonManagerActivity)
                            .setTitle(provider.name)
                            .setMessage(provider.description.ifBlank { "Açıklama yok." } + "\\n\\nSürüm: " + provider.version +
                                "\\nDosya: " + provider.filename + "\\nFormatlar: " + provider.formats.joinToString(", ") +
                                "\\nKataloglar: " + provider.catalogs.joinToString(", ") { it.name })
                            .setPositiveButton("Sağlayıcı ayarları") { _, _ -> manageProviderSettings(repository, provider.id) }
                            .setNeutralButton("Ağ izinleri") { _, _ -> manageProviderNetworkPermissions(repository, provider.id) }
                            .setNegativeButton("Kapat", null)
                            .show()
                    })
                    controls.addView(providerButton("Kaynakları dene") {
                        if (!enabled) Toast.makeText(this@AddonManagerActivity, "Önce sağlayıcıyı etkinleştir.", Toast.LENGTH_SHORT).show()
                        else promptProviderTest(repository, addon, provider)
                    })
                    row.addView(controls)
                    section.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (density * 7).toInt() })
                }
            }
        }
    }

    private fun showWatchHistory() {
        val recent = WatchProgress.recent(this, 50)
        if (recent.isEmpty()) {
            Toast.makeText(this, "Henüz izleme geçmişi yok.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = recent.map { item ->
            val title = item.title.ifBlank { "İçerik" }
            val detail = if (item.mediaType == "movie") "Film" else "S${item.season} B${item.episode}"
            val progress = when {
                item.isComplete -> "Tamamlandı"
                item.durationMs > 0L -> "%${(item.positionMs * 100 / item.durationMs).coerceIn(0, 100)}"
                else -> "İzleniyor"
            }
            "$title · $detail · $progress"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Son izlenenler")
            .setItems(labels) { _, index ->
                val item = recent[index]
                val tmdbId = item.tmdbId.takeIf { it > 0 } ?: item.seriesId.substringAfterLast("|").toIntOrNull()
                if (tmdbId != null && tmdbId > 0 && item.title.isNotBlank()) {
                    startAutoSearch(
                        item.title, tmdbId, item.mediaType,
                        if (item.mediaType == "movie") null else item.season,
                        if (item.mediaType == "movie") null else item.episode
                    )
                } else {
                    Toast.makeText(this, "Bu kayıt için otomatik kaynak bilgisi bulunmuyor.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun askUrl() {
        val input = EditText(this).apply {
            hint = "https://ornek.com"
            setSingleLine(true)
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("Eklenti deposu")
            .setMessage("Manifest adresini veya depo adresini gir.")
            .setView(input)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Ekle") { _, _ ->
                try {
                    Addons.add(this, input.text.toString())
                    recreate()
                } catch (e: Exception) {
                    status.text = e.message ?: "Adres eklenemedi."
                }
            }.show()
    }

    private fun testRepository(address: String, line: TextView) {
        line.text = "● Bağlantı kontrol ediliyor…"
        line.setTextColor(0xFFFFD27A.toInt())
        worker.execute {
            try {
                Addons.clearCache(address)
                val startedAt = System.nanoTime()
                val manifest = Addons.fetchManifest(address)
                val latencyMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                val now = System.currentTimeMillis()
                getSharedPreferences("addon_connection_status", MODE_PRIVATE).edit()
                    .putLong("latency:$address", latencyMs)
                    .putLong("success:$address", now)
                    .remove("error:$address")
                    .commit()
                runOnUiThread {
                    val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(now))
                    line.text = "● Bağlantı başarılı · ${manifest.name} · ${latencyMs} ms · $stamp"
                    line.setTextColor(0xFF8ED6A5.toInt())
                    Toast.makeText(this, "Eklenti deposuna erişim başarılı.", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                val reason = (e.message ?: "Bağlantı kurulamadı").take(120)
                getSharedPreferences("addon_connection_status", MODE_PRIVATE).edit()
                    .putString("error:$address", reason)
                    .commit()
                runOnUiThread {
                    line.text = "● Bağlantı hatası · $reason"
                    line.setTextColor(0xFFFFB4AB.toInt())
                    Toast.makeText(this, "Eklenti bağlantısı başarısız.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun loadCatalogs(address: String) {
        status.text = "Manifest ve kataloglar yükleniyor…"
        worker.execute {
            try {
                val addon = Addons.fetchManifest(address)
                if (addon.format == "nuvio-js") {
                    runOnUiThread {
                        status.text = addon.name + " · Nuvio deposu tanındı · " +
                            addon.scrapers.size + " sağlayıcı"
                        val labels = addon.scrapers.map { provider ->
                            provider.name + " · " +
                                (if (provider.supportedTypes.isEmpty()) "Tür belirtilmemiş"
                                else provider.supportedTypes.joinToString(", ")) +
                                " · " + provider.catalogs.size + " katalog" +
                                (if (provider.enabled) "" else " · devre dışı")
                        }.toTypedArray()
                        if (labels.isEmpty()) {
                            status.text = addon.name + " · Manifestte sağlayıcı bulunamadı."
                            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }

                        // Show the safety notice separately; combining setMessage and setItems
                        // can leave Android TV displaying only the message instead of the list.
                        AlertDialog.Builder(this)
                            .setTitle(addon.name + " · Güvenlik")
                            .setMessage(
                                "Sağlayıcılar izole JavaScript ortamında çalıştırılır. " +
                                    "Ağ erişimi varsayılan olarak kapalıdır. Gerekli HTTPS alan adlarına " +
                                    "Ağ izinleri bölümünden izin vermen gerekir. " +
                                    "Yalnızca kullanım hakkına sahip olduğun medya içeriklerini oynat."
                            )
                            .setNegativeButton("Kapat", null)
                            .setPositiveButton("Devam et") { _, _ ->
                                AlertDialog.Builder(this)
                                    .setTitle(addon.name + " · Sağlayıcılar (" + labels.size + ")")
                                    .setItems(labels) { _, index ->
                                        val provider = addon.scrapers[index]
                                        AlertDialog.Builder(this)
                                            .setTitle(provider.name)
                                            .setMessage(
                                                provider.description.ifBlank { "Açıklama yok." } + "\n\n" +
                                                    "Sürüm: " + provider.version + "\n" +
                                                    "Dosya: " + provider.filename + "\n" +
                                                    "Formatlar: " + provider.formats.joinToString(", ") + "\n" +
                                                    "Kataloglar: " + provider.catalogs.joinToString("\n") { catalog ->
                                                        catalog.name + " (" + catalog.type + ")" +
                                                            if (catalog.extras.isEmpty()) "" else " · " + catalog.extras.joinToString(", ")
                                                    }
                                            )
                                            .setPositiveButton("Kaynakları ara") { _, _ -> promptProviderTest(address, addon, provider) }
                                            .setNeutralButton("Ağ izinleri") {
                                                _, _ -> manageProviderNetworkPermissions(address, provider.id)
                                            }
                                            .setNegativeButton("Ayarlar") { _, _ ->
                                                manageProviderSettings(address, provider.id)
                                            }
                                            .show()
                                    }
                                    .setNegativeButton("Kapat", null)
                                    .show()
                            }
                            .show()
                    }
                } else {
                    val orderedCatalogs = AddonLibrary.orderedCatalogs(this, address, addon.catalogs)
                    val results = orderedCatalogs.map { catalog ->
                        catalog to runCatching { Addons.fetchCatalog(addon, catalog) }.getOrNull()
                    }
                    runOnUiThread {
                        status.text = if (results.isEmpty()) {
                            addon.name + " · Katalog bulunamadı."
                        } else {
                            addon.name + " · " + results.size + " katalog hazır."
                        }
                        if (results.isNotEmpty()) {
                            showCatalogList(addon, address, results)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = e.message ?: "Eklenti okunamadı." }
            }
        }
    }



    private fun showCatalogList(
        addon: Addons.Installed,
        repository: String,
        results: List<Pair<Addons.Catalog, List<Addons.CatalogItem>?>>
    ) {
        val labels = results.map { (catalog, items) ->
            val enabled = if (AddonLibrary.isCatalogEnabled(this, repository, catalog)) "●" else "○"
            "$enabled  ${catalog.name} · ${items?.size?.toString() ?: "okunamadı"} kayıt"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(addon.name + " · Katalog sırası")
            .setItems(labels) { _, index -> showCatalogActions(addon, repository, results, index) }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showCatalogActions(
        addon: Addons.Installed,
        repository: String,
        results: List<Pair<Addons.Catalog, List<Addons.CatalogItem>?>>,
        index: Int
    ) {
        val (catalog, items) = results[index]
        val enabled = AddonLibrary.isCatalogEnabled(this, repository, catalog)
        val actions = listOf(
            "Kataloğu aç",
            if (enabled) "Kataloğu devre dışı bırak" else "Kataloğu etkinleştir",
            "Koleksiyona ekle",
            "Yukarı taşı",
            "Aşağı taşı"
        )
        AlertDialog.Builder(this)
            .setTitle(catalog.name)
            .setItems(actions.toTypedArray()) { _, action ->
                when (action) {
                    0 -> if (items.isNullOrEmpty()) {
                        Toast.makeText(this, "Bu katalog yüklenemedi veya boş.", Toast.LENGTH_LONG).show()
                    } else showCatalogItems(addon, catalog, items)
                    1 -> {
                        AddonLibrary.setCatalogEnabled(this, repository, catalog, !enabled)
                        showCatalogList(addon, repository, results)
                    }
                    2 -> addCatalogToCollection(repository, catalog)
                    3, 4 -> {
                        AddonLibrary.moveCatalog(this, repository, results.map { it.first }, catalog, if (action == 3) -1 else 1)
                        val reordered = AddonLibrary.orderedCatalogs(this, repository, results.map { it.first })
                        val byKey = results.associateBy { AddonLibrary.catalogKey(repository, it.first) }
                        showCatalogList(addon, repository, reordered.mapNotNull { byKey[AddonLibrary.catalogKey(repository, it)] })
                    }
                }
            }
            .setNegativeButton("Geri") { _, _ -> showCatalogList(addon, repository, results) }
            .show()
    }

    private fun addCatalogToCollection(repository: String, catalog: Addons.Catalog) {
        val current = AddonLibrary.collections(this)
        val labels = current.map { it.name }.toMutableList().apply { add("＋ Yeni koleksiyon oluştur") }
        AlertDialog.Builder(this)
            .setTitle("Koleksiyona ekle")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == current.size) {
                    promptCreateCollection { created ->
                        AddonLibrary.addCatalogToCollection(this, created.id, AddonLibrary.catalogKey(repository, catalog))
                        Toast.makeText(this, "Kataloğa koleksiyon eklendi.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    AddonLibrary.addCatalogToCollection(this, current[index].id, AddonLibrary.catalogKey(repository, catalog))
                    Toast.makeText(this, "Koleksiyona eklendi.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun manageCollections() {
        val collections = AddonLibrary.collections(this)
        val labels = collections.map { "${it.name} · ${it.catalogKeys.size} katalog" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Koleksiyonlar")
            .setItems(labels) { _, index -> showCollectionDetails(collections[index]) }
            .setPositiveButton("Yeni oluştur") { _, _ -> promptCreateCollection() }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun promptCreateCollection(onCreated: ((AddonLibrary.Collection) -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Koleksiyon adı"
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8B929E.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("Yeni koleksiyon")
            .setView(input)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Oluştur") { _, _ ->
                runCatching { AddonLibrary.createCollection(this, input.text.toString()) }
                    .onSuccess { created ->
                        onCreated?.invoke(created)
                        if (onCreated == null) manageCollections()
                    }
                    .onFailure { Toast.makeText(this, it.message ?: "Koleksiyon oluşturulamadı.", Toast.LENGTH_LONG).show() }
            }
            .show()
    }

    private fun showCollectionDetails(collection: AddonLibrary.Collection) {
        val current = AddonLibrary.collections(this).firstOrNull { it.id == collection.id } ?: return
        val labels = current.catalogKeys.map { key ->
            val parts = key.split("|")
            val host = runCatching { android.net.Uri.parse(parts.first()).host }.getOrNull().orEmpty()
            "${host.ifBlank { parts.firstOrNull().orEmpty() }} · ${parts.getOrNull(1).orEmpty()} · ${parts.getOrNull(2).orEmpty()}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(current.name)
            .setItems(labels) { _, index ->
                AddonLibrary.removeCatalogFromCollection(this, current.id, current.catalogKeys[index])
                showCollectionDetails(current)
            }
            .setPositiveButton("Yeniden adlandır") { _, _ -> promptRenameCollection(current) }
            .setNeutralButton("Sil") { _, _ ->
                AlertDialog.Builder(this).setMessage("${current.name} koleksiyonu silinsin mi?")
                    .setNegativeButton("Vazgeç", null)
                    .setPositiveButton("Sil") { _, _ ->
                        AddonLibrary.deleteCollection(this, current.id)
                        manageCollections()
                    }.show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun promptRenameCollection(collection: AddonLibrary.Collection) {
        val input = EditText(this).apply {
            setText(collection.name)
            setSingleLine(true)
            setTextColor(0xFFFFFFFF.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("Koleksiyonu yeniden adlandır")
            .setView(input)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaydet") { _, _ ->
                runCatching { AddonLibrary.renameCollection(this, collection.id, input.text.toString()) }
                    .onSuccess { manageCollections() }
                    .onFailure { Toast.makeText(this, it.message ?: "Ad değiştirilemedi.", Toast.LENGTH_LONG).show() }
            }
            .show()
    }

    private fun promptProviderTest(repository: String, addon: Addons.Installed, provider: Addons.ScraperProvider) {
        val idInput = EditText(this).apply {
            hint = "TMDB ID (ör. 550)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
        }
        val seasonInput = EditText(this).apply {
            hint = "Sezon (ör. 1)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
        }
        val episodeInput = EditText(this).apply {
            hint = "Bölüm (ör. 1)"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
        }
        val mediaGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val movie = RadioButton(this).apply { text = "Film"; setTextColor(-1); id = android.view.View.generateViewId() }
        val tv = RadioButton(this).apply { text = "Dizi"; setTextColor(-1); id = android.view.View.generateViewId() }
        val supportsMovie = provider.supportedTypes.isEmpty() || "movie" in provider.supportedTypes
        val supportsTv = provider.supportedTypes.isEmpty() || "tv" in provider.supportedTypes
        if (supportsMovie) mediaGroup.addView(movie)
        if (supportsTv) mediaGroup.addView(tv)
        val defaultTv = !supportsMovie && supportsTv
        mediaGroup.check(if (defaultTv) tv.id else movie.id)
        val episodeFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seasonInput)
            addView(episodeInput)
            visibility = if (defaultTv) android.view.View.VISIBLE else android.view.View.GONE
        }
        mediaGroup.setOnCheckedChangeListener { _, checkedId ->
            episodeFields.visibility = if (checkedId == tv.id) android.view.View.VISIBLE else android.view.View.GONE
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(mediaGroup)
            addView(idInput)
            addView(episodeFields)
        }
        AlertDialog.Builder(this)
            .setTitle(provider.name + " · İçerik ara")
            .setMessage("TMDB numarasını gir. Dizi için sezon ve bölüm bilgisi de gereklidir.")
            .setView(content)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaynakları bul") { _, _ ->
                val tmdbId = idInput.text.toString().trim()
                if (!tmdbId.matches(Regex("^[0-9]{1,12}$"))) {
                    Toast.makeText(this, "Geçerli bir TMDB numarası gir.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val isTv = mediaGroup.checkedRadioButtonId == tv.id
                val season = if (isTv) seasonInput.text.toString().toIntOrNull() else null
                val episode = if (isTv) episodeInput.text.toString().toIntOrNull() else null
                if (isTv && (season == null || season < 0 || episode == null || episode < 1)) {
                    Toast.makeText(this, "Geçerli sezon ve bölüm numarası gir.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                runProvider(repository, addon, provider, tmdbId, if (isTv) "tv" else "movie", season, episode)
            }
            .show()
    }

    /** Searches enabled Nuvio providers from the configured repository scope using the known TMDB ID. */
    private fun startAutoSearch(
        title: String,
        tmdbId: Int,
        mediaType: String,
        initialSeason: Int? = null,
        initialEpisode: Int? = null
    ) {
        val urls = AddonLibrary.enabledRepositories(this)
        val prefs = Prefs(this)
        val repositories = when (prefs.addonMatchMode) {
            "selected" -> listOf(prefs.addonMatchRepository.takeIf { it in urls } ?: urls.firstOrNull().orEmpty())
                .filter { it.isNotBlank() }
            "all" -> urls
            else -> emptyList()
        }
        if (repositories.isEmpty()) {
            status.text = "Önce Ayarlar › Eklenti eşleştirme bölümünden bir depo seç."
            return
        }
        status.text = "$title · film kaynakları hazırlanıyor…"
        worker.execute {
            val options = ArrayList<ProviderOption>()
            for (repository in repositories) {
                val addon = runCatching { Addons.fetchManifest(repository) }.getOrNull() ?: continue
                addon.scrapers.filter { provider ->
                    Addons.isProviderEnabled(this, repository, provider) &&
                        (provider.supportedTypes.isEmpty() || mediaType in provider.supportedTypes) &&
                        !isAnimeOnlyProvider(provider) && !provider.limited
                }.take(8).forEach { provider -> options.add(ProviderOption(repository, addon, provider)) }
            }
            runOnUiThread {
                if (options.isEmpty()) {
                    status.text = "Bu içerik türü için etkin film/dizi sağlayıcısı bulunamadı."
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                } else if (mediaType == "tv" && (initialSeason == null || initialEpisode == null)) {
                    askSeriesEpisode(options, title, tmdbId)
                } else {
                    searchProviderStreams(options, title, tmdbId, mediaType, initialSeason, initialEpisode)
                }
            }
        }
    }

    /** Excludes entries explicitly identified as anime-only from ordinary film/TV searches. */
    private fun isAnimeOnlyProvider(provider: Addons.ScraperProvider): Boolean {
        val identity = (provider.id + " " + provider.name + " " + provider.description).lowercase(Locale.ROOT)
        return listOf("anime", "manga", "anidb", "ani-zone", "anikoto", "aniworld", "animekai", "animepahe")
            .any { identity.contains(it) }
    }

    /** Execute providers for this exact title and display only providers returning playable streams. */
    private fun searchProviderStreams(
        options: List<ProviderOption>, title: String, tmdbId: Int,
        mediaType: String, season: Int?, episode: Int?
    ) {
        if (options.isEmpty()) { status.text = "Aranacak etkin sağlayıcı yok."; return }
        status.text = title + " · " + options.size + " sağlayıcıda gerçek kaynak aranıyor…"
        val found = ArrayList<FoundProviderStream>()
        val failures = ArrayList<String>()
        fun runAt(index: Int) {
            if (index >= options.size) {
                if (found.isEmpty()) {
                    status.text = title + " · oynatılabilir kaynak bulunamadı."
                    val details = failures.take(6).joinToString("\n")
                    AlertDialog.Builder(this)
                        .setTitle("Kaynak bulunamadı")
                        .setMessage("Etkin sağlayıcılar bu içerik için oynatılabilir bağlantı döndürmedi. Sağlayıcıların istediği HTTPS alan adları için Ağ izinleri gerekebilir." +
                            if (details.isBlank()) "" else "\n\nKontrol sonuçları:\n" + details)
                        .setPositiveButton("Kapat", null).show()
                } else showFoundProviderStreams(found, title, tmdbId, mediaType, season, episode)
                return
            }
            val option = options[index]
            status.text = title + " · " + (index + 1) + "/" + options.size + ": " + option.provider.name + " deneniyor…"
            worker.execute {
                val source = runCatching { Addons.fetchScraperSource(option.addon, option.provider) }
                runOnUiThread {
                    if (source.isFailure) {
                        failures.add(option.provider.name + ": " + (source.exceptionOrNull()?.message ?: "Dosya alınamadı"))
                        runAt(index + 1)
                        return@runOnUiThread
                    }
                    val args = org.json.JSONObject().put("tmdbId", tmdbId.toString())
                        .put("mediaType", mediaType)
                        .put("season", season ?: org.json.JSONObject.NULL)
                        .put("episode", episode ?: org.json.JSONObject.NULL).toString()
                    AddonSandbox.evaluate(this, source.getOrThrow(), option.repository, option.provider.id, args,
                        Addons.providerSettings(this, option.repository, option.provider.id)) { result ->
                        if (!result.success) failures.add(option.provider.name + ": " + result.value.take(180))
                        else {
                            val parsed = runCatching { Addons.parseProviderResult(result.value) }
                            if (parsed.isFailure) failures.add(option.provider.name + ": yanıt okunamadı")
                            else {
                                val providerResult = parsed.getOrThrow()
                                providerResult.streams.forEach { stream ->
                                    found.add(FoundProviderStream(option, stream, providerResult.subtitles))
                                }
                                if (providerResult.streams.isEmpty()) {
                                    val diagnostic = providerResult.diagnostics.firstOrNull()
                                    failures.add(option.provider.name + ": " + (diagnostic ?: "kaynak döndürmedi"))
                                }
                            }
                        }
                        runAt(index + 1)
                    }
                }
            }
        }
        runAt(0)
    }

    private fun showFoundProviderStreams(
        results: List<FoundProviderStream>, title: String, tmdbId: Int,
        mediaType: String, season: Int?, episode: Int?
    ) {
        val labels = results.map { result ->
            result.option.provider.name + " · " + result.stream.title +
                if (result.stream.quality.isNotBlank()) " · " + result.stream.quality else ""
        }.toTypedArray()
        status.text = title + " · " + results.size + " oynatılabilir kaynak bulundu."
        AlertDialog.Builder(this).setTitle(title + " · Bulunan kaynaklar (" + results.size + ")")
            .setItems(labels) { _, index ->
                val result = results[index]
                val option = result.option
                fun play(subtitle: Addons.ProviderSubtitle?) {
                    launchProviderStream(option.addon, option.provider, tmdbId.toString(), mediaType,
                        season, episode, result.stream, subtitle, title, emptyList())
                }
                if (result.subtitles.isEmpty()) play(null)
                else {
                    val subtitleLabels = (listOf("Altyazısız") + result.subtitles.map {
                        it.name + if (it.language.isNotBlank()) " · " + it.language else ""
                    }).toTypedArray()
                    AlertDialog.Builder(this).setTitle("Altyazı seç")
                        .setItems(subtitleLabels) { _, selected ->
                            play(if (selected == 0) null else result.subtitles[selected - 1])
                        }.setNegativeButton("Kapat", null).show()
                }
            }.setNegativeButton("Kapat", null).show()
    }

    private fun askSeriesEpisode(options: List<ProviderOption>, title: String, tmdbId: Int) {
        val seasonInput = EditText(this).apply {
            hint = "Sezon numarası"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
        }
        val episodeInput = EditText(this).apply {
            hint = "Bölüm numarası"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(seasonInput)
            addView(episodeInput)
        }
        AlertDialog.Builder(this)
            .setTitle(title + " · Bölüm seç")
            .setMessage("TMDB kimliği otomatik alındı. Kaynak aramak için sezon ve bölüm numarasını gir.")
            .setView(content)
            .setPositiveButton("Sağlayıcıları ara") { _, _ ->
                val season = seasonInput.text.toString().toIntOrNull()
                val episode = episodeInput.text.toString().toIntOrNull()
                if (season == null || season < 0 || episode == null || episode < 1) {
                    Toast.makeText(this, "Geçerli sezon ve bölüm numarası gir.", Toast.LENGTH_LONG).show()
                } else searchProviderStreams(options, title, tmdbId, "tv", season, episode)
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun chooseProviderOptions(
        options: List<ProviderOption>,
        title: String,
        tmdbId: Int,
        mediaType: String,
        season: Int?,
        episode: Int?,
        nextEpisodes: List<Addons.EpisodeItem> = emptyList()
    ) {
        val saved = rememberedProvider(mediaType, tmdbId)
        val savedOption = saved?.let { (repository, providerId) ->
            options.firstOrNull { it.repository == repository && it.provider.id == providerId }
        }
        if (savedOption != null) {
            AlertDialog.Builder(this)
                .setTitle("Kaydedilen sağlayıcı")
                .setMessage(title + " için " + savedOption.provider.name + " daha önce seçilmiş.")
                .setPositiveButton("Bununla devam et") { _, _ ->
                    runProvider(savedOption.repository, savedOption.addon, savedOption.provider,
                        tmdbId.toString(), mediaType, season, episode, title, nextEpisodes)
                }
                .setNeutralButton("Başka sağlayıcı seç") { _, _ ->
                    showProviderOptions(options, title, tmdbId, mediaType, season, episode, nextEpisodes)
                }
                .setNegativeButton("Kapat", null)
                .show()
            return
        }
        showProviderOptions(options, title, tmdbId, mediaType, season, episode, nextEpisodes)
    }

    private fun showProviderOptions(
        options: List<ProviderOption>,
        title: String,
        tmdbId: Int,
        mediaType: String,
        season: Int?,
        episode: Int?,
        nextEpisodes: List<Addons.EpisodeItem> = emptyList()
    ) {
        val labels = options.map { option ->
            option.addon.name + " · " + option.provider.name + " · " +
                option.repository.removePrefix("https://").removeSuffix("/") +
                if (option.latencyMs > 0L) " · ~${option.latencyMs} ms" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Kaynak sağlayıcısı · " + title)
            .setItems(labels) { _, index ->
                val option = options[index]
                AlertDialog.Builder(this)
                    .setTitle("Sağlayıcıyı hatırla?")
                    .setMessage(option.provider.name + " sağlayıcısı bu içerik için sonraki izlemelerde de kullanılsın mı?")
                    .setPositiveButton("Hatırla") { _, _ ->
                        rememberProvider(mediaType, tmdbId, option)
                        runProvider(option.repository, option.addon, option.provider,
                            tmdbId.toString(), mediaType, season, episode, title, nextEpisodes)
                    }
                    .setNegativeButton("Sadece bu kez") { _, _ ->
                        runProvider(option.repository, option.addon, option.provider,
                            tmdbId.toString(), mediaType, season, episode, title, nextEpisodes)
                    }
                    .setNeutralButton("Vazgeç", null)
                    .show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun providerLatencyKey(repository: String, providerId: String) =
        "provider_latency:" + repository.trimEnd('/') + ":" + providerId

    private fun rememberedProvider(mediaType: String, tmdbId: Int): Pair<String, String>? {
        val value = getSharedPreferences("addon_provider_preferences", MODE_PRIVATE)
            .getString("provider:$mediaType:$tmdbId", null) ?: return null
        val split = value.indexOf('\n')
        if (split <= 0 || split == value.lastIndex) return null
        return value.substring(0, split) to value.substring(split + 1)
    }

    private fun rememberProvider(mediaType: String, tmdbId: Int, option: ProviderOption) {
        getSharedPreferences("addon_provider_preferences", MODE_PRIVATE)
            .edit()
            .putString("provider:$mediaType:$tmdbId", option.repository + "\n" + option.provider.id)
            .apply()
        Toast.makeText(this, "Sağlayıcı bu içerik için hatırlanacak.", Toast.LENGTH_SHORT).show()
    }

    /** Uses the TMDB ID already attached to the home-screen title; no manual ID entry is needed. */
    private fun chooseProviderAndSearch(
        addon: Addons.Installed,
        tmdbId: Int,
        mediaType: String,
        season: Int?,
        episode: Int?,
        contentTitle: String = addon.name,
        nextEpisodes: List<Addons.EpisodeItem> = emptyList()
    ) {
        val providers = addon.scrapers.filter { provider ->
            provider.enabled && (provider.supportedTypes.isEmpty() || mediaType in provider.supportedTypes)
        }
        if (providers.isEmpty()) {
            Toast.makeText(this, "Bu depoda etkin ve uyumlu sağlayıcı yok.", Toast.LENGTH_LONG).show()
            return
        }
        chooseProviderOptions(
            providers.map { provider ->
                val latency = getSharedPreferences("addon_provider_latency", MODE_PRIVATE)
                    .getLong(providerLatencyKey(addon.baseUrl, provider.id),
                        getSharedPreferences("addon_connection_status", MODE_PRIVATE).getLong("latency:${addon.baseUrl}", 0L))
                ProviderOption(addon.baseUrl, addon, provider, latency)
            }.sortedBy { if (it.latencyMs > 0L) it.latencyMs else Long.MAX_VALUE },
            contentTitle,
            tmdbId,
            mediaType,
            season,
            episode,
            nextEpisodes
        )
    }

    private fun runProvider(
        repository: String,
        addon: Addons.Installed,
        provider: Addons.ScraperProvider,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        contentTitle: String = addon.name,
        nextEpisodes: List<Addons.EpisodeItem> = emptyList()
    ) {
        status.text = provider.name + " çalıştırılıyor…"
        worker.execute {
            val startedAt = System.nanoTime()
            val source = runCatching { Addons.fetchScraperSource(addon, provider) }
            if (source.isFailure) {
                runOnUiThread { status.text = source.exceptionOrNull()?.message ?: "Sağlayıcı dosyası alınamadı." }
                return@execute
            }
            val args = org.json.JSONObject()
                .put("tmdbId", tmdbId)
                .put("mediaType", mediaType)
                .put("season", season ?: org.json.JSONObject.NULL)
                .put("episode", episode ?: org.json.JSONObject.NULL)
                .toString()
            runOnUiThread {
                status.text = provider.name + " kaynakları taranıyor…"
                val settingsJson = Addons.providerSettings(this, repository, provider.id)
                AddonSandbox.evaluate(this, source.getOrThrow(), repository, provider.id, args, settingsJson) { result ->
                    val elapsedMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                    getSharedPreferences("addon_provider_latency", MODE_PRIVATE).edit()
                        .putLong(providerLatencyKey(repository, provider.id), elapsedMs).apply()
                    if (!result.success) {
                        status.text = "Sağlayıcı hatası: " + result.value
                        Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                        return@evaluate
                    }
                    val providerResult = runCatching { Addons.parseProviderResult(result.value) }.getOrElse {
                        status.text = "Sağlayıcı yanıtı okunamadı: " + (it.message ?: "")
                        return@evaluate
                    }
                    val streams = providerResult.streams
                    status.text = provider.name + " · " + streams.size + " kaynak · " +
                        providerResult.subtitles.size + " altyazı · ${elapsedMs} ms"
                    if (streams.isEmpty()) {
                        Toast.makeText(this, "Kaynak bulunamadı. Alan adı izinlerini kontrol et.", Toast.LENGTH_LONG).show()
                        return@evaluate
                    }
                    val labels = streams.map { stream ->
                        stream.title + if (stream.quality.isNotBlank()) " · " + stream.quality else ""
                    }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle(provider.name + " · Oynatma kaynakları")
                        .setItems(labels) { _, index ->
                            val stream = streams[index]
                            val subtitles = providerResult.subtitles
                            if (subtitles.isEmpty()) {
                                launchProviderStream(addon, provider, tmdbId, mediaType, season, episode, stream, null, contentTitle, nextEpisodes)
                            } else {
                                val subtitleLabels = (listOf("Altyazısız") + subtitles.map { subtitle ->
                                    subtitle.name + if (subtitle.language.isNotBlank()) " · " + subtitle.language else ""
                                }).toTypedArray()
                                AlertDialog.Builder(this)
                                    .setTitle("Altyazı seç")
                                    .setItems(subtitleLabels) { _, selected ->
                                        launchProviderStream(addon, provider, tmdbId, mediaType, season, episode, stream,
                                            if (selected == 0) null else subtitles[selected - 1], contentTitle, nextEpisodes)
                                    }
                                    .setNegativeButton("Kapat", null)
                                    .show()
                            }
                        }
                        .setNegativeButton("Kapat", null)
                        .show()
                }
            }
        }
    }

    private fun launchProviderStream(
        addon: Addons.Installed,
        provider: Addons.ScraperProvider,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
        stream: Addons.ProviderStream,
        subtitle: Addons.ProviderSubtitle?,
        contentTitle: String,
        nextEpisodes: List<Addons.EpisodeItem>
    ) {
        startActivity(android.content.Intent(this, AddonPlayerActivity::class.java).apply {
            putExtra(AddonPlayerActivity.EXTRA_URL, stream.url)
            putExtra(AddonPlayerActivity.EXTRA_HEADERS, org.json.JSONObject(stream.headers).toString())
            putExtra(AddonPlayerActivity.EXTRA_SERIES_ID, addon.baseUrl + "|" + tmdbId)
            putExtra(AddonPlayerActivity.EXTRA_SEASON, season ?: 0)
            putExtra(AddonPlayerActivity.EXTRA_EPISODE, episode ?: 1)
            putExtra(AddonPlayerActivity.EXTRA_PROVIDER_REPOSITORY, addon.baseUrl)
            putExtra(AddonPlayerActivity.EXTRA_PROVIDER_ID, provider.id)
            putExtra(AddonPlayerActivity.EXTRA_TMDB_ID, tmdbId.toIntOrNull() ?: 0)
            putExtra(AddonPlayerActivity.EXTRA_MEDIA_TYPE, mediaType)
            putExtra(AddonPlayerActivity.EXTRA_CONTENT_TITLE, contentTitle)
            putExtra(AddonPlayerActivity.EXTRA_NEXT_EPISODES, org.json.JSONArray().apply {
                nextEpisodes.forEach { item ->
                    put(org.json.JSONObject().put("season", item.season).put("episode", item.episode).put("title", item.title))
                }
            }.toString())
            if (subtitle != null) {
                putExtra(AddonPlayerActivity.EXTRA_SUBTITLE_URL, subtitle.url)
                putExtra(AddonPlayerActivity.EXTRA_SUBTITLE_MIME, when (subtitle.format) {
                    "srt" -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                    else -> androidx.media3.common.MimeTypes.TEXT_VTT
                })
                putExtra(AddonPlayerActivity.EXTRA_SUBTITLE_LANGUAGE, subtitle.language.ifBlank { "und" })
                putExtra(AddonPlayerActivity.EXTRA_SUBTITLE_HEADERS, org.json.JSONObject(subtitle.headers).toString())
            }
        })
    }

    private fun manageProviderSettings(repository: String, providerId: String) {
        val input = EditText(this).apply {
            setText(Addons.providerSettings(this@AddonManagerActivity, repository, providerId))
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
            hint = "{ \"apiKey\": \"...\", \"language\": \"tr\" }"
            minLines = 6
            maxLines = 12
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        AlertDialog.Builder(this)
            .setTitle(providerId + " · Sağlayıcı ayarları")
            .setMessage("JSON nesnesi olarak kaydedilir ve JavaScript içinde SCRAPER_SETTINGS değişkeninden okunur.")
            .setView(input)
            .setPositiveButton("Kaydet") { _, _ ->
                try {
                    Addons.saveProviderSettings(this, repository, providerId, input.text.toString())
                    Toast.makeText(this, "Sağlayıcı ayarları kaydedildi.", Toast.LENGTH_LONG).show()
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "JSON ayarları geçersiz.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Varsayılana dön") { _, _ ->
                Addons.saveProviderSettings(this, repository, providerId, "{}")
                Toast.makeText(this, "Sağlayıcı ayarları sıfırlandı.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun manageProviderNetworkPermissions(repository: String, providerId: String) {
        val current = AddonNetworkPermissions.grantedHosts(this, repository, providerId)
        val input = EditText(this).apply {
            hint = "api.example.com"
            setSingleLine(true)
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
        }
        val message = buildString {
            append("Sağlayıcı: ").append(providerId).append("\n")
            append("İzinli HTTPS alan adları: ")
            append(if (current.isEmpty()) "Henüz yok" else current.sorted().joinToString(", "))
            append("\n\nYalnızca tam alan adı eklenir. Joker karakter, IP ve HTTP kabul edilmez.")
            append("\nİzin verilen alan adlarına yalnızca bu sağlayıcı, HTTPS üzerinden erişebilir.")
        }
        AlertDialog.Builder(this)
            .setTitle("Sağlayıcı ağ izinleri")
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Alan adı ekle") { _, _ ->
                try {
                    AddonNetworkPermissions.grant(this, repository, providerId, input.text.toString())
                    Toast.makeText(this, "Alan adı izin listesine eklendi.", Toast.LENGTH_LONG).show()
                    manageProviderNetworkPermissions(repository, providerId)
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "Alan adı eklenemedi.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("İzinleri temizle") { _, _ ->
                current.forEach { host ->
                    AddonNetworkPermissions.revoke(this, repository, providerId, host)
                }
                Toast.makeText(this, "Sağlayıcı ağ izinleri temizlendi.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showCatalogItems(
        addon: Addons.Installed,
        catalog: Addons.Catalog,
        entries: List<Addons.CatalogItem>,
        nextSkip: Int = entries.size
    ) {
        val labels = entries.map { item ->
            item.name.ifBlank { item.id } + if (item.releaseInfo.isNotBlank()) " · " + item.releaseInfo else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(addon.name + " · " + catalog.name)
            .setItems(labels) { _, which ->
                val item = entries[which]
                status.text = item.name.ifBlank { item.id } + " yükleniyor…"
                worker.execute {
                    try {
                        val details = Addons.fetchMetaDetails(addon, item.type.ifBlank { catalog.type }, item.id)
                        val meta = details.item
                        val seriesProgressId = addon.baseUrl + "|" + meta.id.ifBlank { item.id }
                        val isSeries = meta.type.equals("series", true) ||
                            meta.type.equals("tv", true) || catalog.type.equals("series", true) ||
                            catalog.type.equals("tv", true)
                        val episodes = if (isSeries) details.episodes else emptyList()
                        val film = Pick(
                            title = meta.name.ifBlank { item.name.ifBlank { item.id } },
                            meta = listOf(meta.releaseInfo, meta.genres.take(3).joinToString(", "))
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            overview = meta.description,
                            backdrop = meta.background.ifBlank { meta.poster },
                            reason = "Eklenti kataloğu · " + addon.name,
                            trailer = "",
                            logo = "",
                            imdb = meta.imdbRating,
                            year = meta.releaseInfo.take(4),
                            genres = meta.genres,
                            runtime = meta.runtime,
                            poster = meta.poster,
                            kind = if (isSeries) "tv" else "movie",
                            seasons = meta.seasons
                        )
                        runOnUiThread {
                            status.text = "Metadata yüklendi: " + film.title
                            if (isSeries) {
                                status.text = episodes.size.toString() + " bölüm bulundu."
                                showSeriesOverview(film, addon.name, seriesProgressId, episodes)
                            } else {
                                askMovieSource(film, seriesProgressId)
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            status.text = e.message ?: "Metadata alınamadı."
                            Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setPositiveButton("Daha fazla yükle") { _, _ ->
                status.text = "Sonraki katalog sayfası yükleniyor…"
                worker.execute {
                    try {
                        val more = Addons.fetchCatalog(addon, catalog, skip = nextSkip)
                        val known = entries.map { it.id }.toSet()
                        val unique = more.filterNot { it.id in known }
                        runOnUiThread {
                            if (unique.isEmpty()) {
                                Toast.makeText(this, "Başka kayıt bulunamadı.", Toast.LENGTH_LONG).show()
                            } else {
                                showCatalogItems(addon, catalog, entries + unique, nextSkip + more.size)
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, e.message ?: "Sonraki sayfa yüklenemedi.", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showSeriesOverview(
        film: Pick,
        addonName: String,
        progressId: String,
        episodes: List<Addons.EpisodeItem>,
        addon: Addons.Installed? = null,
        tmdbId: Int = 0
    ) {
        val latest = WatchProgress.latest(this, progressId)
        val latestEpisode = latest?.let { saved ->
            episodes.firstOrNull { it.season == saved.season && it.episode == saved.episode }
        }
        val latestProgress = latest?.let { saved ->
            when {
                saved.isComplete -> "Tamamlandı"
                saved.durationMs > 0 -> "Devam · ${(saved.positionMs * 100 / saved.durationMs).coerceIn(0, 100)}%"
                saved.positionMs > 0 -> "Devam ediyor"
                else -> null
            }
        }
        val message = buildString {
            if (film.meta.isNotBlank()) append(film.meta)
            if (film.runtime > 0) {
                if (isNotEmpty()) append(" · ")
                append(film.runtime.toString() + " dk")
            }
            if (film.seasons > 0) {
                if (isNotEmpty()) append(" · ")
                append(film.seasons.toString() + " sezon")
            }
            if (film.overview.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(film.overview)
            }
            if (isNotEmpty()) append("\n\n")
            append(episodes.size.toString() + " bölüm metadata kaydı")
            if (latest != null && latestProgress != null) {
                append("\nSon izleme: ")
                append(latestEpisode?.let { "S${it.season} B${it.episode} · ${it.title}" }
                    ?: "S${latest.season} B${latest.episode}")
                append(" · ")
                append(latestProgress)
            }
        }
        val canResume = latest != null && !latest.isComplete && latestEpisode != null
        AlertDialog.Builder(this)
            .setTitle(film.title)
            .setMessage(message)
            .setPositiveButton(
                when {
                    canResume -> "Kaldığın yerden devam et"
                    episodes.isNotEmpty() -> "Bölümler"
                    else -> "Tamam"
                }
            ) { _, _ ->
                if (canResume) {
                    if (addon != null && tmdbId > 0 && addon.scrapers.isNotEmpty()) {
                        chooseProviderAndSearch(addon, tmdbId, "tv", latestEpisode!!.season, latestEpisode.episode, film.title, episodes)
                    } else askEpisodeSource(addonName, progressId, latestEpisode!!)
                } else if (episodes.isNotEmpty()) {
                    showEpisodes(addonName, progressId, episodes, addon, tmdbId, film.title)
                }
            }
            .setNeutralButton(if (canResume) "Bölümler" else "Detaylar") { _, _ ->
                if (canResume) showEpisodes(addonName, progressId, episodes, addon, tmdbId, film.title)
                else DetailActivity.open(this, film)
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun askMovieSource(film: Pick, progressId: String) {
        val input = EditText(this).apply {
            hint = "https://ornek.com/video.mp4"
            setSingleLine(true)
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
        }
        AlertDialog.Builder(this)
            .setTitle(film.title)
            .setMessage("Yalnızca kullanım hakkına sahip olduğun doğrudan HTTPS medya adresini gir. Eklenti kaynak uçları çağrılmaz.")
            .setView(input)
            .setPositiveButton("Oynat") { _, _ ->
                openDirectPlayer(input.text.toString(), progressId, 0, 1, film.title, "movie")
            }
            .setNeutralButton("Detaylar") { _, _ -> DetailActivity.open(this, film) }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showEpisodes(
        addonName: String,
        seriesId: String,
        episodes: List<Addons.EpisodeItem>,
        addon: Addons.Installed? = null,
        tmdbId: Int = 0,
        contentTitle: String = addonName
    ) {
        val labels = episodes.map { episode ->
            val progress = WatchProgress.get(this, seriesId, episode.season, episode.episode)
            val marker = when {
                progress?.isComplete == true -> " · Tamamlandı ✓"
                progress != null && progress.positionMs > 0 && progress.durationMs > 0 -> {
                    val percent = (progress.positionMs * 100 / progress.durationMs).coerceIn(0, 100)
                    " · Devam %${percent}"
                }
                progress != null && progress.positionMs > 0 -> " · Devam ediyor"
                else -> ""
            }
            val release = episode.released.take(10).takeIf { it.isNotBlank() }?.let { " · " + it }.orEmpty()
            "S" + episode.season + " · B" + episode.episode + "  " + episode.title + release + marker
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(addonName + " · Bölümler")
            .setItems(labels) { _, index ->
                val episode = episodes[index]
                val saved = WatchProgress.get(this, seriesId, episode.season, episode.episode)
                val progressText = when {
                    saved?.isComplete == true -> "Bu bölüm tamamlandı."
                    saved != null && saved.durationMs > 0 ->
                        "İlerleme: " + (saved.positionMs * 100 / saved.durationMs).coerceIn(0, 100) + "%"
                    saved != null && saved.positionMs > 0 -> "Devam edilecek konum kaydedildi."
                    else -> "Henüz izleme ilerlemesi yok."
                }
                if (addon != null && tmdbId > 0 && addon.scrapers.isNotEmpty()) {
                    chooseProviderAndSearch(addon, tmdbId, "tv", episode.season, episode.episode, contentTitle, episodes)
                } else askEpisodeSource(addonName, seriesId, episode, progressText)
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun askEpisodeSource(
        addonName: String,
        seriesId: String,
        episode: Addons.EpisodeItem,
        knownProgressText: String? = null
    ) {
        val saved = WatchProgress.get(this, seriesId, episode.season, episode.episode)
        val progressText = knownProgressText ?: when {
            saved?.isComplete == true -> "Bu bölüm tamamlandı."
            saved != null && saved.durationMs > 0 ->
                "İlerleme: " + (saved.positionMs * 100 / saved.durationMs).coerceIn(0, 100) + "%"
            saved != null && saved.positionMs > 0 -> "Devam edilecek konum kaydedildi."
            else -> "Henüz izleme ilerlemesi yok."
        }
        val input = EditText(this).apply {
            hint = "https://ornek.com/video.mp4"
            setSingleLine(true)
            setTextColor(-1)
            setHintTextColor(0xFF8B929E.toInt())
        }
        val message = buildString {
            append(addonName + " · Sezon " + episode.season + ", bölüm " + episode.episode + "\n")
            if (episode.released.isNotBlank()) append("Yayın: " + episode.released + "\n")
            append(progressText)
            append("\n\nKullanım hakkına sahip olduğun doğrudan HTTPS medya adresini gir.")
        }
        AlertDialog.Builder(this)
            .setTitle(episode.title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Oynat") { _, _ ->
                openDirectPlayer(input.text.toString(), seriesId, episode.season, episode.episode, episode.title, "tv")
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun openDirectPlayer(
        rawUrl: String,
        progressId: String,
        season: Int,
        episode: Int,
        contentTitle: String = "",
        mediaType: String = "tv"
    ) {
        val source = rawUrl.trim()
        val uri = runCatching { android.net.Uri.parse(source) }.getOrNull()
        if (uri == null || !uri.scheme.equals("https", true) ||
            uri.host.isNullOrBlank() || !uri.userInfo.isNullOrBlank()) {
            Toast.makeText(this, "Yalnızca doğrudan HTTPS medya adresi kabul edilir.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(android.content.Intent(this, AddonPlayerActivity::class.java).apply {
            putExtra(AddonPlayerActivity.EXTRA_URL, source)
            putExtra(AddonPlayerActivity.EXTRA_SERIES_ID, progressId)
            putExtra(AddonPlayerActivity.EXTRA_SEASON, season)
            putExtra(AddonPlayerActivity.EXTRA_EPISODE, episode)
            putExtra(AddonPlayerActivity.EXTRA_CONTENT_TITLE, contentTitle)
            putExtra(AddonPlayerActivity.EXTRA_MEDIA_TYPE, mediaType)
        })
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
