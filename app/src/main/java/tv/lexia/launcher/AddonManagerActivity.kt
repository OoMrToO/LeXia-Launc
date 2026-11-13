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

/** TV-friendly manager for HTTPS addon repositories and their metadata catalogues. */
class AddonManagerActivity : Activity() {
    companion object {
        private const val EXTRA_MATCH_REPOSITORY = "addon_match_repository"
        private const val EXTRA_MATCH_TYPE = "addon_match_type"
        private const val EXTRA_MATCH_ID = "addon_match_id"

        fun openMatch(context: android.content.Context, repository: String, type: String, itemId: String) {
            context.startActivity(android.content.Intent(context, AddonManagerActivity::class.java).apply {
                putExtra(EXTRA_MATCH_REPOSITORY, repository)
                putExtra(EXTRA_MATCH_TYPE, type)
                putExtra(EXTRA_MATCH_ID, itemId)
            })
        }
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
        val matchedRepository = intent.getStringExtra(EXTRA_MATCH_REPOSITORY).orEmpty()
        val matchedType = intent.getStringExtra(EXTRA_MATCH_TYPE).orEmpty()
        val matchedId = intent.getStringExtra(EXTRA_MATCH_ID).orEmpty()
        if (matchedRepository.isNotBlank() && matchedType.isNotBlank() && matchedId.isNotBlank()) {
            openMatchedItem(matchedRepository, matchedType, matchedId)
        }
    }

    private fun openMatchedItem(repository: String, type: String, itemId: String) {
        status.text = "Eklentideki içerik yükleniyor…"
        worker.execute {
            try {
                val addon = Addons.fetchManifest(repository)
                val details = Addons.fetchMetaDetails(addon, type, itemId)
                val meta = details.item
                val isSeries = meta.type.equals("series", true) || meta.type.equals("tv", true) ||
                    type.equals("series", true) || type.equals("tv", true)
                val progressId = addon.baseUrl + "|" + meta.id.ifBlank { itemId }
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
                    if (isSeries) showSeriesOverview(film, addon.name, progressId, details.episodes)
                    else askMovieSource(film, progressId)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = e.message ?: "Eklenti içeriği açılamadı."
                    Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
                }
            }
        }
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
                        AlertDialog.Builder(this)
                            .setTitle(addon.name + " · Sağlayıcılar")
                            .setMessage(
                                "Sağlayıcılar izole JavaScript ortamında çalıştırılabilir. " +
                                    "Ağ erişimi varsayılan olarak kapalıdır; sağlayıcı için gereken " +
                                    "HTTPS alan adlarını Ağ izinleri bölümünden tek tek onayla. " +
                                    "Yalnızca kullanım hakkına sahip olduğun doğrudan medya adreslerini oynat."
                            )
                            .setItems(labels) { _, index ->
                                val provider = addon.scrapers[index]
                                AlertDialog.Builder(this)
                                    .setTitle(provider.name)
                                    .setMessage(
                                        provider.description.ifBlank { "Açıklama yok." } + "\\n\\n" +
                                            "Sürüm: " + provider.version + "\\n" +
                                            "Dosya: " + provider.filename + "\\n" +
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
                                    .show()
                            }
                            .setNegativeButton("Kapat", null)
                            .show()
                    }
                } else {
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
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = e.message ?: "Eklenti okunamadı." }
            }
        }
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

    private fun runProvider(
        repository: String,
        addon: Addons.Installed,
        provider: Addons.ScraperProvider,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?
    ) {
        status.text = provider.name + " çalıştırılıyor…"
        worker.execute {
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
                AddonSandbox.evaluate(this, source.getOrThrow(), repository, provider.id, args) { result ->
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
                        providerResult.subtitles.size + " altyazı bulundu."
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
                                launchProviderStream(addon, tmdbId, season, episode, stream, null)
                            } else {
                                val subtitleLabels = (listOf("Altyazısız") + subtitles.map { subtitle ->
                                    subtitle.name + if (subtitle.language.isNotBlank()) " · " + subtitle.language else ""
                                }).toTypedArray()
                                AlertDialog.Builder(this)
                                    .setTitle("Altyazı seç")
                                    .setItems(subtitleLabels) { _, selected ->
                                        launchProviderStream(addon, tmdbId, season, episode, stream,
                                            if (selected == 0) null else subtitles[selected - 1])
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
        tmdbId: String,
        season: Int?,
        episode: Int?,
        stream: Addons.ProviderStream,
        subtitle: Addons.ProviderSubtitle?
    ) {
        startActivity(android.content.Intent(this, AddonPlayerActivity::class.java).apply {
            putExtra(AddonPlayerActivity.EXTRA_URL, stream.url)
            putExtra(AddonPlayerActivity.EXTRA_HEADERS, org.json.JSONObject(stream.headers).toString())
            putExtra(AddonPlayerActivity.EXTRA_SERIES_ID, addon.baseUrl + "|" + tmdbId)
            putExtra(AddonPlayerActivity.EXTRA_SEASON, season ?: 0)
            putExtra(AddonPlayerActivity.EXTRA_EPISODE, episode ?: 1)
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
