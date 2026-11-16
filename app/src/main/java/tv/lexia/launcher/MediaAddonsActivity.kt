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
    private lateinit var addButton: TextView
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
            text = "Stremio uyumlu bir eklentinin ya da bir Nuvio JS deposunun manifest adresini ekle. LeXia adresi doğrular; film ve dizi kaynakları bu eklentilerde aranır."
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
        val add = action("+  Eklenti ekle", primary = true).apply {
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
        addButton = add
        // The remote starts on the first button; a focused container would swallow every D-pad press.
        add.requestFocus()
    }

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
        status.text = "${addons.size} Stremio eklentisi  ·  ${nuvioRepos.size} Nuvio deposu  ·  $nuvioCount sağlayıcı"

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
                val on = repo.scrapers.count { it.enabled }
                text = "NUVIO DEPOSU  ·  ${repo.scrapers.size} sağlayıcı, $on açık" + if (repo.enabled) "" else "  ·  KAPALI"
                textSize = 14f
                setTextColor(Color.rgb(125, 151, 255))
                setPadding(0, dp(5), 0, dp(4))
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
                val kinds = scraper.supportedTypes.map { if (it.equals("movie", true)) "Film" else "Dizi" }.distinct().joinToString(", ")
                val langs = scraper.contentLanguage.joinToString(", ") { Lang.name(Lang.code(it)).ifBlank { it.uppercase() } }
                val line = listOf(kinds, langs, scraper.description).filter { it.isNotBlank() }.joinToString("  ·  ")
                if (line.isNotBlank()) details.addView(TextView(this).apply {
                    text = line
                    textSize = 12f
                    setTextColor(Color.rgb(150, 160, 180))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                row.addView(details, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                if (scraper.hasSettings) {
                    row.addView(action("Ayarlar").apply {
                        tag = "settings:${repo.id}:${scraper.id}"
                        setOnClickListener { showScraperSettings(repo.id, scraper) }
                    })
                }
                val toggle = action(if (scraper.enabled) "Açık" else "Kapalı", primary = scraper.enabled).apply {
                    tag = "toggle:${repo.id}:${scraper.id}"
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
                tag = "all:${repo.id}"
                setOnClickListener {
                    nuvioRepository.setAllScrapersEnabled(repo.id, !allEnabled)
                    renderAddons()
                }
            })
            repoActions.addView(action("Depoyu güncelle").apply {
                tag = "update:${repo.id}"
                setOnClickListener { refreshNuvioRepository(repo.id, repo.name) }
            })
            repoActions.addView(action("Depoyu kaldır").apply {
                setOnClickListener { confirmRemoveNuvio(repo.id, repo.name) }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10) })
            card.addView(repoActions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addCardToList(card)
        }

        addons.forEachIndexed { index, addon ->
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
                val gives = listOfNotNull(
                    "Kaynak".takeIf { "stream" in addon.resources },
                    "Bölüm listesi".takeIf { "meta" in addon.resources },
                    "Altyazı".takeIf { addon.resources.any { it.startsWith("subtitle", true) } },
                    "Katalog".takeIf { "catalog" in addon.resources }
                ).joinToString(", ").ifBlank { "bilgi yok" }
                text = "STREMIO  ·  Sürüm ${addon.version}  ·  Verdiği: $gives"
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
            if (addon.description.isNotBlank()) {
                card.addView(TextView(this).apply {
                    text = addon.description
                    textSize = 14f
                    setTextColor(Color.rgb(170, 180, 198))
                    maxLines = 2
                    setPadding(0, dp(6), 0, 0)
                })
            }
            // As in Nuvio: switch on or off, order, the add-on's own settings, remove.
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun button(label: String, key: String, primary: Boolean = false, click: () -> Unit) =
                row.addView(action(label, primary).apply {
                    tag = "$key:${addon.baseUrl}"
                    setOnClickListener { click() }
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            button(if (addon.enabled) "Açık" else "Kapalı", "on", primary = addon.enabled) {
                repository.setEnabled(addon.baseUrl, !addon.enabled)
                renderAddons()
            }
            if (index > 0) button("Yukarı", "up") {
                repository.move(addon.baseUrl, -1)
                renderAddons()
            }
            if (index < addons.lastIndex) button("Aşağı", "down") {
                repository.move(addon.baseUrl, 1)
                renderAddons()
            }
            if (addon.configurable) button("Ayarlarını değiştir", "conf") { showConfigure(addon) }
            button("Kaldır", "rm") { confirmRemove(addon.baseUrl, addon.name) }
            card.addView(row, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            card.alpha = if (addon.enabled) 1f else 0.6f
            addCardToList(card)
        }

        val target = focusedKey?.let { list.findViewWithTag<View>(it) }
        list.post {
            if (isFinishing) return@post
            if (target != null) target.requestFocus()
            else if (root.findFocus() == null) addButton.requestFocus()
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
                                status.text = "${nuvio.name} eklendi · ${nuvio.scrapers.size} sağlayıcı"
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

    /**
     * The add-on's settings live on its own web page. It is opened on the phone; the address it
     * gives at the end is entered here with "Eklenti ekle", and replaces this one in its place.
     */
    private fun showConfigure(addon: InstalledMediaAddon) {
        AlertDialog.Builder(this)
            .setTitle("${addon.name} · Ayarlar")
            .setMessage(
                "Bu adresi telefonunda aç ve ayarları yap:\n\n${addon.configureUrl}\n\n" +
                    "Sayfanın verdiği yeni eklenti adresini \"Eklenti ekle\" ile buraya gir; eklenti listede aynı yerinde güncellenir."
            )
            .setPositiveButton("Tamam", null)
            .show()
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
        val current = runCatching {
            org.json.JSONObject(nuvioRepository.scraperSettings(repositoryId, scraper.id))
        }.getOrDefault(org.json.JSONObject())
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(rows)
        }
        fun addSettingRow(key: String = "", value: String = "") {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val keyInput = EditText(this).apply {
                hint = "Ayar adı"
                setSingleLine(true)
                setText(key)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                textSize = 15f
            }
            val valueInput = EditText(this).apply {
                hint = "Değer"
                setSingleLine(true)
                setText(value)
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                textSize = 15f
            }
            row.addView(keyInput, LinearLayout.LayoutParams(0, dp(52), 0.9f))
            row.addView(valueInput, LinearLayout.LayoutParams(0, dp(52), 1.1f).apply {
                leftMargin = dp(8)
            })
            row.addView(action("Sil").apply {
                setOnClickListener { rows.removeView(row) }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            rows.addView(row)
        }
        val keys = current.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = current.opt(key)
            addSettingRow(key, when (value) {
                null, org.json.JSONObject.NULL -> ""
                is org.json.JSONObject, is org.json.JSONArray -> value.toString()
                else -> value.toString()
            })
        }
        container.addView(scroll, LinearLayout.LayoutParams(-1, dp(300)))
        container.addView(action("+  Ayar ekle").apply {
            setOnClickListener { addSettingRow() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("${scraper.name} · Ayarlar")
            .setMessage("Ayarları anahtar/değer olarak düzenle. Boolean ve sayısal değerler uygun JSON türünde saklanır.")
            .setView(container)
            .setNegativeButton("Vazgeç", null)
            .setPositiveButton("Kaydet", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val output = org.json.JSONObject()
                    for (i in 0 until rows.childCount) {
                        val row = rows.getChildAt(i) as? LinearLayout ?: continue
                        val keyInput = row.getChildAt(0) as? EditText ?: continue
                        val valueInput = row.getChildAt(1) as? EditText ?: continue
                        val key = keyInput.text.toString().trim()
                        if (key.isBlank()) {
                            keyInput.error = "Ayar adı gerekli"
                            return@setOnClickListener
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
                    nuvioRepository.setScraperSettings(repositoryId, scraper.id, output.toString())
                    dialog.dismiss()
                    status.text = "${scraper.name} ayarları kaydedildi."
                } catch (error: Exception) {
                    status.text = "Ayarlar kaydedilemedi: ${error.message ?: "geçersiz değer"}"
                }
            }
        }
        dialog.show()
        dialog.window?.setLayout(dp(760), LinearLayout.LayoutParams.WRAP_CONTENT)
        if (rows.childCount > 0) rows.getChildAt(0).requestFocus()
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
        AlertDialog.Builder(this)
            .setTitle("Nuvio deposu kaldırılsın mı?")
            .setMessage("$name deposu ve sağlayıcıları kaldırılacak.")
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
