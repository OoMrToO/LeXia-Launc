package tv.lexia.launcher

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/** TV-friendly manager for HTTPS addon repositories and their metadata catalogues. */
class AddonManagerActivity : Activity() {
    companion object {
        const val EXTRA_SEARCH_TITLE = "addon_search_title"
        const val EXTRA_SEARCH_TYPE = "addon_search_type"
    }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 24).toInt()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, pad)
            setBackgroundColor(android.graphics.Color.rgb(14, 16, 20))
        }
        val title = TextView(this).apply {
            text = "EKLENTİLER"
            textSize = 26f
            setTextColor(-1)
        }
        root.addView(title)
        status = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFB8BEC9.toInt())
            setPadding(0, pad / 2, 0, pad)
        }
        root.addView(status)
        val add = Button(this).apply {
            text = "Eklenti adresi ekle"
            setOnClickListener { askUrl() }
        }
        root.addView(add)
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        add.requestFocus()
        render(list)
        val searchTitle = intent.getStringExtra(EXTRA_SEARCH_TITLE).orEmpty()
        if (searchTitle.isNotBlank()) searchConfigured(searchTitle, intent.getStringExtra(EXTRA_SEARCH_TYPE) ?: "movie")
    }

    private fun render(list: LinearLayout) {
        list.removeAllViews()
        val urls = Addons.urls(this)
        status.text = if (urls.isEmpty()) "Henüz eklenti eklenmedi." else "${urls.size} depo kayıtlı"
        urls.forEach { address ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18, 14, 18, 14)
                setBackgroundColor(0xFF252A33.toInt())
            }
            val label = TextView(this).apply {
                text = address
                textSize = 15f
                setTextColor(-1)
            }
            card.addView(label)
            val actions = LinearLayout(this).apply { gravity = Gravity.END }
            val inspect = Button(this).apply {
                text = "Katalogları yükle"
                setOnClickListener { loadCatalogs(address) }
            }
            val remove = Button(this).apply {
                text = "Kaldır"
                setOnClickListener {
                    Addons.remove(this@AddonManagerActivity, address)
                    render(list)
                }
            }
            actions.addView(inspect)
            actions.addView(remove)
            card.addView(actions)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
        }
    }

    private fun searchConfigured(title: String, type: String) {
        status.text = "“$title” eklenti kataloglarında aranıyor…"
        worker.execute {
            val matches = Addons.searchConfigured(this, title, type)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (matches.isEmpty()) {
                    status.text = "“$title” için eşleşme bulunamadı. Eklenti ayarlarını ve katalog arama desteğini kontrol et."
                    AlertDialog.Builder(this)
                        .setTitle("Eşleşme bulunamadı")
                        .setMessage("Seçtiğin arama kapsamındaki kataloglarda bu içerik bulunamadı.")
                        .setPositiveButton("Tamam", null)
                        .show()
                    return@runOnUiThread
                }
                status.text = "${matches.size} katalog eşleşmesi bulundu."
                val labels = matches.map { (addon, catalog, item) ->
                    "${item.name}  ·  ${addon.name} / ${catalog.name}"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("“$title” eşleşmeleri")
                    .setItems(labels) { _, index ->
                        val (addon, catalog, item) = matches[index]
                        showCatalogItems(addon, catalog, listOf(item))
                    }
                    .setNegativeButton("Kapat", null)
                    .show()
            }
        }
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

    private fun loadCatalogs(address: String) {
        status.text = "Manifest ve kataloglar yükleniyor…"
        worker.execute {
            try {
                val addon = Addons.fetchManifest(address)
                val results = addon.catalogs.map { catalog ->
                    catalog to runCatching { Addons.fetchCatalog(addon, catalog) }.getOrNull()
                }
                runOnUiThread {
                    status.text = if (results.isEmpty()) {
                        addon.name + " · Katalog bulunamadı."
                    } else {
                        addon.name + " · " + results.size + " katalog hazır."
                    }
                    if (results.isNotEmpty()) {
                        val labels = results.map { (catalog, items) ->
                            catalog.name + " · " + (items?.size?.toString() ?: "okunamadı") + " kayıt"
                        }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle(addon.name + " · Kataloglar")
                            .setItems(labels) { _, index ->
                                val (catalog, items) = results[index]
                                if (items.isNullOrEmpty()) {
                                    Toast.makeText(this, "Bu katalog yüklenemedi veya boş.", Toast.LENGTH_LONG).show()
                                } else {
                                    showCatalogItems(addon, catalog, items)
                                }
                            }
                            .setNegativeButton("Kapat", null)
                            .show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = e.message ?: "Eklenti okunamadı." }
            }
        }
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
        episodes: List<Addons.EpisodeItem>
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
                    askEpisodeSource(addonName, progressId, latestEpisode!!)
                } else if (episodes.isNotEmpty()) {
                    showEpisodes(addonName, progressId, episodes)
                }
            }
            .setNeutralButton(if (canResume) "Bölümler" else "Detaylar") { _, _ ->
                if (canResume) showEpisodes(addonName, progressId, episodes)
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
                openDirectPlayer(input.text.toString(), progressId, 0, 1)
            }
            .setNeutralButton("Detaylar") { _, _ -> DetailActivity.open(this, film) }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun showEpisodes(
        addonName: String,
        seriesId: String,
        episodes: List<Addons.EpisodeItem>
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
                askEpisodeSource(addonName, seriesId, episode, progressText)
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
                openDirectPlayer(input.text.toString(), seriesId, episode.season, episode.episode)
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun openDirectPlayer(rawUrl: String, progressId: String, season: Int, episode: Int) {
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
        })
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
