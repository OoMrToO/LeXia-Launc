package tv.lexia.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Ayarlar › Medya kaynakları: Stremio add-ons and Nuvio JavaScript repositories, as glass cards
 * over the blurred home screen. Each provider can be tried on the spot ("Dene"), a whole
 * repository at once ("Hepsini dene"), and everything refreshed with one button.
 */
class MediaAddonsActivity : Activity() {

    private val repository by lazy { MediaAddonRepository(this) }
    private val nuvioRepository by lazy { NuvioJsRepository(this) }
    private val memory by lazy { SourceMemory(this) }
    private val ui by lazy { Ui(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var addButton: TextView
    private var busy = false

    /** What "Dene" found, per provider id, for as long as this page is open. */
    private val tested = HashMap<String, Pair<Boolean, String>>()
    /** Names of the providers sent to "Dene", for the status line. */
    private val known = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Providers waiting for their try, or being tried now. */
    private val testing: MutableSet<String> = java.util.Collections.synchronizedSet(LinkedHashSet())

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
            setPadding(ui.u(96), ui.u(64), ui.u(96), 0)
        }

        root.addView(ui.label("MEDYA KAYNAKLARI", C.TEXT3))
        root.addView(ui.text("Eklentiler", 56, C.TEXT, 700), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(10) })
        val intro = ui.text(
            "Stremio eklentileri ve Nuvio depoları. Kaynak aranırken açık olanlar sorulur. " +
                "Bir sağlayıcının çalışıp çalışmadığını “Dene” ile hemen görebilirsin.",
            24, C.TEXT2, 400
        )
        intro.setLineSpacing(0f, 1.2f)
        intro.maxWidth = ui.u(1300)
        root.addView(intro, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(10) })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val add = action("+  Eklenti ekle", primary = true).apply {
            tag = "top:add"
            setOnClickListener { showAddDialog() }
        }
        controls.addView(add)
        controls.addView(action("Tümünü güncelle").apply {
            tag = "top:update"
            setOnClickListener { updateAll() }
        }, gap())
        controls.addView(action("Kapat").apply {
            tag = "top:close"
            setOnClickListener { finish() }
        }, gap())
        root.addView(controls, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(30) })

        status = ui.text("", 21, C.TEXT2, 500)
        root.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(18); bottomMargin = ui.u(14) })

        val scroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            clipChildren = false
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(ui.u(6), ui.u(6), ui.u(6), ui.u(80))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // The blurred home screen behind dark glass, as in Ayarlar.
        val bg = ui.gradient(
            GradientDrawable.Orientation.TL_BR,
            Color.parseColor("#22262E"), Color.parseColor("#0E1014"), Color.BLACK
        )
        root.background = Fx.backdrop?.let {
            android.graphics.drawable.LayerDrawable(
                arrayOf(
                    android.graphics.drawable.BitmapDrawable(resources, it),
                    ui.gradient(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        Color.argb(215, 10, 11, 14), Color.argb(235, 6, 7, 9), Color.argb(245, 0, 0, 0)
                    )
                )
            )
        } ?: bg
        setContentView(root)
        Sound.attach(this)
        addButton = add
        // The remote starts on the first button; a focused container would swallow every D-pad press.
        add.requestFocus()
    }

    private fun gap() = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = ui.u(14) }

    /**
     * Rebuilds the list. The remote stays on the button it was on (each button carries a name),
     * so switching ten scrapers on or off does not send it back to the top ten times.
     */
    private fun renderAddons() {
        val focusedKey = currentFocus?.tag as? String
        list.removeAllViews()
        val addons = repository.installedAddons()
        val nuvioRepos = nuvioRepository.installedRepositories()
        val nuvioCount = nuvioRepos.sumOf { it.scrapers.size }
        val nuvioOn = nuvioRepos.sumOf { r -> r.scrapers.count { it.enabled } }
        if (!busy && testing.isEmpty()) {
            status.text = "${addons.size} Stremio eklentisi  ·  ${nuvioRepos.size} Nuvio deposu  ·  $nuvioCount sağlayıcı, $nuvioOn açık"
        }

        if (addons.isEmpty() && nuvioRepos.isEmpty()) {
            list.addView(
                infoCard(
                    "İlk kaynağını ekle",
                    "“Eklenti ekle” ile bir Stremio eklentisinin ya da Nuvio deposunun adresini gir. LeXia adresi doğrular."
                ),
                cardParams()
            )
        }

        if (nuvioRepos.isNotEmpty()) {
            // Nuvio's "Enable plugin providers globally" switch.
            val on = nuvioRepository.pluginsEnabled()
            val row = card(horizontal = true)
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(ui.text("Nuvio sağlayıcıları", 30, C.TEXT, 600))
            texts.addView(ui.text(
                if (on) "Kaynak aranırken depolardaki sağlayıcılar da sorulur." else "Kapalı: kaynak aranırken depolar sorulmaz.",
                21, C.TEXT2, 400
            ), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(4) })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(toggle(on, "nuvio:global") {
                nuvioRepository.setPluginsEnabled(!on)
                renderAddons()
            })
            list.addView(row, cardParams())
        }

        nuvioRepos.forEach { repo -> list.addView(repoCard(repo), cardParams()) }

        if (addons.isNotEmpty()) {
            list.addView(ui.label("STREMIO EKLENTİLERİ", C.TEXT3), LinearLayout.LayoutParams(-2, -2).apply {
                leftMargin = ui.u(8); topMargin = ui.u(18); bottomMargin = ui.u(14)
            })
        }
        addons.forEachIndexed { index, addon -> list.addView(addonCard(addon, index, addons.lastIndex), cardParams()) }

        val target = focusedKey?.let { list.findViewWithTag<View>(it) ?: root.findViewWithTag<View>(it) }
        list.post {
            if (isFinishing) return@post
            if (target != null) target.requestFocus()
            else if (root.findFocus() == null) addButton.requestFocus()
        }
    }

    private fun repoCard(repo: NuvioRepositoryInfo): View {
        val card = card()
        card.addView(ui.label("NUVIO DEPOSU", C.TEXT3))
        card.addView(ui.text(repo.name, 36, C.TEXT, 700), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(6) })
        val on = repo.scrapers.count { it.enabled }
        card.addView(ui.text(
            listOfNotNull(
                "${repo.scrapers.size} sağlayıcı, $on açık",
                repo.version.takeIf { it.isNotBlank() }?.let { "Sürüm $it" },
                "Kapalı".takeIf { !repo.enabled }
            ).joinToString("  ·  "),
            21, C.TEXT2, 500
        ), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(4); bottomMargin = ui.u(16) })

        val repoActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val allEnabled = repo.scrapers.all { it.enabled }
        repoActions.addView(action(if (allEnabled) "Tümünü kapat" else "Tümünü aç").apply {
            tag = "all:${repo.id}"
            setOnClickListener {
                nuvioRepository.setAllScrapersEnabled(repo.id, !allEnabled)
                renderAddons()
            }
        })
        repoActions.addView(action("Hepsini dene").apply {
            tag = "testall:${repo.id}"
            setOnClickListener { test(repo.scrapers.filter { it.enabled }.ifEmpty { repo.scrapers }) }
        }, gap())
        repoActions.addView(action("Depoyu güncelle").apply {
            tag = "update:${repo.id}"
            setOnClickListener { refreshNuvioRepository(repo.id, repo.name) }
        }, gap())
        repoActions.addView(action("Depoyu kaldır").apply {
            tag = "rmrepo:${repo.id}"
            setOnClickListener { confirmRemoveNuvio(repo.id, repo.name) }
        }, gap())
        card.addView(repoActions)

        // The providers, each on a line of the glass pane, a hairline between them.
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ui.round(Color.argb(16, 255, 255, 255), 22)
            setPadding(ui.u(22), ui.u(6), ui.u(16), ui.u(6))
        }
        repo.scrapers.forEachIndexed { i, scraper ->
            if (i > 0) pane.addView(View(this).apply { setBackgroundColor(Color.argb(22, 255, 255, 255)) }, LinearLayout.LayoutParams(-1, 1))
            pane.addView(scraperRow(repo, scraper))
        }
        card.addView(pane, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(20) })
        return card
    }

    private fun scraperRow(repo: NuvioRepositoryInfo, scraper: NuvioScraperInfo): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, ui.u(14), 0, ui.u(14))
            alpha = if (scraper.enabled) 1f else 0.62f
        }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(ui.text(scraper.name, 26, C.TEXT, 600).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val kinds = scraper.supportedTypes.map { if (it.equals("movie", true)) "Film" else "Dizi" }.distinct().joinToString(", ")
        val langs = scraper.contentLanguage.joinToString(", ") { Lang.name(Lang.code(it)).ifBlank { it.uppercase() } }
        // Many repositories describe a scraper only as "Film • Dizi", which repeats the kinds.
        val onlyKinds = scraper.description.split(Regex("[^\\p{L}]+")).filter { it.isNotBlank() }
            .all { it.lowercase() in setOf("film", "dizi", "movie", "movies", "tv", "series", "show", "shows", "and", "ve") }
        val about = if (onlyKinds) "" else scraper.description
        val shaky = memory.unreliable(scraper.name)
        val line = listOf(kinds, langs, "Sık açılmıyor".takeIf { shaky }.orEmpty(), about).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (line.isNotBlank()) details.addView(ui.text(line, 19, C.TEXT3, 400).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(4) })
        // What "Dene" found: green when it gave sources, red-ish when it did not.
        val result = synchronized(tested) { tested[scraper.id] }
        val note = when {
            scraper.id in testing -> "Deneniyor…" to C.TEXT2
            result != null -> result.second to if (result.first) Color.parseColor("#5BD98A") else Color.parseColor("#FF8A80")
            else -> null
        }
        if (note != null) details.addView(ui.text(note.first, 20, note.second, 600).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(4) })
        row.addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(action("Dene", small = true).apply {
            tag = "test:${scraper.id}"
            setOnClickListener { test(listOf(scraper)) }
        }, gap())
        if (scraper.hasSettings) {
            row.addView(action("Ayarlar", small = true).apply {
                tag = "settings:${repo.id}:${scraper.id}"
                setOnClickListener { showScraperSettings(repo.id, scraper) }
            }, gap())
        }
        row.addView(toggle(scraper.enabled, "toggle:${repo.id}:${scraper.id}", small = true) {
            if (!scraper.enabled && nuvioRepository.needsConfirmation(scraper)) {
                // As Nuvio's plugin screen asks (PluginSafety).
                Dialogs.confirm(this, "${scraper.name} açılsın mı?", "Bu sağlayıcı bazı içeriklerde çökmeye yol açabiliyor.") {
                    nuvioRepository.setScraperEnabled(repo.id, scraper.id, true)
                    renderAddons()
                }
            } else {
                nuvioRepository.setScraperEnabled(repo.id, scraper.id, !scraper.enabled)
                renderAddons()
            }
        }, gap())
        return row
    }

    private fun addonCard(addon: InstalledMediaAddon, index: Int, last: Int): View {
        val card = card()
        card.addView(ui.label("STREMIO  ·  SÜRÜM ${addon.version}".uppercase(java.util.Locale.forLanguageTag("tr-TR")), C.TEXT3))
        card.addView(ui.text(addon.name, 36, C.TEXT, 700), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(6) })
        val gives = listOfNotNull(
            "Kaynak".takeIf { "stream" in addon.resources },
            "Bölüm listesi".takeIf { "meta" in addon.resources },
            "Altyazı".takeIf { addon.resources.any { it.startsWith("subtitle", true) } },
            "Katalog".takeIf { "catalog" in addon.resources }
        ).joinToString(", ").ifBlank { "bilgi yok" }
        card.addView(ui.text("Verdiği: $gives" + if (addon.enabled) "" else "  ·  Kapalı", 21, C.TEXT2, 500),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(4) })
        if (addon.description.isNotBlank()) {
            card.addView(ui.text(addon.description, 20, C.TEXT3, 400).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.15f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.u(8) })
        }
        // Only the server's name: the full address can be long and carry the add-on's settings.
        val host = runCatching { java.net.URI(addon.baseUrl).host }.getOrNull().orEmpty()
        if (host.isNotBlank()) card.addView(ui.text(host, 19, C.TEXT3, 400), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(6) })

        // As in Nuvio: switch on or off, order, the add-on's own settings, refresh, remove.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, key: String, click: () -> Unit) =
            row.addView(action(label).apply {
                tag = "$key:${addon.baseUrl}"
                setOnClickListener { click() }
            }, if (row.childCount == 0) LinearLayout.LayoutParams(-2, -2) else gap())
        row.addView(toggle(addon.enabled, "on:${addon.baseUrl}") {
            repository.setEnabled(addon.baseUrl, !addon.enabled)
            renderAddons()
        })
        if (index > 0) button("Yukarı", "up") {
            repository.move(addon.baseUrl, -1)
            renderAddons()
        }
        if (index < last) button("Aşağı", "down") {
            repository.move(addon.baseUrl, 1)
            renderAddons()
        }
        if (addon.configurable) button("Ayarları", "conf") { showConfigure(addon) }
        button("Güncelle", "refresh") { refreshAddon(addon) }
        button("Kaldır", "rm") {
            Dialogs.confirm(this, "${addon.name} kaldırılsın mı?", "Kaynak listesinden çıkar.") {
                repository.removeAddon(addon.baseUrl)
                renderAddons()
            }
        }
        card.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(20) })
        return card
    }

    private fun showAddDialog() {
        Dialogs.input(
            this, "Eklenti ekle", "https://",
            subtitle = "Stremio eklentisinin ya da Nuvio deposunun adresi. LeXia adresi doğrular."
        ) { raw ->
            val address = raw.trim()
            if (address.isBlank() || address == "https://" || busy) return@input
            busy = true
            status.text = "Adres doğrulanıyor…"
            worker.execute {
                try {
                    val addon = try {
                        repository.addAddon(address)
                    } catch (stremioError: Exception) {
                        // Not a Stremio add-on: perhaps a Nuvio JS repository. If it is neither,
                        // both reasons are shown; either may be the one that matters.
                        val nuvio = try {
                            nuvioRepository.addRepository(address)
                        } catch (nuvioError: Exception) {
                            val reasons = listOf(stremioError.message, nuvioError.message)
                                .filterNotNull().filter { it.isNotBlank() }.distinct()
                            throw IllegalStateException(
                                "Eklenti eklenemedi. " + reasons.joinToString("  ·  ").ifBlank { "Adres tanınmadı." }
                            )
                        }
                        runOnUiThread {
                            busy = false
                            status.text = "${nuvio.name} eklendi  ·  ${nuvio.scrapers.size} sağlayıcı"
                            renderAddons()
                        }
                        return@execute
                    }
                    runOnUiThread {
                        busy = false
                        status.text = "${addon.name} eklendi"
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

    /** Asks the add-on's server for its list again (new version, new abilities); it keeps its place. */
    private fun refreshAddon(addon: InstalledMediaAddon) {
        if (busy) return
        busy = true
        status.text = "${addon.name} güncelleniyor…"
        worker.execute {
            val text = try {
                val fresh = repository.addAddon(addon.baseUrl)
                "${fresh.name} güncel  ·  sürüm ${fresh.version}"
            } catch (e: Exception) {
                "${addon.name} güncellenemedi: ${e.message ?: "sunucu cevap vermedi"}"
            }
            runOnUiThread {
                busy = false
                status.text = text
                renderAddons()
            }
        }
    }

    /** Every Nuvio repository and Stremio add-on asked for its newest list, one after another. */
    private fun updateAll() {
        if (busy) return
        val repos = nuvioRepository.installedRepositories()
        val addons = repository.installedAddons()
        if (repos.isEmpty() && addons.isEmpty()) return
        busy = true
        status.text = "Hepsi güncelleniyor…"
        worker.execute {
            var ok = 0
            val failed = ArrayList<String>()
            for (r in repos) {
                runOnUiThread { status.text = "${r.name} güncelleniyor…" }
                try { nuvioRepository.refreshRepository(r.id); ok++ } catch (e: Exception) { failed.add(r.name) }
            }
            for (a in addons) {
                runOnUiThread { status.text = "${a.name} güncelleniyor…" }
                try { repository.addAddon(a.baseUrl); ok++ } catch (e: Exception) { failed.add(a.name) }
            }
            runOnUiThread {
                busy = false
                renderAddons()
                status.text = if (failed.isEmpty()) "Hepsi güncel  ·  $ok kaynak" else "$ok güncellendi  ·  güncellenemeyen: ${failed.joinToString(", ")}"
            }
        }
    }

    /**
     * Tries providers one after another on a well-known film, as Nuvio's own "Test" does, and
     * writes under each how many sources it found and how fast, or why it found none.
     */
    private fun test(scrapers: List<NuvioScraperInfo>) {
        val fresh = scrapers.filter { it.id !in testing }
        known.putAll(scrapers.associate { it.id to it.name })
        if (fresh.isEmpty()) return
        val idle = testing.isEmpty()
        synchronized(testing) { fresh.forEach { testing.add(it.id) } }
        renderAddons()
        status.text = "Deneniyor…"
        if (!idle) return
        worker.execute {
            while (true) {
                // The page was closed: the remaining tries are dropped, not run in the background.
                if (isDestroyed || Thread.currentThread().isInterrupted) {
                    testing.clear()
                    break
                }
                val next = synchronized(testing) { testing.firstOrNull() } ?: break
                val name = known[next].orEmpty()
                runOnUiThread { status.text = "$name deneniyor…" }
                val started = System.currentTimeMillis()
                val (count, error) = try {
                    nuvioRepository.testScraper(next)
                } catch (e: Exception) {
                    0 to (e.message ?: "")
                }
                val secs = (System.currentTimeMillis() - started) / 100 / 10.0
                val time = String.format(java.util.Locale.forLanguageTag("tr-TR"), "%.1f sn", secs)
                val result = if (count > 0) true to "✓ Çalışıyor  ·  $count kaynak buldu  ·  $time"
                else false to ("✕ Sonuç yok  ·  $time" + error.takeIf { it.isNotBlank() }?.let { "  ·  ${it.take(80)}" }.orEmpty())
                synchronized(tested) { tested[next] = result }
                testing.remove(next)
                runOnUiThread { if (!isFinishing) renderAddons() }
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val all = synchronized(tested) { tested.values.toList() }
                status.text = "Deneme bitti  ·  ${all.size} sağlayıcıdan ${all.count { it.first }} tanesi kaynak buldu"
            }
        }
    }

    /**
     * The add-on's settings live on its own web page. It is opened on the phone; the address it
     * gives at the end is entered here with "Eklenti ekle", and replaces this one in its place.
     */
    private fun showConfigure(addon: InstalledMediaAddon) {
        Dialogs.message(
            this, "${addon.name} · Ayarlar",
            "Bu adresi telefonunda aç ve ayarları yap:\n\n${addon.configureUrl}\n\n" +
                "Sayfanın verdiği yeni eklenti adresini “Eklenti ekle” ile buraya gir; eklenti listede aynı yerinde güncellenir."
        )
    }

    private fun showScraperSettings(repositoryId: String, scraper: NuvioScraperInfo) {
        val current = runCatching {
            org.json.JSONObject(nuvioRepository.scraperSettings(repositoryId, scraper.id))
        }.getOrDefault(org.json.JSONObject())
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var form: Ui? = null
        fun addSettingRow(key: String = "", value: String = "") {
            val u = form ?: return
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, u.u(6), 0, u.u(6))
            }
            val keyInput = Dialogs.field(this, u, key, "Ayar adı")
            val valueInput = Dialogs.field(this, u, value, "Değer")
            row.addView(keyInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
            row.addView(valueInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f).apply {
                leftMargin = u.u(12)
            })
            row.addView(action("Sil", small = true).apply {
                setOnClickListener { rows.removeView(row) }
            }, gap())
            rows.addView(row)
        }
        Dialogs.form(
            this, "${scraper.name} · Ayarlar",
            "Ayarları ad ve değer olarak düzenle. Açık/kapalı (true/false) ve sayılar doğru türde saklanır.",
            "Kaydet", wide = true,
            build = { u, content ->
                form = u
                val keys = current.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = current.opt(key)
                    addSettingRow(key, when (value) {
                        null, org.json.JSONObject.NULL -> ""
                        else -> value.toString()
                    })
                }
                val scroll = ScrollView(this).apply {
                    isVerticalScrollBarEnabled = false
                    addView(rows)
                }
                content.addView(scroll, LinearLayout.LayoutParams(-1, u.u(420)))
                val more = action("+  Ayar ekle", small = true).apply { setOnClickListener { addSettingRow() } }
                content.addView(more, LinearLayout.LayoutParams(-2, -2).apply { topMargin = u.u(14) })
                rows.getChildAt(0) ?: more
            },
            ok = {
                try {
                    val output = org.json.JSONObject()
                    var bad: EditText? = null
                    for (i in 0 until rows.childCount) {
                        val row = rows.getChildAt(i) as? LinearLayout ?: continue
                        val keyInput = row.getChildAt(0) as? EditText ?: continue
                        val valueInput = row.getChildAt(1) as? EditText ?: continue
                        val key = keyInput.text.toString().trim()
                        if (key.isBlank()) {
                            keyInput.error = "Ayar adı gerekli"
                            bad = keyInput
                            break
                        }
                        val raw = valueInput.text.toString()
                        val value: Any = when {
                            raw.equals("true", true) -> true
                            raw.equals("false", true) -> false
                            raw.toLongOrNull() != null -> raw.toLong()
                            raw.toDoubleOrNull() != null -> raw.toDouble()
                            raw.startsWith("{") -> org.json.JSONObject(raw)
                            raw.startsWith("[") -> org.json.JSONArray(raw)
                            else -> raw
                        }
                        output.put(key, value)
                    }
                    if (bad != null) {
                        bad.requestFocus()
                        false
                    } else {
                        nuvioRepository.setScraperSettings(repositoryId, scraper.id, output.toString())
                        status.text = "${scraper.name} ayarları kaydedildi."
                        true
                    }
                } catch (error: Exception) {
                    status.text = "Ayarlar kaydedilemedi: ${error.message ?: "geçersiz değer"}"
                    Glass.toast(this, "Geçersiz değer")
                    false
                }
            }
        )
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
                    status.text = "${updated.name} güncellendi · ${updated.scrapers.size} sağlayıcı"
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
        Dialogs.confirm(this, "$name kaldırılsın mı?", "Depo ve içindeki sağlayıcılar kaldırılır.") {
            nuvioRepository.removeRepository(repositoryId)
            renderAddons()
        }
    }

    private fun infoCard(title: String, description: String): View {
        val box = card()
        box.addView(ui.text(title, 32, C.TEXT, 700))
        box.addView(ui.text(description, 23, C.TEXT2, 400).apply { setLineSpacing(0f, 1.2f) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.u(8) })
        return box
    }

    /** A pane of dark glass. */
    private fun card(horizontal: Boolean = false) = LinearLayout(this).apply {
        orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        gravity = if (horizontal) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
        setPadding(ui.u(36), ui.u(30), ui.u(36), ui.u(30))
        background = ui.round(Color.argb(24, 255, 255, 255), 30, 1, Color.argb(26, 255, 255, 255))
        clipChildren = false
        clipToPadding = false
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = ui.u(20) }

    /** An on/off switch as a small pill: a green light and "Açık", or a dim "Kapalı". */
    private fun toggle(on: Boolean, key: String, small: Boolean = false, click: () -> Unit): TextView =
        action(if (on) "●  Açık" else "○  Kapalı", small = small).apply {
            tag = key
            if (on) setTextColor(Color.parseColor("#5BD98A"))
            idleInk = currentTextColor
            setOnClickListener { click() }
        }

    /** Text colour a button returns to when the remote leaves it. */
    private var TextView.idleInk: Int
        get() = (getTag(R.id.lexia_idle_ink) as? Int) ?: Color.WHITE
        set(v) = setTag(R.id.lexia_idle_ink, v)

    private fun action(label: String, primary: Boolean = false, small: Boolean = false): TextView =
        ui.text(label, if (small) 21 else 24, Color.WHITE, 600).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            if (small) setPadding(ui.u(22), ui.u(11), ui.u(22), ui.u(11))
            else setPadding(ui.u(30), ui.u(16), ui.u(30), ui.u(16))
            val idle = ui.round(
                Color.argb(if (primary) 64 else 30, 255, 255, 255), 40, 1,
                Color.argb(if (primary) 90 else 34, 255, 255, 255)
            )
            // Selected: white with dark text, as everywhere in LeXia.
            val focusedLook = ui.round(Color.WHITE, 40)
            background = idle
            setOnFocusChangeListener { view, focused ->
                val t = view as TextView
                t.background = if (focused) focusedLook else idle
                t.setTextColor(if (focused) C.INK else t.idleInk)
                val to = if (focused) 1.06f else 1f
                t.animate().scaleX(to).scaleY(to).setDuration(if (focused) D.SPRING else D.FAST)
                    .setInterpolator(if (focused) android.view.animation.OvershootInterpolator(1.4f) else android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
